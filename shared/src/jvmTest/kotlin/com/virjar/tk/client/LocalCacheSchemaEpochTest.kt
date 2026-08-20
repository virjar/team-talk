package com.virjar.tk.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.model.User
import java.io.File
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
        assertEquals("cache_e2.db", localCacheDatabaseFileName())
        assertEquals("cache_e2_user-1.db", localCacheDatabaseFileName("user-1"))
    }

    @Test
    fun `new epoch ignores legacy database and remains reusable`() {
        val dataDir = File("/tmp/tk-cache-epoch-${System.nanoTime()}")
        val userDir = dataDir.resolve("users/u1").also { it.mkdirs() }
        val legacyFile = userDir.resolve("cache.db")
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

        val reopened = createDesktopLocalCache("u1", dataDir)
        assertNotNull(reopened.getUser("fresh-user"), "同一 epoch 重启应复用当前库")
        assertNull(reopened.getUser("legacy-user"))
        reopened.close()

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
        dataDir.deleteRecursively()
    }
}
