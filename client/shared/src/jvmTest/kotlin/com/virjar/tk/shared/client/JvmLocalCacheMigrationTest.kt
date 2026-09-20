package com.virjar.tk.shared.client

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmLocalCacheMigrationTest {
    @Test
    fun `released schemas upgrade with draft outbox bytes intact`() {
        // v0.0.0/v0.0.1 = 1, v0.0.2 = 2, v0.0.4 = 5。
        // 从当前建表定义去掉对应发行点尚不存在的新增表，重放真实 .sqm。
        val addedTables = mapOf(
            2L to listOf("document_comment_pages", "pending_document_comments", "task_projection",
                "task_pages", "pending_task_commands", "task_reminders", "chat_composer_clock",
                "chat_composer_draft", "chat_asset_upload", "outgoing_chat_asset", "chat_draft_sync"),
            3L to listOf("chat_avatar"),
            4L to listOf("task_details", "task_query_pages"),
            5L to listOf("conversation_read_validation"),
        )
        for (releasedVersion in listOf(1L, 2L, 5L)) {
            val directory = Files.createTempDirectory("tk-released-schema-$releasedVersion-").toFile()
            val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("cache.db").absolutePath}")
            try {
                AppDatabase.Schema.create(driver)
                val queries = AppDatabase(driver).appDatabaseQueries
                queries.upsertConversationDraftOutbox("chat", "unsent", 7L, 0L)
                addedTables.filterKeys { it > releasedVersion }.values.flatten().forEach { table ->
                    driver.execute(null, "DROP TABLE $table", 0)
                }
                driver.execute(null, "PRAGMA user_version = $releasedVersion", 0)
                migrateJvmLocalCache(driver)
                val pending = queries.selectAllConversationDraftOutbox().executeAsOne()
                assertEquals("unsent", pending.draft)
                assertEquals(7L, pending.generation)
                assertEquals(0L, pending.state)
                assertEquals(AppDatabase.Schema.version.toString(), scalar(driver, "PRAGMA user_version"))
                addedTables.values.flatten().forEach { table ->
                    assertEquals("1", scalar(driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"))
                }
            } finally { driver.close(); directory.deleteRecursively() }
        }
    }

    @Test
    fun `released schema one migrates once without losing drafts`() {
        val directory = Files.createTempDirectory("tk-schema-migration-").toFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("cache.db").absolutePath}")
        try {
            driver.execute(null, "CREATE TABLE draft (body TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO draft VALUES ('unsent')", 0)
            driver.execute(null, "PRAGMA user_version = 1", 0)
            migrateJvmLocalCache(driver, migrationSchema { db ->
                db.execute(null, "ALTER TABLE draft ADD COLUMN revision INTEGER NOT NULL DEFAULT 0", 0)
            })
            assertEquals("unsent", scalar(driver, "SELECT body FROM draft"))
            assertEquals("0", scalar(driver, "SELECT revision FROM draft"))
            assertEquals("2", scalar(driver, "PRAGMA user_version"))
            migrateJvmLocalCache(driver, migrationSchema { error("migration must only run once") })
        } finally { driver.close(); directory.deleteRecursively() }
    }

    @Test
    fun `failed migration rolls back data and version and newer schema refuses downgrade`() {
        val directory = Files.createTempDirectory("tk-schema-migration-").toFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("cache.db").absolutePath}")
        try {
            driver.execute(null, "CREATE TABLE draft (body TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO draft VALUES ('unsent')", 0)
            driver.execute(null, "PRAGMA user_version = 1", 0)
            assertFailsWith<IllegalStateException> {
                migrateJvmLocalCache(driver, migrationSchema { db ->
                    db.execute(null, "UPDATE draft SET body = 'lost'", 0)
                    error("migration interrupted")
                })
            }
            assertEquals("unsent", scalar(driver, "SELECT body FROM draft"))
            assertEquals("1", scalar(driver, "PRAGMA user_version"))
            driver.execute(null, "PRAGMA user_version = 3", 0)
            assertFailsWith<IllegalStateException> { migrateJvmLocalCache(driver, migrationSchema {}) }
            assertEquals("unsent", scalar(driver, "SELECT body FROM draft"))
            assertEquals("3", scalar(driver, "PRAGMA user_version"))
        } finally { driver.close(); directory.deleteRecursively() }
    }

    private fun migrationSchema(apply: (SqlDriver) -> Unit) = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 2L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            error("An existing versioned database must migrate without running create")
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            assertEquals(1L, oldVersion)
            assertEquals(2L, newVersion)
            apply(driver)
            return QueryResult.Unit
        }
    }

    private fun scalar(driver: SqlDriver, sql: String): String? = driver.executeQuery(
        null, sql, { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) }, 0,
    ).value
}
