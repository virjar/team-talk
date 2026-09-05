package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import java.io.File
import java.sql.SQLException

private class JvmLocalCacheIntegrityFailure : IllegalStateException(
    "JVM local-cache integrity check reported corruption",
)

private val jvmLocalCacheRecoveryLogger = PlatformOnlyTkLogger("JvmLocalCache")

internal enum class JvmLocalCacheCorruptionPolicy {
    /** Desktop GUI 可以重建服务器投影，同时为诊断保留仅本地事实。 */
    QUARANTINE_AND_REBUILD,

    /** 无头 inbox/outbox owner 绝不能静默替换其可靠本地事实。 */
    FAIL_PRESERVING,
}

/** 打开一个 JVM 账号缓存，在创建干净投影之前保留已确认的损坏。 */
internal fun openCheckedJvmLocalCacheDriver(
    privateData: JvmPrivateDataDirectory,
    privateDirectories: List<String>,
    databaseFileName: String,
    corruptionPolicy: JvmLocalCacheCorruptionPolicy,
): SqlDriver {
    val userDirectory = privateData.ensureDirectory(privateDirectories).toFile()
    val databaseFile = privateData.preparePrivateFile(privateDirectories, databaseFileName)
    var firstDriver: JdbcSqliteDriver? = null
    try {
        firstDriver = newJvmLocalCacheDriver(databaseFile)
        requireHealthyJvmLocalCache(
            driver = firstDriver,
            privateData = privateData,
            privateDirectories = privateDirectories,
            databaseFileName = databaseFileName,
        )
        return firstDriver
    } catch (failure: Throwable) {
        val confirmedCorruption =
            failure is JvmLocalCacheIntegrityFailure || failure.hasJvmSqliteCorruptionCause()
        if (
            !confirmedCorruption ||
            corruptionPolicy == JvmLocalCacheCorruptionPolicy.FAIL_PRESERVING ||
            isFatalSessionLifecycleFailure(failure)
        ) {
            firstDriver?.let { closeOwnedDriverAfterFailure(it, failure) }
            throw failure
        }

        // 在其主文件与日志文件族移动之前，SQLite 必须释放每个描述符。
        if (firstDriver != null) {
            try {
                firstDriver.close()
            } catch (closeFailure: Throwable) {
                throw mergeSessionLifecycleFailures(failure, closeFailure)
            }
        }
        val quarantine = try {
            quarantineJvmLocalCacheUserDirectory(userDirectory).also { retained ->
                privateData.security().forceDirectory(retained.quarantinedUserDirectory.parentFile.toPath())
            }
        } catch (quarantineFailure: Throwable) {
            throw mergeSessionLifecycleFailures(failure, quarantineFailure)
        }
        jvmLocalCacheRecoveryLogger.fault(
            "Corrupt account cache quarantined; local-only facts remain in the retained " +
                "database family while a clean projection is created",
            failure,
        )

        var replacement: JdbcSqliteDriver? = null
        try {
            val replacementFile = privateData.preparePrivateFile(privateDirectories, databaseFileName)
            val replacementDriver = newJvmLocalCacheDriver(replacementFile)
            replacement = replacementDriver
            requireHealthyJvmLocalCache(
                driver = replacementDriver,
                privateData = privateData,
                privateDirectories = privateDirectories,
                databaseFileName = databaseFileName,
            )
            return replacementDriver
        } catch (replacementFailure: Throwable) {
            addSuppressedDistinct(replacementFailure, failure)
            addSuppressedDistinct(
                replacementFailure,
                IllegalStateException(
                    "Corrupt local cache was retained as ${quarantine.quarantinedUserDirectory.name}",
                ),
            )
            replacement?.let { closeOwnedDriverAfterFailure(it, replacementFailure) }
            throw replacementFailure
        }
    }
}

private fun newJvmLocalCacheDriver(databaseFile: File): JdbcSqliteDriver =
    JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")

private fun requireHealthyJvmLocalCache(
    driver: JdbcSqliteDriver,
    privateData: JvmPrivateDataDirectory,
    privateDirectories: List<String>,
    databaseFileName: String,
) {
    // 先确认物理完整性，再按版本执行事务迁移；失败保留原库，不静默重建可靠事实。
    requireJvmLocalCacheQuickCheck(driver)
    migrateJvmLocalCache(driver)
    privateData.requirePrivateFile(privateDirectories, databaseFileName)
}

private fun requireJvmLocalCacheQuickCheck(driver: JdbcSqliteDriver) {
    val rows = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA quick_check",
        mapper = { cursor: SqlCursor ->
            val results = mutableListOf<String?>()
            while (cursor.next().value) results += cursor.getString(0)
            QueryResult.Value(results)
        },
        parameters = 0,
    ).value
    if (rows.size != 1 || rows.singleOrNull() != "ok") {
        throw JvmLocalCacheIntegrityFailure()
    }
}

internal fun Throwable.hasJvmSqliteCorruptionCause(): Boolean {
    var current: Throwable? = this
    repeat(MAX_JVM_SQLITE_CAUSE_DEPTH) {
        val sqlite = current as? SQLException
        if (sqlite != null) {
            val resultCode = sqlite.errorCode
            if (
                resultCode and SQLITE_PRIMARY_CODE_MASK == SQLITE_CORRUPT ||
                resultCode == SQLITE_NOTADB ||
                resultCode == SQLITE_FORMAT
            ) return true
        }
        val next = current?.cause
        if (next == null || next === current) return false
        current = next
    }
    return false
}

private const val MAX_JVM_SQLITE_CAUSE_DEPTH = 16
private const val SQLITE_PRIMARY_CODE_MASK = 0xff
private const val SQLITE_CORRUPT = 11
private const val SQLITE_FORMAT = 24
private const val SQLITE_NOTADB = 26
