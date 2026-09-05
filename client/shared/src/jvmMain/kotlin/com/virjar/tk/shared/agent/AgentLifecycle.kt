package com.virjar.tk.shared.agent

import com.virjar.tk.shared.client.collapseSessionLifecycleFailures
import com.virjar.tk.shared.client.isFatalSessionLifecycleFailure
import com.virjar.tk.shared.client.mergeSessionLifecycleFailures

/** 在发布普通诊断或致命终局之前，运行每个已注册的 release。 */
internal class AgentLifecycleDrain {
    private val failures = mutableListOf<Pair<String, Throwable>>()

    fun release(owner: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            failures += owner to failure
        }
    }

    fun finish(reportOrdinaryFailures: (List<Pair<String, Throwable>>) -> Unit) {
        val ordinaryFailures = failures.filterNot { (_, failure) ->
            isFatalSessionLifecycleFailure(failure)
        }
        if (ordinaryFailures.isNotEmpty()) {
            release("cleanup diagnostics") { reportOrdinaryFailures(ordinaryFailures) }
        }

        val hasFatal = failures.any { (_, failure) ->
            isFatalSessionLifecycleFailure(failure)
        }
        if (!hasFatal) return
        throw checkNotNull(collapseSessionLifecycleFailures(failures.map { (_, failure) -> failure }))
    }
}

/**
 * 把 owner 生命周期的失败优先级应用到 `finally` 清理。
 *
 * 成功之后的普通清理失败仅作诊断。主操作失败会把
 * 清理失败保留为 suppressed，除非清理本身是取消或非 Exception
 * 缺陷，此时那个致命对象成为主体并抑制普通操作。
 */
internal fun handleAgentCleanupFailure(
    primaryFailure: Throwable?,
    cleanupFailure: Throwable,
    reportOrdinaryFailure: (Throwable) -> Unit,
) {
    if (primaryFailure != null) {
        val terminal = mergeSessionLifecycleFailures(primaryFailure, cleanupFailure)
        if (terminal !== primaryFailure) throw terminal
        return
    }
    if (isFatalSessionLifecycleFailure(cleanupFailure)) throw cleanupFailure

    try {
        reportOrdinaryFailure(cleanupFailure)
    } catch (diagnosticFailure: Throwable) {
        val terminal = mergeSessionLifecycleFailures(cleanupFailure, diagnosticFailure)
        if (isFatalSessionLifecycleFailure(terminal)) throw terminal
    }
}
