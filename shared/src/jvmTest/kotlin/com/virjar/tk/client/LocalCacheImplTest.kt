package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Message
import com.virjar.tk.model.Member
import com.virjar.tk.model.User
import com.virjar.tk.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LocalCacheImpl 内存治理与并发正确性（JVM 专属内存 SQLite 测试）。
 * 锁定 lessons C1（StateFlow 读改写竞态）/D4（水位线合并）/窗口 LRU 语义。
 */
class LocalCacheImplTest {

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun user(i: Int) = User(uid = "u$i", username = "user$i", name = "User$i")
    private fun conv(chatId: String, readSeq: Long = 0, unread: Int = 0, peer: Long = 0, draft: String? = null, ts: Long = System.currentTimeMillis()) =
        Conversation(chatId = chatId, chatType = 1, readSeq = readSeq, unreadCount = unread, peerReadSeq = peer, draft = draft, lastMsgTimestamp = ts)

    @Test
    fun `local cache close releases driver idempotently`() {
        val cache = newCache()
        cache.close()
        cache.close()
    }

    @Test
    fun `sync cursor is monotonic and restored by a rebuilt event processor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val firstCache = LocalCacheImpl(driver)

        assertEquals(91L, firstCache.advanceSyncCursor(EventProcessor.SYNC_CURSOR_KEY, 91L))
        assertEquals(
            91L,
            firstCache.advanceSyncCursor(EventProcessor.SYNC_CURSOR_KEY, 40L),
            "a delayed duplicate must not regress the durable cursor",
        )

        val rebuiltCache = LocalCacheImpl(driver)
        val client = ImClient()
        try {
            val rebuiltProcessor = EventProcessor(client, rebuiltCache)
            assertEquals(91L, rebuiltProcessor.lastEventId.value)
            assertEquals(91L, rebuiltCache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `server projection reset is transactional and keeps resident message flow attached`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val message = Message(
            chatId = "c1",
            clientMsgId = "m1",
            serverSeq = 1,
            senderUid = "u1",
            messageType = 1,
            timestamp = 1,
        )
        cache.upsertUser(user(1))
        cache.upsertContact(Contact(uid = "me", friendUid = "u1"))
        cache.upsertChat(Chat(chatId = "c1", chatType = 1))
        cache.upsertMember(Member(chatId = "c1", uid = "u1", role = 0))
        cache.upsertConversation(conv("c1"))
        cache.setConversationDraft("c1", "pending")
        cache.insertMessage(message)
        cache.enqueueBotMessage(9L, message)
        cache.advanceSyncCursor(EventProcessor.SYNC_CURSOR_KEY, 9L)
        val residentMessages = cache.observeMessages("c1")

        cache.resetServerProjection()

        assertNull(cache.getUser("u1"))
        assertTrue(cache.getContacts().isEmpty())
        assertNull(cache.getChat("c1"))
        assertTrue(cache.getMembers("c1").isEmpty())
        assertTrue(cache.getConversations().isEmpty())
        assertTrue(cache.getMessages("c1").isEmpty())
        assertTrue(cache.getPendingConversationDrafts().isEmpty())
        assertEquals(0L, cache.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
        assertNull(cache.peekBotMessage())
        assertTrue(residentMessages.first().isEmpty())

        val replayed = message.copy(clientMsgId = "m2", serverSeq = 2)
        cache.insertMessage(replayed)
        assertEquals(listOf("m2"), residentMessages.first().map(Message::clientMsgId))

        val rebuilt = LocalCacheImpl(driver)
        assertNull(rebuilt.getUser("u1"))
        assertEquals(listOf("m2"), rebuilt.getMessages("c1").map(Message::clientMsgId))
        assertEquals(0L, rebuilt.getSyncCursor(EventProcessor.SYNC_CURSOR_KEY))
    }

    @Test
    fun `并发 upsertUser 无丢失 - stateLock 语义`() {
        val cache = newCache()
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        repeat(threads) { t ->
            pool.submit {
                ready.countDown()
                go.await()
                repeat(perThread) { i ->
                    cache.upsertUser(user(t * perThread + i))
                    cache.upsertContact(Contact(uid = "owner", friendUid = "f${t}_$i"))
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS); go.countDown()
        pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals(threads * perThread, cache.getUser("u${threads * perThread - 1}")?.let { usersCount(cache) } ?: usersCount(cache))
        assertEquals(threads * perThread, cache.getContacts().size)
    }

    @Test
    fun `权威好友快照清理旧客户端污染并持久化`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertContact(Contact(uid = "me", friendUid = "polluted"))
        val generation = cache.contactProjectionGeneration()

        assertTrue(
            cache.applyContactSnapshot(
                generation,
                listOf(Contact(uid = "me", friendUid = "real")),
            ),
        )
        assertEquals(listOf("real"), cache.getContacts().map(Contact::friendUid))

        val reloaded = LocalCacheImpl(driver)
        assertEquals(listOf("real"), reloaded.getContacts().map(Contact::friendUid))
    }

    @Test
    fun `reloaded raw contact is projected with persisted user`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertContact(
            Contact(
                uid = "me",
                friendUid = "friend",
                user = User(uid = "friend", username = "friend", name = "Persisted Name"),
            ),
        )

        val reloaded = LocalCacheImpl(driver)
        val contact = reloaded.observeContacts().first().single()

        assertEquals("Persisted Name", contact.user?.name)
        assertEquals(reloaded.getContacts(), listOf(contact), "get/observe 必须使用同一投影")
    }

    @Test
    fun `later user update re-emits projected contact`() = runBlocking {
        val cache = newCache()
        cache.observeContacts().test {
            assertTrue(awaitItem().isEmpty())

            cache.upsertContact(Contact(uid = "me", friendUid = "friend"))
            assertNull(awaitItem().single().user)

            cache.upsertUser(User(uid = "friend", username = "friend", name = "Updated Name"))
            assertEquals("Updated Name", awaitItem().single().user?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `迟到快照不能在 SQLite 复活请求期间删除的好友`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val stale = Contact(uid = "me", friendUid = "deleted")
        cache.upsertContact(stale)
        val generation = cache.contactProjectionGeneration()

        cache.deleteContact(stale.friendUid)
        assertFalse(cache.applyContactSnapshot(generation, listOf(stale)))
        assertTrue(cache.getContacts().isEmpty())

        val reloaded = LocalCacheImpl(driver)
        assertTrue(reloaded.getContacts().isEmpty())
    }

    @Test
    fun `迟到快照保留请求期间接受的好友并持久化安全项`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val existing = Contact(uid = "me", friendUid = "existing")
        cache.upsertContact(existing)
        val generation = cache.contactProjectionGeneration()

        cache.upsertContact(Contact(uid = "me", friendUid = "accepted"))
        assertFalse(cache.applyContactSnapshot(generation, listOf(existing)))
        assertEquals(
            setOf("existing", "accepted"),
            cache.getContacts().map(Contact::friendUid).toSet(),
        )

        val reloaded = LocalCacheImpl(driver)
        assertEquals(
            setOf("existing", "accepted"),
            reloaded.getContacts().map(Contact::friendUid).toSet(),
        )
    }

    private fun usersCount(cache: LocalCacheImpl): Int {
        // 通过观察流当前值计数
        var n = 0
        var last: String? = null
        for (i in 0 until Int.MAX_VALUE) {
            val u = cache.getUser("u$i") ?: break
            n++
        }
        return n
    }

    @Test
    fun `权威会话快照原子删除旧行和草稿 outbox 但保留 Chat 缓存`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertChat(Chat(chatId = "stale", chatType = 2, name = "cached group"))
        cache.upsertChat(Chat(chatId = "profile-only", chatType = 1, name = "cached profile"))
        cache.upsertConversation(conv("stale", draft = "old"))
        cache.setConversationDraft("stale", "pending")

        val generation = cache.beginConversationSnapshot()
        assertTrue(cache.applyConversationSnapshot(generation, emptyList()))

        assertTrue(cache.getConversations().isEmpty())
        assertTrue(cache.getPendingConversationDrafts().isEmpty())
        assertEquals("cached group", cache.getChat("stale")?.name)
        assertEquals("cached profile", cache.getChat("profile-only")?.name)

        val reloaded = LocalCacheImpl(driver)
        assertTrue(reloaded.getConversations().isEmpty(), "conversation deletion must be durable")
        assertTrue(reloaded.getPendingConversationDrafts().isEmpty(), "draft outbox deletion must be durable")
        assertEquals("cached group", reloaded.getChat("stale")?.name)
        assertEquals("cached profile", reloaded.getChat("profile-only")?.name)
    }

    @Test
    fun `迟到会话快照不能删除新会话或复活删除 tombstone 且结果持久化`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val deleted = conv("deleted")
        cache.upsertConversation(deleted)
        val generation = cache.beginConversationSnapshot()

        cache.deleteConversation(deleted.chatId)
        cache.upsertConversation(conv("created-during-request"))
        assertFalse(cache.applyConversationSnapshot(generation, listOf(deleted)))

        assertEquals(
            listOf("created-during-request"),
            cache.getConversations().map(Conversation::chatId),
        )
        val reloaded = LocalCacheImpl(driver)
        assertEquals(
            listOf("created-during-request"),
            reloaded.getConversations().map(Conversation::chatId),
        )
    }

    @Test
    fun `并发全量请求乱序返回时旧会话快照整体失效`() {
        val cache = newCache()
        val olderGeneration = cache.beginConversationSnapshot()
        val newerGeneration = cache.beginConversationSnapshot()

        assertTrue(
            cache.applyConversationSnapshot(
                newerGeneration,
                listOf(conv("newer-response")),
            ),
        )
        assertFalse(
            cache.applyConversationSnapshot(
                olderGeneration,
                listOf(conv("older-response")),
            ),
        )
        assertEquals(listOf("newer-response"), cache.getConversations().map(Conversation::chatId))
    }

    @Test
    fun `mergeConversation - readSeq 与 peerReadSeq 取 max 且已清红点不复活`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 100, peer = 50, unread = 0).copy(lastSeq = 100))
        // 服务端滞后通知（readSeq 更小）不得回退本地水位线
        cache.upsertConversation(conv("c1", readSeq = 80, peer = 60, unread = 5).copy(lastSeq = 100))
        val merged = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(100L, merged.readSeq, "readSeq 水位线只增不减")
        assertEquals(60L, merged.peerReadSeq, "peerReadSeq 水位线只增不减")
        assertEquals(0, merged.unreadCount, "迟到的会话事件不得复活已经清除的红点")
    }

    @Test
    fun `mergeConversation keeps a newer local message tuple when an older event arrives late`() {
        val cache = newCache()
        cache.upsertConversation(
            conv("c1", readSeq = 100, unread = 1, ts = 1010)
                .copy(lastSeq = 101, lastMessage = "newer", lastMessageType = 2),
        )

        cache.upsertConversation(
            conv("c1", readSeq = 100, unread = 0, ts = 1000)
                .copy(lastSeq = 100, lastMessage = "stale", lastMessageType = 1),
        )

        val merged = cache.getConversations().single { it.chatId == "c1" }
        assertEquals(101L, merged.lastSeq)
        assertEquals("newer", merged.lastMessage)
        assertEquals(2, merged.lastMessageType)
        assertEquals(1, merged.unreadCount, "旧事件不能隐藏更新消息的未读")
    }

    @Test
    fun `mergeConversation recomputes unread after a newer read watermark arrives in an older event`() {
        val cache = newCache()
        cache.upsertConversation(
            conv("c1", readSeq = 90, unread = 11, ts = 1010)
                .copy(lastSeq = 101, lastMessage = "newer"),
        )

        cache.upsertConversation(
            conv("c1", readSeq = 100, unread = 0, ts = 1000)
                .copy(lastSeq = 100, lastMessage = "stale"),
        )

        val merged = cache.getConversations().single { it.chatId == "c1" }
        assertEquals(101L, merged.lastSeq)
        assertEquals(100L, merged.readSeq)
        assertEquals(1, merged.unreadCount)
    }

    @Test
    fun `mergeConversation - 无本地 outbox 时服务端草稿权威`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = "本地草稿"))
        cache.upsertConversation(conv("c1", draft = "服务端镜像"))
        assertEquals("服务端镜像", cache.getConversations().first { it.chatId == "c1" }.draft)
        // 没有待收敛本地操作时，跨设备清空也必须进入。
        cache.upsertConversation(conv("c2", draft = "新草稿"))
        cache.upsertConversation(conv("c2", draft = null))
        assertEquals(null, cache.getConversations().first { it.chatId == "c2" }.draft)
        cache.upsertConversation(conv("c3", draft = null))
        cache.upsertConversation(conv("c3", draft = "服务端草稿"))
        assertEquals("服务端草稿", cache.getConversations().first { it.chatId == "c3" }.draft)
    }

    @Test
    fun `markConversationRead 即时清零未读`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 10, unread = 7).copy(lastSeq = 17))
        cache.markConversationRead("c1", 17)
        val c = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(0, c.unreadCount, "标记已读必须即时清零（不等服务端回环）")
        assertEquals(17L, c.readSeq)
    }

    @Test
    fun `markConversationRead is monotonic and an older completion cannot regress it`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 10, unread = 7).copy(lastSeq = 20))

        cache.markConversationRead("c1", 20)
        cache.markConversationRead("c1", 17)

        val conversation = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(20L, conversation.readSeq)
        assertEquals(0, conversation.unreadCount)
    }

    @Test
    fun `markConversationRead recomputes remaining unread when only part of the window is visible`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 10, unread = 10).copy(lastSeq = 20))

        cache.markConversationRead("c1", 17)

        val conversation = cache.getConversations().single { it.chatId == "c1" }
        assertEquals(17L, conversation.readSeq)
        assertEquals(3, conversation.unreadCount)
    }

    @Test
    fun `置顶排序 - pinned 优先于时间`() {
        val cache = newCache()
        cache.upsertConversation(conv("old", ts = 1000))
        cache.upsertConversation(conv("new", ts = 2000))
        cache.upsertConversation(conv("pinned", ts = 500).copy(isPinned = true))
        val ids = cache.getConversations().map { it.chatId }
        assertEquals(listOf("pinned", "new", "old"), ids)
    }

    @Test
    fun `消息窗口 LRU - 超过 MAX_ACTIVE_CHATS 淘汰最旧且可从DB重载`() {
        val cache = newCache()
        val total = LocalCache.MAX_ACTIVE_CHATS + 5
        // 按顺序触碰 total 个 chat（写入即触碰窗口）
        for (i in 0 until total) {
            cache.insertMessage(Message(chatId = "c$i", clientMsgId = "m$i", serverSeq = 1, senderUid = "u", messageType = 1, timestamp = i.toLong()))
        }
        // 最旧的 5 个窗口被 evict；重新 observe 从 DB 重载不丢数据
        val reloaded = cache.getMessages("c0", 10)
        assertTrue(reloaded.isNotEmpty(), "evicted 窗口应从 DB 重载")
        assertEquals("m0", reloaded.first().clientMsgId)
    }

    @Test
    fun `损坏的持久消息正文必须携带行上下文 fail fast`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        AppDatabase(driver).appDatabaseQueries.insertMessage(
            chat_id = "corrupt-chat",
            client_msg_id = "corrupt-message",
            server_seq = 1L,
            sender_uid = "sender",
            message_type = 999L,
            timestamp = 1L,
            flags = 0L,
            body = byteArrayOf(1),
            send_status = 0L,
        )
        val cache = LocalCacheImpl(driver)

        val failure = assertFailsWith<IllegalStateException> {
            cache.getMessages("corrupt-chat", 10)
        }

        assertTrue(failure.message?.contains("chatId=corrupt-chat") == true)
        assertTrue(failure.message?.contains("msgId=corrupt-message") == true)
        assertTrue(failure.message?.contains("type=999") == true)
    }

    @Test
    fun `消息窗口翻页 - loadMore 向上加载`() = runBlocking {
        val cache = newCache()
        // 逆序插入 150 条（seq 递减写入保证最新在窗口）
        for (seq in 150 downTo 1) {
            cache.insertMessage(Message(chatId = "c1", clientMsgId = "m$seq", serverSeq = seq.toLong(), senderUid = "u", messageType = 1, timestamp = seq.toLong()))
        }
        val pager = cache.pager("c1", windowSize = 100)
        val first = cache.getMessages("c1", 100)
        assertEquals(100, first.size, "初始窗口 100 条")
        pager.loadMore()
        val after = cache.getMessages("c1", 200)
        assertTrue(after.size > 100, "loadMore 追加更旧消息（实际 ${after.size}）")
    }

    @Test
    fun `active message window keeps history pages newest first`() {
        val cache = newCache()
        // Make the window resident before the RPC-like page is inserted so this exercises the
        // incremental upsert path rather than only the initial SQL ORDER BY path.
        cache.pager("history-order", windowSize = 100)
        for (seq in 10 downTo 1) {
            cache.insertMessage(
                Message(
                    chatId = "history-order",
                    clientMsgId = "m$seq",
                    serverSeq = seq.toLong(),
                    senderUid = "u",
                    messageType = 1,
                    timestamp = seq.toLong(),
                ),
            )
        }

        assertEquals(
            (10 downTo 1).map(Int::toLong),
            cache.getMessages("history-order", 20).map { it.serverSeq },
        )
    }

    @Test
    fun `authoritative history batch extends a full resident window without a cursor hole`() {
        val cache = newCache()
        cache.pager("history-capacity", windowSize = 3)
        for (seq in 9 downTo 4) {
            cache.insertMessage(
                Message(
                    chatId = "history-capacity",
                    clientMsgId = "m$seq",
                    serverSeq = seq.toLong(),
                    senderUid = "u",
                    messageType = 1,
                    timestamp = seq.toLong(),
                ),
            )
        }

        cache.insertMessagePage(
            chatId = "history-capacity",
            messages = (3 downTo 1).map { seq ->
                Message(
                    chatId = "history-capacity",
                    clientMsgId = "m$seq",
                    serverSeq = seq.toLong(),
                    senderUid = "u",
                    messageType = 1,
                    timestamp = seq.toLong(),
                )
            },
            resetResidentWindow = false,
        )

        assertEquals((9 downTo 1).map(Int::toLong), cache.getMessages("history-capacity", 20).map { it.serverSeq })
    }

    @Test
    fun `server page provenance keeps legal gaps while isolating a stale cached tail`() {
        val cache = newCache()
        for (seq in 30 downTo 1) {
            cache.insertMessage(
                Message(
                    chatId = "history-gap",
                    clientMsgId = "old-$seq",
                    serverSeq = seq.toLong(),
                    senderUid = "u",
                    messageType = 1,
                    timestamp = seq.toLong(),
                ),
            )
        }
        val pager = cache.pager("history-gap", windowSize = 10)
        assertEquals((30 downTo 21).map(Int::toLong), cache.getMessages("history-gap", 20).map(Message::serverSeq))

        // 98 is absent inside the latest page. That is legal server history, not a broken cache.
        val latestPage = listOf(100L, 99L, 97L).map { seq ->
            Message(
                chatId = "history-gap",
                clientMsgId = "latest-$seq",
                serverSeq = seq,
                senderUid = "u",
                messageType = 1,
                timestamp = seq,
            )
        }
        cache.insertMessagePage("history-gap", latestPage, resetResidentWindow = true)

        assertEquals(
            listOf(100L, 99L, 97L),
            cache.getMessages("history-gap", 20).map(Message::serverSeq),
            "the stale 30..1 tail must not become the cursor",
        )
        assertFalse(pager.hasMore.value, "only another server page may extend an anchored chain")
        pager.loadMore(pageSize = 10)
        assertEquals(listOf(100L, 99L, 97L), cache.getMessages("history-gap", 20).map(Message::serverSeq))

        // 96 is absent across the page boundary; 93..91 are absent inside the older page.
        val olderPage = listOf(95L, 94L, 90L).map { seq ->
            Message(
                chatId = "history-gap",
                clientMsgId = "older-$seq",
                serverSeq = seq,
                senderUid = "u",
                messageType = 1,
                timestamp = seq,
            )
        }
        cache.insertMessagePage("history-gap", olderPage, resetResidentWindow = false)

        assertEquals(
            listOf(100L, 99L, 97L, 95L, 94L, 90L),
            cache.getMessages("history-gap", 200).map(Message::serverSeq),
            "legal holes inside and across authoritative pages must remain visible",
        )
        assertEquals(90L, cache.getMessages("history-gap", 200).minOf(Message::serverSeq))
    }

    @Test
    fun `clientMsgId 幂等覆盖 - 服务端回环不产生重复`() {
        val cache = newCache()
        val msg = Message(chatId = "c1", clientMsgId = "same-id", serverSeq = 5, senderUid = "u", messageType = 1, timestamp = 1)
        cache.insertMessage(msg)
        cache.insertMessage(msg.copy(sendStatus = Message.SEND_STATUS_SENT))
        val list = cache.getMessages("c1", 10)
        assertEquals(1, list.size, "同一 clientMsgId 必须覆盖（服务端 MESSAGE_RECV 含发送者回环）")
    }

    @Test
    fun `没有本地 outbox 时远端草稿包括 null 均为权威值`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = "z"))

        // 只有明确的本地待同步操作才保护本地值。没有 outbox 时必须接受
        // 远端 null，否则其他设备清空草稿后本机会永久保留旧正文。
        cache.upsertConversation(conv("c1", draft = null, unread = 3))
        assertEquals(null, cache.getConversations().first { it.chatId == "c1" }.draft)
    }

    @Test
    fun `草稿清除落库 - setConversationDraft 持久化到 SQLite`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertConversation(conv("c1", draft = "z"))
        cache.setConversationDraft("c1", null)
        // 同一 driver 上的新实例（模拟重启后重读 DB）：草稿不复活
        val reloaded = LocalCacheImpl(driver)
        reloaded.upsertConversation(conv("c1", draft = "服务端尚未清除的旧草稿"))
        assertEquals(null, reloaded.getConversations().first { it.chatId == "c1" }.draft)
        assertEquals(null, reloaded.getPendingConversationDrafts().single().draft)
    }

    @Test
    fun `草稿清除后迟到的旧服务端事件不得复活并写回 SQLite`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertConversation(conv("c1", draft = "已经发送的正文"))

        cache.setConversationDraft("c1", null)
        // 模拟清空 RPC 之前的旧 CONVERSATION_UPDATED 在发送完成后才到达。
        cache.upsertConversation(conv("c1", draft = "已经发送的正文", unread = 2))

        assertEquals(null, cache.getConversations().first { it.chatId == "c1" }.draft)
        val reloaded = LocalCacheImpl(driver)
        assertEquals(null, reloaded.getConversations().first { it.chatId == "c1" }.draft)
    }

    @Test
    fun `本地新草稿不会被迟到事件覆盖且合并值会写入 SQLite`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertConversation(conv("c1", draft = null))

        cache.setConversationDraft("c1", "本地最新草稿")
        cache.upsertConversation(conv("c1", draft = "服务端旧草稿", unread = 3))

        assertEquals("本地最新草稿", cache.getConversations().first { it.chatId == "c1" }.draft)
        val reloaded = LocalCacheImpl(driver)
        reloaded.upsertConversation(conv("c1", draft = "服务端旧草稿"))
        assertEquals("本地最新草稿", reloaded.getConversations().first { it.chatId == "c1" }.draft)
    }

    @Test
    fun `旧 generation ACK 不得确认新草稿`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = null))
        val oldGeneration = cache.setConversationDraft("c1", "A")
        val latestGeneration = cache.setConversationDraft("c1", "B")

        cache.markConversationDraftMirrored("c1", oldGeneration)

        assertEquals(
            PendingConversationDraft("c1", "B", latestGeneration),
            cache.getPendingConversationDrafts().single(),
        )
        cache.upsertConversation(conv("c1", draft = "A"))
        assertEquals("B", cache.getConversations().single().draft)
    }

    @Test
    fun `ACK 后只有匹配远端值才清理 override`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = "旧值"))
        val generation = cache.setConversationDraft("c1", null)
        cache.markConversationDraftMirrored("c1", generation)

        cache.upsertConversation(conv("c1", draft = "旧值"))
        assertEquals(null, cache.getConversations().single().draft)
        cache.upsertConversation(conv("c1", draft = null))
        cache.upsertConversation(conv("c1", draft = "另一设备的新草稿"))

        assertEquals("另一设备的新草稿", cache.getConversations().single().draft)
    }

    @Test
    fun `匹配权威事件先于 ACK 到达也会原子收敛 outbox`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertConversation(conv("c1", draft = "旧值"))
        val generation = cache.setConversationDraft("c1", null)

        cache.upsertConversation(conv("c1", draft = null))
        assertEquals(
            PendingConversationDraft("c1", null, generation),
            cache.getPendingConversationDrafts().single(),
            "RPC 未 ACK 前即使值匹配也不能提前丢失可重试 outbox",
        )
        cache.markConversationDraftMirrored("c1", generation)

        assertTrue(cache.getPendingConversationDrafts().isEmpty())
        val reloaded = LocalCacheImpl(driver)
        reloaded.upsertConversation(conv("c1", draft = "另一设备后续草稿"))
        assertEquals("另一设备后续草稿", reloaded.getConversations().single().draft)
    }

    @Test
    fun `ACK 不得用过期匹配事件误清后续观察到的另一值`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = "旧值"))
        val generation = cache.setConversationDraft("c1", "目标值")

        cache.upsertConversation(conv("c1", draft = "目标值"))
        cache.upsertConversation(conv("c1", draft = "另一值"))
        cache.markConversationDraftMirrored("c1", generation)
        cache.upsertConversation(conv("c1", draft = "另一值"))

        assertEquals("目标值", cache.getConversations().single().draft)
        cache.upsertConversation(conv("c1", draft = "目标值"))
        cache.upsertConversation(conv("c1", draft = "另一设备的最新值"))
        assertEquals("另一设备的最新值", cache.getConversations().single().draft)
    }

    @Test
    fun `旧 generation 的事件先到也不能让迟到 ACK 清理新 generation`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = null))
        val oldGeneration = cache.setConversationDraft("c1", "A")
        cache.upsertConversation(conv("c1", draft = "A"))
        val latestGeneration = cache.setConversationDraft("c1", "B")

        cache.markConversationDraftMirrored("c1", oldGeneration)

        assertEquals(
            PendingConversationDraft("c1", "B", latestGeneration),
            cache.getPendingConversationDrafts().single(),
        )
        cache.upsertConversation(conv("c1", draft = "A"))
        assertEquals("B", cache.getConversations().single().draft)
    }

    @Test
    fun `已收敛 generation 后新操作仍使用更高水位并忽略旧 ACK`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = null))
        val oldGeneration = cache.setConversationDraft("c1", "A")
        cache.upsertConversation(conv("c1", draft = "A"))
        cache.markConversationDraftMirrored("c1", oldGeneration)

        val latestGeneration = cache.setConversationDraft("c1", "B")
        cache.upsertConversation(conv("c1", draft = "B"))
        cache.markConversationDraftMirrored("c1", oldGeneration)

        assertTrue(latestGeneration > oldGeneration)
        assertEquals(
            PendingConversationDraft("c1", "B", latestGeneration),
            cache.getPendingConversationDrafts().single(),
        )
    }
}
