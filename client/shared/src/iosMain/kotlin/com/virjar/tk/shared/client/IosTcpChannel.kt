@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.protocol.*
import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.CoreFoundation.*
import platform.Network.*
import platform.Security.*
import platform.darwin.*
import platform.posix.memcpy

/** Network.framework supplies TCP, DNS and TLS; protocol framing stays in PacketFrames. */
internal class IosTcpChannel(
    private val executor: IosSerialExecutor,
    private val host: String,
    private val port: Int,
    private val certificatePem: String?,
    private val onReady: (IosTcpChannel) -> Unit,
    private val onPacket: (IosTcpChannel, IProto) -> Unit,
    private val onClosed: (IosTcpChannel, Throwable?) -> Unit,
    private val onWriteIdle: (IosTcpChannel) -> Unit,
) {
    private val scope = CoroutineScope(executor + SupervisorJob())
    private var connection: nw_connection_t = null
    private var closed = false
    private var ready = false
    private var connectTimeout: Job? = null
    private var lastRead = platformMonotonicNanos()
    private var lastWrite = lastRead
    var authenticated = false
    val isActive: Boolean get() = ready && !closed

    fun start() {
        require(port in 1..65535)
        require(certificatePem == null || certificatePem.isNotBlank()) { "Configured TCP TLS certificate must not be blank" }
        val useTls = certificatePem != null || requiresClientTransportTls(host)
        // Parse before entering Network.framework's native configuration callback. A Kotlin
        // validation failure must return to the connection owner, never unwind through C.
        val certificate = certificatePem?.let(::pemCertificate)
        val configureTls: nw_parameters_configure_protocol_block_t = { options ->
            if (useTls) {
                val security = nw_tls_copy_sec_protocol_options(options)
                sec_protocol_options_set_tls_server_name(security, host)
                sec_protocol_options_set_min_tls_protocol_version(security, tls_protocol_version_TLSv12)
                certificate?.let { certificateBytes ->
                    sec_protocol_options_set_verify_block(security, { _, trust, complete ->
                        val accepted = runCatching { verifyPinnedServer(trust, certificateBytes, host) }.getOrDefault(false)
                        complete?.invoke(accepted)
                    }, executor.queue)
                }
            }
        }
        val parameters = nw_parameters_create_secure_tcp(configureTls) { tcp ->
            nw_tcp_options_set_no_delay(tcp, true)
            nw_tcp_options_set_enable_keepalive(tcp, true)
        }
        if (!useTls) {
            // Kotlin wraps C blocks, losing the identity of NW_PARAMETERS_DISABLE_PROTOCOL.
            // Remove TLS explicitly for loopback; remote hosts still require TLS above.
            nw_protocol_stack_clear_application_protocols(nw_parameters_copy_default_protocol_stack(parameters))
        }
        val created = checkNotNull(nw_connection_create(nw_endpoint_create_host(host, port.toString()), parameters)) { "Cannot create TCP connection" }
        connection = created
        nw_connection_set_queue(created, executor.queue)
        nw_connection_set_state_changed_handler(created) { state, error ->
            executor.execute {
                if (closed) return@execute
                when (state) {
                    nw_connection_state_ready -> if (!ready) {
                        ready = true
                        connectTimeout?.cancel(); connectTimeout = null
                        lastRead = platformMonotonicNanos(); lastWrite = lastRead
                        try {
                            onReady(this)
                            if (!closed) { readHeader(); startIdleTimer() }
                        } catch (failure: Throwable) { close(failure) }
                    }
                    nw_connection_state_failed, nw_connection_state_waiting ->
                        close(IosTcpException(error?.let { nw_error_get_error_code(it) } ?: -1))
                    nw_connection_state_cancelled -> close()
                }
            }
        }
        connectTimeout = scope.launch { delay(15_000); close(IosTcpException(-1, "TCP/TLS connection timed out")) }
        nw_connection_start(created)
    }

    fun writeAndFlush(proto: IProto) {
        check(isActive) { "TCP channel is closed" }
        val bytes = PacketFrames.encode(proto)
        val data = bytes.usePinned { dispatch_data_create(it.addressOf(0), bytes.size.toULong(), null, null) }
        lastWrite = platformMonotonicNanos()
        nw_connection_send(connection, data, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, true) { error ->
            if (error != null) executor.execute { close(IosTcpException(nw_error_get_error_code(error))) }
        }
    }

    fun close(failure: Throwable? = null) {
        if (closed) return
        closed = true
        ready = false
        scope.cancel()
        connection?.let { active ->
            nw_connection_set_state_changed_handler(active, null)
            nw_connection_cancel(active)
        }
        connection = null
        onClosed(this, failure)
    }

    private fun startIdleTimer() {
        scope.launch {
            while (isActive) {
                delay(1_000)
                val now = platformMonotonicNanos()
                if (now - lastRead >= PacketFrames.READ_IDLE_TIMEOUT_SECONDS * 1_000_000_000L) {
                    close(IosTcpException(-1, "TCP read idle timeout")); return@launch
                }
                if (now - lastWrite >= PacketFrames.PING_INTERVAL_SECONDS * 1_000_000_000L) onWriteIdle(this@IosTcpChannel)
            }
        }
    }

    private fun readHeader(): Unit = readExactly(PacketFrames.HEADER_SIZE) { header ->
        val typeCode = header[0].toInt() and 255
        var length = 0
        for (index in 1..4) length = (length shl 8) or (header[index].toInt() and 255)
        val limit = if (authenticated) PacketFrames.AUTHED_LIMIT else PacketFrames.UNAUTHED_LIMIT
        // The bound and direction are checked before allocating a peer-controlled payload.
        PacketFrames.validateHeader(typeCode, length, PacketInboundRole.CLIENT, limit)
        readExactly(length) { payload ->
            onPacket(this, PacketFrames.decode(typeCode, payload, PacketInboundRole.CLIENT, limit))
            if (isActive) readHeader()
        }
    }

    private fun readExactly(size: Int, consume: (ByteArray) -> Unit) {
        if (!isActive) return
        if (size == 0) { consume(ByteArray(0)); return }
        val buffer = ByteArray(size)
        var offset = 0
        fun receive() {
            if (!isActive) return
            val maximum = minOf(size - offset, 64 * 1024).toUInt()
            nw_connection_receive(connection, 1u, maximum) { data, _, complete, error ->
                executor.execute {
                    if (!isActive) return@execute
                    try {
                        if (error != null) throw IosTcpException(nw_error_get_error_code(error))
                        val count = data?.let(::dispatch_data_get_size)?.toInt() ?: 0
                        check(count in 0..(size - offset)) { "TCP read exceeded requested frame" }
                        if (count > 0) {
                            lastRead = platformMonotonicNanos()
                            buffer.usePinned { pinned ->
                                check(dispatch_data_apply(data) { _, regionOffset, bytes, regionSize ->
                                    memcpy(pinned.addressOf(offset + regionOffset.toInt()), bytes, regionSize)
                                    true
                                })
                            }
                            offset += count
                        }
                        if (offset == size) consume(buffer)
                        else if (complete || count == 0) close(IosTcpException(-1, "TCP stream ended within a frame"))
                        else receive()
                    } catch (failure: Throwable) { close(failure) }
                }
            }
        }
        receive()
    }
}

internal class IosTcpException(val code: Int, message: String = "TCP transport failed ($code)") : IllegalStateException(message)

private fun pemCertificate(pem: String): ByteArray {
    val certificates = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----").findAll(pem).toList()
    require(certificates.size == 1) { "Configure exactly one TCP TLS certificate" }
    return platformBase64Decode(certificates.single().groupValues[1].filterNot(Char::isWhitespace)).also { bytes ->
        require(bytes.isNotEmpty()) { "Configured TCP TLS certificate is empty" }
        val data = bytes.usePinned { CFDataCreate(kCFAllocatorDefault, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
        try {
            val certificate = SecCertificateCreateWithData(kCFAllocatorDefault, data)
            require(certificate != null) { "Configured TCP TLS certificate is invalid" }
            val notBefore = SecCertificateCopyNotValidBeforeDate(certificate)
            val notAfter = SecCertificateCopyNotValidAfterDate(certificate)
            try {
                val now = CFAbsoluteTimeGetCurrent()
                require(notBefore != null && notAfter != null &&
                    now >= CFDateGetAbsoluteTime(notBefore) && now <= CFDateGetAbsoluteTime(notAfter)) {
                    "Configured TCP TLS certificate is not currently valid"
                }
            } finally {
                notBefore?.let(::CFRelease); notAfter?.let(::CFRelease); CFRelease(certificate)
            }
        } finally { CFRelease(data) }
    }
}

private fun verifyPinnedServer(trustObject: sec_trust_t, certificateBytes: ByteArray, host: String): Boolean = memScoped {
    val trust = sec_trust_copy_ref(trustObject) ?: return@memScoped false
    val data = certificateBytes.usePinned { CFDataCreate(kCFAllocatorDefault, it.addressOf(0).reinterpret(), certificateBytes.size.toLong()) }
    val certificate = SecCertificateCreateWithData(kCFAllocatorDefault, data)
    val hostString = CFStringCreateWithCString(kCFAllocatorDefault, host, kCFStringEncodingUTF8)
    val policy = SecPolicyCreateSSL(true, hostString)
    val values = allocArray<COpaquePointerVar>(1)
    values[0] = certificate
    val anchors = CFArrayCreate(kCFAllocatorDefault, values, 1, kCFTypeArrayCallBacks.ptr)
    try {
        certificate != null && policy != null &&
            SecTrustSetPolicies(trust, policy) == errSecSuccess &&
            SecTrustSetAnchorCertificates(trust, anchors) == errSecSuccess &&
            SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess &&
            SecTrustEvaluateWithError(trust, null)
    } finally {
        anchors?.let(::CFRelease); policy?.let(::CFRelease); hostString?.let(::CFRelease)
        certificate?.let(::CFRelease); data?.let(::CFRelease); CFRelease(trust)
    }
}
