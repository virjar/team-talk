package com.virjar.tk.client

import com.virjar.tk.model.Chat
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import com.virjar.tk.testing.FakeLocalCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EventProcessor 全 NOTIFY 类型处理语义（绕过监听协程，直调 handleNotifyPayload）。
 * 覆盖：缓存写入、事件流发射（message/contact/chat/presence）、契约解码路径。
 */
class EventProcessorTest {

    private val cache = FakeLocalCache()
    private val ep = EventProcessor(ImClient(), cache)

    @Test
    fun `MESSAGE_RECV - 写缓存并发 messageEvents`() = runBlocking {
        val msg = Message(chatId = "c1", clientMsgId = "m1", serverSeq = 3, senderUid = "u2", messageType = 1, timestamp = 1)
        val received = launch { withTimeout(2000) { assertEquals(msg, ep.messageEvents.first()) } }
        kotlinx.coroutines.delay(50) // 确保订阅先建立
        ep.handleNotifyPayload(NotifyType.MESSAGE_RECV, ProtoCodec.encode(msg))
        received.join()
        assertEquals(1, cache.getMessages("c1").size)
    }

    @Test
    fun `CONTACT_APPLY - 转换视角入库并发 contactEvents`() = runBlocking {
        val apply = com.virjar.tk.model.ContactApply(id = 1, fromUid = "u2", toUid = "me", token = "t", remark = "hi", fromUser = User(uid = "u2", username = "u2", name = "U2"))
        val received = launch { withTimeout(2000) { ep.contactEvents.first() } }
        kotlinx.coroutines.delay(50)
        ep.handleNotifyPayload(NotifyType.CONTACT_APPLY, ProtoCodec.encode(apply))
        received.join()
        val contact = cache.getContacts().single()
        assertEquals("me", contact.uid, "uid=接收者视角")
        assertEquals("u2", contact.friendUid)
        assertEquals("u2", contact.user?.uid, "user=申请方资料")
    }

    @Test
    fun `CHAT_CREATED - upsertChat 发 chatEvents 触发 dirty`() = runBlocking {
        var dirty = false
        val ep2 = EventProcessor(ImClient(), cache, onConversationsDirty = { dirty = true })
        val chat = Chat(chatId = "g1", chatType = 2, name = "群")
        val received = launch { withTimeout(2000) { assertEquals(NotifyType.CHAT_CREATED, ep2.chatEvents.first().first) } }
        kotlinx.coroutines.delay(50)
        ep2.handleNotifyPayload(NotifyType.CHAT_CREATED, ProtoCodec.encode(chat))
        received.join()
        assertEquals("g1", cache.getChat("g1")?.chatId)
        assertTrue(dirty, "CHAT_CREATED 必须触发会话重拉（被拉入群场景）")
    }

    @Test
    fun `READ_SYNC - 推进 peerReadSeq`() = runBlocking {
        cache.upsertConversation(com.virjar.tk.model.Conversation(chatId = "c1", chatType = 1))
        ep.handleNotifyPayload(NotifyType.READ_SYNC, ProtoCodec.encode(ReadSyncPayload("u2", "c1", 42)))
        assertEquals(42L, cache.getConversations().first { it.chatId == "c1" }.peerReadSeq)
    }

    @Test
    fun `PRESENCE - 契约解码头 presenceEvents`() = runBlocking {
        val received = launch { withTimeout(2000) { assertEquals("u9", ep.presenceEvents.first().uid) } }
        kotlinx.coroutines.delay(50)
        ep.handleNotifyPayload(NotifyType.PRESENCE, ProtoCodec.encode(PresencePayload("u9", 1, 123L)))
        received.join()
    }

    @Test
    fun `USER_UPDATED - upsertUser`() = runBlocking {
        ep.handleNotifyPayload(NotifyType.USER_UPDATED, ProtoCodec.encode(User(uid = "u1", username = "a", name = "A")))
        assertEquals("A", cache.getUser("u1")?.name)
    }
}
