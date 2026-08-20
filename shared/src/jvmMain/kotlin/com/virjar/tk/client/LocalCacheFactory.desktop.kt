package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import java.io.File

/**
 * Desktop(JVM) 平台 LocalCache 工厂。
 *
 * 本地缓存不承载业务权威数据。当 schema 不兼容时通过 [LOCAL_CACHE_SCHEMA_EPOCH]
 * 切换到新文件，再由服务端快照和事件重建，不在客户端启动路径累积历史迁移分支。
 *
 * @param dataDir 应用数据目录（由调用方传入，SDK 不感知 Desktop 的目录解析策略）
 */
fun createDesktopLocalCache(uid: String, dataDir: File): LocalCache {
    val safeUid = validatedLocalCacheOwnerId(uid)
    val dir = File(dataDir, "users/$safeUid")
    if (!dir.exists()) dir.mkdirs()
    val databaseFile = File(dir, localCacheDatabaseFileName())
    val createSchema = !databaseFile.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
    try {
        if (createSchema) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    } catch (throwable: Throwable) {
        driver.close()
        throw throwable
    }
}
