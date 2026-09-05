package com.virjar.tk.shared.client

/**
 * 零号基线的本地数据库文件命名，保持 0 以原样打开已存在的账号数据。
 * 小版本使用 SQLDelight .sqm 迁移，不递增此值换文件；大版本在安装入口统一重置本安装数据。
 */
internal const val LOCAL_CACHE_SCHEMA_EPOCH = 0

/** 服务器身份在 JVM 上成为本地路径组件，在 Android 上成为数据库名。 */
internal fun validatedLocalCacheOwnerId(ownerUid: String): String {
    require(ownerUid.length in 1..64 && ownerUid.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        "Local-cache owner uid is not a safe identifier"
    }
    return ownerUid
}

internal fun validatedDeploymentFingerprint(fingerprint: String): String {
    require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Local-cache deployment fingerprint is invalid"
    }
    return fingerprint
}

internal fun validatedLocalCacheDatasetId(datasetId: String): String {
    com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    return datasetId
}

/** SQLite 可能为主库自建的 sidecar 后缀；与主库同生共死。 */
internal fun localCacheDatabaseSidecars(databaseFileName: String): List<String> = listOf(
    "$databaseFileName-wal",
    "$databaseFileName-shm",
    "$databaseFileName-journal",
)

internal fun localCacheDatabaseFileName(ownerUid: String? = null): String = buildString {
    append("cache_e")
    append(LOCAL_CACHE_SCHEMA_EPOCH)
    ownerUid?.let {
        append('_')
        append(validatedLocalCacheOwnerId(it))
    }
    append(".db")
}

internal fun localCacheDatabaseFileName(
    deploymentFingerprint: String,
    datasetId: String,
    ownerUid: String,
): String = buildString {
    append("cache_e")
    append(LOCAL_CACHE_SCHEMA_EPOCH)
    append('_')
    append(validatedDeploymentFingerprint(deploymentFingerprint))
    append('_')
    append(validatedLocalCacheDatasetId(datasetId))
    append('_')
    append(validatedLocalCacheOwnerId(ownerUid))
    append(".db")
}
