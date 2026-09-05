package com.virjar.tk.app.client

/**
 * 应用认证组合根使用的最大努力式排空。
 *
 * 每一个已注册的 release 都会获得一次清理机会。普通的 [Exception] 失败仍作为诊断记录，
 * 而取消和非 [Exception] 缺陷只有在图排空之后才会被提升，并以原始对象重新抛出，
 * 其他失败作为 suppressed 附加其上。
 */
internal class AuthControllerRetirementDrain {
    private val failures = mutableListOf<Pair<String, Throwable>>()

    val failureCount: Int get() = failures.size
    val firstFailure: Throwable? get() = failures.firstOrNull()?.second
    val primaryFailure: Throwable? get() =
        failures.firstOrNull { (_, failure) -> isFatalClientLifecycleFailure(failure) }?.second
            ?: firstFailure
    val hasFatalFailure: Boolean get() = failures.any { (_, failure) ->
        isFatalClientLifecycleFailure(failure)
    }

    fun record(owner: String, failure: Throwable) {
        failures += owner to failure
    }

    fun checkpoint(): Int = failures.size

    fun firstFailureSince(checkpoint: Int): Throwable? {
        require(checkpoint in 0..failures.size) { "Invalid retirement-drain checkpoint" }
        return failures.getOrNull(checkpoint)?.second
    }

    fun release(owner: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            record(owner, failure)
        }
    }

    /** 诊断性失败加入同一棵终止树，因此不可能掩盖致命缺陷。 */
    fun diagnose(action: (failureCount: Int, firstFailure: Throwable) -> Unit) {
        val first = firstFailure ?: return
        release("cleanup diagnostics") { action(failureCount, first) }
    }

    fun throwIfFatal() {
        throwFatalClientLifecycleFailures(failures.map { (_, failure) -> failure })
    }
}
