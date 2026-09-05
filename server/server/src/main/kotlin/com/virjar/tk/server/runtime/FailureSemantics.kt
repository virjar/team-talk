package com.virjar.tk.server.runtime

import kotlinx.coroutines.CancellationException

/**
 * 清理是尽力而为的，但失败报告绝不能掩盖进程致命失败。
 *
 * 第一个普通异常保持 primary，直到观察到取消或非 [Exception] 的
 * [Throwable]。该致命失败随后成为 primary，同时把完整的
 * 更早失败树保留为 suppressed 上下文。一旦致命失败成为 primary，之后的失败
 * 被追加而不会替换它。身份检查使重复的终结失败可以安全合并。
 */
internal fun mergeRuntimeFailure(current: Throwable?, additional: Throwable): Throwable {
    if (current == null || current === additional) return current ?: additional
    return if (!current.isFatalRuntimeFailure() && additional.isFatalRuntimeFailure()) {
        additional.addSuppressedDistinct(current)
        additional
    } else {
        current.addSuppressedDistinct(additional)
        current
    }
}

internal fun Throwable.isFatalRuntimeFailure(): Boolean =
    this is CancellationException || this !is Exception

internal fun Throwable.addSuppressedDistinct(additional: Throwable) {
    if (this !== additional && suppressed.none { it === additional }) {
        addSuppressed(additional)
    }
}

/** 收集失败，同时允许每个清理动作都运行。 */
internal class RuntimeFailureCollector {
    private var failure: Throwable? = null

    fun record(error: Throwable) {
        failure = mergeRuntimeFailure(failure, error)
    }

    fun capture(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            record(error)
        }
    }

    fun failureOrNull(): Throwable? = failure

    fun throwIfAny() {
        failure?.let { throw it }
    }
}
