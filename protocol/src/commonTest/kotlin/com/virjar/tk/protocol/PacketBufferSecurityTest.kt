package com.virjar.tk.protocol

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.body.CardBody
import com.virjar.tk.body.EditBody
import com.virjar.tk.body.ForwardBody
import com.virjar.tk.body.InteractiveCardBody
import com.virjar.tk.body.LocationBody
import com.virjar.tk.body.MergeForwardBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.ReactionBody
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.RevokeBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.TextBody
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.AuthPayloadPolicy
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.SubscribePayload
import com.virjar.tk.protocol.payload.UnsubscribePayload
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.CorruptedFrameException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketBufferSecurityTest {

    @Test
    fun `varints reject overflow and continuation beyond their wire width`() {
        assertEquals(Int.MAX_VALUE, buffer(0xff, 0xff, 0xff, 0xff, 0x07).readVarInt())
        assertFailsWith<CorruptedFrameException> {
            buffer(0xff, 0xff, 0xff, 0xff, 0x0f).readVarInt()
        }
        assertFailsWith<CorruptedFrameException> {
            buffer(0x80, 0x80, 0x80, 0x80, 0x80).readVarInt()
        }
        assertFailsWith<CorruptedFrameException> {
            buffer(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80).readVarLong()
        }
    }

    @Test
    fun `length delimited values reject impossible allocation before creating arrays`() {
        val declaredIntMax = intArrayOf(1, 0xff, 0xff, 0xff, 0xff, 0x07)
        assertFailsWith<CorruptedFrameException> { buffer(*declaredIntMax).readString() }
        assertFailsWith<CorruptedFrameException> { buffer(*declaredIntMax).readBytes() }
    }

    @Test
    fun `tiny rich text payload cannot preallocate an unbounded mentions list`() {
        val byteBuf = Unpooled.buffer()
        try {
            val writer = PacketBuffer(byteBuf)
            writer.writeString("")
            writer.writeVarInt(Int.MAX_VALUE)

            assertFailsWith<CorruptedFrameException> {
                RichTextBody.readFrom(PacketBuffer(byteBuf))
            }
        } finally {
            byteBuf.release()
        }
    }

    @Test
    fun `packet codec rejects tiny frame with impossible inner string length`() {
        val channel = EmbeddedChannel(PacketCodec())
        val frame = Unpooled.buffer()
        frame.writeByte(PacketType.MESSAGE.code)
        frame.writeInt(6)
        frame.writeByte(1)
        frame.writeBytes(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07))

        try {
            val failure = assertFailsWith<Throwable> { channel.writeInbound(frame) }
            assertTrue(
                generateSequence(failure as Throwable?) { it.cause }.any { it is CorruptedFrameException },
                "恶意帧必须作为损坏帧失败，实际为 ${failure::class.simpleName}",
            )
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `message envelope and every string based body reject oversized wire fields before allocation`() {
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH)) {
            Message.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH)) {
            TextBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH)) {
            RichTextBody.readFrom(it)
        }
        assertDeclaredStringRejected(
            MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_INTERACTIVE_CARD_JSON_LENGTH),
        ) {
            InteractiveCardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            ReplyBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            ForwardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH)) {
            MergeForwardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            RevokeBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            EditBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            ReactionBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH)) {
            CardBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_COORDINATE_TEXT_LENGTH)) {
            LocationBody.readFrom(it)
        }
        assertDeclaredStringRejected(MessageBodyPolicy.utf8WireLimit(AttachmentPolicy.MAX_REFERENCE_LENGTH)) {
            Attachment.readFrom(it)
        }
    }

    @Test
    fun `message body presence marker is strictly boolean`() {
        val byteBuf = Unpooled.buffer()
        try {
            val writer = PacketBuffer(byteBuf)
            writer.writeString("chat")
            writer.writeString("client-message")
            writer.writeVarLong(0)
            writer.writeString("sender")
            writer.writeByte(MessageType.TYPING.code)
            writer.writeVarLong(0)
            writer.writeVarInt(0)
            writer.writeByte(2)

            assertFailsWith<CorruptedFrameException> { Message.readFrom(PacketBuffer(byteBuf)) }
        } finally {
            byteBuf.release()
        }
    }

    @Test
    fun `invoke payload rejects more than four mebibytes from a tiny declaration`() {
        val byteBuf = Unpooled.buffer()
        try {
            val writer = PacketBuffer(byteBuf)
            writer.writeVarInt(1)
            writer.writeString("service")
            writer.writeVarInt(1)
            writer.writeByte(1)
            writer.writeVarInt(InvokePayload.MAX_INVOKE_PAYLOAD_BYTES + 1)

            assertFailsWith<CorruptedFrameException> { InvokePayload.readFrom(PacketBuffer(byteBuf)) }
        } finally {
            byteBuf.release()
        }
    }

    @Test
    fun `subscribe payloads reject oversized chat ids before allocation`() {
        val maximumBytes = MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH)
        assertDeclaredStringRejected(maximumBytes) { SubscribePayload.readFrom(it) }
        assertDeclaredStringRejected(maximumBytes) { UnsubscribePayload.readFrom(it) }
    }

    @Test
    fun `every auth request string has a strict wire budget`() {
        val limits = listOf(
            AuthPayloadPolicy.MAX_USERNAME_LENGTH,
            AuthPayloadPolicy.MAX_PASSWORD_LENGTH,
            AuthPayloadPolicy.MAX_NAME_LENGTH,
            AuthPayloadPolicy.MAX_TOKEN_LENGTH,
            AuthPayloadPolicy.MAX_DEVICE_ID_LENGTH,
            AuthPayloadPolicy.MAX_DEVICE_NAME_LENGTH,
            AuthPayloadPolicy.MAX_DEVICE_MODEL_LENGTH,
        ).map(AuthPayloadPolicy::utf8WireLimit)

        limits.indices.forEach { oversizedField ->
            val byteBuf = Unpooled.buffer()
            try {
                val writer = PacketBuffer(byteBuf)
                writer.writeByte(AuthRequestPayload.PREAMBLE_HIGH)
                writer.writeByte(AuthRequestPayload.PREAMBLE_LOW)
                writer.writeByte(PacketCodec.PROTOCOL_VERSION.toInt())
                writer.writeByte(0x01)
                writer.writeVarInt(0)
                limits.forEachIndexed { field, maximumBytes ->
                    if (field == oversizedField) {
                        writer.writeByte(1)
                        writer.writeVarInt(maximumBytes + 1)
                    } else {
                        // deviceId（索引 4）非空；其余可选字段用 null 保持 wire 最小。
                        writer.writeString(if (field == 4) "device" else null)
                    }
                }
                writer.writeVarInt(0)
                writer.writeVarLong(0)

                assertFailsWith<CorruptedFrameException>("auth field $oversizedField must be bounded") {
                    AuthRequestPayload.readFrom(PacketBuffer(byteBuf))
                }
            } finally {
                byteBuf.release()
            }
        }
    }

    @Test
    fun `server role rejects response direction from header before accumulating payload`() {
        val channel = EmbeddedChannel(
            PacketCodec(
                maxPayloadLimit = PacketCodec.AUTHED_LIMIT,
                inboundRole = PacketInboundRole.SERVER,
            ),
        )
        val headerOnly = Unpooled.buffer(PacketCodec.HEADER_SIZE).apply {
            writeByte(PacketType.RESPONSE.code)
            writeInt(PacketCodec.MAX_PAYLOAD_SIZE)
        }

        try {
            val failure = assertFailsWith<Throwable> { channel.writeInbound(headerOnly) }
            assertTrue(
                generateSequence(failure as Throwable?) { it.cause }.any { it is CorruptedFrameException },
                "方向错误的大帧必须只凭帧头拒绝，实际为 ${failure::class.simpleName}",
            )
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `signal packets reject nonzero payload from the header without accumulating padding`() {
        listOf(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT).forEach { packetType ->
            val channel = EmbeddedChannel(
                PacketCodec(
                    maxPayloadLimit = PacketCodec.AUTHED_LIMIT,
                    inboundRole = PacketInboundRole.SERVER,
                ),
            )
            val headerOnly = Unpooled.buffer(PacketCodec.HEADER_SIZE).apply {
                writeByte(packetType.code)
                writeInt(PacketCodec.MAX_PAYLOAD_SIZE)
            }

            try {
                val failure = assertFailsWith<Throwable>("$packetType must require an empty payload") {
                    channel.writeInbound(headerOnly)
                }
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }.any { it is CorruptedFrameException },
                    "非零信号帧必须作为损坏帧失败，实际为 ${failure::class.simpleName}",
                )
            } finally {
                channel.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `decoded payload must consume the complete frame`() {
        val payload = Unpooled.buffer()
        SubscribePayload("chat", lastSeq = 7).writeTo(PacketBuffer(payload))
        payload.writeByte(0x42)
        val frame = Unpooled.buffer(PacketCodec.HEADER_SIZE + payload.readableBytes()).apply {
            writeByte(PacketType.SUBSCRIBE.code)
            writeInt(payload.readableBytes())
            writeBytes(payload)
        }
        payload.release()
        val channel = EmbeddedChannel(
            PacketCodec(
                maxPayloadLimit = PacketCodec.AUTHED_LIMIT,
                inboundRole = PacketInboundRole.SERVER,
            ),
        )

        try {
            val failure = assertFailsWith<Throwable> { channel.writeInbound(frame) }
            assertTrue(
                generateSequence(failure as Throwable?) { it.cause }.any { it is CorruptedFrameException },
                "尾随 payload 必须作为损坏帧失败，实际为 ${failure::class.simpleName}",
            )
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun assertDeclaredStringRejected(maximumBytes: Int, reader: (PacketBuffer) -> Any?) {
        val byteBuf = Unpooled.buffer()
        try {
            val writer = PacketBuffer(byteBuf)
            writer.writeByte(1)
            writer.writeVarInt(maximumBytes + 1)
            assertFailsWith<CorruptedFrameException> { reader(PacketBuffer(byteBuf)) }
        } finally {
            byteBuf.release()
        }
    }

    private fun buffer(vararg bytes: Int): PacketBuffer = PacketBuffer(
        Unpooled.wrappedBuffer(ByteArray(bytes.size) { index -> bytes[index].toByte() }),
    )
}
