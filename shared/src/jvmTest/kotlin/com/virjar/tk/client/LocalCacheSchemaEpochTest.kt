package com.virjar.tk.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.model.User
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalCacheSchemaEpochTest {

    @Test
    fun `cache owner cannot escape its account directory`() {
        val dataDir = createTempDirectory("teamtalk-cache-owner").toFile()
        try {
            listOf("../other", "user/name", "", ".", "account name").forEach { unsafeUid ->
                assertFailsWith<IllegalArgumentException> {
                    createDesktopLocalCache(unsafeUid, dataDir)
                }
            }
            assertTrue(dataDir.listFiles().isNullOrEmpty())
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `current schema is a migration-free epoch`() {
        assertEquals(1L, AppDatabase.Schema.version)
        assertEquals("cache_e3.db", localCacheDatabaseFileName())
        assertEquals("cache_e3_user-1.db", localCacheDatabaseFileName("user-1"))
    }

    @Test
    fun `new epoch ignores legacy database and remains reusable`() {
        val dataDir = Files.createTempDirectory("tk-cache-epoch-").toFile()
        try {
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir)
            val userDir = privateData.ensureDirectory("users", "u1")
            val legacyFile = privateData.preparePrivateFile(listOf("users", "u1"), "cache_e2.db")
            val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:${legacyFile.absolutePath}")
            AppDatabase.Schema.create(legacyDriver)
            AppDatabase(legacyDriver).appDatabaseQueries.upsertUser(
                uid = "legacy-user",
                username = "legacy",
                name = "Legacy",
                avatar = null,
                phone = null,
                sex = 0L,
                role = 0L,
                status = 1L,
            )
            legacyDriver.close()

            val cache = createDesktopLocalCache("u1", dataDir)
            assertNull(cache.getUser("legacy-user"), "新 epoch 不得读取旧库数据")
            cache.upsertUser(User(uid = "fresh-user", username = "fresh", name = "Fresh"))
            cache.close()

            val epochFile = userDir.resolve(localCacheDatabaseFileName())
            assertTrue(epochFile.isFile)
            assertTrue(legacyFile.isFile, "旧库留给外部清理，启动期不执行破坏性删除")
            if (Files.getFileAttributeView(
                    epochFile.toPath(),
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null
            ) {
                assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(userDir.toPath(), LinkOption.NOFOLLOW_LINKS),
                )
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(epochFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                )
                assertEquals(
                    1,
                    (Files.getAttribute(
                        epochFile.toPath(),
                        "unix:nlink",
                        LinkOption.NOFOLLOW_LINKS,
                    ) as Number).toInt(),
                )
            }

            val reopened = createDesktopLocalCache("u1", dataDir)
            assertNotNull(reopened.getUser("fresh-user"), "同一 epoch 重启应复用当前库")
            assertNull(reopened.getUser("legacy-user"))
            reopened.close()

            DriverManager.getConnection("jdbc:sqlite:${epochFile.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode=WAL").use { result ->
                        assertTrue(result.next())
                        assertEquals("wal", result.getString(1).lowercase())
                    }
                    statement.execute("CREATE TABLE IF NOT EXISTS sidecar_boundary_probe (value TEXT NOT NULL)")
                    statement.executeUpdate("INSERT INTO sidecar_boundary_probe(value) VALUES ('probe')")
                }
                val sidecars = Files.walk(dataDir.toPath()).use { paths ->
                    paths.filter { it.fileName.toString().startsWith("${epochFile.name}-") }.toList()
                }
                assertTrue(sidecars.any { it.fileName.toString() == "${epochFile.name}-wal" })
                sidecars.forEach { sidecar ->
                    assertEquals(userDir.toPath(), sidecar.parent, "SQLite sidecar must stay in the 0700 namespace")
                }
                privateData.ensureDirectory("users", "u1")
            }

            val userCount = JdbcSqliteDriver("jdbc:sqlite:${epochFile.absolutePath}").use { driver ->
                driver.executeQuery(
                    null,
                    "SELECT count(*) FROM user",
                    { cursor: SqlCursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                    },
                    0,
                ).value
            }
            assertEquals(1L, userCount)
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `non-empty partially created current schema self-heals on reopen`() {
        val dataDir = Files.createTempDirectory("tk-cache-partial-schema-").toFile()
        try {
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir)
            val databaseFile = privateData.preparePrivateFile(
                listOf("users", "partial-user"),
                localCacheDatabaseFileName(),
            )
            JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}").use { driver ->
                driver.execute(
                    null,
                    "CREATE TABLE user (uid TEXT PRIMARY KEY NOT NULL, username TEXT NOT NULL, " +
                        "name TEXT NOT NULL, avatar TEXT, phone TEXT, sex INTEGER DEFAULT 0, " +
                        "role INTEGER DEFAULT 0, status INTEGER DEFAULT 1)",
                    0,
                )
            }
            assertTrue(databaseFile.length() > 0L)

            val cache = createDesktopLocalCache("partial-user", dataDir)
            cache.upsertUser(User(uid = "recovered", username = "recovered", name = "Recovered"))
            assertNotNull(cache.getUser("recovered"))
            assertTrue(cache.getConversations().isEmpty())
            assertTrue(cache.recoverOutgoingMessages(now = 1L).isEmpty())
            cache.close()
        } finally {
            dataDir.deleteRecursively()
        }
    }
}
