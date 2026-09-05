package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalMessageRetentionTest {
    @Test
    fun `construction retains the newest count prefix and restart is stable`() {
        val root = createTempDirectory("message-retention-count-").toFile()
        val databaseFile = root.resolve("cache.db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}").let { driver ->
                AppDatabase.Schema.create(driver)
                val queries = AppDatabase(driver).appDatabaseQueries
                (1L..5L).forEach { seq -> seed(queries, message("count", seq)) }
                driver.close()
            }

            repeat(2) {
                val cache = retainedCache(
                    JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}"),
                    retainedCount = 3,
                )
                assertEquals(listOf(5L, 4L, 3L), cache.getMessages("count", 10).map(Message::serverSeq))
                cache.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `byte budget retains one newest prefix and never skips its oversized successor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        seed(queries, message("bytes", 1L))
        seed(queries, message("bytes", 2L), encodedBody = ByteArray(256))
        seed(queries, message("bytes", 3L))

        val cache = retainedCache(driver, retainedCount = 3, retainedBytes = 200L)
        try {
            assertEquals(
                listOf(3L),
                cache.getMessages("bytes", 10).map(Message::serverSeq),
                "an older small row must not be retained beyond the first over-budget row",
            )
        } finally {
            cache.close()
        }
    }

    @Test
    fun `retention preserves seq zero non-success outgoing other chats and reliable outbox`() {
        val root = createTempDirectory("message-retention-facts-").toFile()
        val databaseFile = root.resolve("cache.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val first = LocalCacheImpl(firstDriver)
            val successful = first.enqueueOutgoingMessage(localMessage("protected", "successful"), now = 1L)
            assertNotNull(first.claimNextOutgoingMessage(now = 2L))
            first.completeOutgoingMessage(
                successful.localOrdinal,
                MessageAckPayload("protected", "successful", serverSeq = 1L, code = 0),
                now = 3L,
            )
            first.insertMessage(message("protected", 5L, "eligible-new"))
            val pending = first.enqueueOutgoingMessage(localMessage("protected", "pending"), now = 4L)
            first.insertMessage(
                localMessage("protected", "failed-local").copy(sendStatus = Message.SEND_STATUS_FAILED),
            )
            first.insertMessage(
                localMessage("protected", "seq-zero").copy(sendStatus = Message.SEND_STATUS_FAILED),
            )
            first.insertMessage(message("other", 1L, "other-message"))
            first.enqueueConversationRead("protected", 7L)
            first.close()

            JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}").let { raw ->
                raw.execute(
                    null,
                    "UPDATE message SET server_seq = 3, send_status = 0 " +
                        "WHERE chat_id = 'protected' AND client_msg_id = 'pending'",
                    0,
                )
                raw.execute(
                    null,
                    "UPDATE message SET server_seq = 2, outgoing_failure_code = 1 " +
                        "WHERE chat_id = 'protected' AND client_msg_id = 'failed-local'",
                    0,
                )
                raw.close()
            }

            repeat(2) {
                val cache = retainedCache(
                    JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}"),
                    retainedCount = 1,
                )
                val protectedIds = cache.getMessages("protected", 10).map(Message::clientMsgId).toSet()
                assertEquals(
                    setOf("eligible-new", "pending", "failed-local", "seq-zero"),
                    protectedIds,
                )
                assertEquals(
                    OutgoingMessageState.SUCCESS,
                    cache.getOutgoingMessage("protected", "successful")?.state,
                    "message retention must not delete even an eligible SUCCESS receipt",
                )
                assertEquals(OutgoingMessageState.PENDING, cache.getOutgoingMessage(
                    "protected",
                    "pending",
                )?.state)
                assertEquals(pending.localOrdinal, cache.getOutgoingMessage("protected", "pending")?.localOrdinal)
                assertEquals(listOf("other-message"), cache.getMessages("other", 10).map(Message::clientMsgId))
                assertEquals(7L, cache.getPendingConversationRead("protected")?.readSeq)
                cache.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `active history lease defers amortized retention until exact abandonment`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = retainedCache(driver, retainedCount = 1)
        try {
            val lease = cache.beginMessageHistoryLease("leased", resetResidentWindow = true)
            cache.insertMessage(message("leased", 1L))
            cache.insertMessage(message("leased", 2L))
            assertEquals(listOf(2L, 1L), cache.getMessages("leased", 10).map(Message::serverSeq))

            assertTrue(cache.abandonMessageHistoryLease(lease))
            assertEquals(listOf(2L), cache.getMessages("leased", 10).map(Message::serverSeq))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `last pager close prunes and retires an over-budget resident window`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = retainedCache(driver, retainedCount = 1)
        try {
            val pager = cache.pager("resident")
            cache.insertMessage(message("resident", 1L))
            cache.insertMessage(message("resident", 2L))
            assertEquals(listOf(2L, 1L), cache.getMessages("resident", 10).map(Message::serverSeq))

            pager.close()

            assertEquals(listOf(2L), cache.getMessages("resident", 10).map(Message::serverSeq))
            assertEquals(0, cache.residentMessageWindowCountsForTest().totalWindows)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `failed retention delete rolls back its chat transaction`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val queries = AppDatabase(rawDriver).appDatabaseQueries
        (1L..3L).forEach { seq -> seed(queries, message("rollback", seq)) }
        val failingDriver = FailingRetentionDriver(rawDriver)

        assertFailsWith<InjectedRetentionFailure> {
            retainedCache(failingDriver, retainedCount = 1)
        }
        assertEquals(3L, countMessages(rawDriver, "rollback"))
        rawDriver.close()
    }

    private fun retainedCache(
        driver: SqlDriver,
        retainedCount: Int,
        retainedBytes: Long = MAX_RETAINED_AUTHORITATIVE_MESSAGE_BYTES_PER_CHAT,
    ): LocalCacheImpl = LocalCacheImpl(
        driver = driver,
        outboxLimits = DEFAULT_LOCAL_OUTBOX_LIMITS,
        messageRetentionLimits = LocalMessageRetentionLimits(
            retainedCount = retainedCount,
            retainedBytes = retainedBytes,
        ),
    )

    private fun seed(
        queries: com.virjar.tk.shared.database.AppDatabaseQueries,
        message: Message,
        encodedBody: ByteArray? = null,
    ) {
        queries.insertMessage(
            message.chatId,
            message.clientMsgId,
            message.serverSeq,
            message.senderUid,
            message.messageType.toLong(),
            message.timestamp,
            message.flags.toLong(),
            encodedBody,
            message.sendStatus.toLong(),
        )
    }

    private fun message(chatId: String, seq: Long, id: String = "message-$seq") = Message(
        chatId = chatId,
        clientMsgId = id,
        serverSeq = seq,
        senderUid = "peer",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = seq,
    )

    private fun localMessage(chatId: String, id: String) = Message(
        chatId = chatId,
        clientMsgId = id,
        senderUid = "owner",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = RichTextBody(id, plainText = id),
        sendStatus = Message.SEND_STATUS_QUEUED,
    )

    private fun countMessages(driver: SqlDriver, chatId: String): Long = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM message WHERE chat_id = ?",
        { cursor: SqlCursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        1,
    ) {
        bindString(0, chatId)
    }.value

    private class FailingRetentionDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (sql.replace(Regex("\\s+"), " ").startsWith("DELETE FROM message WHERE message.chat_id")) {
                throw InjectedRetentionFailure()
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private class InjectedRetentionFailure : RuntimeException()
}
