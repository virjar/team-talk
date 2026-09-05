package com.virjar.tk.shared.client

import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.payload.AuthRequestPayload
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImClientConnectionGenerationTest {

    @Test
    fun `automatic refresh reconnect sends fresh correlation and advanced generation`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val authentications = CopyOnWriteArrayList<AuthRequestPayload>()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed && authentications.size < 2) {
                    val socket = server.accept()
                    sockets += socket
                    negotiateTestSocket(socket)
                    val input = DataInputStream(socket.getInputStream())
                    input.readUnsignedByte()
                    val payloadLength = input.readInt()
                    authentications += AuthRequestPayload.readFrom(
                        PacketBuffer(input.readNBytes(payloadLength)),
                    )
                    if (authentications.size == 1) socket.close()
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }

        val client = ImClient()
        val callerTemplate = AuthRequestPayload(
            authType = 2,
            refreshToken = "durable-refresh-token",
            deviceId = "reconnect-device",
            correlationId = "caller-correlation-token",
            connectionGeneration = 77L,
        )
        try {
            client.connectAndAuth(
                callerTemplate,
                "127.0.0.1",
                server.localPort,
                expectedUid = "uid-1",
            )
            awaitCondition { authentications.size >= 2 }

            val first = authentications[0]
            val second = authentications[1]
            assertEquals("durable-refresh-token", first.refreshToken)
            assertEquals(first.refreshToken, second.refreshToken)
            assertTrue(first.correlationId != second.correlationId)
            assertTrue(first.correlationId != callerTemplate.correlationId)
            assertTrue(second.correlationId != callerTemplate.correlationId)
            assertTrue(second.connectionGeneration > first.connectionGeneration)
        } finally {
            client.destroy()
            sockets.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    @Test
    fun `paused simulated drop keeps one client offline until explicitly resumed`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val authentications = CopyOnWriteArrayList<AuthRequestPayload>()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed && authentications.size < 2) {
                    val socket = server.accept()
                    sockets += socket
                    negotiateTestSocket(socket)
                    val input = DataInputStream(socket.getInputStream())
                    input.readUnsignedByte()
                    val payloadLength = input.readInt()
                    authentications += AuthRequestPayload.readFrom(
                        PacketBuffer(input.readNBytes(payloadLength)),
                    )
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }

        val client = ImClient()
        val credentials = AuthRequestPayload(
            authType = 2,
            refreshToken = "paused-reconnect-refresh-token",
            deviceId = "paused-reconnect-device",
            correlationId = "ignored-caller-correlation",
            connectionGeneration = 1L,
        )
        try {
            client.connectAndAuth(
                credentials,
                "127.0.0.1",
                server.localPort,
                expectedUid = "paused-reconnect-uid",
            )
            awaitCondition { authentications.size == 1 }
            withTimeout(5_000) { client.state.first { it == ConnectionState.CONNECTED } }

            client.simulateNetworkDropAndPauseReconnect()
            withTimeout(5_000) { client.state.first { it == ConnectionState.DISCONNECTED } }
            delay(1_300)
            assertEquals(1, authentications.size, "paused client must not auto-reconnect")

            client.resumeReconnectAfterSimulatedDrop()
            awaitCondition { authentications.size == 2 }
            assertTrue(
                authentications[1].connectionGeneration > authentications[0].connectionGeneration,
                "resumed client must establish a fresh physical connection generation",
            )
        } finally {
            client.destroy()
            sockets.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    @Test
    fun `late inactive and retired owner cannot tear down replacement connection`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val usernames = CopyOnWriteArrayList<String?>()
        val authentications = CopyOnWriteArrayList<AuthRequestPayload>()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed && sockets.size < 3) {
                    val socket = server.accept()
                    sockets += socket
                    negotiateTestSocket(socket)
                    val input = DataInputStream(socket.getInputStream())
                    input.readUnsignedByte()
                    val payloadLength = input.readInt()
                    val auth = AuthRequestPayload.readFrom(PacketBuffer(input.readNBytes(payloadLength)))
                    usernames += auth.username
                    authentications += auth
                }
            } catch (_: SocketException) {
                // 测试清理关闭监听器。
            }
        }

        val client = ImClient()
        val authA = AuthRequestPayload(
            authType = 0,
            username = "generation-user-a",
            password = "generation-password",
            deviceId = "generation-device",
            deviceName = "Generation test",
            correlationId = "generation-test-token",
            connectionGeneration = 1L,
        )
        val authB = authA.copy(username = "generation-user-b")

        try {
            client.connectAndAuth(authA, "127.0.0.1", server.localPort)
            awaitCondition { sockets.size >= 1 }
            withTimeout(5_000) { client.state.first { it == ConnectionState.CONNECTED } }
            val firstOwner = client.currentTransportOwnerGeneration

            // 不等旧 channel 的 inactive 回调就启动替代连接。
            client.connectAndAuth(authB, "127.0.0.1", server.localPort)
            awaitCondition { sockets.size >= 2 }
            awaitCondition { usernames.size >= 2 }
            withTimeout(5_000) { client.state.first { it == ConnectionState.CONNECTED } }
            val replacementOwner = client.currentTransportOwnerGeneration
            assertTrue(replacementOwner > firstOwner)

            // 这里模拟 AndroidAppDataStateHolder 关闭一个被保留的、已经退场的会话。
            client.disconnectIfOwned(firstOwner)
            delay(100)

            // 在未修复的客户端上，这个迟到的回调会清空替代 channel 并排队
            // 第三次重连。代次门禁必须让第二条连接保持原样。
            sockets.first().close()
            delay(1_300)

            assertEquals(2, sockets.size, "stale channelInactive must not schedule a reconnect")
            assertEquals(
                listOf("generation-user-a", "generation-user-b"),
                usernames.toList(),
                "replacement owner must send B, never replay A",
            )
            assertEquals(2, authentications.map { it.correlationId }.toSet().size)
            assertTrue(authentications.none { it.correlationId == authA.correlationId })
            assertTrue(
                authentications.zipWithNext().all { (left, right) ->
                    right.connectionGeneration > left.connectionGeneration
                },
                "each replacement physical connection must advance its wire generation",
            )
            assertEquals(ConnectionState.CONNECTED, client.state.value)
        } finally {
            client.destroy()
            sockets.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            acceptor.shutdownNow()
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}
