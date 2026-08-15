package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import java.io.File

/**
 * Desktop(JVM) 平台 LocalCache 工厂。
 *
 * @param dataDir 应用数据目录（由调用方传入，SDK 不感知 Desktop 的目录解析策略）
 */
fun createDesktopLocalCache(uid: String, dataDir: File): LocalCache {
    val dir = File(dataDir, "users/$uid")
    if (!dir.exists()) dir.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dir.absolutePath}/cache.db")
    AppDatabase.Schema.create(driver)
    return LocalCacheImpl(driver)
}
