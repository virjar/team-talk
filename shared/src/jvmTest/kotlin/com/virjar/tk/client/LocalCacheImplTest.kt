package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Message
import com.virjar.tk.model.Member
import com.virjar.tk.model.User
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
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

    private fun outgoing(chatId: String, clientMsgId: String) = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        senderUid = "owner",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = RichTextBody("hello", plainText = "hello"),
        sendStatus = Message.SEND_STATUS_SENDING,
    )

    @Test
    fun `outgoing ordinal is idempotent FIFO and retry head blocks later rows`() {
        val cache = newCache()
        val first = cache.enqueueOutgoingMessage(outgoing("c1", "m1"), now = 1L)
        val second = cache.enqueueOutgoingMessage(outgoing("c2", "m2"), now = 2L)
        val duplicate = cache.enqueueOutgoingMessage(outgoing("c1", "m1"), now = 3L)

        assertEquals(first.localOrdinal, duplicate.localOrdinal)
        assertFailsWith<IllegalArgumentException> {
            cache.enqueueOutgoingMessage(
                outgoing("c1", "m1").copy(body = RichTextBody("changed", plainText = "changed")),
                now = 3L,
            )
        }
        assertTrue(second.localOrdinal > first.localOrdinal)
        val claimed = cache.claimNextOutgoingMessage(now = 4L)!!
        assertEquals(first.localOrdinal, claimed.localOrdinal)
        cache.markOutgoingMessageRetry(claimed.localOrdinal, "network", nextAttemptAt = 100L, now = 5L)

        assertNull(cache.claimNextOutgoingMessage(now = 99L), "future FIFO head must block later ordinals")
        assertEquals(first.localOrdinal, cache.claimNextOutgoingMessage(now = 100L)?.localOrdinal)
    }

    @Test
    fun `explicit cancel wins every late transition`() {
        val cache = newCache()
        val admitted = cache.enqueueOutgoingMessage(outgoing("c1", "m1"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.cancelOutgoingMessages("logout", now = 3L)

        cache.markOutgoingMessageRetry(
            admitted.localOrdinal,
            error = "late network callback",
            nextAttemptAt = 100L,
            now = 4L,
        )
        cache.markOutgoingMessageTerminalFailed(
            admitted.localOrdinal,
            error = "late rejection",
            now = 5L,
        )
        cache.completeOutgoingMessage(
            admitted.localOrdinal,
            MessageAckPayload("m1", serverSeq = 9L, code = 0),
            now = 6L,
        )

        val retained = cache.recoverOutgoingMessages(now = 7L).single()
        assertEquals(OutgoingMessageState.TERMINAL_FAILED, retained.state)
        assertEquals("logout", retained.lastError)
        assertEquals(Message.SEND_STATUS_FAILED, cache.getMessages("c1").single().sendStatus)
        assertEquals(0L, cache.getMessages("c1").single().serverSeq)
    }

    @Test
    fun `projection reset rebuilds optimistic messages from immutable outbox`() {
        val cache = newCache()
        val failed = cache.enqueueOutgoingMessage(outgoing("c2", "m2"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "permanent rejection", now = 3L)
        cache.enqueueOutgoingMessage(outgoing("c1", "m1"), now = 4L)

        cache.resetServerProjection()

        assertEquals("m1", cache.getMessages("c1").single().clientMsgId)
        assertEquals(Message.SEND_STATUS_QUEUED, cache.getMessages("c1").single().sendStatus)
        assertEquals("m2", cache.getMessages("c2").single().clientMsgId)
        assertEquals(Message.SEND_STATUS_FAILED, cache.getMessages("c2").single().sendStatus)
        val diagnostics = cache.recoverOutgoingMessages(now = 5L)
        assertEquals("permanent rejection", diagnostics.single { it.localOrdinal == failed.localOrdinal }.lastError)
        assertEquals("m1", cache.peekNextOutgoingMessage()?.message?.clientMsgId)
    }

    @Test
    fun `authoritative echo promotes lost-ack row and restart never overwrites server fields`() {
        val root = createTempDirectory("outgoing-authority-").toFile()
        val database = root.resolve("cache.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val first = LocalCacheImpl(firstDriver)
            val original = outgoing("c1", "stable-id")
            first.enqueueOutgoingMessage(original, now = 1L)
            first.claimNextOutgoingMessage(now = 2L)
            val authorityInput = original.copy(
                serverSeq = 19L,
                timestamp = 99L,
                flags = Message.FLAG_EDITED,
                body = RichTextBody("server canonical", plainText = "server canonical"),
                sendStatus = Message.SEND_STATUS_QUEUED,
            )
            val authority = authorityInput.copy(sendStatus = Message.SEND_STATUS_SENT)
            first.insertMessage(authorityInput)
            assertEquals(OutgoingMessageState.SUCCESS, first.getOutgoingMessage("c1", "stable-id")?.state)
            assertEquals(authority, first.getMessages("c1").single())
            first.close()

            val second = LocalCacheImpl(JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}"))
            val receipt = second.recoverOutgoingMessages(now = 3L).single()
            assertEquals(OutgoingMessageState.SUCCESS, receipt.state)
            assertEquals(19L, receipt.serverSeq)
            assertEquals(authority, second.getMessages("c1").single())

            val duplicate = second.enqueueOutgoingMessage(original, now = 4L)
            assertEquals(receipt.localOrdinal, duplicate.localOrdinal)
            assertEquals(authority, second.getMessages("c1").single())
            second.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `authoritative history promotes a matching failed receipt without rewriting server fields`() {
        val cache = newCache()
        val admitted = cache.enqueueOutgoingMessage(outgoing("history", "stable-id"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(admitted.localOrdinal, "response lost", now = 3L)
        val serverMessage = outgoing("history", "stable-id").copy(
            serverSeq = 31L,
            timestamp = 41L,
            flags = Message.FLAG_EDITED,
            body = RichTextBody("server history", plainText = "server history"),
            sendStatus = Message.SEND_STATUS_FAILED,
        )
        val lease = cache.beginMessageHistoryLease("history", resetResidentWindow = true)

        assertTrue(cache.applyMessageHistoryPage(lease, listOf(serverMessage)))

        val receipt = cache.getOutgoingMessage("history", "stable-id")!!
        assertEquals(OutgoingMessageState.SUCCESS, receipt.state)
        assertEquals(31L, receipt.serverSeq)
        assertEquals(
            serverMessage.copy(sendStatus = Message.SEND_STATUS_SENT),
            cache.getMessages("history").single(),
        )
    }

    @Test
    fun `restart recovery promotes a preexisting authoritative projection and repairs sent status`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.enqueueOutgoingMessage(outgoing("recovery", "stable-id"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        driver.execute(
            null,
            "UPDATE message SET server_seq=33, timestamp=44, flags=${Message.FLAG_EDITED}, " +
                "send_status=${Message.SEND_STATUS_QUEUED} " +
                "WHERE chat_id='recovery' AND client_msg_id='stable-id'",
            0,
        )
        assertEquals(Message.SEND_STATUS_QUEUED, cache.getMessages("recovery").single().sendStatus)

        val receipt = cache.recoverOutgoingMessages(now = 3L).single()

        assertEquals(OutgoingMessageState.SUCCESS, receipt.state)
        assertEquals(33L, receipt.serverSeq)
        val authority = cache.getMessages("recovery").single()
        assertEquals(33L, authority.serverSeq)
        assertEquals(44L, authority.timestamp)
        assertEquals(Message.FLAG_EDITED, authority.flags)
        assertEquals(Message.SEND_STATUS_SENT, authority.sendStatus)
    }

    @Test
    fun `remote sender id collision is never mutated by local outbox transitions`() {
        val cache = newCache()
        val admitted = cache.enqueueOutgoingMessage(outgoing("collision", "same-id"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        val remote = outgoing("collision", "same-id").copy(
            serverSeq = 70L,
            senderUid = "remote-user",
            timestamp = 71L,
            body = RichTextBody("remote authority", plainText = "remote authority"),
            sendStatus = Message.SEND_STATUS_SENT,
        )
        cache.insertMessage(remote)

        cache.completeOutgoingMessage(
            admitted.localOrdinal,
            MessageAckPayload("same-id", serverSeq = 99L, code = 0),
            now = 3L,
        )

        assertEquals(remote, cache.getMessages("collision").single())
        assertEquals(OutgoingMessageState.SUCCESS, cache.getOutgoingMessage("collision", "same-id")?.state)
    }

    @Test
    fun `local seq zero insert cannot replace authority and later enqueue fails closed`() {
        val cache = newCache()
        val authority = outgoing("authority", "stable-id").copy(
            serverSeq = 17L,
            senderUid = "peer",
            timestamp = 18L,
            body = RichTextBody("authority", plainText = "authority"),
            sendStatus = Message.SEND_STATUS_SENT,
        )
        cache.insertMessage(authority)

        assertFailsWith<OutgoingMessageConflictException> {
            cache.insertMessage(outgoing("authority", "stable-id"))
        }
        assertEquals(authority, cache.getMessages("authority").single())
        assertFailsWith<OutgoingMessageConflictException> {
            cache.enqueueOutgoingMessage(outgoing("authority", "stable-id"), now = 1L)
        }
        assertEquals(authority, cache.getMessages("authority").single())
    }

    @Test
    fun `success receipt never synthesizes a server message after projection reset`() {
        val cache = newCache()
        val admitted = cache.enqueueOutgoingMessage(outgoing("c1", "sent"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.completeOutgoingMessage(admitted.localOrdinal, MessageAckPayload("sent", 7L, 0), now = 3L)

        cache.resetServerProjection()

        assertTrue(cache.getMessages("c1").isEmpty())
        val receipt = cache.getOutgoingMessage("c1", "sent")
        assertEquals(OutgoingMessageState.SUCCESS, receipt?.state)
        assertEquals(7L, receipt?.serverSeq)
    }

    @Test
    fun `success GC is bounded and never removes active or failed rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, successReceiptLimit = 1)

        fun complete(chatId: String, clientMsgId: String, seq: Long) {
            val row = cache.enqueueOutgoingMessage(outgoing(chatId, clientMsgId), now = seq)
            cache.claimNextOutgoingMessage(now = seq)
            cache.completeOutgoingMessage(row.localOrdinal, MessageAckPayload(clientMsgId, seq, 0), now = seq)
        }
        complete("c1", "old-success", 11L)
        complete("c2", "new-success", 12L)
        val failed = cache.enqueueOutgoingMessage(outgoing("c3", "failed"), now = 13L)
        cache.claimNextOutgoingMessage(now = 13L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "rejected", now = 14L, terminalCode = 400)
        cache.enqueueOutgoingMessage(outgoing("c4", "active"), now = 15L)

        assertNull(cache.getOutgoingMessage("c1", "old-success"))
        assertEquals(OutgoingMessageState.SUCCESS, cache.getOutgoingMessage("c2", "new-success")?.state)
        assertEquals(OutgoingMessageState.TERMINAL_FAILED, cache.getOutgoingMessage("c3", "failed")?.state)
        assertEquals(OutgoingMessageState.PENDING, cache.getOutgoingMessage("c4", "active")?.state)
        assertFailsWith<OutgoingMessageConflictException> {
            cache.enqueueOutgoingMessage(outgoing("c1", "old-success"), now = 16L)
        }
        assertEquals(11L, cache.getMessages("c1").single().serverSeq)
    }

    @Test
    fun `success GC retains the most recently completed receipt rather than highest ordinal`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, successReceiptLimit = 1)
        val lowOrdinal = outgoing("c1", "low-ordinal")
        val highOrdinal = outgoing("c2", "high-ordinal")
        cache.enqueueOutgoingMessage(lowOrdinal, now = 1L)
        cache.enqueueOutgoingMessage(highOrdinal, now = 2L)

        cache.insertMessage(highOrdinal.copy(serverSeq = 20L, sendStatus = Message.SEND_STATUS_SENT))
        cache.insertMessage(lowOrdinal.copy(serverSeq = 21L, sendStatus = Message.SEND_STATUS_SENT))

        assertEquals(OutgoingMessageState.SUCCESS, cache.getOutgoingMessage("c1", "low-ordinal")?.state)
        assertNull(cache.getOutgoingMessage("c2", "high-ordinal"))
    }

    @Test
    fun `process restart recovers a response-lost in-flight send from SQLite`() {
        val root = createTempDirectory("outgoing-restart-").toFile()
        val database = root.resolve("cache.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val first = LocalCacheImpl(firstDriver)
            first.enqueueOutgoingMessage(outgoing("c1", "m1"), now = 1L)
            assertEquals(OutgoingMessageState.IN_FLIGHT, first.claimNextOutgoingMessage(2L)?.state)
            first.insertMessage(outgoing("c2", "orphan-sending"))
            first.insertMessage(
                outgoing("c3", "orphan-uploading").copy(sendStatus = Message.SEND_STATUS_UPLOADING),
            )
            first.insertMessage(
                outgoing("c4", "orphan-queued").copy(sendStatus = Message.SEND_STATUS_QUEUED),
            )
            first.close()

            val secondDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            val second = LocalCacheImpl(secondDriver)
            val recovered = second.recoverOutgoingMessages(now = 3L).single()
            assertEquals(OutgoingMessageState.PENDING, recovered.state)
            assertEquals(1L, recovered.attemptCount)
            assertEquals("m1", second.peekNextOutgoingMessage()?.message?.clientMsgId)
            listOf("c2", "c3", "c4").forEach { chatId ->
                assertEquals(
                    Message.SEND_STATUS_FAILED,
                    second.getMessages(chatId).single().sendStatus,
                    "local optimistic projection without an outbox must become diagnosable after restart",
                )
            }
            second.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restart window keeps queued and failed bubbles ahead of fifty plus server messages`() {
        val root = createTempDirectory("outgoing-window-").toFile()
        val database = root.resolve("cache.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val first = LocalCacheImpl(firstDriver)
            val failed = first.enqueueOutgoingMessage(outgoing("busy", "failed-local"), now = 1L)
            first.claimNextOutgoingMessage(now = 2L)
            first.markOutgoingMessageTerminalFailed(failed.localOrdinal, "rejected", now = 3L)
            first.enqueueOutgoingMessage(
                outgoing("busy", "queued-local").copy(timestamp = 2L),
                now = 4L,
            )
            (1L..60L).forEach { seq ->
                first.insertMessage(
                    Message(
                        chatId = "busy",
                        clientMsgId = "server-$seq",
                        serverSeq = seq,
                        senderUid = "peer",
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = 100L + seq,
                        body = RichTextBody("server $seq", plainText = "server $seq"),
                    ),
                )
            }
            first.close()

            val reopened = LocalCacheImpl(JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}"))
            val window = reopened.getMessages("busy", limit = 50)

            assertEquals(50, window.size)
            assertEquals(setOf("queued-local", "failed-local"), window.take(2).mapTo(mutableSetOf()) { it.clientMsgId })
            assertEquals(Message.SEND_STATUS_QUEUED, window.single { it.clientMsgId == "queued-local" }.sendStatus)
            assertEquals(Message.SEND_STATUS_FAILED, window.single { it.clientMsgId == "failed-local" }.sendStatus)
            assertEquals((13L..60L).toSet(), window.filter { it.serverSeq > 0L }.mapTo(mutableSetOf()) { it.serverSeq })
            reopened.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `crowded optimistic window stays bounded and retains an authority paging anchor`() = runBlocking {
        val cache = newCache()
        (1L..60L).forEach { index ->
            cache.enqueueOutgoingMessage(
                outgoing("crowded", "local-$index").copy(timestamp = 1_000L + index),
                now = index,
            )
        }
        (1L..20L).forEach { seq ->
            cache.insertMessage(
                Message(
                    chatId = "crowded",
                    clientMsgId = "server-$seq",
                    serverSeq = seq,
                    senderUid = "peer",
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = 2_000L + seq,
                    body = RichTextBody("server $seq", plainText = "server $seq"),
                ),
            )
        }
        val pager = cache.pager("crowded", windowSize = 10)

        val initial = pager.messages.first()
        assertEquals(10, initial.size)
        assertEquals(9, initial.count { it.serverSeq == 0L })
        assertEquals(listOf(20L), initial.filter { it.serverSeq > 0L }.map { it.serverSeq })
        assertTrue(pager.hasMore.value)

        pager.loadMore(pageSize = 5)
        val expanded = pager.messages.first()
        assertTrue(expanded.size <= 20)
        assertEquals(9, expanded.count { it.serverSeq == 0L })
        assertEquals((15L..20L).toList().reversed(), expanded.filter { it.serverSeq > 0L }.map { it.serverSeq })
        assertTrue(pager.hasMore.value)
    }

    @Test
    fun `local paging advances past the two-window cap without repeating its cursor`() = runBlocking {
        val cache = newCache()
        (1L..12L).forEach { seq ->
            cache.insertMessage(
                Message(
                    chatId = "bounded-local",
                    clientMsgId = "m$seq",
                    serverSeq = seq,
                    senderUid = "peer",
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = seq,
                ),
            )
        }
        val pager = cache.pager("bounded-local", windowSize = 3)

        assertEquals(MessagePageLoadResult.LocalLoaded, pager.loadMore(3))
        assertEquals(MessagePageLoadResult.LocalLoaded, pager.loadMore(3))
        assertEquals(MessagePageLoadResult.LocalLoaded, pager.loadMore(3))

        val resident = pager.messages.first()
        assertTrue(resident.size <= 6)
        assertTrue(resident.any { it.serverSeq == 1L }, "the cursor must progress into the oldest page")
    }

    @Test
    fun `local paging evicts optimistic rows when necessary to retain the newly loaded cursor`() = runBlocking {
        val cache = newCache()
        (1L..6L).forEach { seq ->
            cache.insertMessage(
                Message(
                    chatId = "optimistic-cap",
                    clientMsgId = "server-$seq",
                    serverSeq = seq,
                    senderUid = "peer",
                    messageType = MessageType.RICH_TEXT.code,
                    timestamp = seq,
                ),
            )
        }
        val pager = cache.pager("optimistic-cap", windowSize = 3)
        (1L..5L).forEach { index ->
            cache.enqueueOutgoingMessage(
                outgoing("optimistic-cap", "local-$index").copy(timestamp = 100L + index),
                now = index,
            )
        }
        assertEquals(6L, pager.messages.first().single { it.serverSeq > 0L }.serverSeq)

        assertEquals(MessagePageLoadResult.LocalLoaded, pager.loadMore(3))
        val afterFirstPage = pager.messages.first()
        assertTrue(afterFirstPage.size <= 6)
        assertTrue(afterFirstPage.any { it.serverSeq == 3L }, "newly loaded tail must survive trimming")

        assertEquals(MessagePageLoadResult.LocalLoaded, pager.loadMore(3))
        val afterSecondPage = pager.messages.first()
        assertTrue(afterSecondPage.size <= 6)
        assertTrue(afterSecondPage.any { it.serverSeq == 1L }, "cursor must continue past the cap")
    }

    @Test
    fun `trimmed anchored history exposes an exact remote refetch cursor`() = runBlocking {
        val cache = newCache()
        val pager = cache.pager("bounded-remote", windowSize = 3)
        fun page(vararg seqs: Long): List<Message> = seqs.map { seq ->
            Message(
                chatId = "bounded-remote",
                clientMsgId = "m$seq",
                serverSeq = seq,
                senderUid = "peer",
                messageType = MessageType.RICH_TEXT.code,
                timestamp = seq,
            )
        }
        fun apply(reset: Boolean, messages: List<Message>) {
            val lease = cache.beginMessageHistoryLease("bounded-remote", reset)
            assertTrue(cache.applyMessageHistoryPage(lease, messages))
        }

        apply(true, page(12, 11, 10))
        apply(false, page(9, 8, 7))
        apply(false, page(6, 5, 4))
        apply(false, page(3, 2, 1))
        apply(false, emptyList()) // authoritative end of history
        assertFalse(pager.hasMore.value)

        cache.insertMessage(page(13).single())
        assertTrue(pager.messages.first().size <= 6)
        assertTrue(pager.hasMore.value, "live trim must make the removed authoritative tail reachable")
        assertEquals(
            MessagePageLoadResult.RemoteRequired(beforeServerSeq = 2L),
            pager.loadMore(3),
        )

        apply(false, page(1))
        val restored = pager.messages.first()
        assertTrue(restored.size <= 6)
        assertTrue(restored.any { it.serverSeq == 1L })
    }

    @Test
    fun `window snapshot and registration are linearized with durable message publication`() = runBlocking {
        val cache = newCache()
        val snapshotLoaded = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        cache.windowSnapshotLoadedHookForTest = {
            cache.windowSnapshotLoadedHookForTest = null
            snapshotLoaded.countDown()
            assertTrue(releaseRegistration.await(5, TimeUnit.SECONDS))
        }
        try {
            val pagerFuture = executor.submit<MessagePager> { cache.pager("window-race", 3) }
            assertTrue(snapshotLoaded.await(5, TimeUnit.SECONDS))
            executor.submit {
                writerStarted.countDown()
                cache.insertMessage(
                    Message(
                        chatId = "window-race",
                        clientMsgId = "published-after-snapshot",
                        serverSeq = 1L,
                        senderUid = "peer",
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = 1L,
                    ),
                )
                writerFinished.countDown()
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                writerFinished.await(100, TimeUnit.MILLISECONDS),
                "durable writer must wait until the snapshotted window is registered",
            )

            releaseRegistration.countDown()
            val pager = pagerFuture.get(5, TimeUnit.SECONDS)
            assertTrue(writerFinished.await(5, TimeUnit.SECONDS))
            assertEquals(
                listOf("published-after-snapshot"),
                pager.messages.first().map(Message::clientMsgId),
            )
        } finally {
            releaseRegistration.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `acked bot delivery remains cursor pageable but is not redelivered`() {
        val cache = newCache()
        val first = outgoing("c1", "m1").copy(serverSeq = 1L)
        val second = outgoing("c2", "m2").copy(serverSeq = 1L)
        cache.enqueueBotMessage(11L, first)
        cache.enqueueBotMessage(12L, second)
        cache.enqueueBotMessage(11L, outgoing("c3", "conflicting-event").copy(serverSeq = 3L))
        cache.ackBotMessage(11L, now = 20L)

        assertEquals(12L, cache.peekBotMessage()?.eventId)
        assertEquals(listOf(11L, 12L), cache.listBotMessageDeliveries(0L, null, 10).map { it.eventId })
        assertEquals("m1", cache.listBotMessageDeliveries(0L, null, 1).single().message.clientMsgId)
        assertEquals(listOf(12L), cache.listBotMessageDeliveries(11L, null, 10).map { it.eventId })
        assertEquals(listOf(11L), cache.listBotMessageDeliveries(0L, "c1", 10).map { it.eventId })
        assertEquals(12L, cache.maxBotMessageEventId())
    }

    @Test
    fun `bot delivery keeps create edit and revoke events that share one server sequence`() {
        val cache = newCache()
        val created = outgoing("c1", "m1").copy(serverSeq = 7L)
        val edited = created.copy(
            flags = Message.FLAG_EDITED,
            body = RichTextBody("edited", plainText = "edited"),
        )
        val revoked = created.copy(flags = Message.FLAG_REVOKED, body = null)

        cache.enqueueBotMessage(21L, created)
        cache.enqueueBotMessage(22L, edited)
        cache.enqueueBotMessage(23L, revoked)

        val deliveries = cache.listBotMessageDeliveries(0L, "c1", 10)
        assertEquals(listOf(21L, 22L, 23L), deliveries.map { it.eventId })
        assertEquals(listOf(0, Message.FLAG_EDITED, Message.FLAG_REVOKED), deliveries.map { it.message.flags })
        cache.ackBotMessage(21L, now = 30L)
        assertEquals(22L, cache.peekBotMessage()?.eventId)
    }

    @Test
    fun `chat tombstone purges only that chats outgoing and delivery facts`() {
        val cache = newCache()
        cache.enqueueOutgoingMessage(outgoing("deleted", "outgoing-deleted"), now = 1L)
        cache.enqueueOutgoingMessage(outgoing("retained", "outgoing-retained"), now = 2L)
        cache.enqueueBotMessage(11L, outgoing("deleted", "incoming-deleted").copy(serverSeq = 1L))
        cache.enqueueBotMessage(12L, outgoing("retained", "incoming-retained").copy(serverSeq = 1L))

        cache.deleteChat("deleted")

        assertEquals(
            listOf("outgoing-retained"),
            cache.recoverOutgoingMessages(now = 3L).map { it.message.clientMsgId },
        )
        assertEquals(listOf(12L), cache.listBotMessageDeliveries(0L, null, 10).map { it.eventId })
        assertTrue(cache.getMessages("deleted").isEmpty())
        assertEquals(
            listOf("outgoing-retained"),
            cache.getMessages("retained").map { it.clientMsgId },
        )
    }

    @Test
    fun `local cache close releases driver idempotently`() {
        val cache = newCache()
        cache.close()
        cache.close()
    }

    @Test
    fun `late ack cursor draft and captured pager fail before touching closed driver`() {
        val cache = newCache()
        val message = Message(
            chatId = "retired",
            clientMsgId = "late-ack",
            senderUid = "u1",
            messageType = 1,
            timestamp = 1L,
        )
        cache.insertMessage(message)
        cache.upsertConversation(conv("retired"))
        val draftGeneration = cache.setConversationDraft("retired", "pending")
        val pager = cache.pager("retired")

        cache.close()

        assertFailsWith<IllegalStateException> { cache.updateMessage("retired", "late-ack", 9L) }
        assertFailsWith<IllegalStateException> {
            cache.advanceSyncCursor(EventProcessor.SYNC_CURSOR_KEY, 9L)
        }
        assertFailsWith<IllegalStateException> {
            cache.markConversationDraftMirrored("retired", draftGeneration)
        }
        assertFailsWith<IllegalStateException> { pager.loadMore() }
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
        assertTrue(cache.listBotMessageDeliveries(0L, null, 10).isEmpty())
        assertEquals(0L, cache.maxBotMessageEventId())
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
    fun `chat tombstone atomically purges owned rows keeps resident flow and leaves other chats intact`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val deletedChatId = "deleted-chat"
        val retainedChatId = "retained-chat"
        fun message(chatId: String, id: String, seq: Long) = Message(
            chatId = chatId,
            clientMsgId = id,
            serverSeq = seq,
            senderUid = "sender",
            messageType = 1,
            timestamp = seq,
        )

        cache.upsertChat(Chat(chatId = deletedChatId, chatType = 2, name = "deleted"))
        cache.upsertChat(Chat(chatId = retainedChatId, chatType = 2, name = "retained"))
        cache.upsertMember(Member(chatId = deletedChatId, uid = "deleted-member", role = 0))
        cache.upsertMember(Member(chatId = retainedChatId, uid = "retained-member", role = 0))
        cache.upsertConversation(conv(deletedChatId))
        cache.upsertConversation(conv(retainedChatId))
        cache.setConversationDraft(deletedChatId, "deleted draft")
        cache.setConversationDraft(retainedChatId, "retained draft")
        val residentFlow = cache.observeMessages(deletedChatId)
        val deletedMessage = message(deletedChatId, "deleted-message", 1L)
        val retainedMessage = message(retainedChatId, "retained-message", 1L)
        cache.insertMessage(deletedMessage)
        cache.insertMessage(retainedMessage)
        cache.enqueueBotMessage(1L, deletedMessage)
        cache.enqueueBotMessage(2L, retainedMessage)

        cache.deleteChat(deletedChatId)
        cache.deleteChat(deletedChatId) // replayed CHAT_DELETED is an idempotent tombstone

        assertNull(cache.getChat(deletedChatId))
        assertTrue(cache.getMembers(deletedChatId).isEmpty())
        assertTrue(cache.getMessages(deletedChatId).isEmpty())
        assertTrue(cache.getConversations().none { it.chatId == deletedChatId })
        assertTrue(cache.getPendingConversationDrafts().none { it.chatId == deletedChatId })
        assertTrue(residentFlow === cache.observeMessages(deletedChatId))
        assertTrue(residentFlow.first().isEmpty())

        assertEquals("retained", cache.getChat(retainedChatId)?.name)
        assertEquals(listOf("retained-member"), cache.getMembers(retainedChatId).map(Member::uid))
        assertEquals(listOf("retained-message"), cache.getMessages(retainedChatId).map(Message::clientMsgId))
        assertEquals(listOf(retainedChatId), cache.getConversations().map(Conversation::chatId))
        assertEquals(listOf(retainedChatId), cache.getPendingConversationDrafts().map(PendingConversationDraft::chatId))
        assertEquals(2L, cache.peekBotMessage()?.eventId)

        val rebuilt = LocalCacheImpl(driver)
        assertNull(rebuilt.getChat(deletedChatId))
        assertTrue(rebuilt.getMembers(deletedChatId).isEmpty())
        assertTrue(rebuilt.getMessages(deletedChatId).isEmpty())
        assertTrue(rebuilt.getConversations().none { it.chatId == deletedChatId })
        assertTrue(rebuilt.getPendingConversationDrafts().none { it.chatId == deletedChatId })
        assertEquals(2L, rebuilt.peekBotMessage()?.eventId)
        assertEquals("retained", rebuilt.getChat(retainedChatId)?.name)
        assertEquals(listOf("retained-member"), rebuilt.getMembers(retainedChatId).map(Member::uid))
        assertEquals(listOf("retained-message"), rebuilt.getMessages(retainedChatId).map(Message::clientMsgId))
        assertEquals(listOf(retainedChatId), rebuilt.getConversations().map(Conversation::chatId))
        assertEquals(listOf(retainedChatId), rebuilt.getPendingConversationDrafts().map(PendingConversationDraft::chatId))

        val replayed = message(deletedChatId, "replayed-message", 2L)
        cache.insertMessage(replayed)
        assertEquals(listOf("replayed-message"), residentFlow.first().map(Message::clientMsgId))
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
    fun `authoritative history batch advances a bounded resident window without a cursor hole`() {
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
        val newestPage = (9 downTo 4).map { seq ->
            Message(
                chatId = "history-capacity",
                clientMsgId = "m$seq",
                serverSeq = seq.toLong(),
                senderUid = "u",
                messageType = 1,
                timestamp = seq.toLong(),
            )
        }
        val newestLease = cache.beginMessageHistoryLease(
            chatId = "history-capacity",
            resetResidentWindow = true,
        )
        assertTrue(cache.applyMessageHistoryPage(newestLease, newestPage))

        val olderPage = (3 downTo 1).map { seq ->
            Message(
                chatId = "history-capacity",
                clientMsgId = "m$seq",
                serverSeq = seq.toLong(),
                senderUid = "u",
                messageType = 1,
                timestamp = seq.toLong(),
            )
        }
        val olderLease = cache.beginMessageHistoryLease(
            chatId = "history-capacity",
            resetResidentWindow = false,
        )
        assertTrue(cache.applyMessageHistoryPage(olderLease, olderPage))

        val resident = cache.getMessages("history-capacity", 20)
        assertTrue(resident.size <= 6)
        assertEquals(9L, resident.first().serverSeq, "retain one newest authority anchor")
        assertEquals(listOf(3L, 2L, 1L), resident.takeLast(3).map(Message::serverSeq))
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
        val latestLease = cache.beginMessageHistoryLease("history-gap", resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(latestLease, latestPage))

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
        val olderLease = cache.beginMessageHistoryLease("history-gap", resetResidentWindow = false)
        assertTrue(cache.applyMessageHistoryPage(olderLease, olderPage))

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
