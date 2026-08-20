package com.virjar.tk.client

import com.virjar.tk.model.User
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.SyncReadyPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import com.virjar.tk.testing.FakeLocalCache
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImClientSyncResetTest {

    @Test
    fun `rejected high cursor resets projection then reaches ready from zero on same socket`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val exchange = CompletableFuture<List<Long>>()
        val allowServerClose = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    assertEquals(PacketType.AUTH, readFrame(input).first)
                    output.write(
                        encode(
                            AuthResponsePayload(
                                code = AuthResponsePayload.CODE_OK,
                                uid = "u1",
                                username = "u1",
                                name = "U1",
                                accessToken = "access",
                                refreshToken = "refresh",
                            ),
                        ),
                    )
                    output.flush()

                    val first = readSyncRequest(input)
                    output.write(encode(SyncResetPayload))
                    output.flush()
                    val second = readSyncRequest(input)
                    output.write(encode(SyncReadyPayload))
                    output.flush()
                    exchange.complete(listOf(first, second))
                    allowServerClose.await(5, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                exchange.completeExceptionally(failure)
            }
        }

        val cache = FakeLocalCache().apply {
            upsertUser(User(uid = "stale", username = "stale", name = "Stale"))
            advanceSyncCursor(EventProcessor.SYNC_CURSOR_KEY, 99L)
        }
        val client = ImClient()
        val processor = EventProcessor(client, cache)
        try {
            processor.start()
            client.connectAndAuth(
                AuthRequestPayload(
                    authType = 0,
                    username = "u1",
                    password = "password",
                    deviceId = "sync-reset-device",
                ),
                "127.0.0.1",
                server.localPort,
            )

            withTimeout(5_000) { client.state.first { it == ConnectionState.AUTHENTICATED } }
            assertEquals(listOf(99L, 0L), exchange.get(5, TimeUnit.SECONDS))
            assertEquals(0L, cache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
            assertNull(cache.getUser("stale"))
        } finally {
            allowServerClose.countDown()
            processor.stop()
            client.destroy()
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    private fun readSyncRequest(input: DataInputStream): Long {
        val (type, payload) = readFrame(input)
        assertEquals(PacketType.SYNC_REQUEST, type)
        return ProtoCodec.decode(SyncRequestPayload, payload).lastEventId
    }

    private fun readFrame(input: DataInputStream): Pair<PacketType, ByteArray> {
        val type = PacketType.fromCode(input.readUnsignedByte())
        val length = input.readInt()
        require(length in 0..PacketCodec.MAX_PAYLOAD_SIZE)
        return type to input.readNBytes(length)
    }

    private fun encode(payload: IProto): ByteArray {
        val channel = EmbeddedChannel(PacketCodec())
        return try {
            channel.writeOutbound(payload)
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
