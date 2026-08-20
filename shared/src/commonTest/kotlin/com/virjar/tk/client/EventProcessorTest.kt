package com.virjar.tk.client

import com.virjar.tk.body.GenericPayload
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Message
import com.virjar.tk.model.Member
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.repository.ConversationRepository
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EventProcessor 全 NOTIFY 类型处理语义（绕过监听协程，直调 handleNotifyPayload）。
 * 覆盖：缓存写入、事件流发射（message/contact/chat/presence）、契约解码路径。
 */
class EventProcessorTest {

    private val cache = FakeLocalCache()
    private val ep = EventProcessor(ImClient(), cache)

    @Test
    fun `stop is idempotent and terminal for the session-owned processor`() {
        val owned = EventProcessor(ImClient(), FakeLocalCache())
        owned.start()

        owned.stop()
        owned.stop()

        assertFailsWith<IllegalStateException> { owned.start() }
    }

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
    fun `CONTACT_APPLY - 只缓存申请人资料且不得写入好友`() = runBlocking {
        val apply = com.virjar.tk.model.ContactApply(id = 1, fromUid = "u2", toUid = "me", token = "t", remark = "hi", fromUser = User(uid = "u2", username = "u2", name = "U2"))
        val received = launch { withTimeout(2000) { ep.contactEvents.first() } }
        kotlinx.coroutines.delay(50)
        ep.handleNotifyPayload(NotifyType.CONTACT_APPLY, ProtoCodec.encode(apply))
        received.join()
        assertTrue(cache.getContacts().isEmpty(), "申请尚未接受，不得出现在好友投影")
        assertEquals("U2", cache.getUser("u2")?.name, "申请人的公开资料可以安全缓存")
    }

    @Test
    fun `CONTACT_ACCEPTED - 写入服务端权威好友快照`() = runBlocking {
        val contact = Contact(
            uid = "me",
            friendUid = "u2",
            user = User(uid = "u2", username = "u2", name = "U2"),
        )

        ep.handleNotifyPayload(NotifyType.CONTACT_ACCEPTED, ProtoCodec.encode(contact))

        assertEquals(contact, cache.getContacts().single())
    }

    @Test
    fun `CONTACT_DELETED - 即使 payload status 默认为正常也必须删除好友`() = runBlocking {
        cache.upsertContact(
            Contact(
                uid = "me",
                friendUid = "u2",
                user = User(uid = "u2", username = "u2", name = "U2"),
            ),
        )

        ep.handleNotifyPayload(
            NotifyType.CONTACT_DELETED,
            ProtoCodec.encode(Contact(uid = "me", friendUid = "u2")),
        )

        assertTrue(cache.getContacts().isEmpty(), "删除 tombstone 不得被当成正常好友 upsert")
    }

    @Test
    fun `CHAT_CREATED - replay 先提交本地投影和 cursor 再合并刷新`() = runBlocking {
        var refreshCount = 0
        val ep2 = EventProcessor(ImClient(), cache, onConversationsDirty = { refreshCount += 1 })
        val chat = Chat(chatId = "g1", chatType = 2, name = "群")
        val received = launch { withTimeout(2000) { assertEquals(NotifyType.CHAT_CREATED, ep2.chatEvents.first().first) } }
        kotlinx.coroutines.delay(50)
        ep2.processNotify(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.CHAT_CREATED.code,
                payload = ProtoCodec.encode(chat),
            ),
        )
        received.join()
        assertEquals("g1", cache.getChat("g1")?.chatId)
        assertEquals(1L, cache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
        assertEquals(0, refreshCount, "SYNCHRONIZING replay 不得调用业务 RPC")
        assertTrue(ep2.hasDirtyConversations)

        assertTrue(ep2.refreshDirtyConversations(authenticated = true))
        assertEquals(1, refreshCount)
        assertTrue(!ep2.hasDirtyConversations)
    }

    @Test
    fun `CHAT_CREATED - 刷新失败不回滚 cursor 且保留 dirty`() = runBlocking {
        val ep2 = EventProcessor(ImClient(), cache, onConversationsDirty = { error("rpc unavailable") })
        val chat = Chat(chatId = "g2", chatType = 2, name = "群 2")

        ep2.processNotify(
            NotifyPayload(
                eventId = 2L,
                notifyType = NotifyType.CHAT_CREATED.code,
                payload = ProtoCodec.encode(chat),
            ),
        )

        assertTrue(!ep2.refreshDirtyConversations(authenticated = true))
        assertEquals(2L, cache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
        assertTrue(ep2.hasDirtyConversations)
    }

    @Test
    fun `CHAT_DELETED applies one comprehensive local tombstone`() = runBlocking {
        val deleted = Chat(chatId = "deleted-chat", chatType = 2, name = "deleted")
        val retained = Chat(chatId = "retained-chat", chatType = 2, name = "retained")
        cache.upsertChat(deleted)
        cache.upsertChat(retained)
        cache.upsertMember(Member(chatId = deleted.chatId, uid = "removed", role = 0))
        cache.upsertMember(Member(chatId = retained.chatId, uid = "retained", role = 0))
        cache.upsertConversation(Conversation(chatId = deleted.chatId, chatType = 2))
        cache.upsertConversation(Conversation(chatId = retained.chatId, chatType = 2))
        cache.setConversationDraft(deleted.chatId, "deleted draft")
        val deletedMessage = Message(
            chatId = deleted.chatId,
            clientMsgId = "deleted-message",
            serverSeq = 1,
            senderUid = "sender",
            messageType = 1,
            timestamp = 1,
        )
        val retainedMessage = deletedMessage.copy(
            chatId = retained.chatId,
            clientMsgId = "retained-message",
        )
        val residentFlow = cache.observeMessages(deleted.chatId)
        cache.insertMessage(deletedMessage)
        cache.insertMessage(retainedMessage)
        cache.enqueueBotMessage(1L, deletedMessage)
        cache.enqueueBotMessage(2L, retainedMessage)

        ep.handleNotifyPayload(NotifyType.CHAT_DELETED, ProtoCodec.encode(deleted))

        assertNull(cache.getChat(deleted.chatId))
        assertTrue(cache.getMembers(deleted.chatId).isEmpty())
        assertTrue(cache.getMessages(deleted.chatId).isEmpty())
        assertTrue(cache.getConversations().none { it.chatId == deleted.chatId })
        assertTrue(cache.getPendingConversationDrafts().none { it.chatId == deleted.chatId })
        assertTrue(residentFlow.first().isEmpty())
        assertEquals(retained, cache.getChat(retained.chatId))
        assertEquals(listOf("retained-message"), cache.getMessages(retained.chatId).map(Message::clientMsgId))
        assertEquals(2L, cache.peekBotMessage()?.eventId)
    }

    @Test
    fun `conversation RPC Outcome failure is thrown across refresh callback and keeps dirty`() = runBlocking {
        val local = FakeLocalCache()
        val rpc = FakeRpcInvoker().apply { enqueueError(503, "rpc unavailable") }
        val repository = ConversationRepository(rpc, local)
        val ep2 = EventProcessor(
            ImClient(),
            local,
            onConversationsDirty = { repository.listConversations().getOrThrow() },
        )
        ep2.requireConversationReconciliation()

        assertFalse(ep2.refreshDirtyConversations(authenticated = true))

        assertTrue(ep2.hasDirtyConversations)
        assertEquals(1, rpc.calls.size)
    }

    @Test
    fun `new processor reconciles conversations on ready even when dirty memory was lost`() = runBlocking {
        var refreshCount = 0
        // Simulates process reconstruction after CHAT_CREATED + cursor committed. The new instance
        // intentionally starts with no in-memory dirty marker.
        val reconstructed = EventProcessor(
            ImClient(),
            FakeLocalCache(),
            onConversationsDirty = { refreshCount += 1 },
        )
        assertTrue(!reconstructed.hasDirtyConversations)

        reconstructed.requireConversationReconciliation()
        assertTrue(reconstructed.refreshDirtyConversations(authenticated = true))

        assertEquals(1, refreshCount)
        assertTrue(!reconstructed.hasDirtyConversations)
    }

    @Test
    fun `durable projection never waits for a slow SharedFlow observer`() = runBlocking {
        val reliableMessages = mutableListOf<String>()
        val ep2 = EventProcessor(
            ImClient(),
            cache,
            durableMessageSink = { _, message -> reliableMessages += message.clientMsgId },
        )
        val firstDelivery = CompletableDeferred<Unit>()
        val releaseObserver = CompletableDeferred<Unit>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            ep2.messageEvents.collect {
                firstDelivery.complete(Unit)
                releaseObserver.await()
            }
        }
        val events = (1L..100L).map { eventId ->
            val message = Message(
                chatId = "c1",
                clientMsgId = "m$eventId",
                serverSeq = eventId,
                senderUid = "u2",
                messageType = 1,
                timestamp = eventId,
            )
            NotifyPayload(
                eventId = eventId,
                notifyType = NotifyType.MESSAGE_RECV.code,
                payload = ProtoCodec.encode(message),
            )
        }

        withTimeout(2_000) { ep2.processBatch(events) }

        firstDelivery.await()
        assertEquals(100L, ep2.lastEventId.value)
        assertEquals((1L..100L).map { "m$it" }, reliableMessages)
        assertEquals(100, cache.getMessages("c1", 200).size)
        releaseObserver.complete(Unit)
        observer.cancelAndJoin()
    }

    @Test
    fun `durable message sink failure aborts cursor so replay can retry`() = runBlocking {
        val ep2 = EventProcessor(
            ImClient(),
            cache,
            durableMessageSink = { _, _ -> error("inbox closed") },
        )
        val message = Message(
            chatId = "c1",
            clientMsgId = "m1",
            serverSeq = 1,
            senderUid = "u2",
            messageType = 1,
            timestamp = 1,
        )

        assertFailsWith<IllegalStateException> {
            ep2.processNotify(
                NotifyPayload(
                    eventId = 1L,
                    notifyType = NotifyType.MESSAGE_RECV.code,
                    payload = ProtoCodec.encode(message),
                ),
            )
        }

        assertEquals(0L, ep2.lastEventId.value)
        assertEquals(0L, cache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
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

    @Test
    fun `unknown GENERIC notify is ignored after strict decode and advances durable cursor`() = runBlocking {
        val local = FakeLocalCache()
        val processor = EventProcessor(ImClient(), local)

        processor.processNotify(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.GENERIC.code,
                payload = ProtoCodec.encode(GenericPayload(404, byteArrayOf(1, 2, 3))),
            ),
        )

        assertEquals(1L, processor.lastEventId.value)
        assertEquals(1L, local.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
    }

    @Test
    fun `unknown GENERIC message keeps opaque bytes in local projection`() = runBlocking {
        val local = FakeLocalCache()
        val processor = EventProcessor(ImClient(), local)
        val generic = GenericPayload(405, byteArrayOf(0, 9, 0, 8))
        val message = Message(
            chatId = "c-generic",
            clientMsgId = "m-generic",
            serverSeq = 1,
            senderUid = "future-client",
            messageType = com.virjar.tk.protocol.MessageType.GENERIC.code,
            timestamp = 1,
            body = generic,
        )

        processor.processNotify(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.MESSAGE_RECV.code,
                payload = ProtoCodec.encode(message),
            ),
        )

        assertEquals(generic, local.getMessages("c-generic").single().body)
        assertEquals(1L, processor.lastEventId.value)
    }

    @Test
    fun `durable batch stops at first failed projection and cannot advance past it`() = runBlocking {
        val events = listOf(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = ProtoCodec.encode(User(uid = "u1", username = "a", name = "A")),
            ),
            NotifyPayload(
                eventId = 2L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = byteArrayOf(0x7f),
            ),
            NotifyPayload(
                eventId = 3L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = ProtoCodec.encode(User(uid = "u3", username = "c", name = "C")),
            ),
        )

        assertFailsWith<Exception> {
            ep.processBatch(events)
        }

        assertEquals(1L, ep.lastEventId.value)
        assertEquals(1L, cache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
        assertEquals("A", cache.getUser("u1")?.name)
        assertNull(cache.getUser("u3"), "events after the failed item must not be projected")
    }

    @Test
    fun `sync reset clears projections and returns cursor zero`() = runBlocking {
        val local = FakeLocalCache()
        val processor = EventProcessor(ImClient(), local)
        local.upsertUser(User(uid = "u1", username = "u1", name = "U1"))
        local.upsertContact(Contact(uid = "me", friendUid = "u1"))
        local.upsertChat(Chat(chatId = "c1", chatType = 1))
        local.upsertConversation(com.virjar.tk.model.Conversation(chatId = "c1", chatType = 1))
        local.setConversationDraft("c1", "pending")
        val message = Message(
            chatId = "c1",
            clientMsgId = "m1",
            serverSeq = 1,
            senderUid = "u1",
            messageType = 1,
            timestamp = 1,
        )
        local.insertMessage(message)
        local.enqueueBotMessage(7L, message)
        local.advanceSyncCursor(EventProcessor.SYNC_CURSOR_KEY, 7L)

        assertEquals(0L, processor.resetServerProjection())

        assertEquals(0L, processor.lastEventId.value)
        assertTrue(local.getContacts().isEmpty())
        assertNull(local.getChat("c1"))
        assertTrue(local.getConversations().isEmpty())
        assertTrue(local.getMessages("c1").isEmpty())
        assertTrue(local.getPendingConversationDrafts().isEmpty())
        assertNull(local.peekBotMessage())
    }
}
