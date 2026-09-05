package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.AuthRules
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImClientAuthenticationTransportTest {
    @Test
    fun `prepared refresh owns a generation without opening a socket until started`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
        val accepted = AtomicInteger()
        val authReceived = CompletableDeferred<Unit>()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                server.accept().use { socket ->
                    accepted.incrementAndGet()
                    negotiateTestSocket(socket)
                    DataInputStream(socket.getInputStream()).run {
                        readUnsignedByte()
                        val payloadLength = readInt()
                        readNBytes(payloadLength)
                    }
                    authReceived.complete(Unit)
                }
            } catch (failure: Throwable) {
                if (!server.isClosed) authReceived.completeExceptionally(failure)
            }
        }
        val client = ImClient()

        try {
            val prepared = client.prepareAuthentication(
                uid = "prepared-refresh-owner",
                token = "prepared-refresh-token",
                deviceId = "prepared-refresh-device",
                deviceName = "Prepared refresh test",
                host = LOOPBACK,
                port = server.localPort,
            )
            withTimeout(5_000L) { client.awaitTransportOwnerStart() }

            assertTrue(client.currentTransportOwnerGeneration > 0L)
            assertEquals(0L, client.currentConnectionGeneration)
            assertEquals(ConnectionState.DISCONNECTED, client.state.value)
            assertEquals(0, accepted.get())

            assertTrue(prepared.start())
            assertFalse(prepared.start())
            withTimeout(5_000L) { authReceived.await() }

            assertEquals(1, accepted.get())
            assertTrue(client.currentConnectionGeneration > 0L)
        } finally {
            client.destroy()
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    @Test
    fun `invalid registration fields are rejected before reserving a transport owner`() {
        val client = ImClient()

        try {
            assertFailsWith<IllegalArgumentException> {
                client.register(
                    username = "valid-register-user",
                    password = "register-password",
                    name = "n".repeat(AuthRules.DISPLAY_NAME_MAX_LENGTH + 1),
                    deviceId = "register-device",
                    deviceName = "Registration test",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                client.register(
                    username = "valid-register-user",
                    password = "register-password",
                    name = "Valid User",
                    deviceId = "register-device",
                    deviceName = "d".repeat(AuthRules.DEVICE_METADATA_MAX_LENGTH + 1),
                )
            }

            assertEquals(0L, client.currentConnectionGeneration)
            assertEquals(ConnectionState.DISCONNECTED, client.state.value)
            assertNull(client.authenticationAttemptFailure.value)
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `invalid prepared refresh identity is rejected before reserving a transport owner`() {
        val client = ImClient()

        try {
            assertFailsWith<IllegalArgumentException> {
                client.prepareAuthentication(
                    uid = " ",
                    token = "refresh-token",
                    deviceId = "refresh-device",
                    deviceName = "Refresh test",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                client.prepareAuthentication(
                    uid = "refresh-owner",
                    token = " ",
                    deviceId = "refresh-device",
                    deviceName = "Refresh test",
                )
            }

            assertEquals(0L, client.currentTransportOwnerGeneration)
            assertEquals(0L, client.currentConnectionGeneration)
            assertEquals(ConnectionState.DISCONNECTED, client.state.value)
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `password AUTH sent to a silent-closing socket is attempted only once`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))
        val accepted = AtomicInteger()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        accepted.incrementAndGet()
                        negotiateTestSocket(socket)
                        DataInputStream(socket.getInputStream()).run {
                            readUnsignedByte()
                            val payloadLength = readInt()
                            readNBytes(payloadLength)
                        }
                        // 对端观察到完整的 AUTH 帧，但从未产出 AUTH_RESP。
                    }
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器/当前 socket。
            }
        }
        val client = ImClient()

        try {
            client.login(
                username = "one-shot-user",
                password = "one-shot-password",
                deviceId = "one-shot-device",
                deviceName = "One-shot test",
                host = LOOPBACK,
                port = server.localPort,
            )
            val failure = withTimeout(5_000) {
                client.authenticationAttemptFailure.first { it != null }
            }

            assertEquals(AuthenticationAttemptFailureKind.TRANSPORT_UNAVAILABLE, failure?.kind)
            assertNull(client.authenticationFailure.value)
            assertEquals(ConnectionState.DISCONNECTED, client.state.value)
            delay(1_300)
            assertEquals(1, accepted.get(), "password AUTH must not reconnect after response-less close")
        } finally {
            client.destroy()
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    @Test
    fun `registration refused by TCP is attempted only once`() = runBlocking {
        val refusedPort = unusedLoopbackPort()
        val client = ImClient()

        try {
            client.register(
                username = "one-shot-register",
                password = "register-password",
                name = "One Shot",
                deviceId = "register-device",
                deviceName = "Registration test",
                host = LOOPBACK,
                port = refusedPort,
            )
            withTimeout(5_000) {
                client.authenticationAttemptFailure.first { it != null }
            }
            val endedGeneration = client.currentConnectionGeneration

            assertTrue(endedGeneration > 0L)
            assertEquals(ConnectionState.DISCONNECTED, client.state.value)
            delay(1_300)
            assertEquals(
                endedGeneration,
                client.currentConnectionGeneration,
                "registration must not schedule a second TCP attempt",
            )
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `durable refresh refused by TCP keeps reconnecting`() = runBlocking {
        val refusedPort = unusedLoopbackPort()
        val client = ImClient()

        try {
            client.authenticate(
                uid = "refresh-owner",
                token = "durable-refresh-token",
                deviceId = "refresh-device",
                deviceName = "Refresh test",
                host = LOOPBACK,
                port = refusedPort,
            )
            withTimeout(5_000) {
                while (client.currentConnectionGeneration < 2L) delay(10)
            }

            assertNull(client.authenticationAttemptFailure.value)
            assertNull(client.authenticationFailure.value)
        } finally {
            client.destroy()
        }
    }

    private fun unusedLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
