package com.virjar.tk.shared.client

import com.virjar.tk.protocol.netty.PacketCodec
import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImClientProtocolVersionTest {
    @Test
    fun `minor recommendation permits authentication but incompatible range closes before credentials`() = runBlocking {
        val current = ProtocolVersions.SUPPORTED
        val cases = listOf(
            ProtocolRange(current.major, 0, current.currentMinor + 1) to false,
            ProtocolRange(current.major, current.currentMinor + 1, current.currentMinor + 1) to true,
            ProtocolRange(current.major + 1, 0, 0) to true,
        )
        for ((range, rejects) in cases) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val receivedCredentials = CompletableDeferred<Boolean>()
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        negotiateTestSocket(socket, range)
                        val input = DataInputStream(socket.getInputStream())
                        val type = input.read()
                        if (rejects) {
                            assertEquals(-1, type, "incompatible peer must close without sending AUTH")
                            receivedCredentials.complete(false)
                        } else {
                            assertEquals(PacketType.AUTH.code, type)
                            val auth = ProtoCodec.decode(AuthRequestPayload, input.readNBytes(input.readInt()))
                            assertEquals("version-user", auth.username)
                            receivedCredentials.complete(true)
                            while (input.read() >= 0) Unit
                        }
                    }
                } catch (failure: Throwable) {
                    receivedCredentials.completeExceptionally(failure)
                }
            }
            val client = ImClient()
            try {
                client.connectAndAuth(authRequest(), "127.0.0.1", server.localPort)
                assertEquals(!rejects, withTimeout(5_000) { receivedCredentials.await() })
                val result = checkNotNull(client.protocolCompatibility.value)
                assertEquals(rejects, result.requiresUpgrade)
                assertEquals(!rejects, result.recommendsUpgrade)
                if (rejects) {
                    assertEquals(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, client.authenticationFailure.value?.kind)
                    assertEquals(true, client.authenticationFailure.value?.requiresClientUpgrade)
                    assertEquals(ConnectionState.AUTH_FAILED, client.state.value)
                } else {
                    assertFalse(result.requiresUpgrade)
                    assertNull(client.authenticationFailure.value)
                    assertEquals(current.current, result.negotiated)
                }
            } finally {
                client.destroy()
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `server upgrade permits the same client to retry after a temporary protocol refusal`() = runBlocking {
        val windows = listOf(
            ProtocolRange(0, 1, 1) to ProtocolRange(0, 0, 0),
            ProtocolRange(1, 0, 0) to ProtocolRange(0, 0, 0),
        )
        for ((clientWindow, oldServerWindow) in windows) {
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val attempts = AtomicInteger()
            val firstConnectionClosed = CompletableDeferred<Unit>()
            val observedFailures = CopyOnWriteArrayList<AuthenticationFailure>()
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    server.accept().use { socket ->
                        attempts.incrementAndGet()
                        socket.soTimeout = 3_000
                        negotiateTestSocket(socket, oldServerWindow)
                        assertEquals(-1, socket.getInputStream().read(), "no credentials before compatible negotiation")
                        firstConnectionClosed.complete(Unit)
                    }
                    server.accept().use { socket ->
                        attempts.incrementAndGet()
                        socket.soTimeout = 3_000
                        negotiateTestSocket(socket, clientWindow)
                        val input = DataInputStream(socket.getInputStream())
                        assertEquals(PacketType.AUTH.code, input.readUnsignedByte())
                        val auth = ProtoCodec.decode(AuthRequestPayload, input.readNBytes(input.readInt()))
                        assertEquals("version-user", auth.username)
                        socket.getOutputStream().apply {
                            write(encode(AuthResponsePayload(
                                code = AuthResponsePayload.CODE_OK,
                                uid = "version-owner",
                                username = "version-user",
                                name = "Version User",
                                refreshToken = "refresh-after-server-upgrade",
                                accessToken = "access-after-server-upgrade",
                                datasetId = TEST_SYNC_DATASET_ID,
                            )))
                            flush()
                        }
                        while (input.read() >= 0) Unit
                    }
                } catch (failure: Throwable) {
                    firstConnectionClosed.completeExceptionally(failure)
                }
            }
            val client = ImClient(
                supportedProtocol = clientWindow,
                onAuthenticationFailureObserved = { observedFailures += it },
            )
            try {
                client.connectAndAuth(authRequest(), "127.0.0.1", server.localPort)
                withTimeout(5_000) { firstConnectionClosed.await() }
                assertEquals(ConnectionState.AUTH_FAILED, client.state.value)
                val refusal = checkNotNull(client.authenticationFailure.value)
                assertEquals(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, refusal.kind)
                assertFalse(refusal.requiresClientUpgrade)
                assertEquals(listOf(refusal), observedFailures.toList())
                assertTrue(checkNotNull(client.protocolCompatibility.value).requiresUpgrade)
                delay(1_300)
                assertEquals(1, attempts.get(), "refusal terminates this owner without automatic retry")

                // 同一端点升级服务器后，客户端版本、凭据和进程均不改变；显式新尝试重新协商。
                client.connectAndAuth(authRequest(), "127.0.0.1", server.localPort)
                withTimeout(5_000) { client.state.first { it == ConnectionState.SYNCHRONIZING } }
                assertEquals(2, attempts.get())
                assertNull(client.authenticationFailure.value)
                assertFalse(checkNotNull(client.protocolCompatibility.value).requiresUpgrade)
                assertEquals(clientWindow.current, client.protocolCompatibility.value?.negotiated)
            } finally {
                client.destroy()
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `retryable maintenance response reconnects the retained authentication owner`() = runBlocking {
        val maintenance = encode(
            AuthResponsePayload(code = AuthResponsePayload.CODE_SERVER_MAINTENANCE),
        )
        val acceptedAuth = encode(
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_OK,
                uid = "retry-owner",
                username = "retry-user",
                name = "Retry User",
                refreshToken = "rotated-refresh",
                accessToken = "access-after-retry",
                datasetId = TEST_SYNC_DATASET_ID,
            ),
        )
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()
        val successfulAuth = AtomicInteger()
        val failedAuth = AtomicInteger()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        val attempt = accepted.incrementAndGet()
                        negotiateTestSocket(socket)
                        val input = DataInputStream(socket.getInputStream())
                        input.readUnsignedByte()
                        val payloadLength = input.readInt()
                        input.readNBytes(payloadLength)
                        socket.getOutputStream().apply {
                            write(if (attempt == 1) maintenance else acceptedAuth)
                            flush()
                        }
                        // 保持已接受的连接稳定，直到客户端清理；第一次
                        // maintenance 尝试会立即被重试状态机关闭。
                        while (input.read() >= 0) {
                            // 排空，直到客户端关闭这条测试连接。
                        }
                    }
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器/当前 socket。
            }
        }
        val client = ImClient(
            onAuthResult = { success, _, _, _, _, _, _, _ ->
                if (success) successfulAuth.incrementAndGet() else failedAuth.incrementAndGet()
            },
        )

        try {
            client.connectAndAuth(
                authRequest().copy(
                    authType = 2,
                    username = null,
                    password = null,
                    refreshToken = "persisted-refresh",
                ),
                "127.0.0.1",
                server.localPort,
                expectedUid = "retry-owner",
            )
            withTimeout(7_000) {
                while (successfulAuth.get() == 0) delay(10)
            }

            assertTrue(accepted.get() >= 2)
            assertEquals(1, failedAuth.get())
            assertEquals(1, successfulAuth.get())
            assertNull(client.authenticationFailure.value)
            assertEquals(ConnectionState.SYNCHRONIZING, client.state.value)
        } finally {
            client.destroy()
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    @Test
    fun `explicit unsupported response enters terminal typed failure without reconnecting`() = runBlocking {
        val responseBytes = encode(
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED,
                reason = "upgrade required",
            ),
        )
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        accepted.incrementAndGet()
                        negotiateTestSocket(socket)
                        val input = DataInputStream(socket.getInputStream())
                        input.readUnsignedByte()
                        val payloadLength = input.readInt()
                        input.readNBytes(payloadLength)
                        socket.getOutputStream().apply {
                            write(responseBytes)
                            flush()
                        }
                    }
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }
        val client = ImClient()

        try {
            client.connectAndAuth(authRequest(), "127.0.0.1", server.localPort)
            val failure = withTimeout(5_000) {
                client.authenticationFailure.first { it != null }
            }

            assertEquals(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, failure?.kind)
            assertEquals(ConnectionState.AUTH_FAILED, client.state.value)
            delay(1_300)
            assertEquals(1, accepted.get(), "an explicit version rejection must be terminal")
        } finally {
            client.destroy()
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    @Test
    fun `silent transport close is not classified as a version mismatch`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { accepted.incrementAndGet() }
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }
        val client = ImClient()

        try {
            client.connectAndAuth(authRequest(), "127.0.0.1", server.localPort)
            withTimeout(5_000) {
                while (accepted.get() == 0) delay(10)
            }
            delay(200)

            assertNull(client.authenticationFailure.value)
        } finally {
            client.destroy()
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    private fun authRequest() = AuthRequestPayload(
        authType = 0,
        username = "version-user",
        password = "version-password",
        deviceId = "version-device",
        deviceName = "Version test",
        correlationId = "protocol-version-test-token",
        connectionGeneration = 1L,
    )

    private fun encode(response: AuthResponsePayload): ByteArray {
        val channel = EmbeddedChannel(PacketCodec())
        return try {
            channel.writeOutbound(response)
            val buffer = channel.readOutbound<ByteBuf>()
            try {
                ByteArray(buffer.readableBytes()).also(buffer::readBytes)
            } finally {
                buffer.release()
            }
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
