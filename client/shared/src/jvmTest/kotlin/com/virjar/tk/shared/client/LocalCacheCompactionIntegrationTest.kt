package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.shared.database.AppDatabase
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import kotlin.test.*

/** 真实 SQLite 文件与排他租约验证：压缩只能回收空闲页，不能改变可靠事实或外部资料。 */
class LocalCacheCompactionIntegrationTest {
    @Test
    fun `vacuum reclaims free pages while preserving every stored value and neighboring owner files`() = root { directory ->
        val database = database(directory, "alice")
        createDatabase(database)
        populate(database)
        val neighbor = database(directory, "bob")
        createDatabase(neighbor)
        populate(neighbor)
        // 其他账号的隔离资料不属于本次操作，也不能阻止目标账号回收空闲页。
        privateFile(directory, ownerDirectories("bob.corrupt-retained"), "cache_e0.db").writeText(SECRET)
        privateFile(directory, listOf("document-drafts", FINGERPRINT, DATASET, "alice"), "manifest.json").writeText(SECRET)
        privateFile(directory, listOf("chat-assets", FINGERPRINT, DATASET, "alice"), "source.bin").writeBytes(PAYLOAD)
        val valuesBefore = contents(database)
        val unrelatedBefore = inventory(directory).filterKeys { !it.startsWith(relative(directory, database)) }
        val bytesBefore = database.length()

        val report = LocalCacheCompaction.compact(directory, relative(directory, database))

        assertEquals(bytesBefore, report.bytesBefore)
        assertEquals(database.length(), report.bytesAfter)
        assertTrue(report.freePagesBefore > 0, "fixture must contain reusable pages")
        assertTrue(report.pagesAfter < report.pagesBefore)
        assertTrue(report.bytesAfter < report.bytesBefore)
        assertEquals(0L, report.freePagesAfter)
        assertEquals(AppDatabase.Schema.version, report.schemaVersion)
        assertEquals(valuesBefore, contents(database), "all payload bytes, stable IDs, clocks and receipts must survive")
        assertEquals(unrelatedBefore, inventory(directory).filterKeys { !it.startsWith(relative(directory, database)) })
        connection(database).use { db ->
            assertEquals("ok", string(db, "PRAGMA integrity_check"))
            assertEquals(AppDatabase.Schema.version, number(db, "PRAGMA user_version"))
            val retiredOrdinal = number(db, "SELECT seq FROM sqlite_sequence WHERE name='outgoing_message'")
            assertTrue(retiredOrdinal > number(db, "SELECT max(local_ordinal) FROM outgoing_message"))
            insertOutgoing(db, "after-compaction", 0)
            assertTrue(number(db, "SELECT local_ordinal FROM outgoing_message WHERE client_msg_id='after-compaction'") > retiredOrdinal,
                "compaction must not let a new outgoing message reuse a retired AUTOINCREMENT identity")
        }
        JvmClientDataLease.acquire(directory).close()
    }

    @Test
    fun `committed rows present only in a retained WAL survive compaction`() = root { directory ->
        val source = database(directory, "capture-source")
        val target = database(directory, "wal-owner")
        createDatabase(source)
        populate(source)
        lateinit var expected: Map<String, List<List<String>>>
        connection(source).use { writer ->
            writer.createStatement().use { sql ->
                sql.execute("PRAGMA journal_mode=WAL")
                sql.execute("PRAGMA wal_autocheckpoint=0")
                sql.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
            val originalMain = hash(source)
            insertOutgoing(writer, "only-in-wal", 1)
            assertEquals(originalMain, hash(source))
            expected = contents(writer)
            // 无写入者活动时复制真实文件族，构造关闭后仍保留 WAL 的独立目标；不打开目标触发 checkpoint。
            target.writeBytes(source.readBytes())
            val wal = File(source.path + "-wal")
            assertTrue(wal.length() > 0)
            privateFile(directory, ownerDirectories("wal-owner"), target.name + "-wal").writeBytes(wal.readBytes())
        }
        val unrelatedBefore = inventory(directory).filterKeys { !it.startsWith(relative(directory, target)) }

        val report = LocalCacheCompaction.compact(directory, relative(directory, target))

        assertTrue(report.pagesAfter < report.pagesBefore)
        assertEquals(expected, contents(target))
        assertEquals(0L, File(target.path + "-wal").length(), "successful compaction must finish its WAL checkpoint")
        assertEquals(unrelatedBefore, inventory(directory).filterKeys { !it.startsWith(relative(directory, target)) })
    }

    @Test
    fun `held installation lease refuses compaction without modifying the database family`() = root { directory ->
        val database = database(directory, "leased")
        createDatabase(database)
        populate(database)
        JvmClientDataLease.acquire(directory).use {
            val before = inventory(directory)
            rejected("CLIENT_LOCK_UNAVAILABLE") { LocalCacheCompaction.compact(directory, relative(directory, database)) }
            assertEquals(before, inventory(directory))
        }
        LocalCacheCompaction.compact(directory, relative(directory, database))
    }

    @Test
    fun `independent SQLite owner refuses compaction even without the installation lease`() = root { directory ->
        val database = database(directory, "sqlite-busy")
        createDatabase(database)
        populate(database)
        connection(database).use { writer ->
            writer.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
            try {
                val before = inventory(directory)
                rejected("DATABASE_IN_USE") { LocalCacheCompaction.compact(directory, relative(directory, database)) }
                assertEquals(before, inventory(directory))
            } finally { writer.createStatement().use { it.execute("ROLLBACK") } }
        }
        LocalCacheCompaction.compact(directory, relative(directory, database))
    }

    @Test
    fun `corruption and unsupported schema are retained without migration or quarantine`() = root { directory ->
        val corrupt = database(directory, "corrupt")
        corrupt.writeBytes(PAYLOAD)
        val invalid = database(directory, "invalid-check")
        createDatabase(invalid)
        connection(invalid).use { db ->
            db.createStatement().use {
                it.execute("PRAGMA ignore_check_constraints=ON")
                it.executeUpdate("INSERT INTO user(uid,username,name,revision) VALUES ('bad','bad','$SECRET',0)")
            }
        }
        val unsupported = listOf(0L, 1L, AppDatabase.Schema.version + 1).map { version ->
            database(directory, "schema-$version").also { file ->
                createDatabase(file)
                connection(file).use { db -> db.createStatement().use { it.execute("PRAGMA user_version=$version") } }
            }
        }
        val targets = listOf(corrupt to "DATABASE_CORRUPT", invalid to "INTEGRITY_CHECK_FAILED") +
            unsupported.map { it to "UNSUPPORTED_SCHEMA_VERSION" }
        for ((target, reason) in targets) {
            val before = inventory(directory)
            rejected(reason) { LocalCacheCompaction.compact(directory, relative(directory, target)) }
            assertEquals(before, inventory(directory), "failed preflight must retain the exact original files")
        }
    }

    @Test
    fun `retained quarantine and noncurrent paths cannot be compacted`() = root { directory ->
        val active = database(directory, "retained-owner")
        val quarantine = database(directory, "retained-owner.corrupt-kept")
        val obsolete = privateFile(directory, ownerDirectories("old-epoch"), "cache_e1.db")
        listOf(active, quarantine, obsolete).forEach(::createDatabase)
        val targets = listOf(
            relative(directory, active) to "QUARANTINE_REQUIRES_DISPOSITION",
            relative(directory, quarantine) to "Local-cache owner uid is not a safe identifier",
        ) + listOf(relative(directory, obsolete), "cache_e0.db", "databases/cache_e0.db", "../outside.db",
            relative(directory, active).replace("users/", "users/./")).map {
            it to "--database must name one current JVM account database from the doctor report"
        }
        val before = inventory(directory)
        targets.forEach { (path, reason) -> rejected(reason) { LocalCacheCompaction.compact(directory, path) } }
        assertEquals(before, inventory(directory))
    }

    @Test
    fun `symlink database and sidecar cannot access or mutate external data`() = root { directory ->
        root { outside ->
            val external = database(outside, "external")
            createDatabase(external)
            populate(external)
            val linked = database(directory, "linked")
            Files.delete(linked.toPath())
            Files.createSymbolicLink(linked.toPath(), external.toPath())
            val sidecarOwner = database(directory, "linked-wal")
            createDatabase(sidecarOwner)
            Files.createSymbolicLink(File(sidecarOwner.path + "-wal").toPath(), external.toPath())
            val outsideBefore = inventory(outside)
            val before = inventory(directory)

            listOf(linked, sidecarOwner).forEach { file ->
                rejected("PATH_STATE_OR_IO_FAILURE") { LocalCacheCompaction.compact(directory, relative(directory, file)) }
            }

            assertEquals(before, inventory(directory))
            assertEquals(outsideBefore, inventory(outside))
        }
    }

    @Test
    fun `interrupted reset future major and invalid installation markers cannot be repaired by compaction`() = root { directory ->
        val database = database(directory, "versioned")
        createDatabase(database)
        val marker = File(directory, ".client-data-version")
        for (value in listOf("reset:${ProtocolVersions.MAJOR}", "ready:${ProtocolVersions.MAJOR + 1}", "invalid")) {
            marker.writeText(value)
            val before = inventory(directory)
            rejected("INSTALLATION_VERSION_NOT_READY") { LocalCacheCompaction.compact(directory, relative(directory, database)) }
            assertEquals(before, inventory(directory))
        }
        assertTrue(marker.delete())
        val withoutMarker = inventory(directory)
        rejected("INSTALLATION_VERSION_NOT_READY") { LocalCacheCompaction.compact(directory, relative(directory, database)) }
        assertEquals(withoutMarker, inventory(directory), "maintenance must not adopt an unmarked installation")
    }

    private fun populate(file: File) = connection(file).use { db ->
        db.autoCommit = false
        try {
            db.prepareStatement("INSERT INTO message(chat_id,client_msg_id,sender_uid,message_type,timestamp,body) VALUES ('chat',?,'alice',1,1,?)").use { sql ->
                repeat(256) { index -> sql.setString(1, "discard-$index"); sql.setBytes(2, ByteArray(8192) { 7 }); sql.addBatch() }
                sql.executeBatch()
            }
            db.createStatement().use { it.executeUpdate("DELETE FROM message") }
            repeat(5) { insertOutgoing(db, "outgoing-$it", it) }
            insertOutgoing(db, "retired-highest-ordinal", 4)
            db.createStatement().use { it.executeUpdate("DELETE FROM outgoing_message WHERE client_msg_id='retired-highest-ordinal'") }
            db.prepareStatement("INSERT INTO chat_composer_draft VALUES ('chat',41,0,?)").use { it.setBytes(1, PAYLOAD); it.executeUpdate() }
            db.prepareStatement("INSERT INTO chat_asset_upload VALUES ('asset','chat','source',1,?)").use { it.setBytes(1, PAYLOAD); it.executeUpdate() }
            db.createStatement().use { sql ->
                sql.executeUpdate("UPDATE chat_composer_clock SET revision=41")
                sql.executeUpdate("INSERT INTO outgoing_chat_asset VALUES ('chat','outgoing-0','alice','asset')")
                sql.executeUpdate("INSERT INTO chat_draft_sync VALUES ('chat','{\"dirty\":true,\"pending\":{\"operationId\":\"stable\"},\"consume\":{\"clientMsgId\":\"outgoing-0\"},\"conflict\":true}')")
                sql.executeUpdate("INSERT INTO pending_task_commands VALUES ('task','task-operation','$SECRET')")
                sql.executeUpdate("INSERT INTO conversation_draft_outbox VALUES ('chat','$SECRET',7,0)")
                sql.executeUpdate("INSERT INTO sync_state VALUES (1,'$DATASET',19)")
                sql.executeUpdate("INSERT INTO bot_message_inbox VALUES (19,'chat',9,X'0001FF',0,1,NULL)")
            }
            db.commit()
        } catch (failure: Throwable) { db.rollback(); throw failure }
        assertTrue(number(db, "PRAGMA freelist_count") > 0)
    }

    private fun insertOutgoing(db: Connection, id: String, state: Int) {
        db.prepareStatement("INSERT INTO outgoing_message(client_msg_id,chat_id,sender_uid,payload,request_fingerprint,state,created_at,updated_at) VALUES (?,'chat','alice',?,?,?,1,2)").use {
            it.setString(1, id); it.setBytes(2, PAYLOAD); it.setBytes(3, byteArrayOf(0, 1, -1)); it.setInt(4, state); it.executeUpdate()
        }
    }

    private fun createDatabase(file: File) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            AppDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0)
        } finally { driver.close() }
    }

    private fun contents(file: File) = connection(file).use(::contents)
    private fun contents(db: Connection): Map<String, List<List<String>>> {
        val tables = db.createStatement().use { sql ->
            sql.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
        return tables.associateWith { table ->
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

    private fun connection(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.toPath().toUri()}?mode=rw")
    private fun number(db: Connection, sql: String): Long = db.createStatement().use { it.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getLong(1) } }
    private fun string(db: Connection, sql: String): String = db.createStatement().use { it.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getString(1) } }
    private fun database(root: File, uid: String) = privateFile(root, ownerDirectories(uid), localCacheDatabaseFileName())
    private fun ownerDirectories(uid: String): List<String> {
        validatedDeploymentFingerprint(FINGERPRINT)
        validatedLocalCacheDatasetId(DATASET)
        validatedLocalCacheOwnerId(uid.substringBefore(".corrupt-"))
        return listOf("deployments", FINGERPRINT, "datasets", DATASET, "users", uid)
    }
    private fun privateFile(root: File, path: List<String>, name: String) = JvmPrivateDataDirectory.openExisting(root).preparePrivateFile(path, name)
    private fun relative(root: File, file: File) = file.relativeTo(root).invariantSeparatorsPath
    private fun hash(file: File) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList().associate { path ->
            root.toPath().relativize(path).toString().replace(File.separatorChar, '/') to
                if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}" else hash(path.toFile())
        }
    }
    private fun rejected(reason: String, action: () -> Unit) {
        val failure = assertFails(block = action)
        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException, "expected a fixed diagnostic rejection, got ${failure.javaClass.name}")
        assertEquals(reason, failure.message?.removePrefix("Local cache compaction failed: "), "rejection must reach the intended boundary")
        assertFalse(failure.message.orEmpty().contains(SECRET))
    }
    private fun root(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("tk-cache-compaction-").toRealPath().toFile()
        try {
            JvmClientDataLease.acquire(directory).use { prepareJvmClientDataVersion(directory) }
            block(directory)
        } finally { directory.deleteRecursively() }
    }
    private companion object {
        const val FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DATASET = "11111111-1111-4111-8111-111111111111"
        const val SECRET = "private-draft-and-operation-content"
        val PAYLOAD = byteArrayOf(0, -1, 1) + SECRET.encodeToByteArray()
    }
}
