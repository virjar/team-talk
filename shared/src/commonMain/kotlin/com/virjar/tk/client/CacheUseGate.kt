package com.virjar.tk.client

/**
 * Synchronous owner gate for LocalCache and every MessageWindow borrowing its SQL driver.
 *
 * The common-source monitor is held for the complete admitted operation. That gives one fixed lock
 * order (`CacheUseGate -> stateLock -> chatLock -> SQL transaction -> window lock`), makes close
 * wait for already-admitted driver work, and rejects every later use before it can touch SQL.
 */
internal class CacheUseGate {
    private val ownerLock = Any()
    @Volatile
    private var open = true
    /** Non-zero only while this monitor's current re-entrant owner is inside [use]. */
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
     * Atomically drops a result from work which was admitted before cache retirement.
     *
     * New business calls use [use] and fail loudly after close. Lease-based asynchronous results
     * use this path so a close racing their response is a normal stale result (`false`) and can
     * never reach the released SQL owner.
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

    /** Closes admission exactly once after all admitted synchronous work has left. */
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
