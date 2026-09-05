package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import kotlinx.coroutines.CancellationException
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalCacheCloseMaintenanceTest {
    @Test
    fun `clean close checkpoints before closing the driver exactly once`() {
        val driver = newTrackingMemoryDriver()
        val cache = LocalCacheImpl(driver)

        cache.close()
        cache.close()

        assertEquals(listOf("checkpoint", "close"), driver.events)
        assertEquals(1, driver.closeCalls)
        assertNotNull(driver.checkpointResult)
    }

    @Test
    fun `ordinary checkpoint failure is diagnostic and still closes the driver`() {
        val driver = newTrackingMemoryDriver().apply {
            checkpointFailure = SQLException("checkpoint unavailable")
        }
        val cache = LocalCacheImpl(driver)

        cache.close()

        assertEquals(listOf("checkpoint", "close"), driver.events)
        assertEquals(1, driver.closeCalls)
        assertFailsWith<IllegalStateException> { cache.getUser("after-close") }
    }

    @Test
    fun `fatal checkpoint failure stays primary while driver close also fails`() {
        val checkpointFailure = AssertionError("checkpoint fatal")
        val closeFailure = IllegalStateException("close failed")
        val driver = newTrackingMemoryDriver().apply {
            this.checkpointFailure = checkpointFailure
            this.closeFailure = closeFailure
        }
        val cache = LocalCacheImpl(driver)

        val thrown = assertFailsWith<AssertionError> { cache.close() }

        assertTrue(thrown === checkpointFailure)
        assertEquals(listOf("checkpoint", "close"), driver.events)
        assertEquals(1, driver.closeCalls)
        assertTrue(thrown.suppressed.any { it === closeFailure })
    }

    @Test
    fun `ordinary checkpoint failure does not hide driver close failure`() {
        val closeFailure = IllegalStateException("close failed")
        val driver = newTrackingMemoryDriver().apply {
            checkpointFailure = SQLException("checkpoint unavailable")
            this.closeFailure = closeFailure
        }
        val cache = LocalCacheImpl(driver)

        val thrown = assertFailsWith<IllegalStateException> { cache.close() }

        assertTrue(thrown === closeFailure)
        assertEquals(listOf("checkpoint", "close"), driver.events)
        assertEquals(1, driver.closeCalls)
    }

    @Test
    fun `checkpoint cancellation stays primary while driver close also fails`() {
        val cancellation = CancellationException("checkpoint cancelled")
        val closeFailure = IllegalStateException("close failed")
        val driver = newTrackingMemoryDriver().apply {
            checkpointFailure = cancellation
            this.closeFailure = closeFailure
        }
        val cache = LocalCacheImpl(driver)

        val thrown = assertFailsWith<CancellationException> { cache.close() }

        assertTrue(thrown === cancellation)
        assertEquals(listOf("checkpoint", "close"), driver.events)
        assertEquals(1, driver.closeCalls)
        assertTrue(thrown.suppressed.any { it === closeFailure })
    }

    @Test
    fun `close waits for admitted SQL before checkpointing`() {
        val driver = newTrackingMemoryDriver()
        val cache = LocalCacheImpl(driver)
        driver.blockUserRead = true
        val readFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val reader = thread(name = "local-cache-admitted-read") {
            try {
                cache.getUser("blocked")
            } catch (failure: Throwable) {
                readFailure.set(failure)
            }
        }
        assertTrue(driver.userReadEntered.await(2, TimeUnit.SECONDS))

        val closerStarted = CountDownLatch(1)
        val closer = thread(name = "local-cache-close") {
            closerStarted.countDown()
            try {
                cache.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            }
        }
        assertTrue(closerStarted.await(2, TimeUnit.SECONDS))
        assertTrue(!driver.checkpointStarted.await(100, TimeUnit.MILLISECONDS))

        driver.releaseUserRead.countDown()
        reader.join(2_000)
        closer.join(2_000)

        assertTrue(!reader.isAlive)
        assertTrue(!closer.isAlive)
        assertNull(readFailure.get())
        assertNull(closeFailure.get())
        assertEquals(listOf("checkpoint", "close"), driver.events)
    }

    @Test
    fun `passive checkpoint leaves a pinned reader bounded and durable facts reopen`() {
        val root = createTempDirectory("local-cache-close-wal-").toFile()
        val databaseFile = root.resolve("cache.db")
        val databaseUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
        try {
            val schemaDriver = JdbcSqliteDriver(databaseUrl)
            AppDatabase.Schema.create(schemaDriver)
            schemaDriver.close()
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode=WAL").use { result ->
                        assertTrue(result.next())
                        assertEquals("wal", result.getString(1).lowercase())
                    }
                }
            }

            val initialCache = LocalCacheImpl(JdbcSqliteDriver(databaseUrl))
            try {
                initialCache.upsertConversation(Conversation(chatId = "wal-chat", chatType = 1))
            } finally {
                initialCache.close()
            }

            DriverManager.getConnection(databaseUrl).use { pinnedReader ->
                pinnedReader.autoCommit = false
                pinnedReader.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM conversation").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                }

                val trackingDriver = TrackingSqlDriver(JdbcSqliteDriver(databaseUrl))
                val cache = LocalCacheImpl(trackingDriver)
                cache.setConversationDraft("wal-chat", "offline draft")
                cache.enqueueOutgoingMessage(outgoing("wal-chat", "wal-outgoing"), now = 1L)
                cache.close()

                val checkpoint = assertNotNull(trackingDriver.checkpointResult)
                assertTrue(checkpoint.logFrames > 0L)
                assertTrue(checkpoint.checkpointedFrames in 0L until checkpoint.logFrames)
                pinnedReader.rollback()
            }

            val reopened = LocalCacheImpl(JdbcSqliteDriver(databaseUrl))
            try {
                assertEquals(
                    "offline draft",
                    reopened.getPendingConversationDraft("wal-chat")?.draft,
                )
                assertEquals(
                    listOf("wal-outgoing"),
                    reopened.recoverOutgoingMessages(now = 2L).map { it.message.clientMsgId },
                )
            } finally {
                reopened.close()
            }
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { result ->
                        assertTrue(result.next())
                        assertEquals("ok", result.getString(1))
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun newTrackingMemoryDriver(): TrackingSqlDriver {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(raw)
        return TrackingSqlDriver(raw)
    }

    private fun outgoing(chatId: String, clientMsgId: String) = Message(
        chatId = chatId,
        clientMsgId = clientMsgId,
        senderUid = "owner",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1L,
        body = RichTextBody("hello", plainText = "hello"),
        sendStatus = Message.SEND_STATUS_SENDING,
    )

    private class TrackingSqlDriver(
        private val delegate: SqlDriver,
    ) : SqlDriver by delegate {
        val events = CopyOnWriteArrayList<String>()
        val checkpointStarted = CountDownLatch(1)
        val userReadEntered = CountDownLatch(1)
        val releaseUserRead = CountDownLatch(1)

        @Volatile
        var checkpointFailure: Throwable? = null

        @Volatile
        var closeFailure: Throwable? = null

        @Volatile
        var checkpointResult: LocalCacheWalCheckpointResult? = null

        @Volatile
        var blockUserRead = false

        @Volatile
        var closeCalls = 0

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val normalized = sql.replace(Regex("\\s+"), " ").trim()
            if (blockUserRead && normalized.contains("FROM user WHERE uid = ?")) {
                userReadEntered.countDown()
                check(releaseUserRead.await(2, TimeUnit.SECONDS)) { "test did not release user read" }
            }
            if (normalized.equals("PRAGMA wal_checkpoint(PASSIVE)", ignoreCase = true)) {
                events += "checkpoint"
                checkpointStarted.countDown()
                checkpointFailure?.let { throw it }
                return delegate.executeQuery(identifier, sql, mapper, parameters, binders).also {
                    checkpointResult = (it as? QueryResult.Value<*>)?.value
                        as? LocalCacheWalCheckpointResult
                }
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        override fun close() {
            events += "close"
            closeCalls += 1
            delegate.close()
            closeFailure?.let { throw it }
        }
    }
}
