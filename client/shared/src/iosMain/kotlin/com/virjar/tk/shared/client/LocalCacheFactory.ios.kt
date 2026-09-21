@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.db.QueryResult
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import co.touchlab.sqliter.sqlite3.*
import co.touchlab.sqliter.SynchronousFlag
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import platform.posix.*

/** A sandbox database preserves the same schema migrations and account namespace as desktop. */
fun createIosLocalCache(
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    uid: String,
    dataDir: PlatformFile = platformDataDir(),
): LocalCache {
    val directory = iosPrivateDirectory(dataDir, listOf(
        "deployments", validatedDeploymentFingerprint(deploymentIdentity.fingerprint),
        "datasets", validatedLocalCacheDatasetId(datasetId), "users", validatedLocalCacheOwnerId(uid),
    ))
    val file = PlatformFile(directory, localCacheDatabaseFileName())
    requireIosRegularFile(file)
    localCacheDatabaseSidecars(file.name).forEach { requireIosRegularFile(PlatformFile(directory, it)) }
    val lease = open(PlatformFile(directory, ".database.lock").path, O_RDWR or O_CREAT or O_NOFOLLOW, 384)
    check(lease >= 0) { "Cannot open database owner lock" }
    check(flock(lease, LOCK_EX or LOCK_NB) == 0) {
        close(lease)
        "Another cache is already using this account database"
    }
    var leaseTransferred = false
    try {
        var recovery = resumeIosCacheRecovery(directory, file.name)
        if (file.exists() && file.length() > 0) {
            try { preflightIosDatabase(file) } catch (failure: IosLocalCacheCorruption) {
                if (recovery != null) throw failure
                recovery = beginIosCacheRecovery(directory, file.name)
                PlatformOnlyTkLogger("IosLocalCache").fault("Corrupt account cache moved aside; rebuilding from server", failure)
            }
        }
        if (!file.exists() || file.length() == 0L) {
            if (!file.exists()) check(file.createNewFile()) { "Cannot create database" }
            check(chmod(file.path, 384u) == 0) { "Cannot protect database" }
        }
        val driver = NativeSqliteDriver(
            schema = AppDatabase.Schema,
            name = file.name,
            onConfiguration = { configuration ->
                configuration.copy(extendedConfig = configuration.extendedConfig.copy(basePath = directory.path, synchronousFlag = SynchronousFlag.FULL))
            },
        )
        try {
            val healthy = driver.executeQuery(null, "PRAGMA quick_check", { cursor ->
                val ok = cursor.next().value && cursor.getString(0) == "ok" && !cursor.next().value
                QueryResult.Value(ok)
            }, 0).value
            check(healthy) { "Replacement local cache failed integrity check; old data retained" }
            recovery?.let { finishIosCacheRecovery(directory, file.name, it) }
        } catch (failure: Throwable) { closeOwnedDriverAfterFailure(driver, failure) }
        val ownedDriver = object : app.cash.sqldelight.db.SqlDriver by driver {
            private val lock = PlatformLock()
            private var closed = false
            override fun close() = synchronized(lock) {
                if (closed) return@synchronized
                closed = true
                try { driver.close() } finally { flock(lease, LOCK_UN); platform.posix.close(lease) }
            }
        }
        leaseTransferred = true
        return createLocalCacheWithOwnedDriver(ownedDriver, storageMaintenance = LocalCacheStorageMaintenance(file))
    } catch (failure: Throwable) {
        if (!leaseTransferred) { flock(lease, LOCK_UN); close(lease) }
        throw failure
    }
}

/** Refuse unsupported or corrupt bytes before sqliter can create or migrate any schema. */
private fun preflightIosDatabase(file: PlatformFile) = memScoped {
    val holder = alloc<CPointerVar<sqlite3>>()
    val openResult = sqlite3_open_v2(file.path, holder.ptr, SQLITE_OPEN_READONLY, null)
    if (openResult != SQLITE_OK) {
        holder.value?.let { sqlite3_close(it) }
        requireHealthySqliteResult(openResult)
        error("Cannot inspect local database; existing data retained")
    }
    val database = checkNotNull(holder.value)
    try {
        fun scalar(sql: String): String {
            val statement = alloc<CPointerVar<sqlite3_stmt>>()
            requireHealthySqliteResult(sqlite3_prepare_v2(database, sql.cstr.ptr, -1, statement.ptr, null))
            val query = checkNotNull(statement.value)
            try {
                val result = sqlite3_step(query)
                requireHealthySqliteResult(result)
                check(result == SQLITE_ROW) { "Cannot inspect local database; data retained" }
                return sqlite3_column_text(query, 0)?.reinterpret<ByteVar>()?.toKString().orEmpty()
            } finally { sqlite3_finalize(query) }
        }
        val version = scalar("PRAGMA user_version").toLong()
        check(version <= AppDatabase.Schema.version) { "Local database is newer than this client; data retained" }
        val tables = scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").toLong()
        check(version != 0L || tables == 0L) { "Unversioned local database predates supported releases; data retained" }
        if (scalar("PRAGMA integrity_check(1)") != "ok") throw IosLocalCacheCorruption()
    } finally { sqlite3_close(database) }
}

private class IosLocalCacheCorruption : IllegalStateException("Local SQLite integrity check reported corruption")
private fun requireHealthySqliteResult(code: Int) {
    if (code and 255 in setOf(SQLITE_CORRUPT, SQLITE_NOTADB, SQLITE_FORMAT)) throw IosLocalCacheCorruption()
    check(code in setOf(SQLITE_OK, SQLITE_ROW, SQLITE_DONE)) { "Local SQLite operation failed ($code); data retained" }
}

/** A durable two-phase journal prevents a crash while moving WAL files from mixing database families. */
private fun beginIosCacheRecovery(directory: PlatformFile, databaseName: String): String {
    val id = platformRandomUuid()
    privateAtomicTextFileStore(directory, emptyList(), ".cache-recovery").replaceText("move:$id", 128)
    return checkNotNull(resumeIosCacheRecovery(directory, databaseName))
}
private fun resumeIosCacheRecovery(directory: PlatformFile, databaseName: String): String? {
    val marker = privateAtomicTextFileStore(directory, emptyList(), ".cache-recovery")
    val value = marker.readText(128) ?: return null
    val parts = value.split(':')
    require(parts.size == 2 && parts[0] in setOf("move", "rebuild")) { "Invalid cache recovery journal; data retained" }
    val id = platformCanonicalUuid(parts[1])
    val quarantine = iosPrivateDirectory(directory, listOf(".cache-corrupt-$id"))
    if (parts[0] == "move") {
        (listOf(databaseName) + localCacheDatabaseSidecars(databaseName)).forEach { name ->
            val source = PlatformFile(directory, name)
            val target = PlatformFile(quarantine, name)
            requireIosRegularFile(source); requireIosRegularFile(target)
            check(!source.exists() || !target.exists()) { "Conflicting cache recovery files; data retained" }
            if (source.exists()) target.atomicReplaceWith(source)
        }
        quarantine.syncToDisk(); directory.syncToDisk()
        marker.replaceText("rebuild:$id", 128)
    }
    return id
}
private fun finishIosCacheRecovery(directory: PlatformFile, databaseName: String, id: String) {
    val quarantine = iosPrivateDirectory(directory, listOf(".cache-corrupt-$id"))
    val family = (listOf(databaseName) + localCacheDatabaseSidecars(databaseName)).toSet()
    checkNotNull(quarantine.listFiles()).forEach { file ->
        check(file.name in family) { "Unexpected cache quarantine entry; recovery journal retained" }
        requireIosRegularFile(file)
        check(file.delete()) { "Cannot remove validated cache quarantine" }
    }
    check(quarantine.delete()); directory.syncToDisk()
    privateAtomicTextFileStore(directory, emptyList(), ".cache-recovery").delete()
}
