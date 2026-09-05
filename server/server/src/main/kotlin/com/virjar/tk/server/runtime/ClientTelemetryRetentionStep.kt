package com.virjar.tk.server.runtime

import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import kotlinx.coroutines.CancellationException

/**
 * 把 PostgreSQL 控制面过期与本地 Lucene 事件过期作为独立工作运行。
 * 控制面故障绝不能挂起物理七天事件 TTL。
 *
 * @return 任一侧失败或仍可能有有界积压时返回 true。
 */
internal suspend fun runClientTelemetryRetentionStep(
    now: Long,
    expirePolicies: (now: Long, limit: Int) -> Int,
    ensureEventStoreStarted: () -> Boolean,
    deleteEventsBefore: suspend (cutoff: Long) -> Boolean,
    warn: (operation: String, failure: Exception) -> Unit,
): Boolean {
    var needsCatchUp = false
    try {
        val expired = expirePolicies(now, TelemetryStoragePolicy.MAX_RETENTION_DELETE_BATCHES)
        if (expired == TelemetryStoragePolicy.MAX_RETENTION_DELETE_BATCHES) needsCatchUp = true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        needsCatchUp = true
        warn("policy expiry", failure)
    }

    try {
        val available = ensureEventStoreStarted()
        val deleted = available && deleteEventsBefore(now - TelemetryStoragePolicy.RETENTION_MILLIS)
        if (!deleted) needsCatchUp = true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        needsCatchUp = true
        warn("event expiry", failure)
    }
    return needsCatchUp
}
