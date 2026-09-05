package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalDeliveryLogStoreTest {
    @Test
    fun `history page limit is rejected before delivery SQL`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val driver = InspectingSqlDriver(rawDriver)
        val store = LocalDeliveryLogStore(
            queries = AppDatabase(driver).appDatabaseQueries,
            cacheUseGate = CacheUseGate(),
            stateLock = Any(),
            limits = BotDeliveryLogLimits(historyPageSize = 3),
        )
        try {
            val baseline = driver.deliveryHistorySelectCount

            assertFailsWith<IllegalArgumentException> {
                store.listBotMessageDeliveries(afterEventId = 0L, chatId = null, limit = 4)
            }

            assertEquals(baseline, driver.deliveryHistorySelectCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `startup count cleanup stops before pending rows and rejects stale cursor`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        try {
            (1L..4L).forEach { seed(queries, eventId = it, acked = true) }
            seed(queries, eventId = 5L, acked = false)

            val store = LocalDeliveryLogStore(
                queries = queries,
                cacheUseGate = CacheUseGate(),
                stateLock = Any(),
                limits = BotDeliveryLogLimits(ackedHistoryCount = 2),
            )

            assertEquals(5L, store.peekBotMessage()?.eventId)
            assertEquals(
                listOf(3L, 4L, 5L),
                store.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
            )
            val expired = assertFailsWith<BotDeliveryHistoryCursorExpiredException> {
                store.listBotMessageDeliveries(1L, null, 10)
            }
            assertEquals(1L, expired.afterEventId)
            assertEquals(2L, expired.retainedFloorEventId)
            assertEquals(
                listOf(3L, 4L, 5L),
                store.listBotMessageDeliveries(2L, null, 10).map { it.eventId },
            )

            // 一个已退场的 event id 仍然是持久的重放墓碑，即使它的 ACK 行已经消失。
            store.enqueueBotMessage(1L, message(1L))
            assertEquals(
                listOf(3L, 4L, 5L),
                store.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `out of order ack history never compacts across an older pending row`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        try {
            seed(queries, eventId = 1L, acked = false)
            (2L..5L).forEach { seed(queries, eventId = it, acked = true) }

            val store = LocalDeliveryLogStore(
                queries = queries,
                cacheUseGate = CacheUseGate(),
                stateLock = Any(),
                limits = BotDeliveryLogLimits(ackedHistoryCount = 2),
            )

            assertEquals(1L, store.peekBotMessage()?.eventId)
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L),
                store.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
            )
            assertEquals(
                listOf(2L, 3L, 4L, 5L),
                store.listBotMessageDeliveries(1L, null, 10).map { it.eventId },
            )

            store.ackBotMessage(1L, now = 101L)
            assertNull(store.peekBotMessage())
            assertEquals(
                listOf(4L, 5L),
                store.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
                "acknowledging the pinned head must immediately converge the retained suffix",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ack cleanup enforces encoded payload byte budget`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        val first = message(1L, "first")
        val second = message(2L, "second")
        val third = message(3L, "third")
        val newestPairBytes = ProtoCodec.encode(second).size.toLong() + ProtoCodec.encode(third).size.toLong()
        try {
            val store = LocalDeliveryLogStore(
                queries = queries,
                cacheUseGate = CacheUseGate(),
                stateLock = Any(),
                limits = BotDeliveryLogLimits(
                    ackedHistoryCount = 10,
                    ackedHistoryPayloadBytes = newestPairBytes,
                ),
            )
            listOf(first, second, third).forEachIndexed { index, item ->
                val eventId = index.toLong() + 1L
                store.enqueueBotMessage(eventId, item)
                store.ackBotMessage(eventId, now = 100L + eventId)
            }

            assertNull(store.peekBotMessage())
            assertEquals(
                listOf(2L, 3L),
                store.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `retained floor remains the max cursor after oversized ack row is removed`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        try {
            val store = LocalDeliveryLogStore(
                queries = queries,
                cacheUseGate = CacheUseGate(),
                stateLock = Any(),
                limits = BotDeliveryLogLimits(ackedHistoryPayloadBytes = 1L),
            )
            store.enqueueBotMessage(7L, message(7L))
            store.ackBotMessage(7L, now = 107L)

            assertEquals(0, store.listBotMessageDeliveries(0L, null, 10).size)
            assertEquals(7L, store.maxBotMessageEventId())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `retained floor survives restart without sharing sync state`() {
        val directory = createTempDirectory("bot-inbox-metadata-").toFile()
        val databaseFile = File(directory, "inbox.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            try {
                val first = LocalDeliveryLogStore(
                    queries = AppDatabase(firstDriver).appDatabaseQueries,
                    cacheUseGate = CacheUseGate(),
                    stateLock = Any(),
                    limits = BotDeliveryLogLimits(ackedHistoryCount = 1),
                )
                assertEquals(
                    ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 0L),
                    first.bindSyncDataset(TEST_SYNC_DATASET_ID),
                )
                assertEquals(
                    ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 99L),
                    first.advanceSyncCursor(TEST_SYNC_DATASET_ID, 99L),
                )
                (1L..3L).forEach { eventId ->
                    first.enqueueBotMessage(eventId, message(eventId))
                    first.ackBotMessage(eventId, now = 100L + eventId)
                }
            } finally {
                firstDriver.close()
            }

            val restartedDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
            AppDatabase.Schema.create(restartedDriver)
            try {
                val restarted = LocalDeliveryLogStore(
                    queries = AppDatabase(restartedDriver).appDatabaseQueries,
                    cacheUseGate = CacheUseGate(),
                    stateLock = Any(),
                    limits = BotDeliveryLogLimits(ackedHistoryCount = 1),
                )

                assertEquals(
                    ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 99L),
                    restarted.getSyncState(),
                )
                assertEquals(3L, restarted.maxBotMessageEventId())
                assertEquals(
                    listOf(3L),
                    restarted.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
                )
                val expired = assertFailsWith<BotDeliveryHistoryCursorExpiredException> {
                    restarted.listBotMessageDeliveries(1L, null, 10)
                }
                assertEquals(2L, expired.retainedFloorEventId)
            } finally {
                restartedDriver.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `projection reset rolls back or clears inbox floor and sync state together`() {
        val rawDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(rawDriver)
        val driver = InspectingSqlDriver(rawDriver)
        val queries = AppDatabase(driver).appDatabaseQueries
        queries.transaction {
            queries.bindSyncDataset(TEST_SYNC_DATASET_ID)
            queries.advanceSyncCursor(
                eventId = 17L,
                expectedDatasetId = TEST_SYNC_DATASET_ID,
            )
            queries.ensureBotInboxMetadata()
            queries.advanceBotInboxRetainedFloor(7L)
            seed(queries, eventId = 8L, acked = false)
        }
        val cache = LocalCacheImpl(driver)
        try {
            driver.failExecuteContaining = "INSERT OR REPLACE INTO sync_state"
            assertFailsWith<InjectedSqlFailure> {
                cache.resetServerProjection(TEST_SYNC_DATASET_ID)
            }

            assertEquals(
                ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 17L),
                cache.getSyncState(),
            )
            assertEquals(7L, queries.selectBotInboxRetainedFloor().executeAsOne())
            assertEquals(
                listOf(8L),
                cache.listBotMessageDeliveries(0L, null, 10).map { it.eventId },
            )

            driver.failExecuteContaining = null
            cache.resetServerProjection(TEST_SYNC_DATASET_ID)

            assertEquals(
                ServerProjectionSyncState(TEST_SYNC_DATASET_ID, 0L),
                cache.getSyncState(),
            )
            assertEquals(0L, queries.selectBotInboxRetainedFloor().executeAsOne())
            assertNull(cache.peekBotMessage())
            assertEquals(0L, cache.maxBotMessageEventId())

            cache.enqueueBotMessage(7L, message(7L))
            assertEquals(7L, cache.peekBotMessage()?.eventId, "reset floor must admit replay from cursor zero")
        } finally {
            cache.close()
        }
    }

    private fun seed(queries: AppDatabaseQueries, eventId: Long, acked: Boolean) {
        val message = message(eventId)
        queries.enqueueBotMessage(
            eventId,
            message.chatId,
            message.serverSeq,
            ProtoCodec.encode(message),
            eventId,
        )
        if (acked) queries.ackBotMessage(eventId + 100L, eventId)
    }

    private fun message(eventId: Long, text: String = "event-$eventId") = Message(
        chatId = "chat-1",
        clientMsgId = "message-$eventId",
        serverSeq = eventId,
        senderUid = "peer",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = eventId,
        body = RichTextBody(text, plainText = text),
    )

    private class InspectingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        var deliveryHistorySelectCount = 0
            private set
        var failExecuteContaining: String? = null

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val normalized = sql.replace(Regex("\\s+"), " ")
            if (normalized.contains("FROM bot_message_inbox WHERE event_id >")) {
                deliveryHistorySelectCount += 1
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
}
