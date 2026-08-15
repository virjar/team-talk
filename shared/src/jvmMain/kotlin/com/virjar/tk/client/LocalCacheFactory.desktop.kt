package com.virjar.tk.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import java.io.File
import java.sql.DriverManager

/**
 * Desktop(JVM) 平台 LocalCache 工厂。
 *
 * schema 迁移：JDBC driver 不管理 user_version（区别于 Android 的 openHelper），
 * 此处用原生 JDBC Statement 维护版本（PRAGMA 经 PreparedStatement 行为不可靠）——
 * 空库走 Schema.create，旧库按 user_version 走 Schema.migrate（migrations 目录 .sqm 文件）。
 *
 * @param dataDir 应用数据目录（由调用方传入，SDK 不感知 Desktop 的目录解析策略）
 */
fun createDesktopLocalCache(uid: String, dataDir:File): LocalCache {
    val dir = File(dataDir, "users/$uid")
    if (!dir.exists()) dir.mkdirs()
    val url = "jdbc:sqlite:${dir.absolutePath}/cache.db"
    migrateSchema(url, JdbcSqliteDriver(url))
    return LocalCacheImpl(JdbcSqliteDriver(url))
}

/** 迁移/建库（幂等）：PRAGMA user_version 与 AppDatabase.Schema.version 对齐。 */
internal fun migrateSchema(url: String, driver: SqlDriver) {
    val target = AppDatabase.Schema.version
    DriverManager.getConnection(url).use { conn ->
        val current = conn.createStatement().executeQuery("PRAGMA user_version").use { rs ->
            if (rs.next()) rs.getLong(1) else 0L
        }
        val hasTables = conn.createStatement().executeQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('user','conversation','chat')"
        ).use { rs -> rs.next() && rs.getLong(1) > 0 }

        if (current >= target) return
        if (current == 0L && !hasTables) {
            // 空库 → 全新建表
            AppDatabase.Schema.create(driver)
        } else {
            // 已有表但版本落后。历史坑：v1 时代 Schema.create 不写 user_version，
            // 遗留旧库版本恒 0——不能据 0 判新库，需结合"已有表"识别（按 v1 起迁）。
            (AppDatabase.Schema as SqlSchema<QueryResult.Value<Unit>>)
                .migrate(driver, maxOf(current, 1L), target)
        }
        conn.createStatement().execute("PRAGMA user_version = $target")
    }
}
