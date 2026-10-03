package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProtocolEventProjectionTest {
    @Test
    fun `private draft changes preserve old cursors without exposing new payloads`() {
        val event = NotifyPayload(23L, NotifyType.CHAT_DRAFT_CHANGED.code,
            ProtoCodec.encode(com.virjar.tk.protocol.ChatDraftChangedPayload("chat-a", 1)))
        assertEquals(NotifyPayload(23L, NotifyType.EVENT_CURSOR_ADVANCED.code, null), eventFrameForProtocol(event, ProtocolVersion(0, 1)))
        assertSame(event, eventFrameForProtocol(event, ProtocolVersion(0, 2)))
    }
    @Test
    fun `task events advance old cursors while message references project as placeholders`() {
        val taskId = "bb161090-0261-4c0f-9bde-9af48557d3f0"
        val message = Message("chat-a", "message-a", 3L, "user-a", com.virjar.tk.protocol.MessageType.TASK_REF.code, 1L,
            body = com.virjar.tk.protocol.body.TaskRefBody(taskId, "任务标题", "任务 · 待处理"))
        val events = listOf(
            NotifyPayload(20L, NotifyType.TASK_CHANGED.code, ProtoCodec.encode(com.virjar.tk.protocol.TaskChangedPayload(taskId, 1L, 1))),
            NotifyPayload(21L, NotifyType.TASK_DUE.code, ProtoCodec.encode(com.virjar.tk.protocol.TaskDuePayload(taskId, 1L, 100L))),
            NotifyPayload(22L, NotifyType.MESSAGE_RECV.code, ProtoCodec.encode(message)),
        )
        val old = eventFrameForProtocol(SyncBatchPayload(events), ProtocolVersion(0, 1)) as SyncBatchPayload
        assertEquals(listOf(20L, 21L, 22L), old.events.map { it.eventId })
        // 旧版本可解的 notify（TASK_CHANGED/DUE）降级为游标推进
        old.events.take(2).forEach { assertEquals(NotifyType.EVENT_CURSOR_ADVANCED.code, it.notifyType); assertNull(it.payload) }
        // 消息事件不跳过：占位形态保留身份与类型码、剥正文、打标记位——旧客户端红点
        // 背后有可见的"当前版本不支持"，且 hasBody=false 可被旧解码器干净解析
        val projectedMessage = old.events.last()
        assertEquals(NotifyType.MESSAGE_RECV.code, projectedMessage.notifyType)
        val placeholder = ProtoCodec.decode(Message, checkNotNull(projectedMessage.payload))
        assertEquals(message.clientMsgId, placeholder.clientMsgId)
        assertEquals(message.serverSeq, placeholder.serverSeq)
        assertEquals(com.virjar.tk.protocol.MessageType.TASK_REF.code, placeholder.messageType)
        assertEquals(null, placeholder.body)
        assertEquals(0, placeholder.flags and Message.FLAG_PROJECTION_PLACEHOLDER.inv())
        val current = eventFrameForProtocol(SyncBatchPayload(events), ProtocolVersion(0, 2)) as SyncBatchPayload
        assertEquals(events, current.events)
    }

    @Test
    fun `document changes preserve old client cursors and reach minor two clients`() {
        val event = NotifyPayload(12L, NotifyType.DOCUMENT_CHANGED.code, byteArrayOf(1, 2, 3))
        assertEquals(
            NotifyPayload(12L, NotifyType.EVENT_CURSOR_ADVANCED.code, null),
            eventFrameForProtocol(event, ProtocolVersion(0, 1)),
        )
        assertSame(event, eventFrameForProtocol(event, ProtocolVersion(0, 2)))
    }

    @Test
    fun `contact updates are available in the zero release baseline`() {
        val event = NotifyPayload(8L, NotifyType.CONTACT_UPDATED.code, byteArrayOf(1, 2, 3))
        assertSame(event, eventFrameForProtocol(event, ProtocolVersion(0, 0)))
        val batch = eventFrameForProtocol(SyncBatchPayload(listOf(event)), ProtocolVersion(0, 0)) as SyncBatchPayload
        assertSame(event, batch.events.single())
    }

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
            flags = Message.FLAG_FORWARDED,
        )
        val event = NotifyPayload(11L, NotifyType.MESSAGE_RECV.code, ProtoCodec.encode(message))
        val projected = eventFrameForProtocol(event, ProtocolVersions.CURRENT) as NotifyPayload
        assertEquals(11L, projected.eventId)
        assertEquals(NotifyType.MESSAGE_RECV.code, projected.notifyType)
        val placeholder = ProtoCodec.decode(Message, checkNotNull(projected.payload))
        assertEquals(message.clientMsgId, placeholder.clientMsgId)
        assertEquals(255, placeholder.messageType)
        assertEquals(null, placeholder.body)
        // 既有 flags 保留，仅叠加投影标记位
        assertEquals(Message.FLAG_FORWARDED or Message.FLAG_PROJECTION_PLACEHOLDER, placeholder.flags)
    }

    @Test
    fun `available notifications preserve their exact payload`() {
        val event = NotifyPayload(7L, NotifyType.GROUP_FILE_CHANGED.code, byteArrayOf(1, 2, 3))
        assertSame(event, eventFrameForProtocol(event, ProtocolVersions.CURRENT))
    }
}
