package com.virjar.tk.shared.client

/**
 * 关闭一个资源，而不允许普通动作失败掩盖取消或致命关闭失败。Kotlin 标准 `use` 在该情况下保持
 * 动作失败为主因。
 */
internal fun <R : AutoCloseable, T> useResourcePreservingFatalFailure(
    resource: R,
    action: (R) -> T,
): T {
    var actionFailure: Throwable? = null
    try {
        return action(resource)
    } catch (failure: Throwable) {
        actionFailure = failure
        throw failure
    } finally {
        val closeFailure = closeAllResourcesPreservingFatalFailure(resource::close)
        if (closeFailure != null) {
            val terminal = mergeSessionLifecycleFailures(actionFailure, closeFailure)
            if (actionFailure == null || terminal !== actionFailure) throw terminal
        }
    }
}

internal fun closeAllResourcesPreservingFatalFailure(vararg closes: () -> Unit): Throwable? {
    var failure: Throwable? = null
    closes.forEach { close ->
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure = mergeSessionLifecycleFailures(failure, closeFailure)
        }
    }
    return failure
}
