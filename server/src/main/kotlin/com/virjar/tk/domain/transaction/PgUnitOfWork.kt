package com.virjar.tk.domain.transaction

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType

/**
 * PostgreSQL command boundary exposed to domain services without leaking Exposed types.
 *
 * Repository calls made by [block] must join the outer transaction. Durable event intents are
 * encoded while the command is running, then persisted only after [block] returns. This ordering
 * makes the per-user stream rows the final database locks acquired by a command.
 */
interface PgUnitOfWork {
    suspend fun <T> write(block: suspend PgWriteScope.() -> T): T
}

/**
 * Opaque handle proving that a repository mutation is enlisted in the active outer transaction.
 *
 * Domain code may pass this handle to a write repository, but cannot inspect or create it. The
 * infrastructure adapter owns the concrete database transaction and rejects handles from another
 * implementation. This keeps Exposed out of the domain while making accidental standalone writes
 * impossible at the type boundary.
 */
interface PgTransactionContext

interface PgWriteScope {
    val transaction: PgTransactionContext

    /** Append one durable event to the recipient's user-scoped stream at commit time. */
    fun appendEvent(
        uid: String,
        notifyType: NotifyType,
        payload: IProto,
        dedupeKey: String? = null,
    )

    /**
     * Register a process-local cache/invalidation callback.
     *
     * It runs only after the database commit succeeds and before the live dispatcher is signalled,
     * so an event-triggered read cannot observe an older process-local cache snapshot. The durable
     * dispatcher has its own startup scan, so crash recovery must not depend on the callback.
     */
    fun afterCommit(action: () -> Unit)
}
