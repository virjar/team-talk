package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.StreamEndPayload
import com.virjar.tk.protocol.payload.StreamItemPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolBaselineTest {

    @Test
    fun `dense current RPC contracts pin the consolidated protocol epoch`() {
        assertEquals(ProtocolVersion(0, 0), ProtocolVersions.CURRENT)
        assertEquals(9, PacketType.SYNC_RESET.code)
        val datasetId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val request = SyncRequestPayload(lastEventId = 41L, datasetId = datasetId)
        assertEquals(request, ProtoCodec.decode(SyncRequestPayload, ProtoCodec.encode(request)))
        val reset = SyncResetPayload(datasetId)
        assertEquals(reset, ProtoCodec.decode(SyncResetPayload, ProtoCodec.encode(reset)))
        val auth = AuthResponsePayload(
            code = AuthResponsePayload.CODE_OK,
            uid = "uid-1",
            username = "user-1",
            name = "User 1",
            accessToken = "access",
            refreshToken = "refresh",
            datasetId = datasetId,
        )
        assertEquals(auth, ProtoCodec.decode(AuthResponsePayload, ProtoCodec.encode(auth)))
        assertFailsWith<IllegalArgumentException> { SyncRequestPayload(0L, "") }
        assertFailsWith<IllegalArgumentException> {
            SyncRequestPayload(0L, datasetId.uppercase())
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(
                SyncRequestPayload,
                ProtoCodec.encodePayload {
                    writeVarLong(0L)
                    writeString(datasetId.uppercase())
                },
            )
        }
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(AuthResponsePayload(code = AuthResponsePayload.CODE_OK))
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(
                AuthResponsePayload,
                ProtoCodec.encodePayload {
                    writeVarInt(AuthResponsePayload.CODE_OK)
                    repeat(6) { writeString(null) }
                    writeVarLong(0L)
                    writeString(null)
                },
            )
        }
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(
                AuthResponsePayload(
                    code = AuthResponsePayload.CODE_AUTH_FAILED,
                    datasetId = datasetId,
                ),
            )
        }
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
                "NEGOTIATE" to 14,
                "NEGOTIATE_RESP" to 15,
                "MESSAGE" to 20,
                "MESSAGE_ACK" to 21,
                "NOTIFY" to 30,
                "CONNECTION_TRACE_CONTEXT" to 31,
            ),
            PacketType.entries.map { it.name to it.code },
        )
        val streamItem = StreamItemPayload(requestId = 7, payload = null)
        val streamEnd = StreamEndPayload(requestId = 7, status = 0, payload = null)
        assertEquals(streamItem, ProtoCodec.decode(StreamItemPayload, ProtoCodec.encode(streamItem)))
        assertEquals(streamEnd, ProtoCodec.decode(StreamEndPayload, ProtoCodec.encode(streamEnd)))
    }

    @Test
    fun `message acknowledgement preserves composite message identity`() {
        val ack = MessageAckPayload(
            chatId = "chat-1",
            clientMsgId = "client-message-1",
            serverSeq = 42L,
            code = 0,
        )

        assertEquals(ack, ProtoCodec.decode(MessageAckPayload, ProtoCodec.encode(ack)))
    }

    @Test
    fun `markdown is the only text message wire type`() {
        assertEquals(1, MessageType.RICH_TEXT.code)
        assertEquals(16, MessageType.INTERACTIVE_CARD.code)
        assertEquals((1..17).toList(), MessageType.entries.map(MessageType::code))
        assertEquals(MessageType.entries.size, MessageType.entries.map(MessageType::code).toSet().size)
    }

    @Test
    fun `cursor advancement is an explicit payloadless projection`() {
        val marker = NotifyPayload(eventId = 8, notifyType = NotifyType.EVENT_CURSOR_ADVANCED.code, payload = null)
        assertEquals(62, NotifyType.EVENT_CURSOR_ADVANCED.code)
        assertEquals(marker, ProtoCodec.decode(NotifyPayload, ProtoCodec.encode(marker)))
        assertEquals(setOf(NotifyType.EVENT_CURSOR_ADVANCED), NotifyContracts.exempt)
        assertNull(MessageType.fromCode(99))
        assertFailsWith<IllegalArgumentException> { NotifyType.fromCode(99) }
    }

    @Test
    fun `standalone typed payloads reject trailing bytes`() {
        val body = ReadSyncPayload(peerUid = "u2", chatId = "chat-1", peerReadSeq = 7)
        val encoded = ProtoCodec.encode(body)
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ReadSyncPayload, encoded + byteArrayOf(0))
        }
    }

    @Test
    fun `reply payload requires its embedded asset count after markdown content`() {
        val truncatedPayload = ProtoCodec.encodePayload {
            writeString("message-1")
            writeString("user-1")
            writeString(null)
            writeString(null)
            writeString("reply")
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.withPayload(truncatedPayload) { ReplyBody.readFrom(this) }
        }
    }
}
