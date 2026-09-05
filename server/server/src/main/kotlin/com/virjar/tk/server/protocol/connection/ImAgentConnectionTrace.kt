package com.virjar.tk.server.protocol.connection

import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.protocol.trace.Recorder
import com.virjar.tk.server.protocol.trace.RecorderPolicyUpdate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

internal fun Recorder.trace(
    phase: ConnectionTracePhase,
    outcome: ConnectionTraceOutcome,
    throwable: Throwable? = null,
    detail: (() -> String)? = null,
) {
    record(phase, outcome, detail?.let { Supplier(it) }, throwable)
}

/** 只记录有界的 NOTIFY 判别器；载荷绝不会被 trace 工作捕获。 */
internal fun Recorder.traceNotifyDelivery(notifyType: Int) {
    record(
        ConnectionTracePhase.EVENT,
        ConnectionTraceOutcome.SUCCEEDED,
        Supplier { "event=notify type=$notifyType" },
    )
}

/** 在 AUTH_RESP 暴露其可选上下文之前，应用初始权威决策。 */
internal fun Recorder.applyInitialConnectionTracePolicy(
    uid: String,
    deviceId: String,
    policy: ClientTelemetryPolicy?,
    nowEpochMs: Long = System.currentTimeMillis(),
): RecorderPolicyUpdate? {
    return if (
        policy?.mode == TelemetryCollectionMode.DIAGNOSTIC &&
        policy.expiresAt?.let { it > nowEpochMs } == true
    ) {
        applyDiagnosticPolicy(
            uid = uid,
            deviceId = deviceId,
            policyRevision = policy.revision,
            expiresAtEpochMs = checkNotNull(policy.expiresAt),
        )
    } else {
        disablePolicy(policy?.revision ?: 0L)
    }
}

/** 拥有一个物理连接记录器策略的 fail-closed 终结 fence。 */
internal class ImAgentConnectionTracePolicy(
    private val recorder: Recorder,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val terminal = AtomicBoolean(false)

    val identity get() = recorder.authenticationIdentity()

    fun apply(uid: String, deviceId: String, policy: ClientTelemetryPolicy): RecorderPolicyUpdate? {
        check(uid == policy.uid) { "Trace policy owner mismatch" }
        if (policy.deviceId != null) {
            check(deviceId == policy.deviceId) { "Trace policy device mismatch" }
        }
        if (terminal.get()) return recorder.terminalDisablePolicy()
        return recorder.applyInitialConnectionTracePolicy(uid, deviceId, policy, clock())
    }

    fun terminalDisable(): RecorderPolicyUpdate? {
        terminal.set(true)
        return recorder.terminalDisablePolicy()
    }

    fun currentDecision(): RecorderPolicyUpdate? = recorder.currentPolicyDecision()
}
