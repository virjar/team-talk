package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Conversation
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalConversationProjectionStoreComplexityTest {
    @Test
    fun `large snapshot performs one lifetime read outbox query and publishes deterministic order`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val driver = InspectingSqlDriver(rawDriver)
        val cache = LocalCacheImpl(driver)
        try {
            val remote = (0 until LARGE_SNAPSHOT_SIZE).map { index ->
                Conversation(
                    chatId = "chat-${(LARGE_SNAPSHOT_SIZE - index).toString().padStart(4, '0')}",
                    chatType = 1,
                    lastSeq = 100L,
                    lastMsgTimestamp = (index % 5).toLong(),
                    isPinned = index % 23 == 0,
                )
            }
            remote.take(PENDING_READ_COUNT).forEachIndexed { index, conversation ->
                cache.enqueueConversationRead(conversation.chatId, index.toLong() + 1L)
            }
            val generation = cache.beginConversationSnapshot()

            assertTrue(cache.applyConversationSnapshot(generation, remote))

            assertEquals(
                1,
                driver.conversationReadOutboxSelectCount,
                "the outbox is loaded once at construction; snapshot merge must not query per chat",
            )
            assertEquals(PENDING_READ_COUNT, cache.getPendingConversationReads().size)
            val projected = cache.getConversations()
            val projectedById = projected.associateBy(Conversation::chatId)
            assertEquals(LARGE_SNAPSHOT_SIZE, projected.size)
            assertEquals(
                remote.sortedWith(
                    compareByDescending<Conversation> { it.isPinned }
                        .thenByDescending { it.lastMsgTimestamp ?: 0L }
                        .thenBy(Conversation::chatId),
                ).map(Conversation::chatId),
                projected.map(Conversation::chatId),
            )
            remote.take(PENDING_READ_COUNT).forEachIndexed { index, conversation ->
                assertEquals(
                    index.toLong() + 1L,
                    projectedById.getValue(conversation.chatId).readSeq,
                )
            }
            assertEquals(1, driver.conversationReadOutboxSelectCount)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `restart rebuilds keyed read facts and deterministic tie order`() {
        val root = createTempDirectory("conversation-projection-restart-").toFile()
        val database = root.resolve("cache.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val first = LocalCacheImpl(firstDriver)
            first.upsertConversation(
                Conversation(
                    chatId = "z",
                    chatType = 1,
                    lastSeq = 10L,
                    lastMsgTimestamp = 5L,
                    isPinned = true,
                ),
            )
            first.upsertConversation(
                Conversation(
                    chatId = "a",
                    chatType = 1,
                    lastSeq = 10L,
                    lastMsgTimestamp = 5L,
                    isPinned = true,
                ),
            )
            first.enqueueConversationRead("z", 7L)
            first.close()

            val secondRawDriver = JdbcSqliteDriver("jdbc:sqlite:${database.absolutePath}")
            val secondDriver = InspectingSqlDriver(secondRawDriver)
            val second = LocalCacheImpl(secondDriver)
            try {
                assertEquals(listOf("a", "z"), second.getConversations().map(Conversation::chatId))
                assertEquals(7L, second.getPendingConversationReads().single().readSeq)
                assertEquals(1, secondDriver.conversationReadOutboxSelectCount)

                val generation = second.beginConversationSnapshot()
                assertTrue(
                    second.applyConversationSnapshot(
                        generation,
                        listOf(
                            Conversation(
                                chatId = "z",
                                chatType = 1,
                                lastSeq = 10L,
                                lastMsgTimestamp = 5L,
                                isPinned = true,
                            ),
                            Conversation(
                                chatId = "a",
                                chatType = 1,
                                lastSeq = 10L,
                                lastMsgTimestamp = 5L,
                                isPinned = true,
                            ),
                        ),
                    ),
                )
                assertEquals(7L, second.getConversations().single { it.chatId == "z" }.readSeq)
                assertEquals(1, secondDriver.conversationReadOutboxSelectCount)
            } finally {
                second.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `chat creation and deletion during a request reject the whole late snapshot`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val cache = LocalCacheImpl(rawDriver)
        try {
            val deleted = Conversation(chatId = "deleted", chatType = 2)
            cache.upsertConversation(deleted)
            val staleGeneration = cache.beginConversationSnapshot()

            cache.deleteConversation(deleted.chatId)
            cache.upsertChat(Chat(chatId = "created", chatType = 2))

            assertFalse(cache.applyConversationSnapshot(staleGeneration, listOf(deleted)))
            assertTrue(cache.getConversations().isEmpty())

            val currentGeneration = cache.beginConversationSnapshot()
            assertTrue(
                cache.applyConversationSnapshot(
                    currentGeneration,
                    listOf(Conversation(chatId = "created", chatType = 2)),
                ),
            )
            assertEquals(listOf("created"), cache.getConversations().map(Conversation::chatId))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `older response is rejected when a newer snapshot has already begun`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val cache = LocalCacheImpl(rawDriver)
        try {
            val olderGeneration = cache.beginConversationSnapshot()
            val newerGeneration = cache.beginConversationSnapshot()

            assertFalse(
                cache.applyConversationSnapshot(
                    olderGeneration,
                    listOf(Conversation(chatId = "older", chatType = 1)),
                ),
            )
            assertTrue(cache.getConversations().isEmpty())
            assertTrue(
                cache.applyConversationSnapshot(
                    newerGeneration,
                    listOf(Conversation(chatId = "newer", chatType = 1)),
                ),
            )
            assertEquals(listOf("newer"), cache.getConversations().map(Conversation::chatId))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `failed SQL cannot advance conversation or resident read state`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val driver = InspectingSqlDriver(rawDriver)
        val cache = LocalCacheImpl(driver)
        try {
            val stable = Conversation(chatId = "stable", chatType = 1, lastSeq = 4L)
            cache.upsertConversation(stable)
            val snapshotGeneration = cache.beginConversationSnapshot()
            driver.failExecuteContaining = "INSERT OR REPLACE INTO conversation("

            assertFailsWith<InjectedSqlFailure> {
                cache.applyConversationSnapshot(
                    snapshotGeneration,
                    listOf(Conversation(chatId = "replacement", chatType = 1)),
                )
            }
            assertEquals(listOf(stable), cache.getConversations())

            driver.failExecuteContaining = null
            assertTrue(
                cache.applyConversationSnapshot(
                    snapshotGeneration,
                    listOf(Conversation(chatId = "replacement", chatType = 1)),
                ),
            )

            driver.failExecuteContaining = "INSERT OR IGNORE INTO conversation_read_outbox"
            assertFailsWith<InjectedSqlFailure> {
                cache.enqueueConversationRead("replacement", 3L)
            }
            assertTrue(cache.getPendingConversationReads().isEmpty())
            assertEquals(0L, cache.getConversations().single().readSeq)

            driver.failExecuteContaining = null
            cache.enqueueConversationRead("replacement", 3L)
            driver.failExecuteContaining = "DELETE FROM conversation_read_outbox"
            assertFailsWith<InjectedSqlFailure> {
                cache.markConversationReadMirrored("replacement", 3L)
            }
            assertEquals(3L, cache.getPendingConversationReads().single().readSeq)
        } finally {
            driver.failExecuteContaining = null
            cache.close()
        }
    }

    @Test
    fun `draft and read capacity rejection preserves the previous reliable fact atomically`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(
            driver,
            LocalOutboxLimits(
                draftCount = 1,
                readCount = 1,
                draftCharacters = 4L,
                draftUtf8Bytes = 5L,
            ),
        )
        try {
            cache.upsertConversation(Conversation(chatId = "draft-1", chatType = 1))
            assertEquals(1L, cache.setConversationDraft("draft-1", "abcd"))

            val countFailure = assertFailsWith<LocalOutboxCapacityExceededException> {
                cache.setConversationDraft("draft-2", "x")
            }
            assertEquals(LocalOutboxCapacityDimension.ENTRY_COUNT, countFailure.dimension)
            assertEquals("abcd", cache.getPendingConversationDraft("draft-1")?.draft)

            val characterFailure = assertFailsWith<LocalOutboxCapacityExceededException> {
                cache.setConversationDraft("draft-1", "abcde")
            }
            assertEquals(LocalOutboxCapacityDimension.CHARACTER_COUNT, characterFailure.dimension)
            val byteFailure = assertFailsWith<LocalOutboxCapacityExceededException> {
                cache.setConversationDraft("draft-1", "你你")
            }
            assertEquals(LocalOutboxCapacityDimension.STORED_BYTES, byteFailure.dimension)
            assertEquals("abcd", cache.getPendingConversationDraft("draft-1")?.draft)
            assertEquals(2L, cache.setConversationDraft("draft-1", "a"))

            assertEquals(1L, cache.enqueueConversationRead("read-1", 1L))
            val readFailure = assertFailsWith<LocalOutboxCapacityExceededException> {
                cache.enqueueConversationRead("read-2", 1L)
            }
            assertEquals(LocalOutboxKind.CONVERSATION_READ, readFailure.outbox)
            assertEquals(1L, cache.getPendingConversationRead("read-1")?.readSeq)
            assertEquals(3L, cache.enqueueConversationRead("read-1", 3L))
            assertEquals(listOf(PendingConversationRead("read-1", 3L)), cache.getPendingConversationReads())
        } finally {
            cache.close()
        }
    }

    @Test
    fun `persisted draft and read outboxes fail closed when they exceed configured hard limits`() {
        val draftDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(draftDriver)
        try {
            draftDriver.execute(
                null,
                "INSERT INTO conversation_draft_outbox(chat_id, draft, generation, state) " +
                    "VALUES ('draft-1', 'a', 1, 0), ('draft-2', 'b', 2, 0)",
                0,
            )
            assertFailsWith<IllegalStateException> {
                LocalCacheImpl(draftDriver, LocalOutboxLimits(draftCount = 1))
            }
        } finally {
            draftDriver.close()
        }

        val readDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(readDriver)
        try {
            readDriver.execute(
                null,
                "INSERT INTO conversation_read_outbox(chat_id, read_seq) " +
                    "VALUES ('read-1', 1), ('read-2', 2)",
                0,
            )
            assertFailsWith<IllegalStateException> {
                LocalCacheImpl(readDriver, LocalOutboxLimits(readCount = 1))
            }
        } finally {
            readDriver.close()
        }
    }

    private class InspectingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var conversationReadOutboxSelectCount = 0
            private set
        var failExecuteContaining: String? = null

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (sql.contains("FROM conversation_read_outbox")) {
                conversationReadOutboxSelectCount += 1
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            val failureNeedle = failExecuteContaining
            if (failureNeedle != null && sql.contains(failureNeedle)) {
                throw InjectedSqlFailure()
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    private class InjectedSqlFailure : RuntimeException("injected SQL failure")

    private companion object {
        const val LARGE_SNAPSHOT_SIZE = 768
        const val PENDING_READ_COUNT = 64
    }
}
