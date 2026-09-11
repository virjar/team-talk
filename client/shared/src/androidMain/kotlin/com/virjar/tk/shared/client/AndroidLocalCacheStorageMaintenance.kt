package com.virjar.tk.shared.client

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

// Android SQLite builds can force temporary databases into memory, including VACUUM's rebuilt copy.
// A desktop disk-space limit is not an appropriate bound for this native memory allocation.
internal const val MAX_ANDROID_LOCAL_CACHE_COMPACTION_BYTES = 64L * 1024 * 1024

/** The maintenance handle could not be closed; opening another account owner requires process restart. */
class AndroidLocalCacheStorageCloseException(cause: Throwable) :
    IllegalStateException("Android local cache maintenance could not close its database", cause)

/**
 * Blocking, offline maintenance of an existing Android account database. The application must first
 * close its session and keep new account/cache owners out until this call finishes, including close.
 * Unlike the normal cache factory this entry never creates a schema, migrates, quarantines or rebuilds.
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
    // A close failure is not a successful maintenance result; the application must keep admission shut.
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
        // Never delegate to AndroidX's default callback: it deletes the database and its sidecars.
        throw SQLiteDatabaseCorruptException("Local cache maintenance refuses corrupt storage")
    }

    private fun rejectSchema(): Nothing =
        throw LocalCacheStorageCompactionException(LocalCacheStorageCompactionFailure.UNSUPPORTED_SCHEMA_VERSION)
}
