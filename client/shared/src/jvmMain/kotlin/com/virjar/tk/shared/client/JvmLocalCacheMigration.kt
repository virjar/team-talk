package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.TransacterImpl
import com.virjar.tk.shared.database.AppDatabase

/**
 * 与 AndroidSqliteDriver 相同，使用 SQLDelight schema version 和 .sqm 迁移。
 * 零号预览前的 JVM 库没有 user_version，但已经采用完整 schema 1；认领时先用幂等 create
 * 补齐早期未事务化创建可能缺失的基线表，再从 1 起执行增量 .sqm，两种情况都保留既有数据。
 * 该路径依赖 .sqm 可重放：新增对象一律 IF NOT EXISTS / INSERT OR IGNORE，不得直接 ALTER 既有表。
 */
internal fun migrateJvmLocalCache(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>> = AppDatabase.Schema,
) {
    val stored = readSqliteLong(driver, "PRAGMA user_version")
    check(stored <= schema.version) {
        "Local database schema $stored is newer than supported ${schema.version}; data was retained"
    }
    if (stored == schema.version) return
    val existingDatabase = readSqliteLong(
        driver, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
    ) > 0
    // JdbcSqliteDriver 的磁盘模式会在非事务语句结束后关闭连接，必须让驱动持有事务，
    // 不能用裸 BEGIN/COMMIT 假定多条语句始终使用同一连接。
    object : TransacterImpl(driver) {}.transaction(noEnclosing = true) {
        if (!existingDatabase) {
            schema.create(driver).value
        } else if (stored == 0L) {
            schema.create(driver).value
            schema.migrate(driver, 1L, schema.version).value
        } else {
            schema.migrate(driver, stored, schema.version).value
        }
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    }
}

private fun readSqliteLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
    null, sql, { cursor ->
        check(cursor.next().value) { "SQLite did not return a schema value" }
        QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }, 0,
).value
