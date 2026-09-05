package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.protocol.payload.NotifyPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * 持久每用户事件日志的单实例实时分发器。
 *
 * `dispatched_at` 只记录进程本地推送尝试的完成。每次重连仍会
 * 重放其游标之后的所有行，因此离线用户不需要重试。失败的尝试
 * 让最早的事件保持待处理，并阻塞该 uid 之后的事件，直到其退避过期。
 */
class SyncEventDispatcher(
    private val database: Database,
    private val sink: LiveEventSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scanIntervalMillis: Long = DEFAULT_SCAN_INTERVAL_MILLIS,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(SyncEventDispatcher::class.java)
    private val signaledUids = DurableUidSignalMailbox(MAX_SIGNALLED_UIDS)
    private val pendingUidScanner = PendingSyncUidScanner(
        repository = ExposedPendingSyncUidPageRepository(database),
        pageSize = MAX_PENDING_UIDS_PER_SCAN_PAGE,
    )
    private val deliveryGates = Array(DELIVERY_GATE_STRIPES) { Mutex() }
    private val runtime = SyncEventDispatcherRuntime(
        workerDispatcher = Dispatchers.IO,
        scanIntervalMillis = scanIntervalMillis,
        runPass = ::runDispatchPass,
    )
    private var nextDropReportAt = 1L
    private var lastReportedDropCount = 0L
    /** 在从第一个 uid 开始的扫描循环覆盖此溢出版本之前，保持非 null。 */
    private var activeOverflowRecoveryVersion: Long? = null

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
        runtime.start()
    }

    /**
     * 等待强制的启动数据库扫描成功。
     *
     * 失败的扫描以原始失败异常地完成此 await。调用方拥有的
     * 取消也会传播，而不会取消分发器 worker。
     */
    suspend fun awaitStartupScan() {
        runtime.awaitStartupScan()
    }

    /** 公共安全的活跃/就绪状态。它刻意不包含任何内部异常。 */
    fun snapshot(): SyncEventDispatcherSnapshot = runtime.snapshot()

    /** 在 [start] 之前调用也是安全的；uid 会为启动 worker 保持排队。 */
    fun signal(uids: Set<String>) {
        if (!runtime.acceptsSignals() || uids.isEmpty()) return
        signaledUids.offerAll(uids)
        runtime.requestPass()
    }

    suspend fun <T> withDeliveryGate(uid: String, block: suspend () -> T): T =
        deliveryGate(uid).withLock { block() }

    suspend fun deliverTransient(uid: String, notify: NotifyPayload) {
        withDeliveryGate(uid) { sink.push(uid, notify) }
    }

    /**
     * 按严格序号顺序投递一个 uid 的待处理行。
     *
     * 此方法也是确定性集成测试缝隙。它返回本轮
     * 被标记为已分发的行数；失败/未到期的头会返回，而不触碰之后的
     * 行。
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
            } catch (error: Exception) {
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

    private suspend fun runDispatchPass(
        scanDatabase: Boolean,
        requireScanSuccess: Boolean,
    ): SyncEventDispatchPassResult {
        val uids = linkedSetOf<String>()
        val signals = signaledUids.drain()
        uids += signals.uids

        // 恢复循环从第一个 uid 开始，并保留观察到的溢出版本
        // 直到每个 keyset 页都被访问。更新的溢出版本保持未确认，
        // 并触发新循环，而不是反复重置此循环并饿死其尾部。
        if (signals.overflowed && activeOverflowRecoveryVersion == null) {
            activeOverflowRecoveryVersion = signals.overflowVersion
            pendingUidScanner.restartCycle()
        }

        var scanPage: PendingSyncUidPage? = null
        var scanFailed = false
        if (scanDatabase || uids.isEmpty() || signals.overflowed || activeOverflowRecoveryVersion != null) {
            if (requireScanSuccess) {
                scanPage = pendingUidScanner.loadNextPage(clock())
                uids += scanPage.uids
            } else {
                try {
                    scanPage = pendingUidScanner.loadNextPage(clock())
                    uids += scanPage.uids
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    scanFailed = true
                    logger.warn("Failed to scan pending durable sync events", error)
                }
            }
        }
        reportSignalDropsIfNeeded()
        dispatchDurableUidBatch(
            uids = uids.sorted(),
            requireSuccess = requireScanSuccess,
            dispatch = { uid -> dispatchPendingForUid(uid) },
            onFailure = { uid, error ->
                logger.warn("Failed durable sync dispatch pass for uid={}", uid, error)
            },
        )

        val completedOverflowVersion = activeOverflowRecoveryVersion
            ?.takeIf { scanPage?.cycleCompleted == true }
        if (completedOverflowVersion != null) {
            signaledUids.acknowledgeOverflowRecovery(completedOverflowVersion)
            activeOverflowRecoveryVersion = null
        }

        val mandatoryScanIncomplete = requireScanSuccess && scanPage?.cycleCompleted != true
        val newerOverflowObserved = completedOverflowVersion != null &&
            signals.overflowVersion > completedOverflowVersion
        val recoveryIncomplete = activeOverflowRecoveryVersion != null || newerOverflowObserved
        return if (!scanFailed && (mandatoryScanIncomplete || recoveryIncomplete)) {
            SyncEventDispatchPassResult.MORE_REQUIRED
        } else {
            SyncEventDispatchPassResult.COMPLETE
        }
    }

    private fun reportSignalDropsIfNeeded() {
        val totalDropped = signaledUids.droppedCount
        if (totalDropped < nextDropReportAt || totalDropped == lastReportedDropCount) return
        logger.warn(
            "Durable sync signal mailbox saturated at {} unique uids; total dropped signals={}; " +
                "database scan recovery remains active",
            signaledUids.capacity,
            totalDropped,
        )
        lastReportedDropCount = totalDropped
        nextDropReportAt = nextSyncDropReportThreshold(totalDropped)
    }

    private fun loadHead(uid: String): PendingEvent? = transaction(database) {
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
        transaction(database) {
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
        transaction(database) {
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
        try {
            runtime.close()
        } finally {
            signaledUids.close()
        }
    }

    companion object {
        const val DEFAULT_SCAN_INTERVAL_MILLIS = 1_000L
        const val BASE_RETRY_MILLIS = 100L
        const val MAX_RETRY_MILLIS = 60_000L
        private const val MAX_EVENTS_PER_UID_PASS = 64
        private const val DELIVERY_GATE_STRIPES = 64
        private const val MAX_SIGNALLED_UIDS = 4_096
        private const val MAX_PENDING_UIDS_PER_SCAN_PAGE = 256

        internal fun retryDelayMillis(attempts: Int): Long {
            val shift = min((attempts - 1).coerceAtLeast(0), 16)
            return min(MAX_RETRY_MILLIS, BASE_RETRY_MILLIS * (1L shl shift))
        }

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

/**
 * 启动准入与稳态重试刻意具有不同的失败语义。
 *
 * 强制的第一遍必须证明每个发现的持久 uid 都能被读取并推进；
 * 否则发布 READY 会掩盖一个损坏的 PostgreSQL 加载/标记边界。之后的维护
 * pass 保持尽力而为，因为持久行仍是重试的事实来源。
 */
internal suspend fun dispatchDurableUidBatch(
    uids: Iterable<String>,
    requireSuccess: Boolean,
    dispatch: suspend (String) -> Unit,
    onFailure: (String, Exception) -> Unit,
) {
    for (uid in uids) {
        try {
            dispatch(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (requireSuccess) throw error
            onFailure(uid, error)
        }
    }
}

internal data class DurableUidSignalBatch(
    val uids: Set<String>,
    val overflowed: Boolean,
    internal val overflowVersion: Long = 0L,
)

/**
 * 已提交到 PostgreSQL 的行的有界进程提示。
 *
 * 溢出只可能丢弃内存提示，绝不会丢弃持久事件。[drain] 最多转移
 * [capacity] 个唯一 uid，并报告分发器是否必须通过扫描数据库来恢复遗漏。
 */
internal class DurableUidSignalMailbox(internal val capacity: Int) {
    private val lock = Any()
    private val pending = linkedSetOf<String>()
    private var overflowVersion = 0L
    private var recoveredOverflowVersion = 0L
    private var closed = false
    private var dropped = 0L

    init {
        require(capacity > 0) { "Durable uid signal capacity must be positive" }
    }

    val pendingCount: Int get() = synchronized(lock) { pending.size }
    val droppedCount: Long get() = synchronized(lock) { dropped }

    fun offerAll(uids: Set<String>) = synchronized(lock) {
        if (closed) return@synchronized
        uids.forEach { uid ->
            require(uid.isNotBlank()) { "Durable sync uid must not be blank" }
            if (uid in pending) return@forEach
            if (pending.size < capacity) {
                pending += uid
            } else {
                if (overflowVersion < Long.MAX_VALUE) overflowVersion += 1L
                if (dropped < Long.MAX_VALUE) dropped += 1L
            }
        }
    }

    fun drain(): DurableUidSignalBatch = synchronized(lock) {
        val recoveryRequired = overflowVersion > recoveredOverflowVersion
        if (pending.isEmpty() && !recoveryRequired) {
            return@synchronized DurableUidSignalBatch(emptySet(), false)
        }
        DurableUidSignalBatch(pending.toSet(), recoveryRequired, overflowVersion).also {
            pending.clear()
        }
    }

    /** 失败/更旧的数据库扫描不能清除更新的溢出义务。 */
    fun acknowledgeOverflowRecovery(observedVersion: Long) = synchronized(lock) {
        if (observedVersion > recoveredOverflowVersion) {
            recoveredOverflowVersion = minOf(observedVersion, overflowVersion)
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        pending.clear()
        recoveredOverflowVersion = overflowVersion
    }
}

internal fun nextSyncDropReportThreshold(observed: Long): Long {
    require(observed > 0L) { "Observed durable signal drop count must be positive" }
    val highest = java.lang.Long.highestOneBit(observed)
    return if (highest > Long.MAX_VALUE / 2L) Long.MAX_VALUE else highest shl 1
}
