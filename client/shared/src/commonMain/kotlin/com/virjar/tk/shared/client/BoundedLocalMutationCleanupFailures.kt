package com.virjar.tk.shared.client

import kotlinx.coroutines.CancellationException

/** 包含稳定的首个清理原因以及随其保留的任何终态清理缺陷。 */
internal const val MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES = 8

/**
 * 针对清理与观察者缺陷的、固定内存的终态诊断。
 *
 * 首个失败永远不会被挤出。代表性预算用满之后，第一个后续的取消或非 [Exception] 缺陷会替换最新的
 * 普通样本，使终态传播保持失败关闭。每个被省略或挤出的失败都计入一个饱和计数器，而不是无限期
 * 保留其 throwable 图。
 */
internal class BoundedLocalMutationCleanupFailures {
    private val retained = ArrayList<Throwable>(
        MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES,
    )
    private var droppedDiagnostic: LocalMutationCleanupFailuresDroppedException? = null

    fun record(failure: Throwable) {
        if (retained.size < MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES) {
            retained += failure
            return
        }
        if (
            failure.isTerminalMutationFailure() &&
            retained.none { it.isTerminalMutationFailure() }
        ) {
            retained[retained.lastIndex] = failure
        }
        incrementDroppedCount()
    }

    fun snapshot(): List<Throwable> = buildList(retained.size + 1) {
        addAll(retained)
        droppedDiagnostic?.let(::add)
    }

    private fun incrementDroppedCount() {
        val diagnostic = droppedDiagnostic
            ?: LocalMutationCleanupFailuresDroppedException().also { droppedDiagnostic = it }
        diagnostic.incrementDroppedCount()
    }
}

internal class LocalMutationCleanupFailuresDroppedException internal constructor() :
    IllegalStateException("Additional local mutation cleanup failures were omitted") {
    var droppedCount: Long = 0L
        private set

    override val message: String
        get() = "$droppedCount additional local mutation cleanup failure(s) were omitted"

    internal fun incrementDroppedCount() {
        if (droppedCount < Long.MAX_VALUE) droppedCount += 1L
    }
}

internal fun Throwable.isTerminalMutationFailure(): Boolean =
    this is CancellationException || this !is Exception
