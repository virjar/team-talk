package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceIdentity
import com.virjar.tk.server.protocol.trace.RecorderPolicyUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

private const val CONNECTION_TRACE_CONTROL_MAX_BACKOFF_MILLIS = 16L

/**
 * 应用一个权威策略快照。读取失败（[policies] == null）、目标被遗漏、
 * 或策略被归属到另一个目标，对每个匹配的物理连接
 * 都是终结性 fail-closed 结果。
 */
internal fun <Connection> applyConnectionTracePolicySnapshot(
    targets: Set<TelemetryDeviceIdentity>,
    policies: Map<TelemetryDeviceIdentity, ClientTelemetryPolicy>?,
    connectionsFor: (TelemetryDeviceIdentity) -> Iterable<Connection>,
    applyPolicy: (Connection, ClientTelemetryPolicy) -> Unit,
    terminalDisable: (Connection) -> Unit,
) {
    targets.forEach { identity ->
        val policy = policies?.get(identity)?.takeIf { candidate ->
            candidate.uid == identity.uid &&
                (candidate.deviceId == null || candidate.deviceId == identity.deviceId)
        }
        connectionsFor(identity).forEach { connection ->
            if (policy == null) terminalDisable(connection) else applyPolicy(connection, policy)
        }
    }
}

/**
 * 只以有界退避重试队列准入。一旦被接受，执行失败
 * 通过每次尝试的 completion 返回，绝不被误认为饱和/重放。
 */
internal suspend fun <T> awaitConnectionTraceControlAdmission(
    accepting: () -> Boolean,
    trySubmit: ((() -> Unit) -> Boolean),
    backoff: suspend (Long) -> Unit = { delay(it) },
    block: () -> T,
): T {
    var backoffMillis = 1L
    while (true) {
        check(accepting()) { "ClientRegistry no longer accepts connection trace control work" }
        val completion = CompletableDeferred<T>()
        val accepted = trySubmit {
            try {
                completion.complete(block())
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
            }
        }
        if (accepted) return completion.await()
        check(accepting()) { "ClientRegistry stopped before connection trace control admission" }
        backoff(backoffMillis)
        backoffMillis = (backoffMillis * 2L).coerceAtMost(CONNECTION_TRACE_CONTROL_MAX_BACKOFF_MILLIS)
    }
}

/** 只为仍占据已鉴权设备槽的精确连接发布。 */
internal fun <Connection> publishExactConnectionTracePolicyUpdate(
    expected: Connection,
    current: Connection?,
    update: RecorderPolicyUpdate?,
    publish: (Connection, RecorderPolicyUpdate) -> Unit,
) {
    if (current === expected && update != null) publish(expected, update)
}
