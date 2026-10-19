package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.NotifyPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 共享增量帧读取器：与 Netty PacketCodec 同一套决策在回调式字节流上的表现。 */
class PacketFrameReadingTest {
    private val datasetId = "00000000-0000-4000-8000-000000000001"

    private fun authOkFrame(): ByteArray = PacketFrames.encode(
        AuthResponsePayload(
            code = AuthResponsePayload.CODE_OK,
            uid = "u1",
            username = "user",
            name = "User",
            accessToken = "access",
            refreshToken = "refresh",
            datasetId = datasetId,
        ),
    )

    private fun largeNotifyFrame(payloadSize: Int): ByteArray = PacketFrames.encode(
        NotifyPayload(
            eventId = 7L,
            notifyType = NotifyType.MESSAGE_RECV.code,
            payload = ByteArray(payloadSize) { 0x41 },
        ),
    )

    private fun headerOf(frame: ByteArray): ByteArray = frame.copyOf(PacketFrames.HEADER_SIZE)

    @Test
    fun `single frame arrives byte by byte`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        val frame = authOkFrame()
        val decoded = mutableListOf<IProto>()
        frame.forEachIndexed { index, byte ->
            reader.feed(byteArrayOf(byte), 0, 1) { decoded.add(it) }
            if (index < frame.lastIndex) {
                assertTrue(reader.hasPendingBytes(), "半帧期间必须保留未消费字节")
            }
        }
        assertEquals(1, decoded.size)
        assertEquals(AuthResponsePayload.CODE_OK, (decoded.single() as AuthResponsePayload).code)
        assertFalse(reader.hasPendingBytes())
    }

    @Test
    fun `coalesced frames decode in arrival order`() {
        val reader = PacketFrameReader(PacketInboundRole.SERVER)
        val first = PacketFrames.encode(PingSignal)
        val second = PacketFrames.encode(PongSignal)
        val decoded = mutableListOf<IProto>()
        reader.feed(first + second, 0, first.size + second.size) { decoded.add(it) }
        assertEquals(listOf<IProto>(PingSignal, PongSignal), decoded)
        assertFalse(reader.hasPendingBytes())
    }

    @Test
    fun `auth ok widens the limit for a coalesced large frame`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        val auth = authOkFrame()
        val large = largeNotifyFrame(PacketFrames.UNAUTHED_LIMIT + 512)
        val decoded = mutableListOf<IProto>()
        // 与 PacketCodecSecurityTest 同场景：AUTH_RESP 与超限首个同步帧同批到达。
        reader.feed(auth + large, 0, auth.size + large.size) { decoded.add(it) }
        assertEquals(AuthResponsePayload.CODE_OK, (decoded[0] as AuthResponsePayload).code)
        assertEquals(NotifyType.MESSAGE_RECV.code, (decoded[1] as NotifyPayload).notifyType)
        assertEquals(PacketFrames.UNAUTHED_LIMIT + 512, (decoded[1] as NotifyPayload).payload!!.size)
    }

    @Test
    fun `large frame before auth is rejected from its header without buffering the payload`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        val large = largeNotifyFrame(PacketFrames.UNAUTHED_LIMIT + 1)
        assertFailsWith<ProtocolCorruptionException> {
            reader.feed(headerOf(large), 0, PacketFrames.HEADER_SIZE) { }
        }
    }

    @Test
    fun `owner confirmation also widens the limit`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        reader.authenticated = true
        val large = largeNotifyFrame(PacketFrames.UNAUTHED_LIMIT + 1)
        val decoded = mutableListOf<IProto>()
        reader.feed(large, 0, large.size) { decoded.add(it) }
        assertEquals(1, decoded.size)
    }

    @Test
    fun `wrong inbound direction is corruption`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        // NEGOTIATE 只允许服务端入站；客户端读到了它必须按损坏处理。
        val frame = PacketFrames.encode(com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload())
        assertFailsWith<ProtocolCorruptionException> {
            reader.feed(frame, 0, PacketFrames.HEADER_SIZE) { }
        }
    }

    @Test
    fun `signal frames with empty payload round trip`() {
        val reader = PacketFrameReader(PacketInboundRole.SERVER)
        val decoded = mutableListOf<IProto>()
        reader.feed(PacketFrames.encode(PingSignal), 0, PacketFrames.HEADER_SIZE) { decoded.add(it) }
        assertEquals(listOf<IProto>(PingSignal), decoded)
        assertFalse(reader.hasPendingBytes())
    }

    @Test
    fun `eof inside a frame keeps pending bytes visible`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        val frame = largeNotifyFrame(64)
        reader.feed(frame, 0, frame.size - 10) { }
        assertTrue(reader.hasPendingBytes())
    }

    @Test
    fun `unread bytes survive across feeds until the frame completes`() {
        val reader = PacketFrameReader(PacketInboundRole.CLIENT)
        val frame = authOkFrame()
        val decoded = mutableListOf<IProto>()
        // 任意切分点都必须等价：前 5 字节、再 3 字节、其余。
        reader.feed(frame, 0, 5) { decoded.add(it) }
        reader.feed(frame, 5, 8) { decoded.add(it) }
        reader.feed(frame, 8, frame.size) { decoded.add(it) }
        assertEquals(1, decoded.size)
    }

    @Test
    fun `frames after a mid-batch stop stay pending instead of being delivered`() {
        val reader = PacketFrameReader(PacketInboundRole.SERVER)
        val first = PacketFrames.encode(PingSignal)
        val second = PacketFrames.encode(PongSignal)
        val third = PacketFrames.encode(PingSignal)
        val delivered = mutableListOf<IProto>()
        reader.feed(first + second + third, 0, first.size + second.size + third.size,
            onPacket = { delivered.add(it) },
            keepReading = { delivered.size < 1 },
        )
        assertEquals(listOf<IProto>(PingSignal), delivered, "只投递到停止点")
        assertTrue(reader.hasPendingBytes(), "停止点之后的帧必须留在缓冲")
    }

    @Test
    fun `direct source read rolls back when payload is incomplete`() {
        val source = object : PacketByteSource {
            var bytes = ByteArray(0)
            private var position = 0
            private var mark = 0

            override fun readableBytes(): Int = bytes.size - position
            override fun markPosition() {
                mark = position
            }

            override fun reset() {
                position = mark
            }

            override fun readUnsignedByte(): Int = bytes[position++].toInt() and 0xFF

            override fun readIntBigEndian(): Int {
                var value = 0
                repeat(4) { value = (value shl 8) or (bytes[position++].toInt() and 0xFF) }
                return value
            }

            override fun readBytes(count: Int): ByteArray =
                bytes.copyOfRange(position, position + count).also { position += count }
        }
        val frame = authOkFrame()
        source.bytes = headerOf(frame)
        assertEquals(null, source.readPacketFrame(PacketInboundRole.CLIENT, PacketFrames.AUTHED_LIMIT))
        // 回滚后补充剩余字节应完整解出。
        source.bytes = frame
        val decoded = source.readPacketFrame(PacketInboundRole.CLIENT, PacketFrames.AUTHED_LIMIT)
        assertEquals(AuthResponsePayload.CODE_OK, (decoded!!.packet as AuthResponsePayload).code)
        assertTrue(decoded.widensPayloadLimit)
    }
}
