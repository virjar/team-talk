package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import java.io.File

/**
 * Desktop(JVM) 平台 LocalCache 工厂。
 *
 * 本地缓存不承载业务权威数据。当 schema 不兼容时通过 [LOCAL_CACHE_SCHEMA_EPOCH]
 * 切换到新文件，再由服务端快照和事件重建，不在客户端启动路径累积历史迁移分支。
 * 主 DB 在 SQLite 打开前预创建为 0600/owner-only；SQLite 可能自建的 journal/WAL/SHM
 * sidecar 由专属账号 namespace（POSIX 0700 / Windows owner-only ACL 继承）承担安全边界，
 * 不假设 sidecar 的单文件 mode 由 SQLite 恒定为 0600。
 *
 * @param dataDir 应用数据目录（由调用方传入，SDK 不感知 Desktop 的目录解析策略）
 */
fun createDesktopLocalCache(uid: String, dataDir: File): LocalCache {
    val safeUid = validatedLocalCacheOwnerId(uid)
    val privateData = JvmPrivateDataDirectory.openExisting(dataDir)
    val databaseFile = privateData.preparePrivateFile(
        privateDirectories = listOf("users", safeUid),
        fileName = localCacheDatabaseFileName(),
    )
    val createSchema = databaseFile.length() == 0L
    val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
    try {
        if (createSchema) AppDatabase.Schema.create(driver)
        privateData.requirePrivateFile(
            privateDirectories = listOf("users", safeUid),
            fileName = localCacheDatabaseFileName(),
        )
        return LocalCacheImpl(driver)
    } catch (throwable: Throwable) {
        driver.close()
        throw throwable
    }
}
