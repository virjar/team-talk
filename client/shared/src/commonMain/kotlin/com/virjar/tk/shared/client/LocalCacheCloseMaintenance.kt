package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.virjar.tk.shared.log.PlatformOnlyTkLogger

/** SQLite 非阻塞被动 WAL 检查点返回的结果元组。 */
internal data class LocalCacheWalCheckpointResult(
    val busy: Long,
    val logFrames: Long,
    val checkpointedFrames: Long,
)

private val localCacheCloseLogger = PlatformOnlyTkLogger("LocalCache")

/**
 * 检查当前账号数据库，而不等待其他 SQLite 读取者或写入者。
 *
 * PASSIVE 刻意在 WAL 中保留固定后缀，而不是延迟会话退役。随后的 driver close 让 SQLite 在本进程
 * 拥有最终连接时移除/截断副文件。非 WAL 数据库报告 -1/-1，无需单独的模式探测。
 */
internal fun passiveLocalCacheWalCheckpoint(driver: SqlDriver): LocalCacheWalCheckpointResult =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA wal_checkpoint(PASSIVE)",
        mapper = { cursor: SqlCursor ->
            check(cursor.next().value) { "SQLite WAL checkpoint returned no result" }
            QueryResult.Value(
                LocalCacheWalCheckpointResult(
                    busy = checkNotNull(cursor.getLong(0)),
                    logFrames = checkNotNull(cursor.getLong(1)),
                    checkpointedFrames = checkNotNull(cursor.getLong(2)),
                ),
            )
        },
        parameters = 0,
    ).value

/**
 * 仅在 [CacheUseGate] 已排空精确 LocalCache owner 之后使用的干净关闭助手。
 *
 * 维护异常不能泄漏 driver。普通检查点失败是诊断性的：已提交的 SQLite 事实仍然权威，下次打开
 * 仍执行其正常完整性策略。取消与 VM 致命失败保留高于 close 的生命周期优先级。
 */
internal fun closeLocalCacheSqliteDriver(driver: SqlDriver) {
    var terminalFailure: Throwable? = null
    try {
        val result = passiveLocalCacheWalCheckpoint(driver)
        if (result.logFrames >= 0L && result.checkpointedFrames < result.logFrames) {
            terminalFailure = retainFatalLocalCacheCloseDiagnosticFailure(terminalFailure) {
                localCacheCloseLogger.trace(
                    "Passive WAL checkpoint left ${result.logFrames - result.checkpointedFrames} " +
                        "frame(s) pinned by another SQLite owner",
                )
            }
        }
    } catch (failure: Throwable) {
        if (isFatalSessionLifecycleFailure(failure)) {
            terminalFailure = failure
        } else {
            terminalFailure = retainFatalLocalCacheCloseDiagnosticFailure(terminalFailure) {
                localCacheCloseLogger.fault(
                    "Passive WAL checkpoint failed during LocalCache close; closing the driver",
                    failure,
                )
            }
        }
    }

    try {
        driver.close()
    } catch (closeFailure: Throwable) {
        terminalFailure = mergeSessionLifecycleFailures(terminalFailure, closeFailure)
    }
    terminalFailure?.let { throw it }
}

private inline fun retainFatalLocalCacheCloseDiagnosticFailure(
    current: Throwable?,
    diagnostic: () -> Unit,
): Throwable? = try {
    diagnostic()
    current
} catch (failure: Throwable) {
    if (isFatalSessionLifecycleFailure(failure)) {
        mergeSessionLifecycleFailures(current, failure)
    } else {
        current
    }
}
