package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.TransacterImpl
import com.virjar.tk.shared.database.AppDatabase

/**
 * 与 AndroidSqliteDriver 相同，使用 SQLDelight schema version 和 .sqm 迁移。
 * 正式 tag 从 schema 1 起记录 user_version；空库创建，已标版本的库按 .sqm 升级。
 * 发行前未标版本的既有库不推测格式、不自动补表，拒绝打开并保留原资料。
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
    check(stored != 0L || !existingDatabase) {
        "Unversioned local database predates supported releases; data was retained"
    }
    // JdbcSqliteDriver 的磁盘模式会在非事务语句结束后关闭连接，必须让驱动持有事务，
    // 不能用裸 BEGIN/COMMIT 假定多条语句始终使用同一连接。
    object : TransacterImpl(driver) {}.transaction(noEnclosing = true) {
        if (!existingDatabase) {
            schema.create(driver).value
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
