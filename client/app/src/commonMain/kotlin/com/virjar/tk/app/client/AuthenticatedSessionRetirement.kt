package com.virjar.tk.app.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 在第一次 dispatcher 跳转之前进入退役，并且总是运行一个幂等的 close fallback，
 * 包括当拥有的 Compose scope 在 launch 之前就已被取消的情形。
 */
internal fun CoroutineScope.launchRetirementWithFallback(
    fallback: () -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val fallbackGate = AuthenticatedRetirementFallbackGate()
    val parentJob = coroutineContext[Job]
    if (parentJob?.isActive == false) {
        // invokeOnCompletion 不能改变一个已经完成的 Job 的 cause。同步处理可观察到的
        // 预先取消情形，这样致命 fallback 才能原样逃逸到调用方。
        fallbackGate.run(fallback)
        return Job(parentJob)
    }
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
        var terminalFailure: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            terminalFailure = failure
        }
        try {
            fallbackGate.run(fallback)
        } catch (failure: Throwable) {
            terminalFailure = mergeClientLifecycleFailures(terminalFailure, failure)
        }
        terminalFailure?.let { throw it }
    }
    // 已取消的 parent 可能在协程体到达它自己的 finally 路径之前就拒绝它。
    job.invokeOnCompletion { completionFailure ->
        try {
            fallbackGate.run(fallback)
        } catch (fallbackFailure: Throwable) {
            // 同步 parent 检查之后的取消竞争可能落到这里。协程通过 CoroutineExceptionHandler
            // 报告抛出的 completion-handler 缺陷（包装为 CompletionHandlerException）；
            // 致命错误仍然是它的 cause，绝不会被丢弃。
            throw mergeClientLifecycleFailures(completionFailure, fallbackFailure)
        }
    }
    return job
}

private class AuthenticatedRetirementFallbackGate {
    private val lock = Any()
    private var completed = false

    fun run(fallback: () -> Unit) = synchronized(lock) {
        if (completed) return@synchronized
        completed = true
        fallback()
    }
}

/**
 * 在控制器的本地 session 边界周围运行一对平台/UI 退役。
 * 普通的 hook 失败只是诊断性的：一个破损的外壳绝不能阻止 credential/cache owner
 * 到达其终止状态。取消和 VM 致命缺陷只有在 [retirement] 和 [after]
 * 都获得各自的清理机会之后才传播。
 */
internal fun <T> withAuthenticatedSessionRetirementBoundary(
    before: () -> Unit,
    retirement: () -> T,
    after: () -> Unit,
    onHookFailure: (stage: String, failure: Throwable) -> Unit = { _, _ -> },
): T {
    val observedFailures = mutableListOf<Throwable>()
    var retirementFailure: Throwable? = null
    var completed = false
    var result: T? = null

    fun recordHookFailure(stage: String, failure: Throwable) {
        observedFailures += failure
        try {
            onHookFailure(stage, failure)
        } catch (diagnosticFailure: Throwable) {
            observedFailures += diagnosticFailure
        }
    }

    try {
        before()
    } catch (failure: Throwable) {
        recordHookFailure("before", failure)
    }
    try {
        result = retirement()
        completed = true
    } catch (failure: Throwable) {
        retirementFailure = failure
        observedFailures += failure
    }
    try {
        after()
    } catch (failure: Throwable) {
        recordHookFailure("after", failure)
    }

    val fatal = observedFailures.firstOrNull(::isFatalClientLifecycleFailure)
    if (fatal != null) {
        observedFailures.forEach { failure -> addSuppressedClientLifecycleFailure(fatal, failure) }
        throw fatal
    }
    retirementFailure?.let { failure ->
        observedFailures.forEach { observed -> addSuppressedClientLifecycleFailure(failure, observed) }
        throw failure
    }
    check(completed) { "Authenticated session retirement did not complete" }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** 即使 ClientSession 尚不存在，AUTH_FAILED 也是终止性的；总是释放原始 socket。 */
internal inline fun retireAuthFailureAndDisconnect(
    endSession: () -> Unit,
    disconnectTransport: () -> Unit,
) {
    var terminalFailure: Throwable? = null
    try {
        endSession()
    } catch (failure: Throwable) {
        terminalFailure = failure
    }
    try {
        disconnectTransport()
    } catch (failure: Throwable) {
        terminalFailure = mergeClientLifecycleFailures(terminalFailure, failure)
    }
    terminalFailure?.let { throw it }
}

internal fun isFatalClientLifecycleFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

internal fun mergeClientLifecycleFailures(primary: Throwable?, additional: Throwable): Throwable {
    if (primary == null || primary === additional) return additional
    return if (!isFatalClientLifecycleFailure(primary) && isFatalClientLifecycleFailure(additional)) {
        addSuppressedClientLifecycleFailure(additional, primary)
        additional
    } else {
        addSuppressedClientLifecycleFailure(primary, additional)
        primary
    }
}

internal fun collapseClientLifecycleFailures(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val primary = failures.firstOrNull(::isFatalClientLifecycleFailure) ?: failures.first()
    failures.forEach { failure -> addSuppressedClientLifecycleFailure(primary, failure) }
    return primary
}

internal fun throwFatalClientLifecycleFailures(failures: List<Throwable>) {
    if (failures.none(::isFatalClientLifecycleFailure)) return
    throw checkNotNull(collapseClientLifecycleFailures(failures))
}

private fun addSuppressedClientLifecycleFailure(primary: Throwable, additional: Throwable) {
    if (primary !== additional) primary.addSuppressed(additional)
}
