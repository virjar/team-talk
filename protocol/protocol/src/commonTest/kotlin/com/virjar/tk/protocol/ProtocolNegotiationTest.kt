package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProtocolNegotiationTest {
    @Test
    fun `same major chooses highest intersection and distinguishes both upgrade directions`() {
        val server = ProtocolRange(0, 2, 5)
        val older = ProtocolNegotiation.negotiate(ProtocolRange(0, 0, 3), server, "0.0.1")
        assertEquals(ProtocolVersion(0, 3), older.negotiated)
        assertEquals(ProtocolVersion(0, 5), ProtocolNegotiation.negotiate(ProtocolRange(0, 1, 8), server).negotiated)
        assertEquals(ProtocolNegotiateResponsePayload.CODE_CLIENT_TOO_OLD, ProtocolNegotiation.negotiate(ProtocolRange(0, 0, 1), server).code)
        assertEquals(ProtocolNegotiateResponsePayload.CODE_SERVER_TOO_OLD, ProtocolNegotiation.negotiate(ProtocolRange(0, 6, 8), server).code)
        assertEquals(ProtocolNegotiateResponsePayload.CODE_MAJOR_UNSUPPORTED, ProtocolNegotiation.negotiate(ProtocolRange(1, 0, 0), server).code)
        ProtocolNegotiation.requireValidResponse(ProtocolRange(0, 0, 3), older)
        assertFailsWith<IllegalArgumentException> {
            ProtocolNegotiation.requireValidResponse(ProtocolRange(0, 0, 3), older.copy(negotiated = ProtocolVersion(0, 4)))
        }
    }

    @Test
    fun `bootstrap request and response retain exact golden layout`() {
        val request = ProtocolNegotiateRequestPayload(ProtocolRange(0, 0, 0), "0.0.0")
        val requestBytes = byteArrayOf(0, 0, 0, 1, 5, 48, 46, 48, 46, 48)
        assertContentEquals(requestBytes, ProtoCodec.encode(request))
        assertEquals(request, ProtoCodec.decode(ProtocolNegotiateRequestPayload, requestBytes))
        val response = ProtocolNegotiation.negotiate(request.supported, request.supported, "0.0.0")
        val responseBytes = byteArrayOf(0, 0, 0, 0, 1, 0, 0, 1, 5, 48, 46, 48, 46, 48)
        assertContentEquals(responseBytes, ProtoCodec.encode(response))
        assertEquals(response, ProtoCodec.decode(ProtocolNegotiateResponsePayload, responseBytes))
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(ProtocolNegotiateRequestPayload, requestBytes + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { ProtocolNegotiateRequestPayload(clientReleaseVersion = "01.0.0") }
    }

    @Test
    fun `packed protocol IDs and availability have no release string dependency`() {
        val maximum = ProtocolVersion(32767, 65535)
        assertEquals(Int.MAX_VALUE, maximum.id)
        assertEquals(maximum, ProtocolVersion.fromId(Int.MAX_VALUE))
        assertEquals(ProtocolVersion(1, 2), ProtocolVersion.fromId(65538))
        assertFailsWith<IllegalArgumentException> { ProtocolVersion.fromId(-1) }
        assertFalse(ProtocolAvailability(1, 3).supports(ProtocolVersion(ProtocolVersions.MAJOR, 3)))
    }

    @Test
    fun `message type header inspection does not need or consume body bytes`() {
        val message = Message("chat", "message", 7, "sender", MessageType.RICH_TEXT.code, 1)
        assertEquals(message.messageType, Message.readMessageType(ProtoCodec.encode(message)))
        val headerOnly = ProtoCodec.encodePayload {
            writeString(message.chatId)
            writeString(message.clientMsgId)
            writeVarLong(message.serverSeq)
            writeString(message.senderUid)
            writeByte(message.messageType)
        }
        assertEquals(message.messageType, Message.readMessageType(headerOnly))
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(Message, headerOnly) }
    }
}
