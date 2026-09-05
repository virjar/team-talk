package com.virjar.tk.shared.client

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import java.io.File
import java.io.FileNotFoundException

private data class AndroidLocalCacheOpenAttempt(
    val driver: AndroidSqliteDriver,
    val callback: NonDeletingAndroidLocalCacheCallback,
)

private class AndroidLocalCacheIntegrityFailure : IllegalStateException(
    "Android local-cache integrity evidence reported corruption",
)

private val androidLocalCacheRecoveryLogger = PlatformOnlyTkLogger("AndroidLocalCache")

internal fun openCheckedAndroidLocalCacheDriver(
    context: Context,
    databaseName: String,
    databaseFile: File,
): SqlDriver {
    val markers = androidLocalCacheLifecycleMarkers(databaseFile)
    val corruptionMarkerWasPresent = markers.corruption.exists()
    if (corruptionMarkerWasPresent && !databaseFile.isFile) {
        throw FileNotFoundException(
            "Android local-cache corruption was reported but its main file is missing",
        )
    }
    val nowEpochMs = System.currentTimeMillis()
    val runQuickCheck = shouldQuickCheckAndroidLocalCache(markers, nowEpochMs)
    val firstAttempt = newAndroidLocalCacheOpenAttempt(
        context = context,
        databaseName = databaseName,
        corruptionMarker = markers.corruption,
    )
    try {
        requireHealthyAndroidLocalCache(
            attempt = firstAttempt,
            corruptionMarkerWasPresent = corruptionMarkerWasPresent,
            runQuickCheck = runQuickCheck,
        )
        if (runQuickCheck) recordSuccessfulAndroidLocalCacheQuickCheck(markers.integrity, nowEpochMs)
        return publishOpenAndroidLocalCacheDriver(firstAttempt.driver, markers.open)
    } catch (failure: Throwable) {
        val confirmedCorruption =
            corruptionMarkerWasPresent ||
                firstAttempt.callback.corruptionReported ||
                failure is AndroidLocalCacheIntegrityFailure ||
                failure.hasAndroidSqliteCorruptionCause()
        if (!confirmedCorruption || isFatalSessionLifecycleFailure(failure)) {
            closeOwnedDriverAfterFailure(firstAttempt.driver, failure)
        }

        // 在 SQLite 主文件与日志文件族移动期间，绝不能保留打开的句柄。
        try {
            firstAttempt.driver.close()
        } catch (closeFailure: Throwable) {
            throw mergeSessionLifecycleFailures(failure, closeFailure)
        }

        val quarantine = try {
            quarantineAndroidLocalCacheDatabase(databaseFile)
        } catch (quarantineFailure: Throwable) {
            throw mergeSessionLifecycleFailures(failure, quarantineFailure)
        }
        androidLocalCacheRecoveryLogger.fault(
            "Corrupt account cache quarantined; local-only facts remain in the retained " +
                "database family while a replacement is created",
            failure,
        )

        val replacement = newAndroidLocalCacheOpenAttempt(
            context = context,
            databaseName = databaseName,
            corruptionMarker = markers.corruption,
        )
        try {
            val replacementCheckAt = System.currentTimeMillis()
            requireHealthyAndroidLocalCache(
                attempt = replacement,
                corruptionMarkerWasPresent = false,
                runQuickCheck = true,
            )
            recordSuccessfulAndroidLocalCacheQuickCheck(markers.integrity, replacementCheckAt)
            return publishOpenAndroidLocalCacheDriver(replacement.driver, markers.open)
        } catch (replacementFailure: Throwable) {
            addSuppressedDistinct(replacementFailure, failure)
            addSuppressedDistinct(
                replacementFailure,
                IllegalStateException(
                    "Corrupt local cache was retained as ${quarantine.quarantinedMainFile.name}",
                ),
            )
            closeOwnedDriverAfterFailure(replacement.driver, replacementFailure)
        }
    }
}

private fun newAndroidLocalCacheOpenAttempt(
    context: Context,
    databaseName: String,
    corruptionMarker: File,
): AndroidLocalCacheOpenAttempt {
    val callback = NonDeletingAndroidLocalCacheCallback(corruptionMarker)
    // AndroidSqliteDriver 的 Configuration 保持 allowDataLossOnRecovery=false。单独传入这个
    // 回调会覆盖独立的默认 onCorruption 删除路径。
    val driver = AndroidSqliteDriver(
        schema = AppDatabase.Schema,
        context = context,
        name = databaseName,
        callback = callback,
    )
    return AndroidLocalCacheOpenAttempt(driver, callback)
}

private fun requireHealthyAndroidLocalCache(
    attempt: AndroidLocalCacheOpenAttempt,
    corruptionMarkerWasPresent: Boolean,
    runQuickCheck: Boolean,
) {
    if (corruptionMarkerWasPresent) throw AndroidLocalCacheIntegrityFailure()
    if (!runQuickCheck) {
        attempt.driver.executeQuery(
            identifier = null,
            sql = "PRAGMA schema_version",
            mapper = { cursor: SqlCursor -> QueryResult.Value(cursor.next().value) },
            parameters = 0,
        ).value.also { hasRow ->
            if (!hasRow || attempt.callback.corruptionReported) {
                throw AndroidLocalCacheIntegrityFailure()
            }
        }
        return
    }
    val rows = attempt.driver.executeQuery(
        identifier = null,
        sql = "PRAGMA quick_check",
        mapper = { cursor: SqlCursor ->
            val results = mutableListOf<String?>()
            while (cursor.next().value) results += cursor.getString(0)
            QueryResult.Value(results)
        },
        parameters = 0,
    ).value
    if (
        attempt.callback.corruptionReported ||
        rows.size != 1 ||
        rows.singleOrNull() != "ok"
    ) {
        throw AndroidLocalCacheIntegrityFailure()
    }
}

private fun publishOpenAndroidLocalCacheDriver(
    driver: AndroidSqliteDriver,
    openMarker: File,
): SqlDriver {
    if (!openMarker.createNewFile() && !openMarker.isFile) {
        throw IllegalStateException("Android local-cache open marker is not a regular file")
    }
    return CleanCloseMarkingAndroidSqlDriver(driver, openMarker)
}

private class CleanCloseMarkingAndroidSqlDriver(
    private val delegate: AndroidSqliteDriver,
    private val openMarker: File,
) : SqlDriver by delegate {
    override fun close() {
        delegate.close()
        try {
            openMarker.delete()
        } catch (_: Exception) {
            // 过期标记只会触发再一次 quick_check；它不会丢失缓存数据。
        }
    }
}

private fun Throwable.hasAndroidSqliteCorruptionCause(): Boolean {
    var current: Throwable? = this
    repeat(MAX_ANDROID_SQLITE_CAUSE_DEPTH) {
        if (current is SQLiteDatabaseCorruptException) return true
        val next = current?.cause
        if (next == null || next === current) return false
        current = next
    }
    return false
}

private const val MAX_ANDROID_SQLITE_CAUSE_DEPTH = 16
