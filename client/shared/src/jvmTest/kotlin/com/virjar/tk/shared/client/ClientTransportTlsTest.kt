package com.virjar.tk.shared.client

import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.payload.AuthRequestPayload
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.handler.ssl.util.SelfSignedCertificate
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class ClientTransportTlsTest {
    @Test
    fun `only strict lexical loopback forms may use plaintext`() {
        listOf(
            "localhost",
            "LOCALHOST",
            "127.0.0.1",
            "127.25.0.9",
            "127.255.255.255",
            "::1",
        ).forEach { host ->
            assertFalse(requiresClientTransportTls(host), host)
        }

        listOf(
            "localhost.",
            "localhost.example",
            "127.0.0.1.example",
            "127.0.0",
            "127.0.0.256",
            "127.0.0.\u0661",
            "[::1]",
            "0:0:0:0:0:0:0:1",
            "192.168.1.20",
            "im.example.test",
        ).forEach { host ->
            assertTrue(requiresClientTransportTls(host), host)
        }
    }

    @Test
    fun `system WebPKI context uses explicit HTTPS hostname verification`() {
        val context = createSystemWebPkiClientSslContext()
        val engine = context.newEngine(
            UnpooledByteBufAllocator.DEFAULT,
            "im.example.test",
            5100,
        )

        assertEquals("HTTPS", engine.sslParameters.endpointIdentificationAlgorithm)
        assertTrue("TLSv1.2" in engine.enabledProtocols)
        assertTrue(
            engine.enabledProtocols.all { protocol -> protocol == "TLSv1.3" || protocol == "TLSv1.2" },
        )
        assertEquals(10_000L, CLIENT_TLS_HANDSHAKE_TIMEOUT_MILLIS)
    }

    @Test
    fun `modern TLS selection requires v1_2 and ignores legacy protocols`() {
        assertEquals(
            listOf("TLSv1.3", "TLSv1.2"),
            selectClientTransportTlsProtocols(
                listOf("TLSv1", "TLSv1.2", "SSLv3", "TLSv1.3", "TLSv1.1"),
            ),
        )
        assertEquals(
            listOf("TLSv1.2"),
            selectClientTransportTlsProtocols(listOf("TLSv1.1", "TLSv1.2")),
        )
        assertFailsWith<IllegalStateException> {
            selectClientTransportTlsProtocols(listOf("TLSv1.3", "TLSv1.1"))
        }
    }

    @Test
    fun `loopback fixture stays plaintext and receives AUTH frame`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
        val releasePeer = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val observed = java.util.concurrent.CompletableFuture<AuthRequestPayload>()
        executor.submit {
            try {
                server.accept().use { socket ->
                    observed.complete(readAuth(socket))
                    releasePeer.await(5, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                observed.completeExceptionally(failure)
            }
        }
        val harness = transportHarness()

        try {
            harness.owner.connect(LOOPBACK, server.localPort)
            val auth = withTimeout(TEST_TIMEOUT_MS) { observed.awaitPolling() }

            assertEquals(TEST_AUTH, auth)
            assertEquals(1, harness.authInvocations.get())
            assertEquals(ConnectionState.CONNECTED, harness.owner.state.value)
        } finally {
            harness.owner.destroy()
            releasePeer.countDown()
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun `AUTH is retained while private TLS handshake is incomplete`() = runBlocking {
        val certificate = SelfSignedCertificate(LOOPBACK_DNS_NAME)
        val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
        val accepted = CountDownLatch(1)
        val releasePeer = CountDownLatch(1)
        val encryptedPrefix = java.util.concurrent.CompletableFuture<ByteArray>()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                server.accept().use { socket ->
                    accepted.countDown()
                    socket.soTimeout = 2_000
                    val prefix = ByteArrayOutputStream()
                    val buffer = ByteArray(512)
                    val count = socket.getInputStream().read(buffer)
                    if (count > 0) prefix.write(buffer, 0, count)
                    encryptedPrefix.complete(prefix.toByteArray())
                    releasePeer.await(5, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                encryptedPrefix.completeExceptionally(failure)
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(tcpTlsCertificatePem = certificate.certificate().readText()),
        )

        try {
            harness.owner.connect(LOOPBACK, server.localPort)
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            val prefix = withTimeout(TEST_TIMEOUT_MS) { encryptedPrefix.awaitPolling() }
            delay(150)

            assertTrue(prefix.isNotEmpty(), "TLS ClientHello should reach the peer")
            assertFalse(prefix.contains(TEST_AUTH.password!!.encodeToByteArray()))
            assertEquals(0, harness.authInvocations.get())
            assertEquals(ConnectionState.CONNECTING, harness.owner.state.value)
        } finally {
            harness.owner.destroy()
            releasePeer.countDown()
            runCatching { server.close() }
            executor.shutdownNow()
            certificate.delete()
        }
    }

    @Test
    fun `configured private certificate completes TLS before encrypted AUTH even on loopback`() = runBlocking {
        val certificate = SelfSignedCertificate(LOOPBACK_DNS_NAME)
        val server = tlsServer(certificate)
        val releasePeer = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val observed = java.util.concurrent.CompletableFuture<AuthRequestPayload>()
        executor.submit {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    observed.complete(readAuth(socket))
                    releasePeer.await(5, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                observed.completeExceptionally(failure)
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(tcpTlsCertificatePem = certificate.certificate().readText()),
        )

        try {
            harness.owner.connect(LOOPBACK_DNS_NAME, server.localPort)
            val auth = withTimeout(TEST_TIMEOUT_MS) { observed.awaitPolling() }

            assertEquals(TEST_AUTH, auth)
            assertEquals(1, harness.authInvocations.get())
            assertEquals(ConnectionState.CONNECTED, harness.owner.state.value)
        } finally {
            harness.owner.destroy()
            releasePeer.countDown()
            runCatching { server.close() }
            executor.shutdownNow()
            certificate.delete()
        }
    }

    @Test
    fun `trusted certificate for another host fails before AUTH`() = runBlocking {
        val certificate = SelfSignedCertificate("wrong.example.test")
        val server = tlsServer(certificate)
        val executor = Executors.newSingleThreadExecutor()
        val serverHandshake = java.util.concurrent.CompletableFuture<Throwable?>()
        executor.submit {
            try {
                (server.accept() as SSLSocket).use { socket -> socket.startHandshake() }
                serverHandshake.complete(null)
            } catch (failure: Throwable) {
                serverHandshake.complete(failure)
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(tcpTlsCertificatePem = certificate.certificate().readText()),
        )

        try {
            harness.owner.connect(LOOPBACK_DNS_NAME, server.localPort)
            awaitCondition { harness.endedAttempts.get() == 1 }

            assertEquals(0, harness.authInvocations.get())
            assertEquals(ConnectionState.DISCONNECTED, harness.owner.state.value)
            assertNotNull(withTimeout(TEST_TIMEOUT_MS) { serverHandshake.awaitPolling() })
            Unit
        } finally {
            harness.owner.destroy()
            runCatching { server.close() }
            executor.shutdownNow()
            certificate.delete()
        }
    }

    @Test
    fun `configured private IP certificate survives build encoding and preserves deployment identity`() = runBlocking {
        val keyStore = ipCertificateKeyStore()
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(keyStore.getCertificate("server").encoded) +
            "\n-----END CERTIFICATE-----\n"
        val config = ServerConfig(
            serverUrl = "http://$LOOPBACK",
            tcpHost = LOOPBACK,
            tcpPort = 5100,
            tcpTlsCertificatePem = decodeTcpTlsCertificateBase64(
                Base64.getEncoder().encodeToString(pem.toByteArray()),
            ),
        )
        assertEquals(config.deploymentIdentity(), config.copy(tcpTlsCertificatePem = null).deploymentIdentity())
        val server = tlsServer(keyStore)
        val executor = Executors.newSingleThreadExecutor()
        val releasePeer = CountDownLatch(1)
        val observed = java.util.concurrent.CompletableFuture<AuthRequestPayload>()
        executor.submit {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    observed.complete(readAuth(socket))
                    releasePeer.await(5, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                observed.completeExceptionally(failure)
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(tcpTlsCertificatePem = config.tcpTlsCertificatePem),
        )
        try {
            harness.owner.connect(config.tcpHost, server.localPort)
            assertEquals(TEST_AUTH, withTimeout(TEST_TIMEOUT_MS) { observed.awaitPolling() })
            assertEquals(1, harness.authInvocations.get())
        } finally {
            harness.owner.destroy()
            releasePeer.countDown()
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun `a different certificate for the same host is rejected before AUTH`() = runBlocking {
        val serverCertificate = SelfSignedCertificate(LOOPBACK_DNS_NAME)
        val configuredCertificate = SelfSignedCertificate(LOOPBACK_DNS_NAME)
        val server = tlsServer(serverCertificate)
        val executor = Executors.newSingleThreadExecutor()
        val serverHandshake = java.util.concurrent.CompletableFuture<Throwable?>()
        executor.submit {
            try {
                (server.accept() as SSLSocket).use { socket -> socket.startHandshake() }
                serverHandshake.complete(null)
            } catch (failure: Throwable) {
                serverHandshake.complete(failure)
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(tcpTlsCertificatePem = configuredCertificate.certificate().readText()),
        )
        try {
            harness.owner.connect(LOOPBACK_DNS_NAME, server.localPort)
            awaitCondition { harness.endedAttempts.get() == 1 }
            assertEquals(0, harness.authInvocations.get())
            assertEquals(ConnectionState.DISCONNECTED, harness.owner.state.value)
            assertNotNull(withTimeout(TEST_TIMEOUT_MS) { serverHandshake.awaitPolling() })
            Unit
        } finally {
            harness.owner.destroy()
            runCatching { server.close() }
            executor.shutdownNow()
            serverCertificate.delete()
            configuredCertificate.delete()
        }
    }

    @Test
    fun `system WebPKI rejects untrusted peer before AUTH`() = runBlocking {
        val certificate = SelfSignedCertificate(LOOPBACK_DNS_NAME)
        val server = tlsServer(certificate)
        val executor = Executors.newSingleThreadExecutor()
        val serverHandshake = java.util.concurrent.CompletableFuture<Throwable?>()
        executor.submit {
            try {
                (server.accept() as SSLSocket).use { socket -> socket.startHandshake() }
                serverHandshake.complete(null)
            } catch (failure: Throwable) {
                serverHandshake.complete(failure)
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(requiresTls = { true }),
        )

        try {
            harness.owner.connect(LOOPBACK_DNS_NAME, server.localPort)
            awaitCondition { harness.endedAttempts.get() == 1 }

            assertEquals(0, harness.authInvocations.get())
            assertEquals(ConnectionState.DISCONNECTED, harness.owner.state.value)
            assertNotNull(withTimeout(TEST_TIMEOUT_MS) { serverHandshake.awaitPolling() })
            Unit
        } finally {
            harness.owner.destroy()
            runCatching { server.close() }
            executor.shutdownNow()
            certificate.delete()
        }
    }

    @Test
    fun `TLS handshake failure follows the retained reconnect lifecycle without AUTH`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
        val accepted = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use {
                        accepted.incrementAndGet()
                        // 在 ClientHello 期间关闭会确定性地使这次 TLS 尝试失败。
                    }
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(requiresTls = { true }),
            reconnectAfterFailure = true,
        )

        try {
            harness.owner.connect(LOOPBACK_DNS_NAME, server.localPort)
            awaitCondition { accepted.get() >= 2 }

            assertTrue(harness.endedAttempts.get() >= 1)
            assertEquals(0, harness.authInvocations.get())
        } finally {
            harness.owner.destroy()
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun `blackhole timeout reconnects once and destroy retires the pending handshake`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
        val sockets = CopyOnWriteArrayList<Socket>()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                while (!server.isClosed) {
                    sockets += server.accept()
                    // 保持每个 TCP 对端开启，且不回应 ClientHello。
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }
        val harness = transportHarness(
            transportTls = ClientTransportTls(
                requiresTls = { true },
                handshakeTimeoutMillis = TEST_HANDSHAKE_TIMEOUT_MS,
            ),
            reconnectAfterFailure = true,
        )

        try {
            harness.owner.connect(LOOPBACK_DNS_NAME, server.localPort)
            val firstTimeoutObserved = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                while (harness.endedAttempts.get() < 1) delay(10)
                true
            } == true
            assertTrue(
                firstTimeoutObserved,
                "blackhole timeout missing: state=${harness.owner.state.value}, " +
                    "accepted=${sockets.size}, ended=${harness.endedAttempts.get()}",
            )
            assertEquals(1, harness.endedAttempts.get(), "one timeout must retire the attempt once")
            awaitCondition { sockets.size >= 2 }

            assertEquals(1, harness.endedAttempts.get())
            assertEquals(2, sockets.size, "one timeout must schedule exactly one reconnect")
            assertEquals(0, harness.authInvocations.get())
            assertEquals(ConnectionState.CONNECTING, harness.owner.state.value)

            // 在替代握手仍处于待处理状态时 teardown。它的 close/failure
            // 回调是过期的，必须既不再次消费该尝试，也不排队第 3 次重试。
            harness.owner.destroy()
            awaitCondition { harness.owner.state.value == ConnectionState.DISCONNECTED }
            delay(STALE_RECONNECT_OBSERVATION_MS)

            assertEquals(1, harness.endedAttempts.get())
            assertEquals(2, sockets.size)
            assertEquals(0, harness.authInvocations.get())
        } finally {
            harness.owner.destroy()
            sockets.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    private fun transportHarness(
        transportTls: ClientTransportTls = ClientTransportTls(),
        reconnectAfterFailure: Boolean = false,
    ): TransportHarness {
        val authInvocations = AtomicInteger()
        val endedAttempts = AtomicInteger()
        lateinit var owner: TransportConnectionOwner
        owner = TransportConnectionOwner(
            initialHost = LOOPBACK,
            initialPort = 0,
            beginProtocolNegotiation = { generation ->
                authInvocations.incrementAndGet()
                owner.writeProtocolNow(TEST_AUTH.copy(connectionGeneration = generation))
            },
            currentAuthenticationAttempt = { null },
            onAuthenticationTransportAttemptEnded = {
                endedAttempts.incrementAndGet()
                reconnectAfterFailure
            },
            onAuthenticationTransportRetired = {},
            authenticationTerminal = { false },
            routePacket = { _, _ -> },
            onTransportDisconnected = {},
            transportTls = transportTls,
        )
        return TransportHarness(owner, authInvocations, endedAttempts)
    }

    private fun tlsServer(certificate: SelfSignedCertificate): SSLServerSocket {
        val password = TEST_KEY_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(
                "server",
                certificate.key(),
                password,
                arrayOf(certificate.cert()),
            )
        }
        return tlsServer(keyStore)
    }

    private fun tlsServer(keyStore: KeyStore): SSLServerSocket {
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, TEST_KEY_PASSWORD.toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManager.keyManagers, null, SecureRandom())
        }
        return context.serverSocketFactory.createServerSocket(
            0,
            16,
            InetAddress.getByName(LOOPBACK),
        ) as SSLServerSocket
    }

    /** Netty's SelfSignedCertificate lacks SAN; use the JDK tool for the real IP identity case. */
    private fun ipCertificateKeyStore(): KeyStore {
        val directory = Files.createTempDirectory("teamtalk-ip-tls-test").toFile()
        try {
            val file = File(directory, "server.p12")
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/$executable").absolutePath,
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=$LOOPBACK", "-ext", "SAN=ip:$LOOPBACK",
                "-storetype", "PKCS12", "-keystore", file.absolutePath,
                "-storepass", TEST_KEY_PASSWORD, "-keypass", TEST_KEY_PASSWORD,
            ).redirectErrorStream(true).start()
            try {
                check(process.waitFor(20, TimeUnit.SECONDS)) { "IP certificate fixture generation timed out" }
                check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
                process.inputStream.close()
            }
            return KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, TEST_KEY_PASSWORD.toCharArray()) }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun readAuth(socket: Socket): AuthRequestPayload {
        val input = DataInputStream(socket.getInputStream())
        input.readUnsignedByte()
        val payloadLength = input.readInt()
        return AuthRequestPayload.readFrom(PacketBuffer(input.readNBytes(payloadLength)))
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(TEST_TIMEOUT_MS) {
            while (!predicate()) delay(10)
        }
    }

    private suspend fun <T> java.util.concurrent.CompletableFuture<T>.awaitPolling(): T {
        while (!isDone) delay(10)
        return get()
    }

    private data class TransportHarness(
        val owner: TransportConnectionOwner,
        val authInvocations: AtomicInteger,
        val endedAttempts: AtomicInteger,
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LOOPBACK_DNS_NAME = "localhost"
        const val TEST_TIMEOUT_MS = 8_000L
        const val TEST_HANDSHAKE_TIMEOUT_MS = 1_000L
        const val STALE_RECONNECT_OBSERVATION_MS = 2_300L
        const val TEST_KEY_PASSWORD = "transport-tls-test"

        val TEST_AUTH = AuthRequestPayload(
            authType = 0,
            username = "tls-test-user",
            password = "tls-test-password",
            deviceId = "tls-test-device",
            deviceName = "TLS transport test",
            correlationId = "tls-transport-test-token",
            connectionGeneration = 1L,
        )
    }
}

private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    return indices.any { start ->
        start + needle.size <= size &&
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }
}
