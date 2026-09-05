package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.OrganizationChangedPayload
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ReadSyncPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.shared.repository.ConversationRepository
import com.virjar.tk.shared.testkit.FakeLocalCache
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
    fun `stop retires the checkpoint and sync request admission together`() {
        val admission = SessionOutboundLease()
        val owned = EventProcessor(ImClient(), FakeLocalCache())
        owned.bindSyncWireAdmission(admission)

        owned.stop()

        assertFalse(admission.isActive())
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
    fun `own authoritative echo refreshes outgoing aggregate after durable projection`() = runBlocking {
        val local = FakeLocalCache()
        var refreshes = 0
        val processor = EventProcessor(
            imClient = ImClient(),
            localCache = local,
            ownerUid = "me",
            onOutgoingProjectionMayHaveChanged = {
                assertEquals(1, local.getMessages("c1").size)
                refreshes += 1
            },
        )

        processor.handleNotifyPayload(
            NotifyType.MESSAGE_RECV,
            ProtoCodec.encode(
                Message(
                    chatId = "c1",
                    clientMsgId = "mine",
                    serverSeq = 7L,
                    senderUid = "me",
                    messageType = 1,
                    timestamp = 1L,
                ),
            ),
        )
        processor.handleNotifyPayload(
            NotifyType.MESSAGE_RECV,
            ProtoCodec.encode(
                Message(
                    chatId = "c1",
                    clientMsgId = "theirs",
                    serverSeq = 8L,
                    senderUid = "other",
                    messageType = 1,
                    timestamp = 2L,
                ),
            ),
        )

        assertEquals(1, refreshes)
    }

    @Test
    fun `CONTACT_APPLY - 只缓存申请人资料且不得写入好友`() = runBlocking {
        val apply = com.virjar.tk.protocol.model.ContactApply(id = 1, fromUid = "u2", toUid = "me", token = "t", remark = "hi", fromUser = User(uid = "u2", username = "u2", name = "U2"))
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
    fun `USER_UPDATED converges profile and embedded member observers through one user projection`() = runBlocking {
        val original = User(uid = "u2", username = "user-2", name = "Old Name")
        val updated = original.copy(name = "Updated Name", revision = 2L)
        cache.upsertMember(Member(uid = original.uid, chatId = "g1", role = 0, user = original))

        val observedUser = async {
            withTimeout(2_000) { cache.observeUser(original.uid).first { it == updated } }
        }
        val observedMember = async {
            withTimeout(2_000) {
                cache.observeMembers("g1").first { members -> members.singleOrNull()?.user == updated }
            }
        }
        ep.handleNotifyPayload(NotifyType.USER_UPDATED, ProtoCodec.encode(updated))

        assertEquals(updated, observedUser.await())
        assertEquals(updated, observedMember.await().single().user)
    }

    @Test
    fun `MEMBER_ROLE_CHANGED upserts final chat and emits an exact member refresh hint`() = runBlocking {
        val member = Member(uid = "u2", chatId = "g1", role = 0)
        val summary = Chat(chatId = member.chatId, chatType = 2, name = "Updated Group", memberCount = 1)
        cache.upsertMember(member)
        val received = launch {
            withTimeout(2_000) {
                assertEquals(NotifyType.MEMBER_ROLE_CHANGED to summary, ep.chatEvents.first())
            }
        }
        kotlinx.coroutines.delay(50)

        ep.handleNotifyPayload(NotifyType.MEMBER_ROLE_CHANGED, ProtoCodec.encode(summary))
        received.join()

        assertEquals(summary, cache.getChat(summary.chatId))
        assertEquals(listOf(member), cache.getMembers(summary.chatId), "事件处理器只发刷新提示，不伪造成员角色")
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
        assertEquals(1L, requireNotNull(cache.getSyncState()).cursor)
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
        assertEquals(2L, requireNotNull(cache.getSyncState()).cursor)
        assertTrue(ep2.hasDirtyConversations)
    }

    @Test
    fun `transient organization change persists monotonic invalidation without business RPC`() = runBlocking {
        val local = FakeLocalCache()
        val root = OrganizationUnit(unitId = "root", parentId = null, name = "公司")
        val member = OrganizationMember(unitId = root.unitId, uid = "u1")
        local.beginOrganizationUnitSnapshot().also { lease ->
            assertTrue(local.applyOrganizationUnitSnapshot(lease, listOf(root), revision = 3L))
        }
        local.beginOrganizationMemberSnapshot(root.unitId).also { lease ->
            assertTrue(local.applyOrganizationMemberSnapshot(lease, listOf(member), revision = 3L))
        }
        var unrelatedRpcRefreshes = 0
        val processor = EventProcessor(
            ImClient(),
            local,
            onConversationsDirty = { unrelatedRpcRefreshes += 1 },
        )

        processor.handleNotifyPayload(
            NotifyType.ORGANIZATION_CHANGED,
            ProtoCodec.encode(OrganizationChangedPayload(4L)),
            eventId = 0L,
        )

        assertEquals(listOf(root.copy(directMemberCount = 1)), local.getOrganizationUnitProjection().units)
        assertFalse(local.getOrganizationUnitProjection().snapshotKnown)
        assertEquals(listOf(member), local.getOrganizationMemberProjection(root.unitId).members)
        assertFalse(local.getOrganizationMemberProjection(root.unitId).snapshotKnown)
        assertEquals(4L, processor.organizationEvents.first())
        assertEquals(0L, processor.lastEventId.value)
        assertEquals(0, unrelatedRpcRefreshes)
    }

    @Test
    fun `positive-id organization delivery commits cursor once and never regresses revision`() = runBlocking {
        val local = FakeLocalCache()
        val processor = EventProcessor(ImClient(), local)

        processor.processNotify(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.ORGANIZATION_CHANGED.code,
                payload = ProtoCodec.encode(OrganizationChangedPayload(5L)),
            ),
        )
        processor.processNotify(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.ORGANIZATION_CHANGED.code,
                payload = ProtoCodec.encode(OrganizationChangedPayload(99L)),
            ),
        )
        processor.processNotify(
            NotifyPayload(
                eventId = 2L,
                notifyType = NotifyType.ORGANIZATION_CHANGED.code,
                payload = ProtoCodec.encode(OrganizationChangedPayload(4L)),
            ),
        )

        assertEquals(2L, processor.lastEventId.value)
        assertEquals(2L, requireNotNull(local.getSyncState()).cursor)
        assertEquals(listOf(5L), processor.organizationEvents.replayCache)
        val lease = local.beginOrganizationUnitSnapshot()
        assertTrue(local.applyOrganizationUnitSnapshot(lease, emptyList(), revision = 6L))
        assertEquals(6L, local.getOrganizationUnitProjection().revision)
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
        val residentPager = cache.pager(deleted.chatId)
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
        assertTrue(residentPager.messages.first().isEmpty())
        assertEquals(retained, cache.getChat(retained.chatId))
        assertEquals(listOf("retained-message"), cache.getMessages(retained.chatId).map(Message::clientMsgId))
        assertEquals(2L, cache.peekBotMessage()?.eventId)
        residentPager.close()
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
        // 模拟 CHAT_CREATED + cursor 提交之后的进程重建。新实例
        // 刻意从没有任何内存脏标记的状态启动。
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
        assertEquals(0L, requireNotNull(cache.getSyncState()).cursor)
    }

    @Test
    fun `stop waits admitted synchronous durable sink and rejects later cursor publication`() = runBlocking {
        val local = FakeLocalCache()
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val processor = EventProcessor(
            ImClient(),
            local,
            durableMessageSink = { _, _ ->
                sinkEntered.complete(Unit)
                while (!releaseSink.isCompleted) {
                    // 扩展契约是同步的。这个确定性的假实现模拟
                    // 一次缓慢的数据库调用，且不引入可恢复的 stop 后回调。
                }
                error("blocked sink released")
            },
        )
        val message = Message(
            chatId = "retired",
            clientMsgId = "late",
            serverSeq = 1L,
            senderUid = "u1",
            messageType = 1,
            timestamp = 1L,
        )
        val processing = async(kotlinx.coroutines.Dispatchers.Default) {
            // 在子协程内捕获：未处理的 async 失败会在
            // 后续 await 侧 catch 检查预期投影失败之前取消 runBlocking。
            try {
                processor.processNotify(
                    NotifyPayload(
                        eventId = 1L,
                        notifyType = NotifyType.MESSAGE_RECV.code,
                        payload = ProtoCodec.encode(message),
                    ),
                )
                null
            } catch (failure: Throwable) {
                failure
            }
        }
        sinkEntered.await()

        val stopStarted = CompletableDeferred<Unit>()
        val stopping = async(kotlinx.coroutines.Dispatchers.Default) {
            stopStarted.complete(Unit)
            processor.stop()
        }
        stopStarted.await()
        assertFalse(stopping.isCompleted, "stop returned while an admitted sink still owned publication")
        releaseSink.complete(Unit)
        stopping.await()

        val lateFailure = processing.await()
        assertTrue(lateFailure is IllegalStateException)
        assertEquals(0L, requireNotNull(local.getSyncState()).cursor)
        assertEquals(0L, processor.lastEventId.value)
    }

    @Test
    fun `READ_SYNC - 推进 peerReadSeq`() = runBlocking {
        cache.upsertConversation(com.virjar.tk.protocol.model.Conversation(chatId = "c1", chatType = 1))
        ep.handleNotifyPayload(NotifyType.READ_SYNC, ProtoCodec.encode(ReadSyncPayload("u2", "c1", 42)))
        assertEquals(42L, cache.getConversations().first { it.chatId == "c1" }.peerReadSeq)
    }

    @Test
    fun `MESSAGE_REACTION - 行级 delta 幂等收敛到本地投影`() = runBlocking {
        ep.handleNotifyPayload(
            NotifyType.MESSAGE_REACTION,
            ProtoCodec.encode(
                com.virjar.tk.protocol.MessageReactionEventPayload("c1", 7L, "👍", "u2", action = 1),
            ),
        )
        // 重放同一条事件不产生重复行
        ep.handleNotifyPayload(
            NotifyType.MESSAGE_REACTION,
            ProtoCodec.encode(
                com.virjar.tk.protocol.MessageReactionEventPayload("c1", 7L, "👍", "u2", action = 1),
            ),
        )
        ep.handleNotifyPayload(
            NotifyType.MESSAGE_REACTION,
            ProtoCodec.encode(
                com.virjar.tk.protocol.MessageReactionEventPayload("c1", 7L, "🎉", "u3", action = 1),
            ),
        )
        ep.handleNotifyPayload(
            NotifyType.MESSAGE_REACTION,
            ProtoCodec.encode(
                com.virjar.tk.protocol.MessageReactionEventPayload("c1", 7L, "🎉", "u3", action = 0),
            ),
        )
        assertEquals(
            setOf(Triple(7L, "👍", "u2")),
            cache.fakeReactionRows("c1"),
        )
    }

    @Test
    fun `PRESENCE - 契约解码头 presenceEvents`() = runBlocking {
        val received = launch { withTimeout(2000) { assertEquals("u9", ep.presenceEvents.first().uid) } }
        kotlinx.coroutines.delay(50)
        ep.handleNotifyPayload(
            NotifyType.PRESENCE,
            ProtoCodec.encode(
                PresencePayload(
                    uid = "u9",
                    status = PresencePayload.STATUS_ONLINE,
                    lastSeenAt = 0L,
                    serverEpoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    revision = 1L,
                ),
            ),
        )
        received.join()
    }

    @Test
    fun `durable USER_UPDATED materializes a user without an existing local relation`() = runBlocking {
        ep.handleNotifyPayload(
            NotifyType.USER_UPDATED,
            ProtoCodec.encode(User(uid = "u1", username = "a", name = "A")),
            eventId = 1L,
        )
        assertEquals("A", cache.getUser("u1")?.name)
    }

    @Test
    fun `version filtered event advances durable cursor without a business projection`() = runBlocking {
        val local = FakeLocalCache()
        val processor = EventProcessor(ImClient(), local)
        processor.processNotify(
            NotifyPayload(eventId = 1L, notifyType = NotifyType.EVENT_CURSOR_ADVANCED.code, payload = null),
        )
        assertEquals(1L, processor.lastEventId.value)
        assertEquals(1L, requireNotNull(local.getSyncState()).cursor)

        assertFailsWith<IllegalArgumentException> {
            processor.processNotify(
                NotifyPayload(eventId = 2L, notifyType = NotifyType.EVENT_CURSOR_ADVANCED.code, payload = byteArrayOf(1)),
            )
        }
        assertEquals(1L, requireNotNull(local.getSyncState()).cursor)
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
        assertEquals(1L, requireNotNull(cache.getSyncState()).cursor)
        assertEquals("A", cache.getUser("u1")?.name)
        assertNull(cache.getUser("u3"), "events after the failed item must not be projected")
    }

    @Test
    fun `transient USER_UPDATED converges an existing user without advancing durable cursor`() = runBlocking {
        val original = User(uid = "active-non-friend", username = "peer", name = "Old Peer")
        val updated = original.copy(name = "Fresh Peer", revision = 2L)
        cache.upsertUser(original)

        ep.processNotify(
            NotifyPayload(
                eventId = 0L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = ProtoCodec.encode(updated),
            ),
        )

        assertEquals(updated, cache.getUser(updated.uid))
        assertEquals(0L, ep.lastEventId.value)
        assertEquals(0L, requireNotNull(cache.getSyncState()).cursor)
    }

    @Test
    fun `transient USER_UPDATED drops an unknown global user`() = runBlocking {
        val unknown = User(uid = "unknown-global-user", username = "stranger", name = "Stranger")

        ep.processNotify(
            NotifyPayload(
                eventId = 0L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = ProtoCodec.encode(unknown),
            ),
        )

        assertNull(cache.getUser(unknown.uid))
        assertEquals(0L, ep.lastEventId.value)
    }

    @Test
    fun `older durable USER_UPDATED cannot roll back a newer transient revision`() = runBlocking {
        val revisionOne = User(uid = "mixed-user", username = "peer", name = "A", revision = 1L)
        val revisionTwo = revisionOne.copy(name = "B", revision = 2L)
        cache.upsertUser(revisionOne)

        ep.processNotify(
            NotifyPayload(
                eventId = 0L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = ProtoCodec.encode(revisionTwo),
            ),
        )
        ep.processNotify(
            NotifyPayload(
                eventId = 1L,
                notifyType = NotifyType.USER_UPDATED.code,
                payload = ProtoCodec.encode(revisionOne),
            ),
        )

        assertEquals(revisionTwo, cache.getUser(revisionOne.uid))
        assertEquals(1L, ep.lastEventId.value, "the stale durable event is still durably consumed")
    }

    @Test
    fun `missing required payload fails before durable cursor advancement`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            ep.processNotify(
                NotifyPayload(
                    eventId = 1L,
                    notifyType = NotifyType.USER_UPDATED.code,
                    payload = null,
                ),
            )
        }

        assertEquals(0L, ep.lastEventId.value)
        assertEquals(0L, requireNotNull(cache.getSyncState()).cursor)
        assertNull(cache.getUser("u1"))
    }

    @Test
    fun `checkpoint reset replaces server projections and retains independent local facts`() = runBlocking {
        val local = FakeLocalCache()
        val checkpoint = ServerProjectionCheckpoint(
            datasetId = TEST_SYNC_DATASET_ID,
            baseEventId = 9L,
            currentUser = User(uid = "me", username = "me", name = "Me"),
            contacts = emptyList(),
            chats = emptyList(),
            conversations = emptyList(),
        )
        val processor = EventProcessor(
            imClient = ImClient(),
            localCache = local,
            ownerUid = "me",
            checkpointLoader = ServerCheckpointLoader { _, _, reportProgress ->
                reportProgress()
                checkpoint
            },
        )
        local.upsertUser(User(uid = "u1", username = "u1", name = "U1"))
        local.upsertContact(Contact(uid = "me", friendUid = "u1"))
        local.upsertChat(Chat(chatId = "c1", chatType = 1))
        local.upsertConversation(com.virjar.tk.protocol.model.Conversation(chatId = "c1", chatType = 1))
        local.setConversationDraft("c1", "pending")
        local.enqueueConversationRead("c1", 1L)
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
        local.advanceSyncCursor(TEST_SYNC_DATASET_ID, 7L)
        processor.handleNotifyPayload(
            NotifyType.ORGANIZATION_CHANGED,
            ProtoCodec.encode(OrganizationChangedPayload(3L)),
        )
        assertEquals(listOf(3L), processor.organizationEvents.replayCache)

        assertEquals(9L, processor.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID))

        assertEquals(9L, processor.lastEventId.value)
        assertEquals("me", local.getUser("me")?.uid)
        assertTrue(local.getContacts().isEmpty())
        assertNull(local.getChat("c1"))
        assertTrue(local.getConversations().isEmpty())
        assertTrue(local.getMessages("c1").isEmpty())
        assertEquals("pending", local.getPendingConversationDrafts().single().draft)
        assertEquals(1L, local.getPendingConversationReads().single().readSeq)
        assertEquals(message, local.peekBotMessage()?.message)
        assertEquals(OrganizationUnitProjection.Unfetched, local.getOrganizationUnitProjection())
        assertEquals(listOf(3L), processor.organizationEvents.replayCache)

        local.upsertConversation(
            com.virjar.tk.protocol.model.Conversation(
                chatId = "c1",
                chatType = 1,
                lastSeq = 2L,
                readSeq = 0L,
                unreadCount = 2,
                draft = "stale server draft",
            ),
        )
        assertEquals("pending", local.getConversations().single().draft)
        assertEquals(1L, local.getConversations().single().readSeq)
        assertEquals(1, local.getConversations().single().unreadCount)
    }
}
