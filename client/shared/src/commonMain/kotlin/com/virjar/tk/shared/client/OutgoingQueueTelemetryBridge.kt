package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.TelemetryPolicy
import com.virjar.tk.protocol.telemetry.TelemetryPolicyMode

/**
 * 把策略刷新与 StateFlow 发射串行化进一条经隐私审查的数值流。
 *
 * 一次策略刷新会调用 [SendQueue.snapshot]，它同样刷新 StateFlow。因此两条路径可能并发呈现同一个
 * 聚合。只有当前 flow 值有资格，并且只有在 recorder 确实按 DIAGNOSTIC 策略准入之后才记住一个聚合。
 */
internal class OutgoingQueueTelemetryBridge(
    private val recorder: ClientTelemetryRecorder,
    private val currentSnapshot: () -> OutgoingQueueSnapshot,
) {
    private val lock = Any()
    private var lastRecorded: RecordedOutgoingQueueTelemetry? = null

    fun recordIfCurrent(snapshot: OutgoingQueueSnapshot): Boolean = synchronized(lock) {
        if (snapshot != currentSnapshot()) return@synchronized false
        val policy = recorder.outgoingQueuePolicySnapshot()
        if (policy.mode != TelemetryPolicyMode.DIAGNOSTIC) return@synchronized false
        val values = snapshot.toTelemetryValues()
        val candidate = RecordedOutgoingQueueTelemetry(policy, values)
        if (candidate == lastRecorded) return@synchronized false
        recorder.recordOutgoingQueueValues(values).also { recorded ->
            if (recorded) lastRecorded = candidate
        }
    }
}

private data class RecordedOutgoingQueueTelemetry(
    /** 完整策略身份用单个修订号区分诊断收集代际。 */
    val policy: TelemetryPolicy,
    val values: OutgoingQueueTelemetryValues,
)

private data class OutgoingQueueTelemetryValues(
    val pendingCount: Int,
    val retryWaitCount: Int,
    val terminalFailedCount: Int,
    val oldestActiveAgeMillis: Long,
    val maxAttemptCount: Long,
)

private fun OutgoingQueueSnapshot.toTelemetryValues(): OutgoingQueueTelemetryValues {
    val pending = pendingOrInFlightCount
        .coerceIn(0L, ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT.toLong())
        .toInt()
    val retry = retryWaitCount
        .coerceIn(
            0L,
            (ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT - pending).toLong(),
        )
        .toInt()
    return OutgoingQueueTelemetryValues(
        pendingCount = pending,
        retryWaitCount = retry,
        terminalFailedCount = terminalFailedCount
            .coerceIn(0L, ClientTelemetryLimits.MAX_OUTGOING_TERMINAL_FAILED_COUNT.toLong())
            .toInt(),
        oldestActiveAgeMillis = oldestActiveAgeMs
            ?.coerceIn(0L, ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_AGE_MILLIS)
            ?: 0L,
        maxAttemptCount = maxAttemptCount
            .coerceIn(0L, ClientTelemetryLimits.MAX_OUTGOING_ATTEMPT_COUNT),
    )
}

private fun ClientTelemetryRecorder.recordOutgoingQueueValues(
    values: OutgoingQueueTelemetryValues,
): Boolean = recordOutgoingQueue(
    pendingCount = values.pendingCount,
    retryWaitCount = values.retryWaitCount,
    terminalFailedCount = values.terminalFailedCount,
    oldestActiveAgeMillis = values.oldestActiveAgeMillis,
    maxAttemptCount = values.maxAttemptCount,
)
