package com.virjar.tk.client

/**
 * Synchronous publication boundary for session-owned workers.
 *
 * A generation lease prevents a cancelled coroutine from publishing through a later lifecycle.
 * [close] is a linearization point: it waits for an admitted synchronous callback and rejects all
 * later callbacks. Suspending work must leave the gate before suspension and re-enter with the
 * same lease before each cache mutation or observable publication.
 */
internal class SessionWorkGate(private val ownerName: String) {
    private val lock = Any()
    private val owner = Any()
    private var generation = 1L
    private var open = true
    /** Monitor reentrancy is otherwise invisible and would let close return inside a callback. */
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

/** A hard boundary cannot successfully return from inside the callback it is waiting to drain. */
internal open class SessionBoundaryReentrantCloseException(message: String) : IllegalStateException(message)

internal class SessionWorkGateReentrantCloseException(ownerName: String) : SessionBoundaryReentrantCloseException(
    "$ownerName cannot close reentrantly from an admitted callback",
)
