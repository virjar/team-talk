package com.virjar.tk.shared.client

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmLocalCacheMigrationTest {
    @Test
    fun `unversioned existing data is completed then migrated from schema one without losing drafts`() {
        val directory = Files.createTempDirectory("tk-schema-migration-").toFile()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("cache.db").absolutePath}")
        try {
            driver.execute(null, "CREATE TABLE draft (body TEXT NOT NULL)", 0)
            driver.execute(null, "INSERT INTO draft VALUES ('unsent')", 0)
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
            assertFailsWith<IllegalStateException> {
                migrateJvmLocalCache(driver, migrationSchema { db ->
                    db.execute(null, "UPDATE draft SET body = 'lost'", 0)
                    error("migration interrupted")
                })
            }
            assertEquals("unsent", scalar(driver, "SELECT body FROM draft"))
            assertEquals("0", scalar(driver, "PRAGMA user_version"))
            driver.execute(null, "PRAGMA user_version = 3", 0)
            assertFailsWith<IllegalStateException> { migrateJvmLocalCache(driver, migrationSchema {}) }
            assertEquals("unsent", scalar(driver, "SELECT body FROM draft"))
            assertEquals("3", scalar(driver, "PRAGMA user_version"))
        } finally { driver.close(); directory.deleteRecursively() }
    }

    private fun migrationSchema(apply: (SqlDriver) -> Unit) = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 2L
        // 幂等的基线 create：只为缺失对象补建终态定义，绝不改写既有表（由 .sqm 迁移负责演进）。
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE IF NOT EXISTS draft (body TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0)",
                0,
            )
            return QueryResult.Unit
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
