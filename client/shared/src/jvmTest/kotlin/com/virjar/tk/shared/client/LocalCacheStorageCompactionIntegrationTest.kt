package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.shared.database.AppDatabase
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** 同一真实 SQLite owner 内整理：可靠载荷、当前会话与读写/关闭准入都必须保持。 */
class LocalCacheStorageCompactionIntegrationTest {
    @Test
    fun `factory cache compaction preserves all persisted values and continues using the same owner`() = workspace { root ->
        withFactoryCache(root) { cache -> populateReliableFacts(cache) }
        val file = databaseFile(root)
        val retiredOrdinal = createFreePages(file)
        val spool = privateFile(root, "chat-assets/${deployment.fingerprint}/$DATASET/$UID/$SOURCE_ID.blob", PAYLOAD)
        val docs = DocumentDraftStoragePaths.jvmDirectories(deployment.fingerprint, DATASET, UID).joinToString("/")
        val document = privateFile(root, "$docs/record.payload", PAYLOAD)
        val neighbor = privateFile(root, "deployments/${deployment.fingerprint}/datasets/$DATASET/users/bob/cache_e0.db", PAYLOAD)
        val outsideBefore = listOf(spool, document, neighbor).associate { it to hash(it) }
        withFactoryCache(root) { cache ->
            val pager = cache.pager("chat")
            val valuesBefore = contents(file)
            val outgoingBefore = assertNotNull(cache.getOutgoingMessage("chat", "pending", FINGERPRINT))
            val draftBefore = assertNotNull(cache.chatDrafts.get("draft"))
            val uploadBefore = assertNotNull(cache.chatDrafts.upload(ASSET_ID))
            val commandBefore = cache.getPendingContactDecisions().single()
            val botBefore = assertNotNull(cache.peekBotMessage())
            try {
                val report = cache.compactStorage()

                assertTrue(report.freePagesBefore > 0, "fixture must allocate and release SQLite pages")
                assertEquals(0L, report.freePagesAfter)
                assertTrue(report.pagesAfter < report.pagesBefore)
                assertTrue(report.bytesAfter < report.bytesBefore)
                assertEquals(report.bytesBefore - report.bytesAfter, report.reclaimedBytes)
                assertEquals(valuesBefore, contents(file), "every column, BLOB, durable ID, clock and sqlite_sequence must survive")
                assertEquals(outgoingBefore, cache.getOutgoingMessage("chat", "pending", FINGERPRINT))
                assertEquals(draftBefore, cache.chatDrafts.get("draft"))
                assertEquals(uploadBefore, cache.chatDrafts.upload(ASSET_ID))
                assertEquals(listOf(commandBefore), cache.getPendingContactDecisions())
                assertEquals(botBefore, cache.peekBotMessage())
                assertEquals(outsideBefore, outsideBefore.keys.associateWith(::hash))
                val next = cache.enqueueOutgoingMessage(message("after-compaction"), 3, FINGERPRINT)
                assertTrue(next.localOrdinal > retiredOrdinal, "VACUUM must not reuse an already retired AUTOINCREMENT identity")
                assertNotNull(cache.findMessage("chat", "after-compaction"))
                pager.loadMore(10)
                cache.chatDrafts.save(draftBefore.copy(revision = cache.chatDrafts.maxRevision() + 1, markdown = "continued editing", pendingAssetIds = emptyList()))
                cache.ackBotMessage(botBefore.eventId, 4)
                assertNull(cache.peekBotMessage())
            } finally { pager.close() }
        }
        withFactoryCache(root) { reopened ->
            assertEquals("continued editing", reopened.chatDrafts.get("draft")?.markdown)
            assertNotNull(reopened.getOutgoingMessage("chat", "after-compaction", FINGERPRINT))
            assertNull(reopened.peekBotMessage())
            assertEquals(1, reopened.getPendingContactDecisions().size)
        }
    }

    @Test
    fun `compaction waits for an admitted read and later reads wait for vacuum`() = rawCache { _, cache, driver ->
        cache.upsertUser(User("user", "user", name = "Before"))
        driver.blockNextUserRead.set(true)
        driver.blockVacuum = true
        val failure = AtomicReference<Throwable?>()
        val initialRead = start(failure, "admitted-read") { assertEquals("Before", cache.getUser("user")?.name) }
        var compactor: Thread? = null
        var laterRead: Thread? = null
        try {
            assertTrue(driver.userReadEntered.await(3, TimeUnit.SECONDS))
            val compactStarted = CountDownLatch(1)
            compactor = start(failure, "waiting-compaction") { compactStarted.countDown(); cache.compactStorage() }
            assertTrue(compactStarted.await(3, TimeUnit.SECONDS))
            assertFalse(driver.vacuumEntered.await(100, TimeUnit.MILLISECONDS))
            driver.releaseUserRead.countDown()
            assertTrue(driver.vacuumEntered.await(3, TimeUnit.SECONDS))
            val readStarted = CountDownLatch(1)
            val readFinished = CountDownLatch(1)
            laterRead = start(failure, "read-after-compaction") {
                readStarted.countDown()
                assertEquals("Before", cache.getUser("user")?.name)
                readFinished.countDown()
            }
            assertTrue(readStarted.await(3, TimeUnit.SECONDS))
            assertFalse(readFinished.await(100, TimeUnit.MILLISECONDS))
            driver.releaseVacuum.countDown()
        } finally {
            driver.releaseUserRead.countDown()
            driver.releaseVacuum.countDown()
            join(initialRead, compactor, laterRead)
        }
        assertNull(failure.get())
        assertEquals(0, driver.closeCalls)
        cache.upsertUser(User("user", "user", name = "After", revision = 2))
        assertEquals("After", cache.getUser("user")?.name)
    }

    @Test
    fun `close waits for compaction before releasing the same driver`() = rawCache { _, cache, driver ->
        driver.blockVacuum = true
        val failure = AtomicReference<Throwable?>()
        val compactor = start(failure, "compaction-before-close") { cache.compactStorage() }
        var closer: Thread? = null
        try {
            assertTrue(driver.vacuumEntered.await(3, TimeUnit.SECONDS))
            val closeStarted = CountDownLatch(1)
            closer = start(failure, "close-after-compaction") { closeStarted.countDown(); cache.close() }
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS))
            assertFalse(driver.closed.await(100, TimeUnit.MILLISECONDS))
            driver.releaseVacuum.countDown()
        } finally {
            driver.releaseVacuum.countDown()
            join(compactor, closer)
        }
        assertNull(failure.get())
        assertEquals(1, driver.closeCalls)
        assertTrue(driver.events.indexOf("vacuum-finished") < driver.events.indexOf("close"))
        assertFailsWith<IllegalStateException> { cache.getUser("closed") }
        cache.close()
        assertEquals(1, driver.closeCalls)
    }

    @Test
    fun `ordinary vacuum failure preserves facts and keeps the owner usable`() = rawCache { file, cache, driver ->
        populateReliableFacts(cache)
        val before = contents(file)
        driver.failNextVacuum = true

        rejected(LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED) { cache.compactStorage() }

        assertEquals(before, contents(file))
        assertEquals(0, driver.closeCalls)
        assertNotNull(cache.getOutgoingMessage("chat", "pending", FINGERPRINT))
        cache.upsertUser(User("after-failure", "after-failure", name = "Still open"))
        assertEquals("Still open", cache.getUser("after-failure")?.name)
        cache.compactStorage()
        assertEquals(0, driver.closeCalls)
    }

    @Test
    fun `platform size limit rejects before vacuum and preserves database bytes and the open owner`() = rawCache { file, cache, driver ->
        populateReliableFacts(cache)
        val valuesBefore = contents(file)
        val bytesBefore = file.readBytes()
        assertTrue(bytesBefore.size > 1, "fixture must contain a real populated SQLite database")
        val maintenance = LocalCacheStorageMaintenance(file, maxDatabaseBytes = bytesBefore.size.toLong() - 1)

        rejected(LocalCacheStorageCompactionFailure.DATABASE_SIZE_LIMIT) { maintenance.compact(driver) }

        assertFalse(driver.events.contains("vacuum-started"))
        assertEquals(0, driver.closeCalls)
        assertContentEquals(bytesBefore, file.readBytes(), "a capacity refusal must not modify the database")
        assertEquals(valuesBefore, contents(file))
        assertNotNull(cache.getOutgoingMessage("chat", "pending", FINGERPRINT))
        assertEquals(41L, cache.chatDrafts.get("draft")?.revision)
        cache.upsertUser(User("after-size-refusal", "after-size-refusal", name = "Still open"))
        assertEquals("Still open", cache.getUser("after-size-refusal")?.name)
        assertEquals(0, driver.closeCalls)
    }

    @Test
    fun `unsupported storage and changed schema refuse before vacuum`() = workspace { root ->
        val file = databaseFile(root)
        createSchema(file)
        val driver = JdbcSqliteDriver(url(file))
        val cache = LocalCacheImpl(driver)
        try {
            rejected(LocalCacheStorageCompactionFailure.UNSUPPORTED_STORAGE) { cache.compactStorage() }
            cache.upsertUser(User("available", "available", name = "Available"))
            assertNotNull(cache.getUser("available"))
        } finally { cache.close() }
        rawCache { versionedFile, versioned, observed ->
            observed.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version + 1}", 0)
            val before = contents(versionedFile)
            rejected(LocalCacheStorageCompactionFailure.UNSUPPORTED_SCHEMA_VERSION) { versioned.compactStorage() }
            assertEquals(before, contents(versionedFile))
            assertFalse(observed.events.contains("vacuum-started"))
            observed.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0)
            versioned.compactStorage()
        }
    }

    @Test
    fun `integrity failure rejects without repairing or replacing the owner`() = rawCache { file, cache, driver ->
        cache.enqueueOutgoingMessage(message("corrupt-check"), 1, FINGERPRINT)
        // The file-backed SQLDelight driver releases its connection after each nontransactional
        // statement. Keep this fixture-only PRAGMA and mutation on one actual JDBC connection.
        connection(file).use { db ->
            db.createStatement().use { sql ->
                sql.execute("PRAGMA ignore_check_constraints=ON")
                try { sql.executeUpdate("UPDATE outgoing_message SET request_fingerprint=X''") }
                finally { sql.execute("PRAGMA ignore_check_constraints=OFF") }
            }
        }
        val before = contents(file)

        rejected(LocalCacheStorageCompactionFailure.INTEGRITY_CHECK_FAILED) { cache.compactStorage() }

        assertEquals(before, contents(file))
        assertFalse(driver.events.contains("vacuum-started"))
        assertEquals(0, driver.closeCalls)
        driver.execute(null, "UPDATE outgoing_message SET request_fingerprint=X'0001FF'", 0)
        cache.compactStorage()
        assertNotNull(cache.getOutgoingMessage("chat", "corrupt-check", FINGERPRINT))
    }

    @Test
    fun `an admitted SQL transaction cannot contain compaction`() = rawCache { _, cache, driver ->
        AppDatabase(driver).transaction {
            rejected(LocalCacheStorageCompactionFailure.DATABASE_IN_USE) { cache.compactStorage() }
            assertFalse(driver.events.contains("vacuum-started"))
        }
        assertNull(driver.currentTransaction())
        cache.enqueueOutgoingMessage(message("after-transaction"), 1)
        cache.compactStorage()
        assertNotNull(cache.getOutgoingMessage("chat", "after-transaction"))
    }

    @Test
    fun `leftover quarantine family does not block maintenance`() = workspace { root ->
        privateFile(root, "deployments/${deployment.fingerprint}/datasets/$DATASET/users/$UID.corrupt-leftover/cache_e0.db", PAYLOAD)
        withFactoryCache(root) { cache ->
            cache.enqueueOutgoingMessage(message("current"), 1)
            val before = contents(databaseFile(root))

            cache.compactStorage()

            assertEquals(before, contents(databaseFile(root)))
            assertNotNull(cache.getOutgoingMessage("chat", "current"))
        }
    }

    private fun populateReliableFacts(cache: LocalCache) {
        cache.enqueueOutgoingMessage(message("pending"), 1, FINGERPRINT)
        cache.claimNextOutgoingMessage(2)
        cache.preparePendingContactDecision(PendingContactDecision(OPERATION_ID, TOKEN_ID, PendingContactDecisionType.ACCEPT, 1))
        cache.bindSyncDataset(DATASET)
        cache.enqueueBotMessage(19, message("inbox").copy(serverSeq = 9))
        cache.chatDrafts.register(ChatAssetUpload(ASSET_ID, "draft", SOURCE_ID, PAYLOAD.size.toLong(), PAYLOAD_SHA256,
            "pending.txt", "text/plain", false, UPLOAD_ID, System.currentTimeMillis()))
        val markdown = "# local draft\n[file](teamtalk-asset://$ASSET_ID)"
        cache.chatDrafts.save(ChatDraftSnapshot("draft", revision = 41, markdown = markdown, pendingAssetIds = listOf(ASSET_ID),
            mode = 1, selectionStart = 2, selectionEnd = 4, replyToClientMsgId = "old-message", replyToServerSeq = 7))
        cache.chatDraftSync.ensure("draft")
    }

    private fun createFreePages(file: File): Long = connection(file).use { db ->
        db.autoCommit = false
        try {
            db.prepareStatement("INSERT INTO message(chat_id,client_msg_id,sender_uid,message_type,timestamp,body) VALUES ('scratch',?,'alice',1,1,?)").use { sql ->
                repeat(256) { index -> sql.setString(1, "discard-$index"); sql.setBytes(2, ByteArray(8192) { 7 }); sql.addBatch() }
                sql.executeBatch()
            }
            db.createStatement().use { sql ->
                sql.executeUpdate("DELETE FROM message WHERE chat_id='scratch'")
                sql.executeUpdate("INSERT INTO outgoing_message(client_msg_id,chat_id,sender_uid,payload,request_fingerprint,created_at,updated_at) SELECT 'retired-highest',chat_id,sender_uid,payload,request_fingerprint,1,1 FROM outgoing_message LIMIT 1")
                sql.executeUpdate("DELETE FROM outgoing_message WHERE client_msg_id='retired-highest'")
            }
            db.commit()
        } catch (failure: Throwable) { db.rollback(); throw failure }
        assertTrue(number(db, "PRAGMA freelist_count") > 0)
        number(db, "SELECT seq FROM sqlite_sequence WHERE name='outgoing_message'")
    }

    private fun rawCache(block: (File, LocalCacheImpl, ObservedDriver) -> Unit) = workspace { root ->
        val file = databaseFile(root)
        createSchema(file)
        val driver = ObservedDriver(JdbcSqliteDriver(url(file)))
        val cache = LocalCacheImpl(driver = driver, outboxLimits = DEFAULT_LOCAL_OUTBOX_LIMITS,
            storageMaintenance = LocalCacheStorageMaintenance(file))
        try { block(file, cache, driver) } finally {
            driver.releaseUserRead.countDown()
            driver.releaseVacuum.countDown()
            cache.close()
        }
    }
    private fun withFactoryCache(root: File, block: (LocalCache) -> Unit) {
        val cache = createDesktopLocalCache(deployment, DATASET, UID, root)
        try { block(cache) } finally { cache.close() }
    }
    private fun createSchema(file: File) {
        val driver = JdbcSqliteDriver(url(file))
        try { AppDatabase.Schema.create(driver); driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0) }
        finally { driver.close() }
    }
    private fun databaseFile(root: File): File = JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(
        listOf("deployments", deployment.fingerprint, "datasets", DATASET, "users", UID), localCacheDatabaseFileName())
    private fun privateFile(root: File, path: String, bytes: ByteArray): File {
        val parts = path.split('/')
        return JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(parts.dropLast(1), parts.last()).also { it.writeBytes(bytes) }
    }
    private fun url(file: File) = "jdbc:sqlite:${file.absolutePath}"
    private fun connection(file: File): Connection = DriverManager.getConnection(url(file))
    private fun contents(file: File): Map<String, List<List<String>>> = connection(file).use { db ->
        val tables = db.createStatement().use { sql ->
            sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
        tables.associateWith { table ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT * FROM \"${table.replace("\"", "\"\"")}\"").use { rows ->
                    buildList {
                        while (rows.next()) add((1..rows.metaData.columnCount).map { column ->
                            when (val value = rows.getObject(column)) {
                                null -> "null"
                                is ByteArray -> "blob:" + Base64.getEncoder().encodeToString(value)
                                else -> value.javaClass.name + ":" + value
                            }
                        })
                    }.sortedBy { it.joinToString("\u0000") }
                }
            }
        }
    }
    private fun number(db: Connection, sql: String): Long = db.createStatement().use { it.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getLong(1) } }
    private fun hash(file: File) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    private fun message(id: String) = Message("chat", id, senderUid = UID, messageType = MessageType.RICH_TEXT.code,
        timestamp = 1, body = RichTextBody("private **content**", plainText = "private content"))
    private fun rejected(reason: LocalCacheStorageCompactionFailure, block: () -> Unit) {
        val failure = assertFailsWith<LocalCacheStorageCompactionException>(block = block)
        assertEquals(reason, failure.reason)
    }
    private fun start(failure: AtomicReference<Throwable?>, name: String, block: () -> Unit) = thread(name = name) {
        try { block() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
    }
    private fun join(vararg threads: Thread?) = threads.filterNotNull().forEach { worker ->
        worker.join(5_000)
        assertFalse(worker.isAlive, "worker did not finish: ${worker.name}")
    }
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("tk-cache-owner-compaction-").toRealPath().toFile()
        try { AccountDataOwner(deployment.fingerprint, DATASET, UID); block(root) }
        finally { root.deleteRecursively() }
    }

    /** Only blocks a real SQL operation; all storage, transactions and cursor behavior stay real. */
    private class ObservedDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val blockNextUserRead = AtomicBoolean(false)
        val userReadEntered = CountDownLatch(1)
        val releaseUserRead = CountDownLatch(1)
        val vacuumEntered = CountDownLatch(1)
        val releaseVacuum = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        @Volatile var blockVacuum = false
        @Volatile var failNextVacuum = false
        @Volatile var closeCalls = 0
        override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                                      parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> {
            if (sql.replace(Regex("\\s+"), " ").contains("FROM user WHERE uid = ?") && blockNextUserRead.compareAndSet(true, false)) {
                userReadEntered.countDown()
                check(releaseUserRead.await(5, TimeUnit.SECONDS)) { "blocked read was not released" }
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (!sql.trim().equals("VACUUM", ignoreCase = true)) return delegate.execute(identifier, sql, parameters, binders)
            events += "vacuum-started"
            vacuumEntered.countDown()
            try {
                if (blockVacuum) check(releaseVacuum.await(5, TimeUnit.SECONDS)) { "blocked vacuum was not released" }
                if (failNextVacuum) { failNextVacuum = false; throw SQLException("controlled maintenance failure") }
                return delegate.execute(identifier, sql, parameters, binders)
            } finally { events += "vacuum-finished" }
        }
        override fun close() {
            events += "close"
            closeCalls += 1
            try { delegate.close() } finally { closed.countDown() }
        }
    }
    private companion object {
        val deployment = DeploymentIdentity.from("storage-compaction.test.example", 5100, "https://storage-compaction.test.example/api")
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val UID = "alice"
        const val OPERATION_ID = "22222222-2222-4222-8222-222222222222"
        const val TOKEN_ID = "33333333-3333-4333-8333-333333333333"
        const val ASSET_ID = "44444444-4444-4444-8444-444444444444"
        const val SOURCE_ID = "55555555-5555-4555-8555-555555555555"
        const val UPLOAD_ID = "66666666-6666-4666-8666-666666666666"
        val PAYLOAD = byteArrayOf(0, -1, 1) + "private source and draft bytes".encodeToByteArray()
        val PAYLOAD_SHA256 = MessageDigest.getInstance("SHA-256").digest(PAYLOAD).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val FINGERPRINT = byteArrayOf(0, 1, -1)
    }
}
