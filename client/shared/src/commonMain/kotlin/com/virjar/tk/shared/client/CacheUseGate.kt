package com.virjar.tk.shared.client

/**
 * LocalCache 与所有借用其 SQL driver 的 MessageWindow 的同步 owner 门禁。
 *
 * 公共源的 monitor 会在整个被准入的操作期间被持有。这给出一个固定的加锁顺序
 * （`CacheUseGate -> stateLock -> chatLock -> SQL transaction -> window lock`），使 close 等待
 * 已被准入的 driver 工作完成，并在任何后续使用触及 SQL 之前就拒绝它。
 */
internal class CacheUseGate {
    private val ownerLock = Any()
    @Volatile
    private var open = true
    /** 仅当该 monitor 当前的重入 owner 位于 [use] 内部时才非零。 */
    private var operationDepth = 0
    private var deferredRelease: (() -> Unit)? = null

    fun <T> use(block: () -> T): T = synchronized(ownerLock) {
        operationDepth += 1
        try {
            check(open) { "LocalCache is closed" }
            block()
        } finally {
            operationDepth -= 1
            releaseDeferredOwnerIfDrained()
        }
    }

    /**
     * 原子地丢弃在缓存退役之前被准入的工作的结果。
     *
     * 新业务调用走 [use]，关闭之后会响亮地失败。基于租约的异步结果走这条路径，因此与其响应竞争的
     * close 只是一个正常的过期结果（`false`），并且永远无法触达已释放的 SQL owner。
     */
    fun runIfOpen(block: () -> Boolean): Boolean = synchronized(ownerLock) {
        if (!open) return@synchronized false
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
            releaseDeferredOwnerIfDrained()
        }
    }

    /** 在所有已被准入的同步工作离开之后，恰好关闭准入一次。 */
    fun close(releaseOwner: () -> Unit): Boolean = synchronized(ownerLock) {
        if (!open) return@synchronized false
        open = false
        if (operationDepth > 0) {
            deferredRelease = releaseOwner
            throw CacheUseGateReentrantCloseException()
        }
        releaseOwner()
        true
    }

    val isOpen: Boolean
        get() = open

    private fun releaseDeferredOwnerIfDrained() {
        if (operationDepth != 0) return
        val releaseOwner = deferredRelease ?: return
        deferredRelease = null
        releaseOwner()
    }
}

internal class CacheUseGateReentrantCloseException : SessionBoundaryReentrantCloseException(
    "LocalCache cannot close reentrantly from an admitted operation",
)
