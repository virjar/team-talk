package com.virjar.tk.protocol

import com.virjar.tk.body.GenericPayload
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.StreamEndPayload
import com.virjar.tk.protocol.payload.StreamItemPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import com.virjar.tk.rpc.GenericRpcContract
import io.netty.handler.codec.CorruptedFrameException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolBaselineTest {

    @Test
    fun `pre-release wire baseline starts at epoch zero`() {
        assertEquals(0, PacketCodec.PROTOCOL_VERSION.toInt())
        assertEquals(9, PacketType.SYNC_RESET.code)
        assertEquals(SyncResetPayload, ProtoCodec.decode(SyncResetPayload, byteArrayOf()))
    }

    @Test
    fun `packet type baseline retains reserved stream entries`() {
        assertEquals(
            listOf(
                "AUTH" to 1,
                "AUTH_RESP" to 2,
                "DISCONNECT" to 3,
                "PING" to 4,
                "PONG" to 5,
                "SYNC_REQUEST" to 6,
                "SYNC_BATCH" to 7,
                "SYNC_READY" to 8,
                "SYNC_RESET" to 9,
                "INVOKE" to 10,
                "RESPONSE" to 11,
                "STREAM_ITEM" to 12,
                "STREAM_END" to 13,
                "MESSAGE" to 20,
                "MESSAGE_ACK" to 21,
                "NOTIFY" to 30,
            ),
            PacketType.entries.map { it.name to it.code },
        )
        val streamItem = StreamItemPayload(requestId = 7, payload = null)
        val streamEnd = StreamEndPayload(requestId = 7, status = 0, payload = null)
        assertEquals(streamItem, ProtoCodec.decode(StreamItemPayload, ProtoCodec.encode(streamItem)))
        assertEquals(streamEnd, ProtoCodec.decode(StreamEndPayload, ProtoCodec.encode(streamEnd)))
    }

    @Test
    fun `markdown is the only text message wire type`() {
        assertEquals(1, MessageType.RICH_TEXT.code)
        assertEquals(16, MessageType.INTERACTIVE_CARD.code)
        assertEquals((1..16).toList() + 99, MessageType.entries.map(MessageType::code))
        assertEquals(MessageType.entries.size, MessageType.entries.map(MessageType::code).toSet().size)
    }

    @Test
    fun `three generic extension entrances remain reserved at stable wire ids`() {
        assertEquals("generic", GenericRpcContract.SERVICE)
        assertEquals(99, NotifyType.GENERIC.code)
        assertEquals(99, MessageType.GENERIC.code)

        // The candidate zone is deliberately empty today. Keeping this compile-time function
        // reference also locks the RPC rule methodId = ExtensionType.code for the first entry.
        val rpcMethodId: (ExtensionType) -> Int = GenericRpcContract::methodId
        assertTrue(ExtensionType.entries.all { rpcMethodId(it) == it.code })
        assertTrue(ExtensionType.entries.isEmpty())
        assertNull(ExtensionType.fromCode(0))
    }

    @Test
    fun `standalone proto payloads reject trailing bytes`() {
        val encoded = ProtoCodec.encode(GenericPayload(extensionType = 7, data = byteArrayOf(1, 2)))

        assertFailsWith<CorruptedFrameException> {
            ProtoCodec.decode(GenericPayload, encoded + byteArrayOf(0))
        }
    }

    @Test
    fun `generic payload round trips through notify and message with strict consumption`() {
        val body = GenericPayload(extensionType = 7, data = byteArrayOf(0, 1, 2, 0x7f))
        val message = Message(
            chatId = "chat-1",
            clientMsgId = "generic-1",
            serverSeq = 3,
            senderUid = "user-1",
            messageType = MessageType.GENERIC.code,
            timestamp = 4,
            body = body,
        )
        val encodedMessage = ProtoCodec.encode(message)
        val decodedMessage = ProtoCodec.decode(Message, encodedMessage)
        assertEquals(body, decodedMessage.body)
        assertFailsWith<CorruptedFrameException> {
            ProtoCodec.decode(Message, encodedMessage + byteArrayOf(0))
        }

        val notify = NotifyPayload(
            eventId = 8,
            notifyType = NotifyType.GENERIC.code,
            payload = ProtoCodec.encode(body),
        )
        val encodedNotify = ProtoCodec.encode(notify)
        val decodedNotify = ProtoCodec.decode(NotifyPayload, encodedNotify)
        assertEquals(body, ProtoCodec.decode(GenericPayload, requireNotNull(decodedNotify.payload)))
        assertFailsWith<CorruptedFrameException> {
            ProtoCodec.decode(NotifyPayload, encodedNotify + byteArrayOf(0))
        }
    }

    @Test
    fun `reply payload requires its markdown content field`() {
        val truncatedPayload = ProtoCodec.encodePayload {
            writeString("message-1")
            writeString("user-1")
            writeString(null)
            writeString(null)
        }

        assertFailsWith<IndexOutOfBoundsException> {
            ProtoCodec.withPayload(truncatedPayload) { ReplyBody.readFrom(this) }
        }
    }
}
