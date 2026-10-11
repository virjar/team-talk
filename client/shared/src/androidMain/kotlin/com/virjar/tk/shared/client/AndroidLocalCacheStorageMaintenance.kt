package com.virjar.tk.shared.client

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

// Android 的 SQLite 构建可能把临时数据库强制放进内存，包括 VACUUM 重建出的副本。
// 桌面端的磁盘空间上限并不适合约束这种原生内存分配。
internal const val MAX_ANDROID_LOCAL_CACHE_COMPACTION_BYTES = 64L * 1024 * 1024

/** 维护句柄无法关闭；打开另一个账号 owner 需要重启进程。 */
class AndroidLocalCacheStorageCloseException(cause: Throwable) :
    IllegalStateException("Android local cache maintenance could not close its database", cause)

/**
 * 对既有 Android 账号数据库的阻塞式离线维护。应用必须先关闭其会话，
 * 并把新的账号/缓存 owner 挡在外面，直到本调用（包括 close）完成。
 * 与普通 cache factory 不同，该入口绝不创建 schema、执行迁移、隔离或重建。
 */
fun compactAndroidLocalCacheStorage(
    context: Context,
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    uid: String,
): LocalCacheStorageCompactionReport {
    val databaseName = localCacheDatabaseFileName(deploymentIdentity.fingerprint, datasetId, uid)
    val databaseFile = context.getDatabasePath(databaseName)
    if (!Files.isRegularFile(databaseFile.toPath(), NOFOLLOW_LINKS)) {
        throw LocalCacheStorageCompactionException(LocalCacheStorageCompactionFailure.STORAGE_IO_FAILED)
    }
    if (androidLocalCacheLifecycleMarkers(databaseFile).corruption.exists()) {
        throw LocalCacheStorageCompactionException(LocalCacheStorageCompactionFailure.INTEGRITY_CHECK_FAILED)
    }
    val driver = AndroidSqliteDriver(
        schema = AppDatabase.Schema,
        context = context.applicationContext,
        name = databaseName,
        callback = ExistingAndroidMaintenanceCallback(),
    )
    val outcome = runCatching {
        LocalCacheStorageMaintenance(databaseFile, MAX_ANDROID_LOCAL_CACHE_COMPACTION_BYTES).compact(driver)
    }
    // close 失败不算一次成功的维护；应用必须保持准入关闭。
    try {
        driver.close()
    } catch (closeFailure: Throwable) {
        val failure = outcome.exceptionOrNull()?.let { mergeSessionLifecycleFailures(it, closeFailure) }
            ?: closeFailure
        if (isFatalSessionLifecycleFailure(failure)) throw failure
        throw AndroidLocalCacheStorageCloseException(failure)
    }
    return outcome.getOrThrow()
}

private class ExistingAndroidMaintenanceCallback : AndroidSqliteDriver.Callback(AppDatabase.Schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        if (db.version.toLong() != AppDatabase.Schema.version) rejectSchema()
    }

    override fun onCreate(db: SupportSQLiteDatabase) = rejectSchema()

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = rejectSchema()

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = rejectSchema()

    override fun onCorruption(db: SupportSQLiteDatabase) {
        // 绝不委托给 AndroidX 的默认回调：它会删除数据库及其 sidecar。
        throw SQLiteDatabaseCorruptException("Local cache maintenance refuses corrupt storage")
    }

    private fun rejectSchema(): Nothing =
        throw LocalCacheStorageCompactionException(LocalCacheStorageCompactionFailure.UNSUPPORTED_SCHEMA_VERSION)
}
