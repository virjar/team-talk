package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private fun personalConversation(chatId: String, peer: User) = Conversation(
        chatId = chatId,
        chatType = 1,
        peerUid = peer.uid,
        peerRevision = peer.revision,
        chatName = peer.name,
        chatAvatar = peer.avatar,
    )

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
    fun `empty newest history response cannot remove an outgoing message confirmed after request`() =
        runBlocking {
            val cache = newCache()
            val chatId = "ack-during-empty-history"
            val clientMsgId = "confirmed-after-request"
            val pager = cache.pager(chatId)
            val history = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
            val admitted = cache.enqueueOutgoingMessage(outgoing(chatId, clientMsgId), now = 1L)
            checkNotNull(cache.claimNextOutgoingMessage(now = 2L))

            cache.completeOutgoingMessage(
                admitted.localOrdinal,
                MessageAckPayload(chatId, clientMsgId, serverSeq = 1L, code = 0),
                now = 3L,
            )

            assertEquals(1L, pager.messages.first().single().serverSeq)
            assertTrue(cache.applyMessageHistoryPage(history, emptyList()))
            assertEquals(1L, cache.getMessages(chatId).single().serverSeq)
            assertEquals(1L, pager.messages.first().single().serverSeq)
            pager.close()
            cache.close()
        }

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
    fun `outgoing queue snapshot aggregates metadata without decoding payloads`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        driver.execute(
            null,
            """
            INSERT INTO outgoing_message(
                local_ordinal, client_msg_id, chat_id, sender_uid, payload, state, attempt_count,
                next_attempt_at, created_at, updated_at, completed_at
            ) VALUES
                (1, 'pending', 'snapshot', 'owner', X'00', 0, 2, 0, 10, 10, NULL),
                (2, 'inflight', 'snapshot', 'owner', X'01', 1, 3, 0, 20, 20, NULL),
                (3, 'retry', 'snapshot', 'owner', X'02', 2, 7, 100, 5, 30, NULL),
                (4, 'failed', 'snapshot', 'owner', X'03', 3, 9, 0, 1, 40, 40),
                (5, 'success', 'snapshot', 'owner', X'04', 4, 100, 0, 1, 50, 50)
            """.trimIndent(),
            0,
        )
        val cache = LocalCacheImpl(driver)

        assertEquals(
            OutgoingQueueSnapshot(
                pendingOrInFlightCount = 2L,
                retryWaitCount = 1L,
                terminalFailedCount = 1L,
                oldestActiveAgeMs = 100L,
                maxAttemptCount = 9L,
            ),
            cache.outgoingQueueSnapshot(now = 105L),
        )
        assertFailsWith<Exception> { cache.recoverOutgoingMessages(now = 105L) }
        cache.close()
    }

    @Test
    fun `terminal failure replacement is one atomic durable and resident transition`() = runBlocking {
        val cache = newCache()
        val failed = cache.enqueueOutgoingMessage(outgoing("recover", "old-id"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(
            failed.localOrdinal,
            "private detail",
            now = 3L,
            terminalCode = 400,
            failureCode = OutgoingFailureCode.CLIENT_VALIDATION,
        )
        val duplicate = cache.enqueueOutgoingMessage(outgoing("recover", "old-id"), now = 4L)
        assertEquals(failed.localOrdinal, duplicate.localOrdinal)
        assertEquals(OutgoingMessageState.TERMINAL_FAILED, duplicate.state)
        assertEquals(
            OutgoingFailureCode.CLIENT_VALIDATION,
            cache.findOutgoingFailureCode("recover", "old-id"),
        )
        val pager = cache.pager("recover")

        pager.messages.test {
            assertEquals(listOf("old-id"), awaitItem().map(Message::clientMsgId))
            val replacement = cache.replaceTerminalFailure(
                ownerUid = "owner",
                chatId = "recover",
                clientMsgId = "old-id",
                replacement = outgoing("recover", "new-id").copy(
                    body = RichTextBody("edited", plainText = "edited"),
                ),
                now = 5L,
            )

            assertEquals(OutgoingMessageState.PENDING, replacement?.state)
            assertEquals(listOf("new-id"), awaitItem().map(Message::clientMsgId))
            expectNoEvents()
            assertNull(cache.getOutgoingMessage("recover", "old-id"))
            assertEquals("new-id", cache.peekNextOutgoingMessage()?.message?.clientMsgId)
            assertEquals(Message.SEND_STATUS_QUEUED, cache.getMessages("recover").single().sendStatus)
            pager.close()
            awaitComplete()
        }
        cache.close()
    }

    @Test
    fun `GCed terminal receipt remains replaceable from exact failed projection`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, terminalReceiptLimit = 1)
        fun fail(id: String, now: Long) {
            val row = cache.enqueueOutgoingMessage(outgoing("gc-recovery", id), now)
            cache.claimNextOutgoingMessage(now)
            cache.markOutgoingMessageTerminalFailed(row.localOrdinal, "rejected-$id", now)
        }
        fail("gc-old", 1L)
        fail("gc-new", 2L)
        assertNull(cache.getOutgoingMessage("gc-recovery", "gc-old"))
        assertEquals(
            OutgoingFailureCode.REMOTE_REJECTED,
            cache.findOutgoingFailureCode("gc-recovery", "gc-old"),
        )

        val replacement = cache.replaceTerminalFailure(
            ownerUid = "owner",
            chatId = "gc-recovery",
            clientMsgId = "gc-old",
            replacement = outgoing("gc-recovery", "gc-replacement"),
            now = 3L,
        )

        assertEquals(OutgoingMessageState.PENDING, replacement?.state)
        assertEquals(
            setOf("gc-new", "gc-replacement"),
            cache.getMessages("gc-recovery").mapTo(mutableSetOf(), Message::clientMsgId),
        )
        cache.close()
    }

    @Test
    fun `fresh id replacement fails closed for uncertain result before and after receipt GC`() {
        val unsafeCodes = OutgoingFailureCode.entries.filterNot {
            it.allowsFreshClientMsgIdReplacement
        }
        val cache = newCache()
        unsafeCodes.forEachIndexed { index, failureCode ->
            val id = "unsafe-$index"
            val row = cache.enqueueOutgoingMessage(outgoing("unsafe", id), now = index + 1L)
            cache.claimNextOutgoingMessage(now = index + 1L)
            cache.markOutgoingMessageTerminalFailed(
                localOrdinal = row.localOrdinal,
                error = "private-$failureCode",
                now = index + 1L,
                failureCode = failureCode,
            )
            assertEquals(failureCode, cache.findOutgoingFailureCode("unsafe", id))
            assertNull(
                cache.replaceTerminalFailure(
                    ownerUid = "owner",
                    chatId = "unsafe",
                    clientMsgId = id,
                    replacement = outgoing("unsafe", "replacement-$index"),
                    now = 100L + index,
                ),
                "fresh-id replacement must reject $failureCode",
            )
        }
        cache.close()

        val gcDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(gcDriver)
        val gcCache = LocalCacheImpl(gcDriver, terminalReceiptLimit = 1)
        fun fail(id: String, code: OutgoingFailureCode, now: Long) {
            val row = gcCache.enqueueOutgoingMessage(outgoing("unsafe-gc", id), now)
            gcCache.claimNextOutgoingMessage(now)
            gcCache.markOutgoingMessageTerminalFailed(
                row.localOrdinal,
                "private-$code",
                now,
                failureCode = code,
            )
        }
        fail("uncertain-old", OutgoingFailureCode.ACK_TIMEOUT, 1L)
        fail("newest", OutgoingFailureCode.REMOTE_REJECTED, 2L)
        assertNull(gcCache.getOutgoingMessage("unsafe-gc", "uncertain-old"))
        assertEquals(
            OutgoingFailureCode.ACK_TIMEOUT,
            gcCache.findOutgoingFailureCode("unsafe-gc", "uncertain-old"),
        )
        assertNull(
            gcCache.replaceTerminalFailure(
                "owner",
                "unsafe-gc",
                "uncertain-old",
                outgoing("unsafe-gc", "must-not-send"),
                now = 3L,
            ),
        )
        assertTrue(gcCache.discardTerminalFailure("owner", "unsafe-gc", "uncertain-old"))
        gcCache.close()
    }

    @Test
    fun `discard and replace fail closed for authority and replacement conflict rolls back`() {
        val cache = newCache()
        val failed = cache.enqueueOutgoingMessage(outgoing("fail-close", "failed"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "rejected", now = 3L)
        val destinationAuthority = outgoing("fail-close", "occupied").copy(
            serverSeq = 9L,
            senderUid = "peer",
            sendStatus = Message.SEND_STATUS_SENT,
        )
        cache.insertMessage(destinationAuthority)

        assertFailsWith<OutgoingMessageConflictException> {
            cache.replaceTerminalFailure(
                "owner",
                "fail-close",
                "failed",
                outgoing("fail-close", "occupied"),
                now = 4L,
            )
        }
        assertEquals(OutgoingMessageState.TERMINAL_FAILED, cache.getOutgoingMessage("fail-close", "failed")?.state)
        assertEquals(Message.SEND_STATUS_FAILED, cache.getMessages("fail-close").first {
            it.clientMsgId == "failed"
        }.sendStatus)

        cache.insertMessage(
            outgoing("fail-close", "failed").copy(
                serverSeq = 10L,
                sendStatus = Message.SEND_STATUS_SENT,
            ),
        )
        assertFalse(cache.discardTerminalFailure("owner", "fail-close", "failed"))
        assertNull(
            cache.replaceTerminalFailure(
                "owner",
                "fail-close",
                "failed",
                outgoing("fail-close", "fresh"),
                now = 5L,
            ),
        )
        assertEquals(10L, cache.getMessages("fail-close").first {
            it.clientMsgId == "failed"
        }.serverSeq)
        cache.close()
    }

    @Test
    fun `replacement rollback restores old outbox after failure behind new insertion`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val failed = cache.enqueueOutgoingMessage(outgoing("atomic", "old"), now = 1L)
        cache.claimNextOutgoingMessage(now = 2L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "rejected", now = 3L)
        driver.execute(
            null,
            """
            CREATE TRIGGER force_failed_projection_delete_abort
            BEFORE DELETE ON message
            WHEN OLD.chat_id = 'atomic' AND OLD.client_msg_id = 'old'
            BEGIN
                SELECT RAISE(ABORT, 'forced rollback');
            END
            """.trimIndent(),
            0,
        )

        assertFailsWith<Exception> {
            cache.replaceTerminalFailure(
                "owner",
                "atomic",
                "old",
                outgoing("atomic", "new"),
                now = 4L,
            )
        }

        assertEquals(OutgoingMessageState.TERMINAL_FAILED, cache.getOutgoingMessage("atomic", "old")?.state)
        assertNull(cache.getOutgoingMessage("atomic", "new"))
        assertEquals(listOf("old"), cache.getMessages("atomic").map(Message::clientMsgId))
        assertEquals(Message.SEND_STATUS_FAILED, cache.getMessages("atomic").single().sendStatus)
        cache.close()
    }

    @Test
    fun `discard removes exact failed projection even after receipt GC`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, terminalReceiptLimit = 1)
        listOf("discard-old", "discard-new").forEachIndexed { index, id ->
            val row = cache.enqueueOutgoingMessage(outgoing("discard", id), now = index + 1L)
            cache.claimNextOutgoingMessage(now = index + 1L)
            cache.markOutgoingMessageTerminalFailed(row.localOrdinal, "rejected", now = index + 1L)
        }
        assertNull(cache.getOutgoingMessage("discard", "discard-old"))

        assertTrue(cache.discardTerminalFailure("owner", "discard", "discard-old"))
        assertFalse(cache.discardTerminalFailure("owner", "discard", "discard-old"))
        assertEquals(listOf("discard-new"), cache.getMessages("discard").map(Message::clientMsgId))
        cache.close()
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
            MessageAckPayload("c1", "m1", serverSeq = 9L, code = 0),
            now = 6L,
        )

        val retained = cache.recoverOutgoingMessages(now = 7L).single()
        assertEquals(OutgoingMessageState.TERMINAL_FAILED, retained.state)
        assertEquals(OutgoingFailureCode.SESSION_RETIRED, retained.failureCode)
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

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertEquals("m1", cache.getMessages("c1").single().clientMsgId)
        assertEquals(Message.SEND_STATUS_QUEUED, cache.getMessages("c1").single().sendStatus)
        assertEquals("m2", cache.getMessages("c2").single().clientMsgId)
        assertEquals(Message.SEND_STATUS_FAILED, cache.getMessages("c2").single().sendStatus)
        val diagnostics = cache.recoverOutgoingMessages(now = 5L)
        assertEquals(
            OutgoingFailureCode.REMOTE_REJECTED,
            diagnostics.single { it.localOrdinal == failed.localOrdinal }.failureCode,
        )
        assertEquals("m1", cache.peekNextOutgoingMessage()?.message?.clientMsgId)
    }

    @Test
    fun `projection reset preserves GCed failed projections for exact discard and replacement`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, terminalReceiptLimit = 1)
        fun fail(id: String, now: Long) {
            val row = cache.enqueueOutgoingMessage(outgoing("reset-gc", id), now)
            assertEquals(row.localOrdinal, cache.claimNextOutgoingMessage(now)?.localOrdinal)
            cache.markOutgoingMessageTerminalFailed(row.localOrdinal, "rejected-$id", now)
        }
        fail("discard-after-reset", 1L)
        fail("replace-after-reset", 2L)
        fail("retained-receipt", 3L)
        assertNull(cache.getOutgoingMessage("reset-gc", "discard-after-reset"))
        assertNull(cache.getOutgoingMessage("reset-gc", "replace-after-reset"))
        assertEquals(
            OutgoingMessageState.TERMINAL_FAILED,
            cache.getOutgoingMessage("reset-gc", "retained-receipt")?.state,
        )
        val resident = cache.pager("reset-gc")

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertEquals(
            setOf("discard-after-reset", "replace-after-reset", "retained-receipt"),
            cache.getMessages("reset-gc").mapTo(mutableSetOf(), Message::clientMsgId),
        )
        assertEquals(3, cache.getMessages("reset-gc").size, "reset must restore each composite key once")
        assertEquals(
            setOf("discard-after-reset", "replace-after-reset", "retained-receipt"),
            resident.messages.first().mapTo(mutableSetOf(), Message::clientMsgId),
        )
        assertTrue(cache.discardTerminalFailure("owner", "reset-gc", "discard-after-reset"))
        val replacement = cache.replaceTerminalFailure(
            ownerUid = "owner",
            chatId = "reset-gc",
            clientMsgId = "replace-after-reset",
            replacement = outgoing("reset-gc", "fresh-after-reset"),
            now = 4L,
        )

        assertEquals(OutgoingMessageState.PENDING, replacement?.state)
        assertEquals(
            setOf("retained-receipt", "fresh-after-reset"),
            cache.getMessages("reset-gc").mapTo(mutableSetOf(), Message::clientMsgId),
        )
        assertFalse(cache.discardTerminalFailure("owner", "reset-gc", "discard-after-reset"))
        assertNull(cache.getOutgoingMessage("reset-gc", "replace-after-reset"))
        assertEquals(
            setOf("retained-receipt", "fresh-after-reset"),
            resident.messages.first().mapTo(mutableSetOf(), Message::clientMsgId),
        )
        resident.close()
        cache.close()
    }

    @Test
    fun `projection reset does not preserve authoritative or non-failed orphan projections`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.insertMessage(outgoing("reset-invalid", "recoverable").copy(
            sendStatus = Message.SEND_STATUS_FAILED,
        ))
        cache.insertMessage(outgoing("reset-invalid", "authoritative-failed").copy(
            serverSeq = 9L,
            sendStatus = Message.SEND_STATUS_SENT,
        ))
        driver.execute(
            null,
            """
            UPDATE message SET send_status = ${Message.SEND_STATUS_FAILED}
            WHERE chat_id = 'reset-invalid' AND client_msg_id = 'authoritative-failed'
            """.trimIndent(),
            0,
        )
        cache.insertMessage(outgoing("reset-invalid", "optimistic-queued").copy(
            sendStatus = Message.SEND_STATUS_QUEUED,
        ))

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertEquals(listOf("recoverable"), cache.getMessages("reset-invalid").map(Message::clientMsgId))
        assertTrue(cache.discardTerminalFailure("owner", "reset-invalid", "recoverable"))
        assertFalse(cache.discardTerminalFailure("owner", "reset-invalid", "authoritative-failed"))
        assertFalse(cache.discardTerminalFailure("owner", "reset-invalid", "optimistic-queued"))
        assertTrue(cache.getMessages("reset-invalid").isEmpty())
        cache.close()
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
    fun `worker recovery restores pending and retry projections to queued status`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.enqueueOutgoingMessage(outgoing("recovery-status", "pending"), now = 1L)
        val retry = cache.enqueueOutgoingMessage(outgoing("recovery-status", "retry"), now = 2L)
        cache.claimNextOutgoingMessage(now = 3L)
        cache.claimNextOutgoingMessage(now = 3L)
        cache.markOutgoingMessageRetry(retry.localOrdinal, "network", nextAttemptAt = 10L, now = 4L)
        driver.execute(
            null,
            "UPDATE message SET send_status=${Message.SEND_STATUS_UPLOADING} WHERE chat_id='recovery-status'",
            0,
        )

        cache.recoverOutgoingState(now = 5L)

        assertEquals(
            setOf(Message.SEND_STATUS_QUEUED),
            cache.getMessages("recovery-status").mapTo(mutableSetOf(), Message::sendStatus),
        )
        assertEquals(
            setOf(OutgoingMessageState.PENDING, OutgoingMessageState.RETRY_WAIT),
            cache.recoverOutgoingMessages(now = 6L).mapTo(mutableSetOf(), OutgoingMessage::state),
        )
        assertEquals(
            OutgoingFailureCode.PROCESS_INTERRUPTED,
            cache.getOutgoingMessage("recovery-status", "pending")?.failureCode,
        )
        assertEquals(
            OutgoingFailureCode.UNEXPECTED_FAILURE,
            cache.getOutgoingMessage("recovery-status", "retry")?.failureCode,
        )
        cache.close()
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
            MessageAckPayload("collision", "same-id", serverSeq = 99L, code = 0),
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
        cache.completeOutgoingMessage(
            admitted.localOrdinal,
            MessageAckPayload("c1", "sent", 7L, 0),
            now = 3L,
        )

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertTrue(cache.getMessages("c1").isEmpty())
        val receipt = cache.getOutgoingMessage("c1", "sent")
        assertEquals(OutgoingMessageState.SUCCESS, receipt?.state)
        assertEquals(7L, receipt?.serverSeq)
    }

    @Test
    fun `terminal receipt GC is combined and never removes active rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver, terminalReceiptLimit = 1)

        fun complete(chatId: String, clientMsgId: String, seq: Long) {
            val row = cache.enqueueOutgoingMessage(outgoing(chatId, clientMsgId), now = seq)
            cache.claimNextOutgoingMessage(now = seq)
            cache.completeOutgoingMessage(
                row.localOrdinal,
                MessageAckPayload(chatId, clientMsgId, seq, 0),
                now = seq,
            )
        }
        complete("c1", "old-success", 11L)
        complete("c2", "new-success", 12L)
        val failed = cache.enqueueOutgoingMessage(outgoing("c3", "failed"), now = 13L)
        cache.claimNextOutgoingMessage(now = 13L)
        cache.markOutgoingMessageTerminalFailed(failed.localOrdinal, "rejected", now = 14L, terminalCode = 400)
        cache.enqueueOutgoingMessage(outgoing("c4", "active"), now = 15L)

        assertNull(cache.getOutgoingMessage("c1", "old-success"))
        assertNull(cache.getOutgoingMessage("c2", "new-success"))
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
        val cache = LocalCacheImpl(driver, terminalReceiptLimit = 1)
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
    fun `active outbox count rejects only new requests after exact idempotency checks`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(
            driver,
            LocalOutboxLimits(activeOutgoingCount = 1),
        )
        val original = outgoing("capacity", "stable")
        val admitted = cache.enqueueOutgoingMessage(original, now = 1L, requestFingerprint = byteArrayOf(1))

        assertEquals(
            admitted.localOrdinal,
            cache.enqueueOutgoingMessage(original, now = 2L, requestFingerprint = byteArrayOf(1)).localOrdinal,
        )
        assertFailsWith<OutgoingMessageConflictException> {
            cache.enqueueOutgoingMessage(original, now = 3L, requestFingerprint = byteArrayOf(2))
        }
        val failure = assertFailsWith<LocalOutboxCapacityExceededException> {
            cache.enqueueOutgoingMessage(outgoing("capacity", "new-id"), now = 4L)
        }
        assertEquals(LocalOutboxKind.OUTGOING_MESSAGE, failure.outbox)
        assertEquals(LocalOutboxCapacityDimension.ENTRY_COUNT, failure.dimension)
        assertEquals(listOf("stable"), cache.recoverOutgoingMessages(5L).map { it.message.clientMsgId })
        cache.close()
    }

    @Test
    fun `active byte rejection is atomic and fingerprints are hard bounded`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(
            driver,
            LocalOutboxLimits(activeOutgoingBytes = 1L),
        )

        val failure = assertFailsWith<LocalOutboxCapacityExceededException> {
            cache.enqueueOutgoingMessage(outgoing("bytes", "rejected"), now = 1L)
        }
        assertEquals(LocalOutboxCapacityDimension.STORED_BYTES, failure.dimension)
        assertTrue(cache.recoverOutgoingMessages(2L).isEmpty())
        assertTrue(cache.getMessages("bytes").isEmpty())

        assertFailsWith<IllegalArgumentException> {
            cache.enqueueOutgoingMessage(outgoing("fp", "empty"), now = 3L, requestFingerprint = byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            cache.enqueueOutgoingMessage(outgoing("fp", "large"), now = 3L, requestFingerprint = ByteArray(65))
        }

        val boundaryMessage = outgoing("fp", "maximum")
        val boundaryBytes =
            ProtoCodec.encode(canonicalizeOutboundMessage(boundaryMessage)).size.toLong() + 64L
        val fingerprintDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(fingerprintDriver)
        val fingerprintCache = LocalCacheImpl(
            fingerprintDriver,
            LocalOutboxLimits(activeOutgoingBytes = boundaryBytes),
        )
        assertEquals(
            OutgoingMessageState.PENDING,
            fingerprintCache.enqueueOutgoingMessage(
                boundaryMessage,
                now = 4L,
                requestFingerprint = ByteArray(64),
            ).state,
        )
        assertEquals(
            LocalOutboxCapacityDimension.STORED_BYTES,
            assertFailsWith<LocalOutboxCapacityExceededException> {
                fingerprintCache.enqueueOutgoingMessage(outgoing("fp", "over-boundary"), now = 5L)
            }.dimension,
        )
        cache.close()
        fingerprintCache.close()
    }

    @Test
    fun `terminal receipt byte GC retains the newest completed prefix and bounds errors`() {
        val firstMessage = outgoing("byte-gc", "receipt-1")
        val oneReceiptBytes = ProtoCodec.encode(canonicalizeOutboundMessage(firstMessage)).size.toLong()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(
            driver,
            LocalOutboxLimits(
                terminalOutgoingCount = 10,
                terminalOutgoingBytes = oneReceiptBytes,
            ),
        )

        fun fail(message: Message, now: Long, error: String) {
            val row = cache.enqueueOutgoingMessage(message, now)
            cache.claimNextOutgoingMessage(now)
            cache.markOutgoingMessageTerminalFailed(row.localOrdinal, error, now)
        }

        fail(firstMessage, now = 1L, error = "old")
        fail(outgoing("byte-gc", "receipt-2"), now = 2L, error = "x".repeat(1_200))

        assertNull(cache.getOutgoingMessage("byte-gc", "receipt-1"))
        val retained = cache.getOutgoingMessage("byte-gc", "receipt-2")
        assertEquals(OutgoingMessageState.TERMINAL_FAILED, retained?.state)
        val retainedDiagnostic = AppDatabase(driver).appDatabaseQueries
            .selectOutgoingMessageById("byte-gc", "receipt-2")
            .executeAsOne()
            .last_error
        assertEquals(MAX_OUTGOING_LAST_ERROR_CHARACTERS, retainedDiagnostic?.length)
        assertEquals("x".repeat(MAX_OUTGOING_LAST_ERROR_CHARACTERS), retainedDiagnostic)
        cache.close()
    }

    @Test
    fun `explicit cancel and startup recovery both enforce the terminal receipt budget`() {
        val root = createTempDirectory("outgoing-terminal-gc-").toFile()
        val database = root.resolve("cache.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val first = LocalCacheImpl(
                firstDriver,
                LocalOutboxLimits(terminalOutgoingCount = 2),
            )
            first.enqueueOutgoingMessage(outgoing("gc", "old"), now = 1L)
            first.enqueueOutgoingMessage(outgoing("gc", "new"), now = 2L)
            first.cancelOutgoingMessages("session retired", now = 3L)
            assertEquals(OutgoingMessageState.TERMINAL_FAILED, first.getOutgoingMessage("gc", "old")?.state)
            assertEquals(OutgoingMessageState.TERMINAL_FAILED, first.getOutgoingMessage("gc", "new")?.state)
            first.close()

            val reopened = LocalCacheImpl(
                JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}"),
                LocalOutboxLimits(terminalOutgoingCount = 1),
            )
            // 构造是被动的；worker 的启动边界负责恢复与回执 GC。
            assertEquals(OutgoingMessageState.TERMINAL_FAILED, reopened.getOutgoingMessage("gc", "old")?.state)
            reopened.recoverOutgoingState(now = 4L)
            assertNull(reopened.getOutgoingMessage("gc", "old"))
            assertEquals(OutgoingMessageState.TERMINAL_FAILED, reopened.getOutgoingMessage("gc", "new")?.state)

            reopened.enqueueOutgoingMessage(outgoing("gc", "cancelled-last"), now = 5L)
            reopened.cancelOutgoingMessages("cancelled", now = 6L)
            assertNull(reopened.getOutgoingMessage("gc", "new"))
            assertEquals(
                OutgoingMessageState.TERMINAL_FAILED,
                reopened.getOutgoingMessage("gc", "cancelled-last")?.state,
            )
            reopened.close()
        } finally {
            root.deleteRecursively()
        }
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
        pager.close()
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
        pager.close()
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
        pager.close()
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
        apply(false, emptyList()) // 权威的历史终点
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
        pager.close()
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
            pager.close()
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
            cache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 9L)
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
        firstCache.bindSyncDataset(TEST_SYNC_DATASET_ID)

        assertEquals(
            91L,
            firstCache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 91L).cursor,
        )
        assertEquals(
            91L,
            firstCache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 40L).cursor,
            "a delayed duplicate must not regress the durable cursor",
        )

        val rebuiltCache = LocalCacheImpl(driver)
        val client = ImClient()
        try {
            val rebuiltProcessor = EventProcessor(client, rebuiltCache)
            assertEquals(91L, rebuiltProcessor.lastEventId.value)
            assertEquals(91L, requireNotNull(rebuiltCache.getSyncState()).cursor)
        } finally {
            client.destroy()
        }
    }

    @Test
    fun `server projection reset is transactional and keeps resident message flow attached`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.bindSyncDataset(TEST_SYNC_DATASET_ID)
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
        cache.enqueueConversationRead("c1", 1L)
        cache.insertMessage(message)
        cache.enqueueBotMessage(9L, message)
        cache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 9L)
        val residentPager = cache.pager("c1")

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertNull(cache.getUser("u1"))
        assertTrue(cache.getContacts().isEmpty())
        assertNull(cache.getChat("c1"))
        assertTrue(cache.getMembers("c1").isEmpty())
        assertTrue(cache.getConversations().isEmpty())
        assertTrue(cache.getMessages("c1").isEmpty())
        assertEquals("pending", cache.getPendingConversationDrafts().single().draft)
        assertEquals(1L, cache.getPendingConversationReads().single().readSeq)
        assertEquals(0L, requireNotNull(cache.getSyncState()).cursor)
        assertNull(cache.peekBotMessage())
        assertTrue(cache.listBotMessageDeliveries(0L, null, 10).isEmpty())
        assertEquals(0L, cache.maxBotMessageEventId())
        assertTrue(residentPager.messages.first().isEmpty())

        val replayed = message.copy(clientMsgId = "m2", serverSeq = 2)
        cache.insertMessage(replayed)
        assertEquals(listOf("m2"), residentPager.messages.first().map(Message::clientMsgId))
        residentPager.close()

        val rebuilt = LocalCacheImpl(driver)
        assertNull(rebuilt.getUser("u1"))
        assertEquals(listOf("m2"), rebuilt.getMessages("c1").map(Message::clientMsgId))
        assertEquals(0L, requireNotNull(rebuilt.getSyncState()).cursor)
    }

    @Test
    fun `checkpoint atomically replaces narrow projection and preserves every local reliable fact`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        cache.bindSyncDataset(TEST_SYNC_DATASET_ID)
        cache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 9L)

        cache.upsertUser(User("stale-user", "stale", "Stale"))
        cache.upsertContact(Contact("current-user", "stale-user"))
        cache.upsertChat(Chat("stale-chat", 2, name = "Stale chat"))
        cache.upsertMember(Member(uid = "stale-user", chatId = "stale-chat", role = 0))
        cache.upsertConversation(conv("checkpoint-chat", readSeq = 1L, draft = "stale remote"))
        val draftGeneration = cache.setConversationDraft("checkpoint-chat", "local draft")
        cache.enqueueConversationRead("checkpoint-chat", 7L)
        cache.setConversationDraft("orphan-chat", "orphan draft")
        cache.enqueueConversationRead("orphan-chat", 3L)

        val authoritativeMessage = Message(
            chatId = "stale-chat",
            clientMsgId = "confirmed",
            serverSeq = 4L,
            senderUid = "stale-user",
            messageType = 1,
            timestamp = 4L,
        )
        cache.insertMessage(authoritativeMessage)
        val outgoing = cache.enqueueOutgoingMessage(outgoing("stale-chat", "outgoing"), now = 5L)
        cache.enqueueBotMessage(8L, authoritativeMessage)
        queries.advanceBotInboxRetainedFloor(6L)
        val residentPager = cache.pager("stale-chat")

        val organizationUnit = OrganizationUnit(unitId = "engineering", name = "Engineering")
        cache.upsertOrganizationUnit(organizationUnit)
        val documentSpace = DocumentSpace(
            spaceId = "docs",
            name = "Docs",
            myRole = DocumentSpace.ROLE_OWNER,
            createdBy = "current-user",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val documentLease = cache.beginDocumentSpaceSnapshot()
        assertTrue(cache.applyDocumentSpaceSnapshot(documentLease, listOf(documentSpace)))

        val groupCreation = PendingGroupCreationCommand.create(
            operationId = "00000000-0000-4000-8000-000000000101",
            creatorUid = "current-user",
            name = "Reliable group",
            memberUids = listOf("friend-user"),
        )
        val contactDecision = PendingContactDecision(
            operationId = "00000000-0000-4000-8000-000000000102",
            token = "00000000-0000-4000-8000-000000000103",
            decision = PendingContactDecisionType.ACCEPT,
            createdAt = 1L,
        )
        val inviteCreation = PendingInviteLinkCreation(
            operationId = "00000000-0000-4000-8000-000000000104",
            chatId = "00000000-0000-4000-8000-000000000105",
            name = "Reliable invite",
            maxUses = 2,
            expiresAt = 0L,
            createdAt = 2L,
        )
        val botCommand = PendingGroupBotCredentialCommand.create(
            operationId = "00000000-0000-4000-8000-000000000106",
            ownerUid = "current-user",
            chatId = "checkpoint-chat",
            name = "Build bot",
            webhookToken = "ttb_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )
        val fileCommand = PendingGroupFileCommand.createFolder(
            commandId = "00000000-0000-4000-8000-000000000107",
            entryId = "00000000-0000-4000-8000-000000000108",
            chatId = "00000000-0000-4000-8000-000000000109",
            parentId = null,
            name = "Reliable folder",
            createdAt = 3L,
        )
        cache.replacePendingGroupCreation(groupCreation)
        cache.preparePendingContactDecision(contactDecision)
        cache.preparePendingInviteLinkCreation(inviteCreation)
        cache.preparePendingGroupBotCredentialCommand(botCommand)
        cache.preparePendingGroupFileCommand(fileCommand)

        val currentUser = User("current-user", "current", "Current")
        val friend = User("friend-user", "friend", "Friend")
        val checkpoint = ServerProjectionCheckpoint(
            datasetId = TEST_SYNC_DATASET_ID,
            baseEventId = 100L,
            currentUser = currentUser,
            contacts = listOf(Contact(currentUser.uid, friend.uid, user = friend)),
            chats = listOf(Chat("checkpoint-chat", 1, name = "Fresh chat")),
            conversations = listOf(
                conv("checkpoint-chat", readSeq = 2L, unread = 8, draft = "remote draft")
                    .copy(lastSeq = 10L),
            ),
        )

        assertEquals(
            ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 100L),
            cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 9L, checkpoint),
        )

        assertNull(cache.getUser("stale-user"))
        assertEquals(currentUser, cache.getUser(currentUser.uid))
        assertEquals(friend, cache.getUser(friend.uid))
        assertEquals(listOf(friend.uid), cache.getContacts().map(Contact::friendUid))
        assertNull(cache.getChat("stale-chat"))
        assertEquals("Fresh chat", cache.getChat("checkpoint-chat")?.name)
        assertTrue(cache.getMembers("stale-chat").isEmpty())
        assertEquals(
            conv("checkpoint-chat", readSeq = 7L, unread = 3, draft = "local draft")
                .copy(lastSeq = 10L, chatName = null, lastMsgTimestamp = checkpoint.conversations.single().lastMsgTimestamp),
            cache.getConversations().single(),
        )
        assertEquals(
            setOf("checkpoint-chat", "orphan-chat"),
            cache.getPendingConversationDrafts().map(PendingConversationDraft::chatId).toSet(),
        )
        assertEquals(draftGeneration, cache.getPendingConversationDraft("checkpoint-chat")?.generation)
        assertEquals(
            setOf("checkpoint-chat", "orphan-chat"),
            cache.getPendingConversationReads().map(PendingConversationRead::chatId).toSet(),
        )
        assertEquals(listOf("outgoing"), cache.getMessages("stale-chat").map(Message::clientMsgId))
        assertEquals(outgoing.localOrdinal, cache.getOutgoingMessage("stale-chat", "outgoing")?.localOrdinal)
        assertEquals(listOf("outgoing"), residentPager.messages.first().map(Message::clientMsgId))
        assertEquals(8L, cache.peekBotMessage()?.eventId)
        assertEquals(6L, queries.selectBotInboxRetainedFloor().executeAsOne())
        assertEquals(listOf(organizationUnit), cache.getOrganizationUnitProjection().units)
        assertEquals(listOf(documentSpace), cache.getDocumentSpaces())
        assertEquals(groupCreation, cache.getPendingGroupCreation())
        assertEquals(listOf(contactDecision), cache.getPendingContactDecisions())
        assertEquals(listOf(inviteCreation), cache.getPendingInviteLinkCreations())
        assertEquals(botCommand, cache.getPendingGroupBotCredentialCommand())
        assertEquals(listOf(fileCommand), cache.getPendingGroupFileCommands())

        val rebuilt = LocalCacheImpl(driver)
        assertEquals(ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 100L), rebuilt.getSyncState())
        assertEquals(currentUser, rebuilt.getUser(currentUser.uid))
        assertEquals(listOf(friend.uid), rebuilt.getContacts().map(Contact::friendUid))
        assertEquals(listOf("outgoing"), rebuilt.getMessages("stale-chat").map(Message::clientMsgId))
        assertEquals(8L, rebuilt.peekBotMessage()?.eventId)
        assertEquals(listOf(organizationUnit), rebuilt.getOrganizationUnitProjection().units)
        assertEquals(listOf(documentSpace), rebuilt.getDocumentSpaces())
        assertEquals(groupCreation, rebuilt.getPendingGroupCreation())
        assertEquals(listOf(contactDecision), rebuilt.getPendingContactDecisions())
        assertEquals(listOf(inviteCreation), rebuilt.getPendingInviteLinkCreations())
        assertEquals(botCommand, rebuilt.getPendingGroupBotCredentialCommand())
        assertEquals(listOf(fileCommand), rebuilt.getPendingGroupFileCommands())
        residentPager.close()
    }

    @Test
    fun `checkpoint preserves newer resident user and conversation identity revisions`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.bindSyncDataset(TEST_SYNC_DATASET_ID)
        cache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 7L)

        val localAvatar = Attachment("peer/avatar-v3.png", "avatar-v3.png", "image/png", 3L)
        val checkpointAvatar = Attachment("peer/avatar-v2.png", "avatar-v2.png", "image/png", 2L)
        val selfV3 = User(
            uid = "self",
            username = "self",
            name = "Self v3",
            revision = 3L,
        )
        val peerV3 = User(
            uid = "peer",
            username = "peer",
            name = "Peer v3",
            avatar = localAvatar,
            revision = 3L,
        )
        val outsideCheckpoint = User(
            uid = "outside",
            username = "outside",
            name = "Outside",
            revision = 4L,
        )
        val localConversation = Conversation(
            chatId = "personal-chat",
            chatType = 1,
            peerUid = peerV3.uid,
            peerRevision = peerV3.revision,
            chatName = peerV3.name,
            chatAvatar = localAvatar,
            lastMessage = "local message",
            lastMsgTimestamp = 30L,
            lastSeq = 30L,
            readSeq = 20L,
            unreadCount = 10,
            peerReadSeq = 25L,
            draft = "local remote draft",
        )
        cache.upsertUser(selfV3)
        cache.upsertContact(Contact(selfV3.uid, peerV3.uid, user = peerV3))
        cache.upsertUser(outsideCheckpoint)
        cache.upsertChat(Chat(localConversation.chatId, 1, name = "Local chat"))
        cache.upsertConversation(localConversation)

        val checkpointSelf = selfV3.copy(name = "Self v2", revision = 2L)
        val checkpointPeer = peerV3.copy(
            name = "Peer v2",
            avatar = checkpointAvatar,
            revision = 2L,
        )
        val checkpointConversation = localConversation.copy(
            peerRevision = checkpointPeer.revision,
            chatName = checkpointPeer.name,
            chatAvatar = checkpointAvatar,
            lastMessage = "checkpoint message",
            lastMsgTimestamp = 12L,
            lastSeq = 12L,
            readSeq = 4L,
            unreadCount = 8,
            peerReadSeq = 3L,
            draft = "checkpoint draft",
        )
        val expectedConversation = checkpointConversation.copy(
            peerUid = localConversation.peerUid,
            peerRevision = localConversation.peerRevision,
            chatName = localConversation.chatName,
            chatAvatar = localConversation.chatAvatar,
        )
        val checkpoint = ServerProjectionCheckpoint(
            datasetId = TEST_SYNC_DATASET_ID,
            baseEventId = 20L,
            currentUser = checkpointSelf,
            contacts = listOf(Contact(checkpointSelf.uid, checkpointPeer.uid, user = checkpointPeer)),
            chats = listOf(Chat(localConversation.chatId, 1, name = "Checkpoint chat")),
            conversations = listOf(checkpointConversation),
        )

        cache.observeUser(selfV3.uid).test {
            assertEquals(selfV3, awaitItem())
            cache.observeContacts().test {
                assertEquals(peerV3, awaitItem().single().user)
                cache.observeConversations().test {
                    assertEquals(localConversation, awaitItem().single())

                    assertEquals(
                        ServerProjectionSyncState(TEST_SYNC_DATASET_ID, checkpoint.baseEventId),
                        cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 7L, checkpoint),
                    )

                    assertEquals(expectedConversation, awaitItem().single())
                    cancelAndIgnoreRemainingEvents()
                }
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(selfV3, cache.getUser(selfV3.uid))
        assertEquals(peerV3, cache.getUser(peerV3.uid))
        assertEquals(peerV3, cache.getContacts().single().user)
        assertNull(cache.getUser(outsideCheckpoint.uid))
        assertEquals(expectedConversation, cache.getConversations().single())

        val rebuilt = LocalCacheImpl(driver)
        assertEquals(selfV3, rebuilt.getUser(selfV3.uid))
        assertEquals(peerV3, rebuilt.getContacts().single().user)
        assertNull(rebuilt.getUser(outsideCheckpoint.uid))
        assertEquals(expectedConversation, rebuilt.getConversations().single())
        rebuilt.close()
        cache.close()
    }

    @Test
    fun `checkpoint reconstructs canonical users for retained organization and personal relations`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.bindSyncDataset(TEST_SYNC_DATASET_ID)
        cache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 5L)

        val organizationUserB = User(
            uid = "organization-user",
            username = "organization-user",
            name = "Organization B",
            revision = 2L,
        )
        val organizationUnit = OrganizationUnit("unit", name = "Unit")
        cache.upsertOrganizationUnit(organizationUnit)
        cache.upsertOrganizationMember(
            OrganizationMember(organizationUnit.unitId, organizationUserB.uid, user = organizationUserB),
        )

        val retainedPeer = User("retained-peer", "retained-peer", "Peer v3", revision = 3L)
        val replacedPeer = User("replaced-peer", "replaced-peer", "Peer v1", revision = 1L)
        cache.upsertUser(retainedPeer)
        cache.upsertUser(replacedPeer)
        cache.upsertUser(User("unrelated", "unrelated", "Unrelated", revision = 4L))
        cache.upsertConversation(personalConversation("retained-chat", retainedPeer))
        cache.upsertConversation(personalConversation("replaced-chat", replacedPeer))

        val checkpointRetainedPeer = retainedPeer.copy(name = "Peer v2", revision = 2L)
        val checkpointReplacedPeer = replacedPeer.copy(name = "Peer v2", revision = 2L)
        val checkpoint = ServerProjectionCheckpoint(
            datasetId = TEST_SYNC_DATASET_ID,
            baseEventId = 15L,
            currentUser = User("self", "self", "Self", revision = 1L),
            contacts = emptyList(),
            chats = listOf(Chat("retained-chat", 1), Chat("replaced-chat", 1)),
            conversations = listOf(
                personalConversation("retained-chat", checkpointRetainedPeer),
                personalConversation("replaced-chat", checkpointReplacedPeer),
            ),
        )

        cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 5L, checkpoint)

        assertEquals(organizationUserB, cache.getUser(organizationUserB.uid))
        assertEquals(
            organizationUserB,
            cache.getOrganizationMemberProjection(organizationUnit.unitId).members.single().user,
        )
        assertEquals(retainedPeer, cache.getUser(retainedPeer.uid))
        assertNull(cache.getUser(replacedPeer.uid))
        assertNull(cache.getUser("unrelated"))
        assertEquals(
            mapOf("retained-chat" to 3L, "replaced-chat" to 2L),
            cache.getConversations().associate { it.chatId to it.peerRevision },
        )

        val staleOrganizationUserA = organizationUserB.copy(name = "Organization A", revision = 1L)
        assertTrue(cache.upsertTransientUserIfRelevant(staleOrganizationUserA))
        assertEquals(organizationUserB, cache.getUser(organizationUserB.uid))
        assertEquals(
            organizationUserB,
            cache.getOrganizationMemberProjection(organizationUnit.unitId).members.single().user,
        )

        val rebuilt = LocalCacheImpl(driver)
        assertEquals(organizationUserB, rebuilt.getUser(organizationUserB.uid))
        assertEquals(retainedPeer, rebuilt.getUser(retainedPeer.uid))
        assertNull(rebuilt.getUser(replacedPeer.uid))
        rebuilt.close()
        cache.close()
    }

    @Test
    fun `checkpoint rejects stale authority and SQL failure rolls back projection plus cursor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.bindSyncDataset(TEST_SYNC_DATASET_ID)
        cache.advanceSyncCursor(TEST_SYNC_DATASET_ID, 9L)
        val oldUser = User("old-user", "old", "Old")
        val oldChat = Chat("old-chat", 1, name = "Old chat")
        val oldConversation = conv("old-chat", draft = "old draft")
        val oldMessage = Message("old-chat", "old-message", 3L, "old-user", 1, 3L)
        cache.upsertUser(oldUser)
        cache.upsertChat(oldChat)
        cache.upsertConversation(oldConversation)
        cache.insertMessage(oldMessage)
        val checkpoint = ServerProjectionCheckpoint(
            datasetId = TEST_SYNC_DATASET_ID,
            baseEventId = 100L,
            currentUser = User("new-user", "new", "New"),
            contacts = emptyList(),
            chats = listOf(Chat("checkpoint-chat", 1)),
            conversations = listOf(conv("checkpoint-chat")),
        )

        assertFailsWith<IllegalStateException> {
            cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 8L, checkpoint)
        }
        driver.execute(
            null,
            """
            CREATE TRIGGER reject_checkpoint_chat
            BEFORE INSERT ON chat
            WHEN NEW.chat_id = 'checkpoint-chat'
            BEGIN
                SELECT RAISE(ABORT, 'injected checkpoint failure');
            END
            """.trimIndent(),
            0,
        )
        assertFailsWith<Exception> {
            cache.applyServerProjectionCheckpoint(TEST_SYNC_DATASET_ID, 9L, checkpoint)
        }

        assertEquals(ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 9L), cache.getSyncState())
        assertEquals(oldUser, cache.getUser(oldUser.uid))
        assertNull(cache.getUser("new-user"))
        assertEquals(oldChat, cache.getChat(oldChat.chatId))
        assertEquals(oldConversation, cache.getConversations().single())
        assertEquals(oldMessage, cache.getMessages(oldChat.chatId).single())
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
        val residentPager = cache.pager(deletedChatId)
        val deletedMessage = message(deletedChatId, "deleted-message", 1L)
        val retainedMessage = message(retainedChatId, "retained-message", 1L)
        cache.insertMessage(deletedMessage)
        cache.insertMessage(retainedMessage)
        cache.enqueueBotMessage(1L, deletedMessage)
        cache.enqueueBotMessage(2L, retainedMessage)

        cache.deleteChat(deletedChatId)
        cache.deleteChat(deletedChatId) // 重放的 CHAT_DELETED 是幂等的墓碑

        assertNull(cache.getChat(deletedChatId))
        assertTrue(cache.getMembers(deletedChatId).isEmpty())
        assertTrue(cache.getMessages(deletedChatId).isEmpty())
        assertTrue(cache.getConversations().none { it.chatId == deletedChatId })
        assertTrue(cache.getPendingConversationDrafts().none { it.chatId == deletedChatId })
        assertTrue(residentPager.messages.first().isEmpty())

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
        assertEquals(listOf("replayed-message"), residentPager.messages.first().map(Message::clientMsgId))
        residentPager.close()
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
    fun `stale empty snapshot cannot delete an orphan read outbox created during request`() {
        val cache = newCache()
        val staleGeneration = cache.beginConversationSnapshot()

        cache.enqueueConversationRead("opened-before-conversation-sync", 7L)

        assertFalse(cache.applyConversationSnapshot(staleGeneration, emptyList()))
        assertEquals(
            PendingConversationRead("opened-before-conversation-sync", 7L),
            cache.getPendingConversationReads().single(),
        )

        val authoritativeGeneration = cache.beginConversationSnapshot()
        assertTrue(cache.applyConversationSnapshot(authoritativeGeneration, emptyList()))
        assertTrue(cache.getPendingConversationReads().isEmpty())
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
        cache.enqueueConversationRead("c1", 17)
        val c = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(0, c.unreadCount, "标记已读必须即时清零（不等服务端回环）")
        assertEquals(17L, c.readSeq)
    }

    @Test
    fun `markConversationRead is monotonic and an older completion cannot regress it`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 10, unread = 7).copy(lastSeq = 20))

        cache.enqueueConversationRead("c1", 20)
        cache.enqueueConversationRead("c1", 17)

        val conversation = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(20L, conversation.readSeq)
        assertEquals(0, conversation.unreadCount)
    }

    @Test
    fun `markConversationRead recomputes remaining unread when only part of the window is visible`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 10, unread = 10).copy(lastSeq = 20))

        cache.enqueueConversationRead("c1", 17)

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
    fun `message short read does not allocate or touch a resident window`() {
        val cache = newCache()
        cache.insertMessage(
            Message(
                chatId = "short-read",
                clientMsgId = "persisted",
                serverSeq = 1,
                senderUid = "u",
                messageType = 1,
                timestamp = 1L,
            ),
        )

        assertEquals(MessageWindowResidentCounts(0, 0, 0), cache.residentMessageWindowCountsForTest())
        assertEquals(listOf("persisted"), cache.getMessages("short-read", 10).map(Message::clientMsgId))
        assertEquals(
            MessageWindowResidentCounts(0, 0, 0),
            cache.residentMessageWindowCountsForTest(),
            "a query must not become an unowned hot window",
        )
    }

    @Test
    fun `active pager survives idle LRU churn and remains on the publication path`() = runBlocking {
        val cache = newCache()
        val active = cache.pager("active", windowSize = 3)

        active.messages.test {
            assertTrue(awaitItem().isEmpty())
            repeat(LocalCache.MAX_ACTIVE_CHATS + 5) { index ->
                cache.pager("idle-$index", windowSize = 3).close()
            }
            cache.insertMessage(
                Message(
                    chatId = "active",
                    clientMsgId = "live",
                    serverSeq = 1L,
                    senderUid = "sender",
                    messageType = 1,
                    timestamp = 1L,
                ),
            )

            assertEquals(listOf("live"), awaitItem().map(Message::clientMsgId))
            val counts = cache.residentMessageWindowCountsForTest()
            assertEquals(LocalCache.MAX_ACTIVE_CHATS, counts.totalWindows)
            assertEquals(1, counts.activeWindows)
            assertEquals(1, counts.activeLeases)
            active.close()
            awaitComplete()
        }
    }

    @Test
    fun `capacity rejects a new chat only when every resident window has an active lease`() {
        val cache = newCache()
        val active = List(LocalCache.MAX_ACTIVE_CHATS) { index -> cache.pager("active-$index") }

        val sameChatSecondOwner = cache.pager("active-0")
        assertEquals(
            MessageWindowResidentCounts(
                totalWindows = LocalCache.MAX_ACTIVE_CHATS,
                activeWindows = LocalCache.MAX_ACTIVE_CHATS,
                activeLeases = LocalCache.MAX_ACTIVE_CHATS + 1,
            ),
            cache.residentMessageWindowCountsForTest(),
        )
        assertFailsWith<MessageWindowCapacityExceededException> { cache.pager("overflow") }

        sameChatSecondOwner.close()
        active.forEach { pager -> pager.close() }
    }

    @Test
    fun `same chat owners close independently and the remaining owner keeps converging`() = runBlocking {
        val cache = newCache()
        val first = cache.pager("shared-owner")
        val second = cache.pager("shared-owner")

        first.messages.test {
            assertTrue(awaitItem().isEmpty())
            first.close()
            awaitComplete()
        }
        assertEquals(MessageWindowResidentCounts(1, 1, 1), cache.residentMessageWindowCountsForTest())
        second.messages.test {
            assertTrue(awaitItem().isEmpty())
            cache.insertMessage(
                Message(
                    chatId = "shared-owner",
                    clientMsgId = "after-first-close",
                    serverSeq = 1L,
                    senderUid = "sender",
                    messageType = 1,
                    timestamp = 1L,
                ),
            )
            assertEquals(listOf("after-first-close"), awaitItem().map(Message::clientMsgId))
            second.close()
            awaitComplete()
        }
        assertEquals(MessageWindowResidentCounts(1, 0, 0), cache.residentMessageWindowCountsForTest())
    }

    @Test
    fun `undispatched collector may close another owner during publication`() = runBlocking {
        val cache = newCache()
        val first = cache.pager("reentrant-close")
        val second = cache.pager("reentrant-close")
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            first.messages.collect { messages ->
                if (messages.any { it.clientMsgId == "trigger" }) second.close()
            }
        }

        cache.insertMessage(
            Message(
                chatId = "reentrant-close",
                clientMsgId = "trigger",
                serverSeq = 1L,
                senderUid = "sender",
                messageType = 1,
                timestamp = 1L,
            ),
        )

        assertFailsWith<IllegalStateException> { second.loadMore() }
        assertEquals(MessageWindowResidentCounts(1, 1, 1), cache.residentMessageWindowCountsForTest())
        first.close()
        withTimeout(5_000L) { collector.join() }
    }

    @Test
    fun `repeated close of an evicted old lease cannot detach its same-chat successor`() = runBlocking {
        val cache = newCache()
        val old = cache.pager("aba")
        old.close()
        repeat(LocalCache.MAX_ACTIVE_CHATS) { index -> cache.pager("aba-churn-$index").close() }
        val successor = cache.pager("aba")

        old.close()
        successor.messages.test {
            assertTrue(awaitItem().isEmpty())
            cache.insertMessage(
                Message(
                    chatId = "aba",
                    clientMsgId = "successor-update",
                    serverSeq = 1L,
                    senderUid = "sender",
                    messageType = 1,
                    timestamp = 1L,
                ),
            )
            assertEquals(listOf("successor-update"), awaitItem().map(Message::clientMsgId))
            successor.close()
            awaitComplete()
        }
    }

    @Test
    fun `cache and lease close terminate collectors and closed pager cannot load`() = runBlocking {
        val cache = newCache()
        val pager = cache.pager("terminal")

        pager.messages.test {
            assertTrue(awaitItem().isEmpty())
            cache.close()
            awaitComplete()
        }
        assertFailsWith<IllegalStateException> { pager.loadMore() }
        pager.close()
    }

    @Test
    fun `message pager and short read enforce overflow-safe bounds`() {
        val cache = newCache()

        assertFailsWith<IllegalArgumentException> { cache.pager("invalid", windowSize = 0) }
        assertFailsWith<IllegalArgumentException> {
            cache.pager("invalid", windowSize = MessagePager.MAX_WINDOW_SIZE + 1)
        }
        assertFailsWith<IllegalArgumentException> { cache.getMessages("invalid", 0) }
        assertFailsWith<IllegalArgumentException> {
            cache.getMessages("invalid", LocalCache.MAX_MESSAGE_READ_LIMIT + 1)
        }
        val pager = cache.pager("bounded")
        assertFailsWith<IllegalArgumentException> { pager.loadMore(0) }
        assertFailsWith<IllegalArgumentException> { pager.loadMore(MessagePager.MAX_PAGE_SIZE + 1) }
        pager.close()
        assertFailsWith<IllegalStateException> { pager.loadMore() }
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
        val first = pager.messages.first()
        assertEquals(100, first.size, "初始窗口 100 条")
        pager.loadMore()
        val after = pager.messages.first()
        assertTrue(after.size > 100, "loadMore 追加更旧消息（实际 ${after.size}）")
        pager.close()
    }

    @Test
    fun `active message window keeps history pages newest first`() = runBlocking {
        val cache = newCache()
        // 在插入类似 RPC 的页面之前先让窗口驻留，这样测试走的是
        // 增量 upsert 路径，而不是只有初始的 SQL ORDER BY 路径。
        val pager = cache.pager("history-order", windowSize = 100)
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
            pager.messages.first().map { it.serverSeq },
        )
        pager.close()
    }

    @Test
    fun `authoritative history batch advances a bounded resident window without a cursor hole`() = runBlocking {
        val cache = newCache()
        val pager = cache.pager("history-capacity", windowSize = 3)
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

        val resident = pager.messages.first()
        assertTrue(resident.size <= 6)
        assertEquals(9L, resident.first().serverSeq, "retain one newest authority anchor")
        assertEquals(listOf(3L, 2L, 1L), resident.takeLast(3).map(Message::serverSeq))
        pager.close()
    }

    @Test
    fun `server page provenance keeps legal gaps while isolating a stale cached tail`() = runBlocking {
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
        assertEquals((30 downTo 21).map(Int::toLong), pager.messages.first().map(Message::serverSeq))

        // 最新页内部缺失 98。那是合法的服务端历史，不是损坏的缓存。
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
            pager.messages.first().map(Message::serverSeq),
            "the stale 30..1 tail must not become the cursor",
        )
        assertFalse(pager.hasMore.value, "only another server page may extend an anchored chain")
        pager.loadMore(pageSize = 10)
        assertEquals(listOf(100L, 99L, 97L), pager.messages.first().map(Message::serverSeq))

        // 96 跨页边界缺失；93..91 在更旧的页面内部缺失。
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
            pager.messages.first().map(Message::serverSeq),
            "legal holes inside and across authoritative pages must remain visible",
        )
        assertEquals(90L, pager.messages.first().minOf(Message::serverSeq))
        pager.close()
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

    @Test
    fun `草稿 generation 跨聊天单调且删除重建后旧 ACK 仍失效`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = null))
        cache.upsertConversation(conv("c2", draft = null))
        val deletedGeneration = cache.setConversationDraft("c1", "旧草稿")
        cache.deleteConversation("c1")
        val otherGeneration = cache.setConversationDraft("c2", "另一会话")
        cache.upsertConversation(conv("c1", draft = null))
        val rebuiltGeneration = cache.setConversationDraft("c1", "重建后的草稿")

        cache.markConversationDraftMirrored("c1", deletedGeneration)

        assertTrue(otherGeneration > deletedGeneration)
        assertTrue(rebuiltGeneration > otherGeneration)
        assertEquals(
            PendingConversationDraft("c1", "重建后的草稿", rebuiltGeneration),
            cache.getPendingConversationDrafts().first { it.chatId == "c1" },
        )
    }
}
