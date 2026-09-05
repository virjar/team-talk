package com.virjar.tk.shared.client

import java.io.File

/**
 * Desktop(JVM) 平台 LocalCache 工厂。
 *
 * 同一协议 major 保留账号数据库文件，SQLite 按 SQLDelight schema version 迁移。
 * [LOCAL_CACHE_SCHEMA_EPOCH] 标识零号文件基线，不再作为小版本清库开关；大版本重置由安装入口
 * 在任何凭据/数据库打开前统一处理。
 * 主 DB 在 SQLite 打开前预创建为 0600/owner-only；SQLite 可能自建的 journal/WAL/SHM
 * sidecar 由专属账号 namespace（POSIX 0700 / Windows owner-only ACL 继承）承担安全边界，
 * 不假设 sidecar 的单文件 mode 由 SQLite 恒定为 0600。
 *
 * @param dataDir 应用数据目录（由调用方传入，SDK 不感知 Desktop 的目录解析策略）
 */
fun createDesktopLocalCache(
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    uid: String,
    dataDir: File,
): LocalCache = createJvmLocalCache(
    deploymentIdentity = deploymentIdentity,
    datasetId = datasetId,
    uid = uid,
    dataDir = dataDir,
    corruptionPolicy = JvmLocalCacheCorruptionPolicy.QUARANTINE_AND_REBUILD,
)

internal fun createJvmLocalCache(
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    uid: String,
    dataDir: File,
    corruptionPolicy: JvmLocalCacheCorruptionPolicy,
): LocalCache {
    val safeUid = validatedLocalCacheOwnerId(uid)
    val datasetNamespace = validatedLocalCacheDatasetId(datasetId)
    val deploymentNamespace = validatedDeploymentFingerprint(deploymentIdentity.fingerprint)
    val privateData = JvmPrivateDataDirectory.openExisting(dataDir)
    val privateDirectories = listOf(
        "deployments", deploymentNamespace, "datasets", datasetNamespace, "users", safeUid,
    )
    val driver = openCheckedJvmLocalCacheDriver(
        privateData = privateData,
        privateDirectories = privateDirectories,
        databaseFileName = localCacheDatabaseFileName(),
        corruptionPolicy = corruptionPolicy,
    )
    return createLocalCacheWithOwnedDriver(driver)
}
