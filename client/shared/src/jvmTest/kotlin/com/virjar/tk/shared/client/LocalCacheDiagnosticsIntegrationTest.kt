package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

/** 真实 SQLite 与安装目录验证诊断边界；不经正常缓存入口触发迁移、隔离或回收。 */
class LocalCacheDiagnosticsIntegrationTest {
    @Test
    fun `current cache reports reliable aggregates without publishing private payloads or changing files`() = directory { root ->
        val database = jvmDatabase(root, "alice")
        createDatabase(database)
        connection(database).use { db ->
            db.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO message(chat_id,client_msg_id,sender_uid,message_type,timestamp,body) " +
                    "VALUES ('chat','message','alice',1,1,CAST('$SECRET' AS BLOB))")
                for (state in 0..4) insertOutgoing(db, "outgoing-$state", state)
                statement.executeUpdate("INSERT INTO chat_composer_draft VALUES ('active',1,0,CAST('$SECRET' AS BLOB))")
                statement.executeUpdate("INSERT INTO chat_composer_draft VALUES ('cleared',2,1,X'')")
                statement.executeUpdate("INSERT INTO chat_asset_upload VALUES ('asset','active','source',1,CAST('$SECRET' AS BLOB))")
                statement.executeUpdate("INSERT INTO outgoing_chat_asset VALUES ('chat','outgoing-0','alice','asset')")
                statement.executeUpdate("INSERT INTO conversation_draft_outbox VALUES ('active','$SECRET',1,0)")
                statement.executeUpdate("INSERT INTO conversation_draft_outbox VALUES ('finished','$SECRET',2,1)")
                statement.executeUpdate("INSERT INTO chat_draft_sync VALUES ('active'," +
                    "'{\"dirty\":true,\"pending\":{\"private\":\"$SECRET\"},\"consume\":{\"clientMsgId\":\"private-id\"},\"conflict\":true}')")
                statement.executeUpdate("INSERT INTO chat_draft_sync VALUES ('clean'," +
                    "'{\"dirty\":false,\"pending\":null,\"consume\":null,\"conflict\":false}')")
            }
        }
        File(database.path + ".open").writeText(SECRET)
        File(database.path + ".integrity-checked").writeText("checked")
        val before = inventory(root)

        val report = LocalCacheDiagnostics.inspect(root)
        val result = report.databases.single()

        assertEquals(root.canonicalFile, File(report.root).canonicalFile)
        assertEquals(LocalCacheDiagnosticLayout.JVM, report.layout)
        assertTrue("\"uninspected\"" in Json.encodeToString(report), "CLI output must retain its diagnostic coverage boundary")
        assertEquals(database.relativeTo(root).invariantSeparatorsPath, result.path)
        assertEquals(LocalCacheDiagnosticOwner(FINGERPRINT, DATASET, "alice"), result.owner)
        assertFalse(result.quarantine)
        assertEquals(AppDatabase.Schema.version, result.schemaVersion)
        assertEquals(1L, result.counts["messages"])
        assertEquals(5L, result.counts["totalOutgoing"])
        assertEquals(3L, result.counts["pendingOutgoing"])
        assertEquals(1L, result.counts["failedOutgoing"])
        assertEquals(1L, result.counts["completedOutgoing"])
        assertEquals(1L, result.counts["conversationDraftMirrors"])
        assertEquals(1L, result.counts["chatDrafts"])
        assertEquals(1L, result.counts["chatUploads"])
        assertEquals(1L, result.counts["outgoingAssets"])
        assertEquals(2L, result.counts["sharedDraftRows"])
        for (key in listOf("sharedDraftDirty", "sharedDraftPending", "sharedDraftConsume", "sharedDraftConflicts")) {
            assertEquals(1L, result.counts[key], key)
        }
        assertTrue(result.familyFiles.any { it.path == result.path && it.bytes == database.length() })
        assertFalse(Json.encodeToString(report).contains(SECRET))
        assertEquals(before, inventory(root), "diagnosis must leave main, sidecars, markers and all neighboring bytes intact")
    }

    @Test
    fun `committed WAL rows are counted from a private snapshot without checkpointing the original`() = directory { root ->
        val database = jvmDatabase(root, "wal-owner")
        createDatabase(database)
        connection(database).use { writer ->
            writer.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA wal_autocheckpoint=0")
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
            val originalMain = digest(database)
            insertOutgoing(writer, "only-in-wal", 1)
            assertEquals(originalMain, digest(database), "fixture must keep the committed row exclusively in WAL")
            assertTrue(File(database.path + "-wal").length() > 0)
            val before = inventory(root)

            val result = LocalCacheDiagnostics.inspect(root).databases.single()

            assertEquals(1L, result.counts["totalOutgoing"], result.issues.toString())
            assertEquals(1L, result.counts["pendingOutgoing"], result.issues.toString())
            assertEquals(before, inventory(root), "reading WAL must not create or checkpoint original SQLite files")
        }
    }

    @Test
    fun `JVM active and retained quarantine databases keep distinct paths and correct account ownership`() = directory { root ->
        val active = jvmDatabase(root, "alice")
        val neighbor = jvmDatabase(root, "bob")
        createDatabase(active)
        createDatabase(neighbor)
        connection(active).use { insertOutgoing(it, "retained", 3) }
        val quarantine = jvmDatabase(root, "alice.corrupt-retained")
        active.copyTo(quarantine)
        connection(active).use { db -> db.createStatement().use { it.executeUpdate("DELETE FROM outgoing_message") } }
        File(quarantine.path + ".corruption-reported").writeText("retained")
        val before = inventory(root)

        val results = LocalCacheDiagnostics.inspect(root).databases.associateBy { it.path }

        assertEquals(3, results.size)
        val retained = assertNotNull(results[quarantine.relativeTo(root).invariantSeparatorsPath])
        assertTrue(retained.quarantine)
        assertEquals(LocalCacheDiagnosticOwner(FINGERPRINT, DATASET, "alice"), retained.owner)
        assertEquals(1L, retained.counts["failedOutgoing"])
        assertEquals(0L, results[active.relativeTo(root).invariantSeparatorsPath]?.counts?.get("totalOutgoing"))
        assertEquals("bob", results[neighbor.relativeTo(root).invariantSeparatorsPath]?.owner?.uid)
        assertEquals(before, inventory(root))
    }

    @Test
    fun `Android exported active and quarantine families are read without selecting unrelated databases`() = directory { root ->
        val databases = File(root, "databases").apply { mkdirs() }
        val name = localCacheDatabaseFileName(FINGERPRINT, DATASET, "android_owner")
        val active = File(databases, name)
        createDatabase(active)
        connection(active).use { insertOutgoing(it, "android-failed", 3) }
        val quarantine = File(databases, "$name.corrupt-retained")
        active.copyTo(quarantine)
        File(quarantine.path + ".open").writeText("retained")
        File(databases, "unrelated.sqlite").writeText(SECRET)
        val before = inventory(root)

        val report = LocalCacheDiagnostics.inspect(root, LocalCacheDiagnosticLayout.ANDROID)

        assertEquals(LocalCacheDiagnosticLayout.ANDROID, report.layout)
        assertEquals(2, report.databases.size)
        assertEquals(setOf(false, true), report.databases.map { it.quarantine }.toSet())
        report.databases.forEach { result ->
            assertEquals(LocalCacheDiagnosticOwner(FINGERPRINT, DATASET, "android_owner"), result.owner)
            assertEquals(1L, result.counts["failedOutgoing"], result.issues.toString())
            assertTrue(result.path.startsWith("databases/"))
        }
        assertFalse(Json.encodeToString(report).contains(SECRET))
        assertEquals(before, inventory(root))
    }

    @Test
    fun `corruption missing tables and future schemas remain unknown rather than empty or repaired`() = directory { root ->
        val corrupt = jvmDatabase(root, "broken.corrupt-original")
        corrupt.writeText("not a SQLite file $SECRET")
        val old = jvmDatabase(root, "old-schema")
        connection(old).use { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE message(body TEXT)")
                it.executeUpdate("INSERT INTO message VALUES ('$SECRET')")
                it.execute("PRAGMA user_version=1")
            }
        }
        val future = jvmDatabase(root, "future-schema")
        createDatabase(future)
        connection(future).use { db ->
            insertOutgoing(db, "future-pending", 0)
            db.createStatement().use { it.execute("PRAGMA user_version=${AppDatabase.Schema.version + 1}") }
        }
        val before = inventory(root)

        val report = LocalCacheDiagnostics.inspect(root)
        val broken = report.databases.single { it.owner?.uid == "broken" }
        val legacy = report.databases.single { it.owner?.uid == "old-schema" }
        val newer = report.databases.single { it.owner?.uid == "future-schema" }

        assertNull(broken.schemaVersion)
        assertNull(broken.counts["totalOutgoing"])
        assertTrue(broken.issues.isNotEmpty())
        assertEquals(1L, legacy.schemaVersion)
        assertEquals(1L, legacy.counts["messages"])
        assertNull(legacy.counts["totalOutgoing"])
        assertTrue(legacy.issues.any { "totalOutgoing" in it })
        assertEquals(AppDatabase.Schema.version + 1, newer.schemaVersion)
        assertTrue(newer.counts.isNotEmpty())
        assertTrue(newer.counts.values.all { it == null }, "a future schema must not borrow today's aggregate semantics")
        assertTrue(newer.issues.isNotEmpty())
        assertFalse(Json.encodeToString(report).contains(SECRET))
        assertEquals(before, inventory(root))
    }

    @Test
    fun `failed integrity and invalid shared draft shapes cannot become reassuring zero counts`() = directory { root ->
        val invalid = jvmDatabase(root, "invalid-check")
        createDatabase(invalid)
        connection(invalid).use { db ->
            insertOutgoing(db, "still-readable", 0)
            db.createStatement().use { statement ->
                statement.execute("PRAGMA ignore_check_constraints=ON")
                statement.executeUpdate("INSERT INTO user(uid,username,name,revision) VALUES ('invalid','private','$SECRET',0)")
                statement.execute("PRAGMA ignore_check_constraints=OFF")
                statement.executeQuery("PRAGMA quick_check").use { result ->
                    assertTrue(result.next())
                    assertNotEquals("ok", result.getString(1), "fixture must fail integrity despite readable rows")
                }
                statement.executeQuery("SELECT count(*) FROM outgoing_message").use { result ->
                    assertTrue(result.next())
                    assertEquals(1L, result.getLong(1), "count(*) alone must not establish trustworthy facts")
                }
            }
        }
        val malformed = jvmDatabase(root, "invalid-shape")
        createDatabase(malformed)
        val wrongShapes = listOf("{}", "[]", """{"dirty":"true","pending":null,"consume":null,"conflict":false}""")
        for (payload in wrongShapes) {
            connection(malformed).use { db ->
                db.prepareStatement("INSERT OR REPLACE INTO chat_draft_sync VALUES ('private-chat',?)").use {
                    it.setString(1, payload)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val before = inventory(root)

            val report = LocalCacheDiagnostics.inspect(root)
            val failedCheck = report.databases.single { it.owner?.uid == "invalid-check" }
            val failedShape = report.databases.single { it.owner?.uid == "invalid-shape" }

            assertTrue(failedCheck.counts.isNotEmpty())
            assertTrue(failedCheck.counts.values.all { it == null }, "failed quick_check invalidates every aggregate")
            assertTrue(failedCheck.issues.isNotEmpty())
            assertEquals(1L, failedShape.counts["sharedDraftRows"], "the SQL row count remains independently observable")
            for (key in listOf("sharedDraftDirty", "sharedDraftPending", "sharedDraftConsume", "sharedDraftConflicts")) {
                assertTrue(key in failedShape.counts, key)
                assertNull(failedShape.counts[key], "$key must be unknown for malformed shared-draft state")
            }
            assertTrue(failedShape.issues.isNotEmpty())
            assertFalse(Json.encodeToString(report).contains(SECRET))
            assertEquals(before, inventory(root))
        }
    }

    @Test
    fun `symlinks cannot escape installation and database limit marks the report incomplete`() = directory { root ->
        directory { outside ->
            val external = File(outside, "private.db")
            createDatabase(external)
            connection(external).use { insertOutgoing(it, "outside", 3) }
            val linked = jvmDatabase(root, "linked")
            Files.createSymbolicLink(linked.toPath(), external.toPath())
            val beforeOutside = inventory(outside)

            val linkedReport = LocalCacheDiagnostics.inspect(root)

            assertTrue(linkedReport.databases.none { it.counts["totalOutgoing"] != null })
            assertTrue(linkedReport.issues.isNotEmpty() || linkedReport.uninspected.isNotEmpty() ||
                linkedReport.databases.any { it.issues.isNotEmpty() })
            assertEquals(beforeOutside, inventory(outside))
            assertTrue(Files.isSymbolicLink(linked.toPath()))

            // Small corrupt files exercise the actual scan bound without allocating hundreds of MiB.
            repeat(129) { jvmDatabase(root, "bounded-$it").writeText("not SQLite") }
            val before = inventory(root)
            val bounded = LocalCacheDiagnostics.inspect(root)
            assertTrue(bounded.truncated)
            assertTrue(bounded.databases.size <= 128)
            assertTrue(bounded.uninspected.isNotEmpty())
            assertEquals(before, inventory(root))
        }
    }

    private fun jvmDatabase(root: File, userDirectory: String): File = File(root,
        "deployments/$FINGERPRINT/datasets/$DATASET/users/$userDirectory/${localCacheDatabaseFileName()}")
        .also { it.parentFile.mkdirs() }

    private fun createDatabase(file: File) {
        file.parentFile.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            AppDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA user_version=${AppDatabase.Schema.version}", 0)
        } finally { driver.close() }
    }

    private fun connection(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    private fun insertOutgoing(connection: Connection, id: String, state: Int) {
        connection.prepareStatement("INSERT INTO outgoing_message(client_msg_id,chat_id,sender_uid,payload,state,last_error,created_at,updated_at) " +
            "VALUES (?,'chat','owner',?,?,?,1,1)").use {
            it.setString(1, id)
            it.setBytes(2, SECRET.encodeToByteArray())
            it.setInt(3, state)
            it.setString(4, SECRET)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun inventory(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().associate { path ->
            val value = if (Files.isSymbolicLink(path)) "symlink:${Files.readSymbolicLink(path)}" else digest(path.toFile())
            root.toPath().relativize(path).toString() to value
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("tk-cache-diagnostics-").toRealPath().toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private companion object {
        val FINGERPRINT = "a".repeat(64)
        const val DATASET = "12345678-1234-4234-9234-123456789abc"
        const val SECRET = "DIAGNOSTIC_PRIVATE_BODY_TOKEN_SENTINEL"
    }
}
