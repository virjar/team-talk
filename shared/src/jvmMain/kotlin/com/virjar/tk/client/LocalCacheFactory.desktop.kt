package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import java.io.File

/**
 * Desktop(JVM) 平台 LocalCache 工厂。
 *
 * 服务端投影可由快照和事件重建；当前发布前策略在明确批准后通过
 * [LOCAL_CACHE_SCHEMA_EPOCH] 切换到新文件，不在启动路径累积历史迁移分支。epoch 3 起库内还含
 * outgoing/delivery 本地事实，后续切 epoch 必须另行决定它们的迁移或丢弃策略。
 * 主 DB 在 SQLite 打开前预创建为 0600/owner-only；SQLite 可能自建的 journal/WAL/SHM
 * sidecar 由专属账号 namespace（POSIX 0700 / Windows owner-only ACL 继承）承担安全边界，
 * 不假设 sidecar 的单文件 mode 由 SQLite 恒定为 0600。
 *
 * @param dataDir 应用数据目录（由调用方传入，SDK 不感知 Desktop 的目录解析策略）
 */
fun createDesktopLocalCache(
    deploymentIdentity: DeploymentIdentity,
    uid: String,
    dataDir: File,
): LocalCache {
    val safeUid = validatedLocalCacheOwnerId(uid)
    val deploymentNamespace = validatedDeploymentFingerprint(deploymentIdentity.fingerprint)
    val privateData = JvmPrivateDataDirectory.openExisting(dataDir)
    val databaseFile = privateData.preparePrivateFile(
        privateDirectories = listOf("deployments", deploymentNamespace, "users", safeUid),
        fileName = localCacheDatabaseFileName(),
    )
    val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
    try {
        // SQLDelight emits multiple DDL statements. A process crash can leave a non-empty file
        // after only a prefix was committed, so file length is not a completion marker. Every DDL
        // in this fresh-epoch schema is idempotent (`IF NOT EXISTS`); replay it on every open.
        AppDatabase.Schema.create(driver)
        privateData.requirePrivateFile(
            privateDirectories = listOf("deployments", deploymentNamespace, "users", safeUid),
            fileName = localCacheDatabaseFileName(),
        )
        return LocalCacheImpl(driver)
    } catch (throwable: Throwable) {
        driver.close()
        throw throwable
    }
}
