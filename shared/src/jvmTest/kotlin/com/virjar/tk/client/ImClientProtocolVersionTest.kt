package com.virjar.tk.client

import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImClientProtocolVersionTest {
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
                // Test cleanup closes the listener.
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
                // Test cleanup closes the listener.
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
