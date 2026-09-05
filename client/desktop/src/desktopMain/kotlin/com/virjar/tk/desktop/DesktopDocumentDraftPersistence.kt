package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPersistence
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadRetryableException
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadStatus
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecordSource
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_MANIFEST_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORD_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORDS
import com.virjar.tk.app.navigation.feature.document.MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class DesktopDocumentDraftStorageReadStatus { ABSENT, AVAILABLE }

/** 由 storage 持有的视图，仅在 [DesktopDocumentDraftStorage.read] 期间有效。 */
internal interface DesktopDocumentDraftStoredRecordSource {
    val manifest: String
    val tombstones: Set<String>
    fun recordByteCount(key: String): Long?
    fun readRecord(key: String): String?
}

/** 同步私有文件边界，在测试中注入以让崩溃顺序变得确定。 */
internal interface DesktopDocumentDraftStorage {
    /** 相同的 identity 共享同一个进程级 writer 栅栏与 I/O 线性化点。 */
    val coordinationIdentity: Any

    fun read(
        limits: DesktopDocumentDraftLimits,
        consume: (DesktopDocumentDraftStoredRecordSource) -> Unit,
    ): DesktopDocumentDraftStorageReadStatus

    /** 安装记录、发布 manifest，然后且仅然后才压缩墓碑。 */
    fun replace(payload: DocumentDraftPayload, limits: DesktopDocumentDraftLimits)
    fun tombstone(recoveryKeys: Set<String>, limits: DesktopDocumentDraftLimits)
    fun delete(limits: DesktopDocumentDraftLimits)
}

internal data class DesktopDocumentDraftLimits(
    val maxManifestBytes: Long,
    val maxRecordBytes: Long,
    val maxTotalRecordBytes: Long,
    val maxRecords: Int,
    val maxTombstones: Int,
)

/**
 * 面向会话的 Desktop 文档工作台恢复记录持久化。
 *
 * UI 线程只会替换一个受限的待处理 payload 闭包。单个守护 writer 逐条编码并安装记录，
 * 从不构造聚合的 JSON 主体或字节数组。每个存储 namespace 还拥有一个进程级的单调租约。
 * 构造后继会话会栅栏隔离旧的超时 worker，而它们物理层面的发布仍然保持串行。
 * 协调器在发布前恰好保留最新被接纳的 payload 闭包，因此后继恢复可以在接受新编辑之前
 * 持久地接管旧会话的热快照。其 namespace 与孤儿 payload 上限均采取失败关闭（fail closed）策略。
 * 分离的 writer 与处置引用让 epoch 保持注册，直到 executor 已终止且 owner 已封存最终的
 * 保留/丢弃决定。
 */
internal class DesktopDocumentDraftPersistence internal constructor(
    private val storage: DesktopDocumentDraftStorage,
    private val ownerKey: DocumentDraftOwnerKey,
    maxManifestBytes: Long = MAX_MANIFEST_BYTES,
    maxRecordBytes: Long = MAX_RECORD_BYTES,
    maxTotalRecordBytes: Long = MAX_TOTAL_RECORD_BYTES,
    flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS,
    leaseSource: DesktopDocumentDraftLeaseSource = DesktopDocumentDraftLeaseCoordinator,
) : DocumentDraftPersistence {
    constructor(
        dataDir: File,
        ownerKey: DocumentDraftOwnerKey,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
        maxRecordBytes: Long = MAX_RECORD_BYTES,
        maxTotalRecordBytes: Long = MAX_TOTAL_RECORD_BYTES,
    ) : this(
        storage = desktopDocumentDraftStorage(dataDir, ownerKey),
        ownerKey = ownerKey,
        maxManifestBytes = maxManifestBytes,
        maxRecordBytes = maxRecordBytes,
        maxTotalRecordBytes = maxTotalRecordBytes,
    )

    private val limits = validatedDesktopDocumentDraftLimits(
        maxManifestBytes = maxManifestBytes,
        maxRecordBytes = maxRecordBytes,
        maxTotalRecordBytes = maxTotalRecordBytes,
        maxRecords = MAX_RECORDS,
        maxTombstones = MAX_TOMBSTONES,
    )
    private val flushTimeoutNanos = desktopDraftFlushTimeoutNanos(flushTimeoutMillis)
    private val lease = leaseSource.acquire(storage.coordinationIdentity)
    private val admissionLock = Any()
    private val stateLock = ReentrantLock()
    private val stateChanged = stateLock.newCondition()
    private val executor = object : ThreadPoolExecutor(
        0,
        1,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        ThreadFactory { task ->
            Thread(task, "desktop-document-draft-io").apply { isDaemon = true }
        },
    ) {
        override fun terminated() {
            try {
                lease.releaseWriter()
            } finally {
                super.terminated()
            }
        }
    }

    private var nextGeneration = 0L
    private var settledGeneration = 0L
    private var pendingWrite: PendingWrite? = null
    private var drainScheduled = false
    private var retired = false
    private var terminalFailure: TerminalFailure? = null

    override fun read(
        ownerKey: DocumentDraftOwnerKey,
        consume: (DocumentDraftRecordSource) -> Unit,
    ): DocumentDraftReadStatus = withOwner(ownerKey) {
        synchronized(admissionLock) {
            val targetGeneration = stateLock.withLock {
                if (retired) return@withOwner DocumentDraftReadStatus.ABSENT
                nextGeneration
            }
            if (!awaitGeneration(targetGeneration)) {
                return@withOwner DocumentDraftReadStatus.RETRYABLE
            }

            var consumed = 0
            val result = try {
                useCurrentLease {
                    val drain = persistLatestProcessPending()
                    clearPublishedWriteFailure(drain.latestPublishedSequence)
                    drain.latestUnrecoveredRejectedSequence?.let { rejectedSequence ->
                        recordRejectedProcessPending(rejectedSequence)
                        throw DesktopDocumentDraftRejectedException(
                            "Process-owned document draft payload was rejected",
                        )
                    }
                    storage.read(limits) { storedSource ->
                        check(++consumed == 1) { "Desktop draft storage exposed multiple sources" }
                        val source = GuardedDocumentDraftRecordSource(storedSource)
                        try {
                            consume(source)
                        } catch (failure: Throwable) {
                            throw DocumentDraftConsumerFailure(failure)
                        } finally {
                            source.invalidate()
                        }
                    }
                }
            } catch (consumerFailure: DocumentDraftConsumerFailure) {
                throw consumerFailure.original
            } catch (_: Exception) {
                return@withOwner DocumentDraftReadStatus.RETRYABLE
            }
            when (result.state) {
                DesktopLeaseUseState.UNAVAILABLE,
                DesktopLeaseUseState.STALE,
                -> DocumentDraftReadStatus.RETRYABLE

                DesktopLeaseUseState.COMPLETED -> when (result.value) {
                    DesktopDocumentDraftStorageReadStatus.ABSENT -> if (consumed == 0) {
                        DocumentDraftReadStatus.ABSENT
                    } else {
                        DocumentDraftReadStatus.RETRYABLE
                    }

                    DesktopDocumentDraftStorageReadStatus.AVAILABLE -> if (consumed == 1) {
                        DocumentDraftReadStatus.AVAILABLE
                    } else {
                        DocumentDraftReadStatus.RETRYABLE
                    }

                    null -> DocumentDraftReadStatus.RETRYABLE
                }
            }
        }
    }

    override fun write(
        ownerKey: DocumentDraftOwnerKey,
        payload: () -> DocumentDraftPayload,
    ): Boolean = withOwner(ownerKey) {
        synchronized(admissionLock) {
            stateLock.withLock {
                if (retired || !lease.isCurrent()) return@withLock false
                val processPending = lease.admit(payload) ?: return@withLock false
                val generation = nextGenerationLocked()
                pendingWrite = PendingWrite(generation, processPending.sequence)
                scheduleDrainLocked()
            }
        }
    }

    /** 只等待在此调用之前被接纳的变更；更晚的写入不会延长该屏障。 */
    override fun flush(): Boolean = synchronized(admissionLock) {
        flushThroughGeneration(stateLock.withLock { nextGeneration })
    }

    override fun tombstone(
        ownerKey: DocumentDraftOwnerKey,
        recoveryKeys: Set<String>,
    ): Boolean = withOwner(ownerKey) {
        if (recoveryKeys.isEmpty()) return@withOwner true
        if (recoveryKeys.size > limits.maxRecords ||
            recoveryKeys.any { !it.matches(DOCUMENT_DRAFT_RECORD_KEY) }
        ) return@withOwner false
        performTombstone(recoveryKeys.toSet())
    }

    override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean = withOwner(ownerKey) {
        performControl(retire = false) { storage.delete(limits) }
    }

    /** 一个持久化实例恰好拥有一个认证会话的 namespace。 */
    override fun clearAll(): Boolean = performControl(retire = false) { storage.delete(limits) }

    /** 持久地排空本会话，然后拒绝所有过期的组合写入，但不删除数据。 */
    fun retirePreservingDraft(): Boolean = synchronized(admissionLock) {
        val targetGeneration = stateLock.withLock {
            retired = true
            nextGeneration
        }
        try {
            flushThroughGeneration(targetGeneration)
        } finally {
            executor.shutdown()
        }
    }

    /** 单调的丢弃边沿，独立于 AppDataState 的先到先赢销毁门。 */
    fun retireAndDelete(): Boolean = try {
        performControl(retire = true) { storage.delete(limits) }
    } finally {
        lease.releaseDisposition()
    }

    /**
     * 在所有可能的 reason 升级都已送达后，封存一次仅保留（preserve-only）的退役。
     * 租约保持注册直到 writer 也终止，因此进行中的 worker 绝不会脱离
     * 用于栅栏其发布的 epoch 与 I/O 锁。
     */
    fun sealPreservedDraft() {
        check(stateLock.withLock { retired }) {
            "Document draft persistence must retire before its preserve result is sealed"
        }
        try {
            executor.shutdown()
        } finally {
            lease.releaseDisposition()
        }
    }

    private fun performControl(retire: Boolean, mutation: () -> Unit): Boolean {
        if (!retire) {
            return synchronized(admissionLock) {
                finishControl(
                    controlGeneration = beginControlLocked(retire = false),
                    retire = false,
                    mutation = mutation,
                )
            }
        }

        // 一旦退役门与进程级丢弃边沿发布，后续写入必须立即被拒绝。可能阻塞的文件系统等待
        // 不再需要准入串行化，因为已退役的实例不可能再接纳任何 payload。
        val controlGeneration = synchronized(admissionLock) {
            beginControlLocked(retire = true)
        }
        return finishControl(
            controlGeneration = controlGeneration,
            retire = true,
            mutation = mutation,
        )
    }

    /** 必须在持有 [admissionLock] 时调用。 */
    private fun beginControlLocked(retire: Boolean): Long {
        val controlGeneration = stateLock.withLock {
            if (retire) retired = true
            pendingWrite = null
            nextGenerationLocked()
        }
        // 进程内丢弃边沿不能依赖文件系统可用性。已经持有 ioLock 的 worker 仍被排序在下面的 delete 之前；
        // 其他所有 worker 都会看到已丢弃的 pending 序列或更新的 epoch，因此在此之后无法再发布。
        if (lease.isCurrent()) lease.discardPendingThroughEpoch()
        return controlGeneration
    }

    private fun finishControl(
        controlGeneration: Long,
        retire: Boolean,
        mutation: () -> Unit,
    ): Boolean {
        val leaseResult = try {
            useCurrentLease(action = mutation)
        } catch (failure: Throwable) {
            settleControl(controlGeneration, failure)
            if (retire) executor.shutdown()
            if (failure !is Exception) throw failure
            return false
        }
        val failure = when (leaseResult.state) {
            DesktopLeaseUseState.COMPLETED,
            // 后继实例拥有此 namespace。过期会话的丢弃是安全的空操作，绝不能删除后继实例的数据。
            DesktopLeaseUseState.STALE,
            -> null

            DesktopLeaseUseState.UNAVAILABLE -> IllegalStateException(
                "Timed out waiting for document draft storage",
            )
        }
        settleControl(controlGeneration, failure)
        if (retire) executor.shutdown()
        return failure == null
    }

    /**
     * 取消先持久生效，然后才发布取消前的热快照。该快照保留无关标签页的编辑，
     * 而其仍然活跃的已取消 key 让墓碑保持失败关闭，直到 UI 接纳取消后的快照。
     */
    private fun performTombstone(recoveryKeys: Set<String>): Boolean =
        synchronized(admissionLock) {
            val unrecoverableWriteFailure = stateLock.withLock {
                val failure = terminalFailure
                failure != null && failure.kind == TerminalFailureKind.WRITE &&
                    failure.processSequence?.let { lease.pending(it) == null } != false
            }
            if (unrecoverableWriteFailure) return@synchronized false

            val controlGeneration = stateLock.withLock {
                pendingWrite = null
                nextGenerationLocked()
            }
            var drain = ProcessPendingDrain(
                latestPublishedSequence = null,
                latestRejectedSequence = null,
            )
            var tombstoneDurable = false
            val leaseResult = try {
                useCurrentLease {
                    storage.tombstone(recoveryKeys, limits)
                    tombstoneDurable = true
                    drain = persistLatestProcessPending(completeRejected = false)
                    drain.latestUnrecoveredRejectedSequence?.let {
                        throw DesktopDocumentDraftRejectedException(
                            "Document draft payload could not be preserved before cancellation",
                        )
                    }
                }
            } catch (failure: Throwable) {
                settleControl(
                    generation = controlGeneration,
                    failure = failure,
                    failureKind = if (tombstoneDurable) {
                        TerminalFailureKind.WRITE
                    } else {
                        TerminalFailureKind.CONTROL
                    },
                    processSequence = lease.latestPending()?.sequence,
                )
                if (failure !is Exception) throw failure
                // 恢复账本是单调的。一旦 storage.tombstone 返回，即使在发布无关热快照失败之后，
                // 在同一 key 下重放一个活跃标签页也不再安全；flush 会持续暴露那次次级失败。
                return@synchronized tombstoneDurable
            }
            val failure = when (leaseResult.state) {
                DesktopLeaseUseState.COMPLETED -> null
                DesktopLeaseUseState.STALE -> IllegalStateException(
                    "Document draft cancellation belongs to a stale session",
                )
                DesktopLeaseUseState.UNAVAILABLE -> IllegalStateException(
                    "Timed out waiting for document draft storage",
                )
            }
            if (failure == null) clearPublishedWriteFailure(drain.latestPublishedSequence)
            settleControl(controlGeneration, failure)
            failure == null
        }

    private fun settleControl(
        generation: Long,
        failure: Throwable?,
        failureKind: TerminalFailureKind = TerminalFailureKind.CONTROL,
        processSequence: Long? = null,
    ) = stateLock.withLock {
        settledGeneration = maxOf(settledGeneration, generation)
        if (failure == null) {
            if (terminalFailure?.generation?.let { it <= generation } == true) {
                terminalFailure = null
            }
        } else {
            terminalFailure = TerminalFailure(
                generation = generation,
                cause = failure,
                kind = failureKind,
                processSequence = processSequence,
            )
        }
        stateChanged.signalAll()
    }

    /** 必须在持有 [stateLock] 时调用。 */
    private fun scheduleDrainLocked(): Boolean {
        if (drainScheduled) return true
        drainScheduled = true
        return try {
            executor.execute { drainWrites() }
            true
        } catch (failure: RejectedExecutionException) {
            drainScheduled = false
            val rejectedPending = pendingWrite
            pendingWrite = null
            terminalFailure = TerminalFailure(
                generation = nextGeneration,
                cause = failure,
                kind = TerminalFailureKind.WRITE,
                processSequence = rejectedPending?.processSequence,
            )
            settledGeneration = maxOf(settledGeneration, nextGeneration)
            stateChanged.signalAll()
            false
        }
    }

    private fun drainWrites() {
        var fatalFailure: Throwable? = null
        while (true) {
            val pending = stateLock.withLock {
                val next = pendingWrite
                if (next == null) {
                    drainScheduled = false
                    stateChanged.signalAll()
                    null
                } else {
                    pendingWrite = null
                    next
                }
            } ?: break

            val failure = persistPendingWrite(pending)
            stateLock.withLock {
                settledGeneration = maxOf(settledGeneration, pending.generation)
                if (failure != null && pending.generation == nextGeneration) {
                    if (failure is DesktopDocumentDraftRejectedException) {
                        lease.complete(pending.processSequence)
                    }
                    terminalFailure = TerminalFailure(
                        generation = pending.generation,
                        cause = failure,
                        kind = TerminalFailureKind.WRITE,
                        processSequence = pending.processSequence,
                    )
                } else if (
                    terminalFailure?.kind == TerminalFailureKind.WRITE &&
                    terminalFailure?.generation?.let { it <= pending.generation } == true
                ) {
                    terminalFailure = null
                }
                stateChanged.signalAll()
            }
            if (failure != null && failure !is Exception) fatalFailure = failure
        }
        fatalFailure?.let { throw it }
    }

    private fun persistPendingWrite(pending: PendingWrite): Throwable? {
        if (!isCurrentGeneration(pending.generation)) return null
        return try {
            val result = useCurrentLease(DesktopDraftLeaseAuthority.WRITER) {
                val processPending = lease.pending(pending.processSequence)
                    ?: return@useCurrentLease
                val payload = try {
                    processPending.payload()
                } catch (rejected: IllegalArgumentException) {
                    throw DesktopDocumentDraftRejectedException(
                        "Document draft payload was rejected before storage",
                        rejected,
                    )
                }
                if (isCurrentGeneration(pending.generation) &&
                    lease.pending(pending.processSequence) != null
                ) {
                    try {
                        storage.replace(payload, limits)
                    } catch (rejected: DesktopDocumentDraftRejectedException) {
                        throw rejected
                    }
                    lease.complete(pending.processSequence)
                }
            }
            when (result.state) {
                DesktopLeaseUseState.COMPLETED,
                -> null

                DesktopLeaseUseState.STALE -> if (lease.pending(pending.processSequence) == null) {
                    null
                } else {
                    DesktopDocumentDraftHandoffPendingException(pending.processSequence)
                }

                DesktopLeaseUseState.UNAVAILABLE -> IllegalStateException(
                    "Timed out waiting for document draft storage",
                )
            }
        } catch (failure: Throwable) {
            failure.takeIf { failure !is Exception || isCurrentGeneration(pending.generation) }
        }
    }

    /** 后继实例在暴露磁盘状态之前，先恢复最后的进程持有热快照。 */
    private fun persistLatestProcessPending(
        completeRejected: Boolean = true,
    ): ProcessPendingDrain {
        var latestPublishedSequence: Long? = null
        var latestRejectedSequence: Long? = null
        while (true) {
            val pending = lease.latestPending() ?: return ProcessPendingDrain(
                latestPublishedSequence = latestPublishedSequence,
                latestRejectedSequence = latestRejectedSequence,
            )
            val payload = try {
                pending.payload()
            } catch (_: IllegalArgumentException) {
                latestRejectedSequence = pending.sequence
                if (!completeRejected) return ProcessPendingDrain(
                    latestPublishedSequence = latestPublishedSequence,
                    latestRejectedSequence = latestRejectedSequence,
                )
                lease.complete(pending.sequence)
                continue
            }
            if (lease.pending(pending.sequence) == null) continue
            try {
                storage.replace(payload, limits)
            } catch (_: DesktopDocumentDraftRejectedException) {
                latestRejectedSequence = pending.sequence
                if (!completeRejected) return ProcessPendingDrain(
                    latestPublishedSequence = latestPublishedSequence,
                    latestRejectedSequence = latestRejectedSequence,
                )
                lease.complete(pending.sequence)
                continue
            }
            lease.complete(pending.sequence)
            latestPublishedSequence = pending.sequence
        }
    }

    /**
     * flush 同时也是前驱实例进程持有热快照的持久性屏障。
     * 持有 [admissionLock] 可以防止本实例不断延长自己的屏障。
     */
    private fun flushThroughGeneration(targetGeneration: Long): Boolean {
        if (!awaitGeneration(targetGeneration)) return false
        val leaseResult = try {
            useCurrentLease(DesktopDraftLeaseAuthority.WRITER) { persistLatestProcessPending() }
        } catch (_: Exception) {
            return false
        }
        val drain = when (leaseResult.state) {
            DesktopLeaseUseState.COMPLETED -> leaseResult.value ?: return false
            DesktopLeaseUseState.STALE -> {
                if (lease.latestPending() != null) return false
                clearCompletedHandoffFailure()
                ProcessPendingDrain(latestPublishedSequence = null, latestRejectedSequence = null)
            }
            DesktopLeaseUseState.UNAVAILABLE -> return false
        }
        clearPublishedWriteFailure(drain.latestPublishedSequence)
        drain.latestUnrecoveredRejectedSequence?.let { rejectedSequence ->
            recordRejectedProcessPending(rejectedSequence)
            return false
        }
        return stateLock.withLock {
            terminalFailure?.let { it.generation <= targetGeneration } != true
        }
    }

    private fun clearPublishedWriteFailure(latestPublishedSequence: Long?) {
        if (latestPublishedSequence == null) return
        stateLock.withLock {
            val failure = terminalFailure
            if (failure?.kind == TerminalFailureKind.WRITE &&
                failure.processSequence?.let { it <= latestPublishedSequence } == true
            ) {
                terminalFailure = null
                stateChanged.signalAll()
            }
        }
    }

    private fun recordRejectedProcessPending(rejectedSequence: Long) = stateLock.withLock {
        if (terminalFailure?.kind != TerminalFailureKind.CONTROL) {
            terminalFailure = TerminalFailure(
                generation = nextGeneration,
                cause = DesktopDocumentDraftRejectedException(
                    "Process-owned document draft payload was rejected",
                ),
                kind = TerminalFailureKind.WRITE,
                processSequence = rejectedSequence,
            )
            stateChanged.signalAll()
        }
    }

    private fun clearCompletedHandoffFailure() = stateLock.withLock {
        val failure = terminalFailure
        val handoff = failure?.cause as? DesktopDocumentDraftHandoffPendingException
        if (handoff != null && lease.pending(handoff.processSequence) == null) {
            terminalFailure = null
            stateChanged.signalAll()
        }
    }

    private fun awaitGeneration(targetGeneration: Long): Boolean {
        var remainingNanos = flushTimeoutNanos
        return stateLock.withLock {
            while (settledGeneration < targetGeneration) {
                if (remainingNanos <= 0L) return@withLock false
                remainingNanos = try {
                    stateChanged.awaitNanos(remainingNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withLock false
                }
            }
            true
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean = stateLock.withLock {
        generation == nextGeneration
    }

    /** 必须在持有 [stateLock] 时调用。 */
    private fun nextGenerationLocked(): Long {
        check(nextGeneration < Long.MAX_VALUE) { "Document draft generation exhausted" }
        nextGeneration += 1L
        return nextGeneration
    }

    private fun <T> useCurrentLease(
        authority: DesktopDraftLeaseAuthority = DesktopDraftLeaseAuthority.DISPOSITION,
        action: () -> T,
    ): DesktopLeaseUse<T> {
        val acquired = try {
            lease.ioLock.tryLock(flushTimeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return DesktopLeaseUse(DesktopLeaseUseState.UNAVAILABLE)
        return try {
            val current = when (authority) {
                DesktopDraftLeaseAuthority.WRITER -> lease.isWriterCurrent()
                DesktopDraftLeaseAuthority.DISPOSITION -> lease.isDispositionCurrent()
            }
            if (!current) {
                DesktopLeaseUse(DesktopLeaseUseState.STALE)
            } else {
                DesktopLeaseUse(DesktopLeaseUseState.COMPLETED, action())
            }
        } finally {
            lease.ioLock.unlock()
        }
    }

    private inline fun <T> withOwner(requestedOwner: DocumentDraftOwnerKey, action: () -> T): T {
        require(requestedOwner == ownerKey) {
            "Document draft persistence belongs to another session"
        }
        return action()
    }

    private data class PendingWrite(
        val generation: Long,
        val processSequence: Long,
    )

    private data class TerminalFailure(
        val generation: Long,
        val cause: Throwable,
        val kind: TerminalFailureKind = TerminalFailureKind.CONTROL,
        val processSequence: Long? = null,
    )

    private enum class TerminalFailureKind { WRITE, CONTROL }

    private enum class DesktopDraftLeaseAuthority { WRITER, DISPOSITION }

    internal companion object {
        const val MAX_MANIFEST_BYTES = MAX_DOCUMENT_DRAFT_MANIFEST_BYTES * 1L
        const val MAX_RECORD_BYTES = MAX_DOCUMENT_DRAFT_RECORD_BYTES * 1L
        const val MAX_TOTAL_RECORD_BYTES = MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES
        internal const val DRAFTS_DIRECTORY = "document-drafts"
        internal const val STORAGE_VERSION_DIRECTORY = "v3"
        internal const val DEPLOYMENTS_DIRECTORY = "deployments"
        internal const val OWNERS_DIRECTORY = "owners"
        internal const val DRAFT_FILE_NAME = "manifest"
        internal const val TOMBSTONE_FILE_NAME = "tombstones"
        private const val MAX_RECORDS = MAX_DOCUMENT_DRAFT_RECORDS
        private const val MAX_TOMBSTONES = 8192
        private const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 15_000L
        private const val WORKER_KEEP_ALIVE_SECONDS = 5L
    }
}
