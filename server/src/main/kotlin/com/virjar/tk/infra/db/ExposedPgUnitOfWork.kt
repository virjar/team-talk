package com.virjar.tk.infra.db

import com.virjar.tk.domain.event.requireNotifyContract
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgWriteScope
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

enum class PgUnitOfWorkStage {
    BEFORE_EVENT_FLUSH,
    AFTER_EVENT_FLUSH_BEFORE_COMMIT,
    AFTER_COMMIT_BEFORE_CALLBACKS,
}

/** Test seam for deterministic rollback/crash-window coverage; production uses [None]. */
fun interface PgUnitOfWorkHooks {
    suspend fun hit(stage: PgUnitOfWorkStage)

    object None : PgUnitOfWorkHooks {
        override suspend fun hit(stage: PgUnitOfWorkStage) = Unit
    }
}

/**
 * Exposed implementation of the aggregate command boundary.
 *
 * The domain block runs first. Event intents are flushed afterwards, acquiring `sync_streams`
 * rows in lexical uid order as the transaction's final locks. A stream lock therefore serializes
 * sequence allocation and commit order without exposing Exposed to the domain API. A command that
 * is already cancelled is rejected before admission; once admitted, transaction, local visibility
 * callbacks and dispatcher signalling form one non-cancellable terminal stage.
 */
class ExposedPgUnitOfWork(
    private val onEventsCommitted: (Set<String>) -> Unit,
    private val hooks: PgUnitOfWorkHooks = PgUnitOfWorkHooks.None,
    private val clock: () -> Long = System::currentTimeMillis,
) : PgUnitOfWork {
    private val logger = LoggerFactory.getLogger(ExposedPgUnitOfWork::class.java)

    override suspend fun <T> write(block: suspend PgWriteScope.() -> T): T {
        check(coroutineContext[ActivePgUnitOfWorkKey] == null) {
            "Nested PgUnitOfWork is forbidden; append the event through the active PgWriteScope"
        }
        // Reject work that was already cancelled, then treat an admitted aggregate command as one
        // terminal stage. In particular, cancellation must not land after PostgreSQL commits but
        // before cache invalidation and dispatcher wake become visible in this process.
        coroutineContext.ensureActive()
        return withContext(NonCancellable + ActivePgUnitOfWorkElement) {
            executeWrite(block)
        }
    }

    private suspend fun <T> executeWrite(block: suspend PgWriteScope.() -> T): T {
        var committedUids: Set<String> = emptySet()
        var afterCommitActions: List<() -> Unit> = emptyList()

        val result = newSuspendedTransaction(coroutineContext + Dispatchers.IO) {
            // Re-running an arbitrary domain block can duplicate generated IDs and process-local work.
            // Callers retry the command through their own stable request/idempotency key instead.
            maxAttempts = 1
            val scope = ExposedPgWriteScope(this, clock)
            val value = scope.block()

            hooks.hit(PgUnitOfWorkStage.BEFORE_EVENT_FLUSH)
            committedUids = scope.flushEvents()
            hooks.hit(PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT)
            afterCommitActions = scope.afterCommitActions.toList()
            value
        }

        // This hook deliberately sits outside Exposed's transaction. Tests use it to model a
        // process exit after commit and before all process-local hints are published.
        hooks.hit(PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS)
        // Publish process-local state before waking live delivery. A durable event may cause the
        // client to immediately issue a read RPC; that read must not observe a pre-commit cache
        // snapshot after the matching event is already visible on the wire. Each action remains
        // best-effort, and dispatcher wake still runs if one cache callback fails (startup/periodic
        // scanning is the durable fallback for a lost wake).
        afterCommitActions.forEach { action ->
            runCatching(action)
                .onFailure { logger.warn("Post-commit callback failed; durable transaction remains committed", it) }
        }
        if (committedUids.isNotEmpty()) {
            runCatching { onEventsCommitted(committedUids) }
                .onFailure { logger.warn("Failed to wake sync dispatcher for uids={}", committedUids, it) }
        }
        return result
    }

    private object ActivePgUnitOfWorkKey : CoroutineContext.Key<ActivePgUnitOfWorkElement>
    private object ActivePgUnitOfWorkElement : AbstractCoroutineContextElement(ActivePgUnitOfWorkKey)

    private class ExposedPgWriteScope(
        exposedTransaction: Transaction,
        private val clock: () -> Long,
    ) : PgWriteScope {
        override val transaction: PgTransactionContext = ExposedPgTransactionContext(exposedTransaction)

        private data class EventIntent(
            val uid: String,
            val notifyType: NotifyType,
            val encoded: ByteArray,
            val dedupeKey: String?,
            val createdAt: Long,
        )

        private val eventIntents = mutableListOf<EventIntent>()
        val afterCommitActions = mutableListOf<() -> Unit>()

        override fun appendEvent(
            uid: String,
            notifyType: NotifyType,
            payload: IProto,
            dedupeKey: String?,
        ) {
            require(uid.isNotBlank()) { "Durable event uid must not be blank" }
            require(uid.length <= 36) { "Durable event uid exceeds 36 characters" }
            require(dedupeKey == null || dedupeKey.length <= 192) {
                "Durable event dedupeKey exceeds 192 characters"
            }
            requireNotifyContract(notifyType, payload)
            eventIntents += EventIntent(
                uid = uid,
                notifyType = notifyType,
                encoded = ProtoCodec.encode(payload).copyOf(),
                dedupeKey = dedupeKey,
                createdAt = clock(),
            )
        }

        override fun afterCommit(action: () -> Unit) {
            afterCommitActions += action
        }

        fun flushEvents(): Set<String> {
            if (eventIntents.isEmpty()) return emptySet()

            val intentsByUid = eventIntents.groupBy { it.uid }
            val sortedUids = intentsByUid.keys.sorted()
            sortedUids.forEach { uid ->
                SyncStreams.insertIgnore {
                    it[SyncStreams.uid] = uid
                    it[SyncStreams.lastSeq] = 0L
                }
            }

            // Force row locks in one deterministic order. Do not add domain SQL below this point.
            val streams = SyncStreams.selectAll()
                .where { SyncStreams.uid inList sortedUids }
                .orderBy(SyncStreams.uid to SortOrder.ASC)
                .forUpdate()
                .associate { row -> row[SyncStreams.uid] to row[SyncStreams.lastSeq] }
            check(streams.size == sortedUids.size) { "Failed to materialize every durable sync stream" }

            val committed = linkedSetOf<String>()
            sortedUids.forEach { uid ->
                var nextSeq = streams.getValue(uid)
                val seenDedupeKeys = mutableSetOf<String>()
                intentsByUid.getValue(uid).forEach intentLoop@{ intent ->
                    val dedupeKey = intent.dedupeKey
                    if (dedupeKey != null) {
                        if (!seenDedupeKeys.add(dedupeKey)) return@intentLoop
                        val alreadyPersisted = SyncEvents.selectAll()
                            .where {
                                (SyncEvents.uid eq uid) and
                                    (SyncEvents.dedupeKey eq dedupeKey)
                            }
                            .limit(1)
                            .any()
                        if (alreadyPersisted) return@intentLoop
                    }

                    nextSeq += 1L
                    SyncEvents.insert {
                        it[SyncEvents.uid] = uid
                        it[SyncEvents.streamSeq] = nextSeq
                        it[SyncEvents.eventType] = intent.notifyType.code
                        it[SyncEvents.payload] = intent.encoded
                        it[SyncEvents.dedupeKey] = intent.dedupeKey
                        it[SyncEvents.createdAt] = intent.createdAt
                        it[SyncEvents.dispatchedAt] = null
                        it[SyncEvents.dispatchAttempts] = 0
                        it[SyncEvents.nextAttemptAt] = intent.createdAt
                        it[SyncEvents.lastDispatchError] = null
                    }
                    committed += uid
                }
                if (nextSeq != streams.getValue(uid)) {
                    SyncStreams.update({ SyncStreams.uid eq uid }) {
                        it[SyncStreams.lastSeq] = nextSeq
                    }
                }
            }
            return committed
        }
    }
}

internal class ExposedPgTransactionContext(
    internal val exposedTransaction: Transaction,
) : PgTransactionContext

/** Resolve an opaque domain transaction handle without allowing a repository to open its own one. */
internal fun PgTransactionContext.requireExposedTransaction(): Transaction {
    val context = this as? ExposedPgTransactionContext
        ?: error("Repository mutation requires an Exposed PgUnitOfWork transaction")
    check(TransactionManager.currentOrNull() === context.exposedTransaction) {
        "Repository mutation escaped its active Exposed PgUnitOfWork transaction"
    }
    return context.exposedTransaction
}
