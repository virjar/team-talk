package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProtocolEventProjectionTest {
    @Test
    fun `unsupported durable events retain every cursor while transient events are dropped`() {
        val unknown = NotifyPayload(eventId = 9L, notifyType = 255, payload = byteArrayOf(1))
        val expected = NotifyPayload(9L, NotifyType.EVENT_CURSOR_ADVANCED.code, null)
        assertEquals(expected, eventFrameForProtocol(unknown, ProtocolVersions.CURRENT))
        assertNull(eventFrameForProtocol(unknown.copy(eventId = 0L), ProtocolVersions.CURRENT))

        val originalPage = SyncBatchPayload(listOf(unknown, unknown.copy(eventId = 10L)))
        val projected = eventFrameForProtocol(originalPage, ProtocolVersions.CURRENT) as SyncBatchPayload
        assertEquals(listOf(9L, 10L), projected.events.map { it.eventId })
        assertEquals(listOf(62, 62), projected.events.map { it.notifyType })
        assertEquals(listOf(255, 255), originalPage.events.map { it.notifyType })
    }

    @Test
    fun `known message notification cannot carry a newer unsupported message body`() {
        val message = Message(
            chatId = "chat-a", clientMsgId = "message-a", serverSeq = 3L,
            senderUid = "user-a", messageType = 255, timestamp = 1L,
        )
        val event = NotifyPayload(11L, NotifyType.MESSAGE_RECV.code, ProtoCodec.encode(message))
        val projected = eventFrameForProtocol(event, ProtocolVersions.CURRENT) as NotifyPayload
        assertEquals(11L, projected.eventId)
        assertEquals(NotifyType.EVENT_CURSOR_ADVANCED.code, projected.notifyType)
        assertNull(projected.payload)
    }

    @Test
    fun `available notifications preserve their exact payload`() {
        val event = NotifyPayload(7L, NotifyType.GROUP_FILE_CHANGED.code, byteArrayOf(1, 2, 3))
        assertSame(event, eventFrameForProtocol(event, ProtocolVersions.CURRENT))
    }
}
