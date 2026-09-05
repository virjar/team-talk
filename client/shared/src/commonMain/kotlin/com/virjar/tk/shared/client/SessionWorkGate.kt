package com.virjar.tk.shared.client

/**
 * 会话拥有 worker 的同步发布边界。
 *
 * 代际租约防止被取消的协程通过之后的生命周期发布。[close] 是线性化点：它等待一个已准入的同步
 * 回调并拒绝所有之后的回调。挂起工作必须在挂起之前离开门禁，并在每次缓存变更或可观察发布之前
 * 用同一租约重新进入。
 */
internal class SessionWorkGate(private val ownerName: String) {
    private val lock = Any()
    private val owner = Any()
    private var generation = 1L
    private var open = true
    /** 否则 monitor 重入不可见，会让 close 在回调内部返回。 */
    private var operationDepth = 0

    internal class Lease internal constructor(
        internal val owner: Any,
        internal val generation: Long,
    )

    fun lease(): Lease = synchronized(lock) {
        check(open) { "$ownerName is stopped" }
        Lease(owner, generation)
    }

    fun requireActive(lease: Lease) = synchronized(lock) {
        check(isActiveLocked(lease)) { "$ownerName is stopped" }
    }

    fun <T> use(lease: Lease, block: () -> T): T = synchronized(lock) {
        check(isActiveLocked(lease)) { "$ownerName is stopped" }
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
        }
    }

    fun runIfActive(lease: Lease, block: () -> Unit): Boolean = synchronized(lock) {
        if (!isActiveLocked(lease)) return@synchronized false
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
        }
        true
    }

    fun close(): Boolean = synchronized(lock) {
        if (!open) return@synchronized false
        open = false
        check(generation < Long.MAX_VALUE) { "$ownerName generation exhausted" }
        generation += 1L
        if (operationDepth > 0) {
            throw SessionWorkGateReentrantCloseException(ownerName)
        }
        true
    }

    private fun isActiveLocked(lease: Lease): Boolean =
        open && lease.owner === owner && lease.generation == generation
}

/** 硬边界不能从它正在等待排空的回调内部成功返回。 */
internal open class SessionBoundaryReentrantCloseException(message: String) : IllegalStateException(message)

internal class SessionWorkGateReentrantCloseException(ownerName: String) : SessionBoundaryReentrantCloseException(
    "$ownerName cannot close reentrantly from an admitted callback",
)
