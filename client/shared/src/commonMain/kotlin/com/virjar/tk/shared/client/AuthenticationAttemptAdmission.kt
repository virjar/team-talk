package com.virjar.tk.shared.client

/**
 * 将显式认证尝试在调用方线程与 transport EventLoop 之间线性化。
 *
 * 预留一份新租约时会同步退役所有更旧的租约。响应处理器为其完整的状态转换持有同一把 monitor，
 * 因此替代/登出要么在该处理器开始之前获胜（该响应变得无效），要么等待它结束之后再清理其结果。
 * 延迟的 EventLoop 任务携带其精确租约，因此在销毁或出现更新的尝试之后无法重新激活自己。
 */
class AuthenticationAttemptAdmission {
    private val lock = Any()
    private var nextGeneration = 0L
    private var activeGeneration = 0L
    /** 否则 monitor 重入会让替代尝试从其自身被准入的操作中返回。 */
    private var operationDepth = 0

    internal fun reserve(): AuthenticationAttemptLease = synchronized(lock) {
        check(operationDepth == 0) {
            "Authentication attempt cannot be replaced reentrantly from an admitted operation"
        }
        check(nextGeneration < Long.MAX_VALUE) { "Authentication attempt generation exhausted" }
        nextGeneration += 1L
        activeGeneration = nextGeneration
        AuthenticationAttemptLease(this, activeGeneration)
    }

    /** 等待一个已被准入的响应处理器，然后使所有当前/后续回调失效。 */
    fun retire() = synchronized(lock) {
        activeGeneration = 0L
        if (operationDepth > 0) throw AuthenticationAttemptReentrantRetirementException()
    }

    /**
     * 原子地检查外部凭据状态、退役每一份 AUTH 租约，并完成匹配的终态转换。谓词与转换与 AUTH 结果
     * 安装共享同一把 monitor：在接受 401 与清除被拒绝会话之间，新尝试无法预留控制器。
     */
    fun retireIf(
        predicate: () -> Boolean,
        retirement: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!predicate()) return@synchronized false
        if (operationDepth > 0) throw AuthenticationAttemptReentrantRetirementException()
        activeGeneration = 0L
        operationDepth += 1
        try {
            retirement()
        } finally {
            operationDepth -= 1
        }
        true
    }

    /**
     * 在一次 monitor 获取中捕获精确的 transport owner、退役认证，并调度该 owner 的拆除。之后的
     * 登录无法在退役与拆除入队之间预留 B，而已经准入 B 的安装者必须先推进其 transport owner，
     * 之后 [captureTeardownOwner] 才能观察到它。
     */
    internal fun <T> retireAndSchedule(
        captureTeardownOwner: () -> T,
        scheduleTeardown: (T) -> Unit,
    ) = synchronized(lock) {
        val rejectReentrantRetirement = operationDepth > 0
        val teardownOwner = captureTeardownOwner()
        activeGeneration = 0L
        operationDepth += 1
        try {
            scheduleTeardown(teardownOwner)
        } finally {
            operationDepth -= 1
        }
        if (rejectReentrantRetirement) {
            throw AuthenticationAttemptReentrantRetirementException()
        }
    }

    internal fun isActive(generation: Long): Boolean = synchronized(lock) {
        generation != 0L && activeGeneration == generation
    }

    internal fun runIfActive(generation: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (generation == 0L || activeGeneration != generation) return@synchronized false
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
        }
        true
    }

    /**
     * 原子地精确消费 [generation] 并运行其终态清理。因此 A 的延迟 transport 回调不能仅仅因为
     * B 复用了同一个 [ImClient] 就退役 B。
     */
    internal fun retireIfActive(generation: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (generation == 0L || activeGeneration != generation) return@synchronized false
        activeGeneration = 0L
        if (operationDepth > 0) throw AuthenticationAttemptReentrantRetirementException()
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
        }
        true
    }
}

internal class AuthenticationAttemptReentrantRetirementException :
    SessionBoundaryReentrantCloseException(
        "Authentication attempt cannot retire reentrantly from its admitted operation",
    )

/** 从提交一路携带到 AUTH 响应的、带代际资格的不透明能力凭证。 */
internal class AuthenticationAttemptLease(
    private val admission: AuthenticationAttemptAdmission,
    private val generation: Long,
) {
    fun isActive(): Boolean = admission.isActive(generation)

    fun runIfActive(block: () -> Unit): Boolean = admission.runIfActive(generation, block)

    fun retireIfActive(block: () -> Unit): Boolean = admission.retireIfActive(generation, block)
}
