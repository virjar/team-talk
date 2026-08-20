package com.virjar.tk.client

import com.virjar.tk.protocol.payload.AuthRequestPayload
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
    fun `late inactive and retired owner cannot tear down replacement connection`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val acceptor = Executors.newSingleThreadExecutor()
        acceptor.submit {
            try {
                while (!server.isClosed && sockets.size < 3) sockets += server.accept()
            } catch (_: SocketException) {
                // Test cleanup closes the listener.
            }
        }

        val client = ImClient()
        val auth = AuthRequestPayload(
            authType = 0,
            username = "generation-user",
            password = "generation-password",
            deviceId = "generation-device",
            deviceName = "Generation test",
        )

        try {
            client.connectAndAuth(auth, "127.0.0.1", server.localPort)
            awaitCondition { sockets.size >= 1 }
            withTimeout(5_000) { client.state.first { it == ConnectionState.CONNECTED } }
            val firstOwner = client.currentTransportOwnerGeneration

            // Start a replacement without waiting for the old channel's inactive callback.
            client.connectAndAuth(auth, "127.0.0.1", server.localPort)
            awaitCondition { sockets.size >= 2 }
            withTimeout(5_000) { client.state.first { it == ConnectionState.CONNECTED } }
            val replacementOwner = client.currentTransportOwnerGeneration
            assertTrue(replacementOwner > firstOwner)

            // This models AndroidAppDataStateHolder closing a retained, already retired session.
            client.disconnectIfOwned(firstOwner)
            delay(100)

            // On the unfixed client this late callback clears the replacement channel and queues a
            // third reconnect. The generation gate must leave the second connection untouched.
            sockets.first().close()
            delay(1_300)

            assertEquals(2, sockets.size, "stale channelInactive must not schedule a reconnect")
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
