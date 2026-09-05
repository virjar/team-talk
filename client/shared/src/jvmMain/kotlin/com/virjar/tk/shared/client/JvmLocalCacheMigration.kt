package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.TransacterImpl
import com.virjar.tk.shared.database.AppDatabase

/**
 * 与 AndroidSqliteDriver 相同，使用 SQLDelight schema version 和 .sqm 迁移。
 * 零号预览前的 JVM 库没有 user_version，但已经采用完整 schema 1；只认领为 1 后逐步迁移，
 * 不能把旧库直接标成未来版本，也不能在每次启动重跑最新建表定义。
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
        if (stored == 0L && (!existingDatabase || schema.version == 1L)) {
            // 仅零号基线的一次性认领允许补齐过去未事务化创建的 schema 1。
            // 后续 schema 不能用最新建表定义“修复”既有库，必须执行 .sqm。
            schema.create(driver).value
        } else {
            val from = if (stored == 0L) 1L else stored
            check(from <= schema.version) { "Cannot adopt legacy database into an older schema" }
            schema.migrate(driver, from, schema.version).value
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
