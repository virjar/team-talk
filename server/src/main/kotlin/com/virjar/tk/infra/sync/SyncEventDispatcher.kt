package com.virjar.tk.infra.sync

import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.protocol.payload.NotifyPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Single-instance live dispatcher for the durable per-user event log.
 *
 * `dispatched_at` records only completion of the process-local push attempt. Every reconnect still
 * replays all rows after its cursor, so an offline user does not need a retry. A failed attempt
 * leaves the earliest event pending and blocks later events for that uid until its backoff expires.
 */
class SyncEventDispatcher(
    private val sink: LiveEventSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scanIntervalMillis: Long = DEFAULT_SCAN_INTERVAL_MILLIS,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SyncEventDispatcher::class.java)
    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val signaledUids = ConcurrentHashMap.newKeySet<String>()
    private val deliveryGates = Array(DELIVERY_GATE_STRIPES) { Mutex() }
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val startupScanCompleted = CompletableDeferred<Unit>()

    init {
        require(scanIntervalMillis > 0L) { "scanIntervalMillis must be positive" }
    }

    private data class PendingEvent(
        val uid: String,
        val streamSeq: Long,
        val eventType: Int,
        val payload: ByteArray,
        val attempts: Int,
        val nextAttemptAt: Long,
    )

    fun start() {
        check(!closed.get()) { "SyncEventDispatcher is already closed" }
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // A process may exit after PostgreSQL commit and before the in-memory wake. The first
            // scan is therefore part of startup correctness, not optional maintenance.
            try {
                runDispatchPass(scanDatabase = true)
            } finally {
                startupScanCompleted.complete(Unit)
            }
            while (isActive) {
                val signaled = withTimeoutOrNull(scanIntervalMillis) { wake.receive() } != null
                runDispatchPass(scanDatabase = !signaled)
            }
        }
    }

    /** Wait until the mandatory startup database scan has finished. */
    suspend fun awaitStartupScan() {
        check(started.get()) { "SyncEventDispatcher has not started" }
        startupScanCompleted.await()
    }

    /** Safe to invoke before [start]; the uid remains queued for the startup worker. */
    fun signal(uids: Set<String>) {
        if (closed.get() || uids.isEmpty()) return
        signaledUids.addAll(uids)
        wake.trySend(Unit)
    }

    suspend fun <T> withDeliveryGate(uid: String, block: suspend () -> T): T =
        deliveryGate(uid).withLock { block() }

    suspend fun deliverTransient(uid: String, notify: NotifyPayload) {
        withDeliveryGate(uid) { sink.push(uid, notify) }
    }

    /**
     * Deliver pending rows for one uid in strict sequence order.
     *
     * This method is also the deterministic integration-test seam. It returns the number of rows
     * marked dispatched during this pass; a failed/not-yet-due head returns without touching later
     * rows.
     */
    suspend fun dispatchPendingForUid(uid: String): Int = withDeliveryGate(uid) {
        var delivered = 0
        while (delivered < MAX_EVENTS_PER_UID_PASS) {
            val pending = loadHead(uid) ?: break
            val now = clock()
            if (pending.nextAttemptAt > now) break

            val notify = NotifyPayload(
                eventId = pending.streamSeq,
                notifyType = pending.eventType,
                payload = pending.payload,
            )
            try {
                sink.push(uid, notify)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordFailure(pending, now, error)
                logger.warn(
                    "Live sync dispatch failed uid={} seq={} attempt={}",
                    uid,
                    pending.streamSeq,
                    pending.attempts + 1,
                    error,
                )
                break
            }

            markDispatched(pending, now)
            delivered += 1
        }
        if (delivered == MAX_EVENTS_PER_UID_PASS) signal(setOf(uid))
        delivered
    }

    /** Scan durable state directly; used at startup and by restart-focused integration tests. */
    suspend fun scanAndDispatchPending(): Int {
        val uids = pendingUids(clock())
        var delivered = 0
        for (uid in uids) delivered += dispatchPendingForUid(uid)
        return delivered
    }

    private suspend fun runDispatchPass(scanDatabase: Boolean) {
        val uids = linkedSetOf<String>()
        uids += drainSignaledUids()
        if (scanDatabase || uids.isEmpty()) {
            runCatching { pendingUids(clock()) }
                .onSuccess { uids += it }
                .onFailure { logger.warn("Failed to scan pending durable sync events", it) }
        }
        for (uid in uids.sorted()) {
            runCatching { dispatchPendingForUid(uid) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logger.warn("Failed durable sync dispatch pass for uid={}", uid, error)
                }
        }
    }

    private fun drainSignaledUids(): Set<String> = buildSet {
        signaledUids.forEach { uid ->
            if (signaledUids.remove(uid)) add(uid)
        }
    }

    private fun pendingUids(now: Long): List<String> = transaction {
        SyncEvents.selectAll()
            .where {
                SyncEvents.dispatchedAt.isNull() and
                    (SyncEvents.nextAttemptAt lessEq now)
            }
            .orderBy(SyncEvents.uid to SortOrder.ASC, SyncEvents.streamSeq to SortOrder.ASC)
            .map { it[SyncEvents.uid] }
            .distinct()
    }

    private fun loadHead(uid: String): PendingEvent? = transaction {
        SyncEvents.selectAll()
            .where {
                (SyncEvents.uid eq uid) and SyncEvents.dispatchedAt.isNull()
            }
            .orderBy(SyncEvents.streamSeq to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                PendingEvent(
                    uid = uid,
                    streamSeq = row[SyncEvents.streamSeq],
                    eventType = row[SyncEvents.eventType],
                    payload = row[SyncEvents.payload],
                    attempts = row[SyncEvents.dispatchAttempts],
                    nextAttemptAt = row[SyncEvents.nextAttemptAt],
                )
            }
    }

    private fun markDispatched(event: PendingEvent, now: Long) {
        transaction {
            SyncEvents.update({
                (SyncEvents.uid eq event.uid) and
                    (SyncEvents.streamSeq eq event.streamSeq) and
                    SyncEvents.dispatchedAt.isNull()
            }) {
                it[SyncEvents.dispatchedAt] = now
                it[SyncEvents.lastDispatchError] = null
            }
        }
    }

    private fun recordFailure(event: PendingEvent, now: Long, error: Throwable) {
        val attempts = event.attempts + 1
        val nextAttempt = saturatedAdd(now, retryDelayMillis(attempts))
        transaction {
            SyncEvents.update({
                (SyncEvents.uid eq event.uid) and
                    (SyncEvents.streamSeq eq event.streamSeq) and
                    SyncEvents.dispatchedAt.isNull()
            }) {
                it[SyncEvents.dispatchAttempts] = attempts
                it[SyncEvents.nextAttemptAt] = nextAttempt
                it[SyncEvents.lastDispatchError] = error::class.java.simpleName.take(500)
            }
        }
    }

    private fun deliveryGate(uid: String): Mutex =
        deliveryGates[(uid.hashCode() and Int.MAX_VALUE) % deliveryGates.size]

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking { lifecycle.cancelAndJoin() }
        wake.close()
        signaledUids.clear()
    }

    companion object {
        const val DEFAULT_SCAN_INTERVAL_MILLIS = 1_000L
        const val BASE_RETRY_MILLIS = 100L
        const val MAX_RETRY_MILLIS = 60_000L
        private const val MAX_EVENTS_PER_UID_PASS = 64
        private const val DELIVERY_GATE_STRIPES = 64

        internal fun retryDelayMillis(attempts: Int): Long {
            val shift = min((attempts - 1).coerceAtLeast(0), 16)
            return min(MAX_RETRY_MILLIS, BASE_RETRY_MILLIS * (1L shl shift))
        }

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
