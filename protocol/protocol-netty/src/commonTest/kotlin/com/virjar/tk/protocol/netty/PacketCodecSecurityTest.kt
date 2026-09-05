package com.virjar.tk.protocol.netty

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolLimits
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.ConnectionTraceContextPayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.CorruptedFrameException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketCodecSecurityTest {
    @Test
    fun `bootstrap negotiation frames decode before authentication in their inbound roles`() {
        val request = com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload()
        val response = com.virjar.tk.protocol.ProtocolNegotiation.negotiate(request.supported, request.supported)
        val server = EmbeddedChannel(PacketCodec(inboundRole = PacketInboundRole.SERVER))
        val client = EmbeddedChannel(PacketCodec(inboundRole = PacketInboundRole.CLIENT))
        try {
            assertTrue(server.writeInbound(Unpooled.wrappedBuffer(encodeFrame(request))))
            assertEquals(request, server.readInbound<com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload>())
            assertTrue(client.writeInbound(Unpooled.wrappedBuffer(encodeFrame(response))))
            assertEquals(response, client.readInbound<com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload>())
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }
    @Test
    fun `connection trace control is client inbound only and round trips`() {
        val context = ConnectionTraceContext(
            correlationId = "correlation-token-0001",
            traceId = "trace-token-000000001",
            sessionId = "session-token-0000001",
            connectionGeneration = 4L,
            policyRevision = 7L,
            expiresAtEpochMs = 9_999_999L,
        )
        val update = ConnectionTraceContextPayload(
            correlationId = context.correlationId,
            connectionGeneration = context.connectionGeneration,
            policyRevision = context.policyRevision,
            context = context,
        )
        val frame = encodeFrame(update)
        val client = EmbeddedChannel(
            PacketCodec(
                maxPayloadLimit = PacketCodec.AUTHED_LIMIT,
                inboundRole = PacketInboundRole.CLIENT,
            ),
        )
        try {
            assertTrue(client.writeInbound(Unpooled.wrappedBuffer(frame)))
            assertEquals(update, client.readInbound())
        } finally {
            client.finishAndReleaseAll()
        }

        val server = serverChannel()
        val forbiddenHeader = Unpooled.buffer(PacketCodec.HEADER_SIZE).apply {
            writeByte(PacketType.CONNECTION_TRACE_CONTEXT.code)
            writeInt(PacketCodec.MAX_PAYLOAD_SIZE)
        }
        try {
            assertCorrupted { server.writeInbound(forbiddenHeader) }
        } finally {
            server.finishAndReleaseAll()
        }
    }

    @Test
    fun `successful auth without dataset identity is corruption and cannot raise the client limit`() {
        val payload = PacketBuffer().apply {
            writeVarInt(AuthResponsePayload.CODE_OK)
            repeat(6) { writeString(null) }
            writeVarLong(0L)
            writeString(null)
        }.toByteArray()
        val frame = Unpooled.buffer(PacketCodec.HEADER_SIZE + payload.size).apply {
            writeByte(PacketType.AUTH_RESP.code)
            writeInt(payload.size)
            writeBytes(payload)
        }
        val codec = PacketCodec(inboundRole = PacketInboundRole.CLIENT)
        val channel = EmbeddedChannel(codec)

        try {
            assertCorrupted { channel.writeInbound(frame) }
            assertEquals(PacketCodec.UNAUTHED_LIMIT, codec.maxPayloadLimit)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `client raises frame limit at auth before a coalesced large notify`() {
        val authFrame = encodeFrame(
            AuthResponsePayload(
                code = AuthResponsePayload.CODE_OK,
                uid = "u1",
                username = "user",
                name = "User",
                accessToken = "access",
                refreshToken = "refresh",
                datasetId = "00000000-0000-4000-8000-000000000001",
            ),
        )
        val largeEvent = NotifyPayload(
            eventId = 1L,
            notifyType = NotifyType.MESSAGE_RECV.code,
            payload = ByteArray(PacketCodec.UNAUTHED_LIMIT + 512) { 0x41 },
        )
        val notifyFrame = encodeFrame(largeEvent)
        val coalesced = Unpooled.buffer(authFrame.size + notifyFrame.size).apply {
            writeBytes(authFrame)
            writeBytes(notifyFrame)
        }
        val channel = EmbeddedChannel(PacketCodec(inboundRole = PacketInboundRole.CLIENT))

        try {
            assertTrue(channel.writeInbound(coalesced))
            assertEquals(AuthResponsePayload.CODE_OK, channel.readInbound<AuthResponsePayload>().code)
            val decodedEvent = channel.readInbound<NotifyPayload>()
            assertEquals(largeEvent.eventId, decodedEvent.eventId)
            assertEquals(largeEvent.notifyType, decodedEvent.notifyType)
            assertTrue(checkNotNull(decodedEvent.payload).contentEquals(checkNotNull(largeEvent.payload)))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `tiny frame with an impossible inner string length is corruption`() {
        val channel = EmbeddedChannel(PacketCodec())
        val frame = Unpooled.buffer().apply {
            writeByte(PacketType.MESSAGE.code)
            writeInt(6)
            writeByte(1)
            writeBytes(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07))
        }

        try {
            assertCorrupted { channel.writeInbound(frame) }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `server rejects a response direction from its header`() {
        val channel = serverChannel()
        val headerOnly = Unpooled.buffer(PacketCodec.HEADER_SIZE).apply {
            writeByte(PacketType.RESPONSE.code)
            writeInt(PacketCodec.MAX_PAYLOAD_SIZE)
        }

        try {
            assertCorrupted { channel.writeInbound(headerOnly) }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `signals reject a nonzero payload from their header`() {
        listOf(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT).forEach { packetType ->
            val channel = serverChannel()
            val headerOnly = Unpooled.buffer(PacketCodec.HEADER_SIZE).apply {
                writeByte(packetType.code)
                writeInt(PacketCodec.MAX_PAYLOAD_SIZE)
            }

            try {
                assertCorrupted { channel.writeInbound(headerOnly) }
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `decoded payload must consume the complete frame`() {
        val payload = PacketBuffer().apply {
            InvokePayload(1, "service", 2, byteArrayOf(3)).writeTo(this)
            writeByte(0x42)
        }.toByteArray()
        val frame = Unpooled.buffer(PacketCodec.HEADER_SIZE + payload.size).apply {
            writeByte(PacketType.INVOKE.code)
            writeInt(payload.size)
            writeBytes(payload)
        }
        val channel = serverChannel()

        try {
            assertCorrupted { channel.writeInbound(frame) }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun serverChannel() = EmbeddedChannel(
        PacketCodec(
            maxPayloadLimit = PacketCodec.AUTHED_LIMIT,
            inboundRole = PacketInboundRole.SERVER,
        ),
    )

    private fun assertCorrupted(block: () -> Unit) {
        val failure = assertFailsWith<Throwable> { block() }
        assertTrue(
            generateSequence(failure as Throwable?) { it.cause }.any { it is CorruptedFrameException },
            "frame must fail as corruption; actual=${failure::class.simpleName}",
        )
    }

    private fun encodeFrame(proto: IProto): ByteArray {
        val channel = EmbeddedChannel(PacketCodec(maxPayloadLimit = PacketCodec.AUTHED_LIMIT))
        return try {
            channel.writeOutbound(proto)
            val frame = channel.readOutbound<ByteBuf>()
            try {
                ByteArray(frame.readableBytes()).also(frame::readBytes)
            } finally {
                frame.release()
            }
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
