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
import platform.posix.memcmp
import platform.posix.memcpy

/** TCP、DNS 与 TLS 由 Network.framework 提供；协议分帧保留在 PacketFrames。 */
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
    private val logger = com.virjar.tk.shared.log.PlatformOnlyTkLogger("IosTcpChannel")
    private val scope = CoroutineScope(executor + SupervisorJob())
    private var connection: nw_connection_t = null
    // NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT 在 Kotlin 绑定里是 NSObject? getter，运行时
    // 会按“返回 void 的 ObjC block 转 Kotlin Any”拒绝并抛 NSGenericException；改为自建
    // 一条连接级消息上下文，语义等价于默认消息上下文且可安全复用。
    private var messageContext: NSObject? = null
    private var closed = false
    private var ready = false
    private var connectTimeout: Job? = null
    private var lastRead = platformMonotonicNanos()
    private var lastWrite = lastRead

    // 半包累积、头部校验与 AUTH_RESP(CODE_OK) 当帧放宽上限都在共享读取器里；
    // owner 的 onAuthenticationAccepted 与它共享同一认证上限状态。
    private val frameReader = PacketFrameReader(PacketInboundRole.CLIENT)
    var authenticated: Boolean
        get() = frameReader.authenticated
        set(value) { frameReader.authenticated = value }
    val isActive: Boolean get() = ready && !closed

    fun start() {
        require(port in 1..65535)
        require(certificatePem == null || certificatePem.isNotBlank()) { "Configured TCP TLS certificate must not be blank" }
        val useTls = certificatePem != null || requiresClientTransportTls(host)
        // 在进入 Network.framework 的原生配置回调之前先完成解析。Kotlin 的
        // 校验失败必须返回给连接 owner，绝不能经 C 栈展开。
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
            // Kotlin 包装 C block 时丢失了 NW_PARAMETERS_DISABLE_PROTOCOL 的身份。
            // 因此为 loopback 显式移除 TLS；远程主机仍要求上面的 TLS。
            nw_protocol_stack_clear_application_protocols(nw_parameters_copy_default_protocol_stack(parameters))
        }
        val created = checkNotNull(nw_connection_create(nw_endpoint_create_host(host, port.toString()), parameters)) { "Cannot create TCP connection" }
        connection = created
        messageContext = nw_content_context_create("TeamTalk")
        nw_connection_set_queue(created, executor.queue)
        nw_connection_set_state_changed_handler(created) { state, error ->
            logger.trace("state=$state error=${error != null}")
            executor.execute {
                if (closed) return@execute
                when (state) {
                    nw_connection_state_ready -> if (!ready) {
                        ready = true
                        connectTimeout?.cancel(); connectTimeout = null
                        lastRead = platformMonotonicNanos(); lastWrite = lastRead
                        try {
                            onReady(this)
                            if (!closed) { startReceiving(); startIdleTimer() }
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
        nw_connection_send(connection, data, messageContext, true) { error ->
            if (error != null) executor.execute { close(IosTcpException(nw_error_get_error_code(error))) }
        }
    }

    fun close(failure: Throwable? = null) {
        if (closed) return
        closed = true
        ready = false
        if (failure != null) logger.fault("transport closed with failure", failure)
        else logger.trace("transport closed")
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

    /** 推模式接收循环：到达多少喂多少，分帧由共享 [PacketFrameReader] 完成。 */
    private fun startReceiving() {
        if (!isActive) return
        val window = 64 * 1024
        nw_connection_receive(connection, 1u, window.toUInt()) { data, _, complete, error ->
            executor.execute {
                if (!isActive) return@execute
                try {
                    if (error != null) throw IosTcpException(nw_error_get_error_code(error))
                    val count = data?.let(::dispatch_data_get_size)?.toInt() ?: 0
                    check(count in 0..window) { "TCP read exceeded the receive window" }
                    if (count > 0) {
                        lastRead = platformMonotonicNanos()
                        val bytes = ByteArray(count)
                        bytes.usePinned { pinned ->
                            check(dispatch_data_apply(data) { _, regionOffset, region, regionSize ->
                                memcpy(pinned.addressOf(regionOffset.toInt()), region, regionSize)
                                true
                            })
                        }
                        frameReader.feed(
                            bytes, 0, count,
                            // 与旧的按需读取一致：投递中连接被关闭后，同批后续帧不再上抛。
                            keepReading = { isActive },
                            onPacket = { packet -> onPacket(this, packet) },
                        )
                    }
                    // 与既有语义一致：流在任意等待点结束都按帧内截断关闭；正常关闭路径
                    // 由读空闲与服务器 DISCONNECT 负责，EOF 只会出现在等待下一帧时。
                    if (complete || count == 0) close(IosTcpException(-1, "TCP stream ended within a frame"))
                    else startReceiving()
                } catch (failure: Throwable) { close(failure) }
            }
        }
    }
}

internal class IosTcpException(val code: Int, message: String = "TCP transport failed ($code)") : IllegalStateException(message)

internal fun pemCertificate(pem: String): ByteArray {
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

internal fun verifyPinnedServer(trustObject: sec_trust_t, certificateBytes: ByteArray, host: String): Boolean = memScoped {
    val trust = sec_trust_copy_ref(trustObject) ?: return@memScoped false
    try {
        // The deployment pins one exact self-signed leaf, so pin identity is settled by the
        // leaf bytes first: Apple's baseline policy rejects pinned leaves whose validity
        // exceeds 825 days (TcpTlsCertificates issues 3650-day certificates), and no
        // exemption exists for custom anchors. Pin expiry was already enforced by
        // pemCertificate at connection setup; the SecTrust evaluation below still guards
        // every presentation that does not byte-match the pin.
        if (pinnedLeafMatches(SecTrustGetCertificateAtIndex(trust, 0)?.let { SecCertificateCopyData(it) },
                certificateBytes)) {
            return@memScoped true
        }
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
            certificate?.let(::CFRelease); data?.let(::CFRelease)
        }
    } finally {
        CFRelease(trust)
    }
}

/** Byte-identical leaf settles pin identity for exact-cert deployments; null never matches. */
internal fun pinnedLeafMatches(presentedData: CFDataRef?, pinned: ByteArray): Boolean {
    if (presentedData == null) return false
    if (CFDataGetLength(presentedData).toInt() != pinned.size) return false
    return pinned.usePinned { pinnedAddress ->
        val presented = CFDataGetBytePtr(presentedData) ?: return@usePinned false
        memcmp(presented, pinnedAddress.addressOf(0), pinned.size.toULong()) == 0
    }
}
