package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadRetryableException
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecordSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal class GuardedDocumentDraftRecordSource(
    private val source: DesktopDocumentDraftStoredRecordSource,
) : DocumentDraftRecordSource {
    @Volatile
    private var valid = true

    override val manifest: String
        get() {
            checkValid()
            return source.manifest
        }

    override val tombstones: Set<String>
        get() {
            checkValid()
            return source.tombstones
        }

    override fun recordByteCount(key: String): Long? {
        checkValid()
        return try {
            source.recordByteCount(key)
        } catch (retryable: DocumentDraftReadRetryableException) {
            throw retryable
        } catch (failure: Exception) {
            throw DocumentDraftReadRetryableException(failure)
        }
    }

    override fun readRecord(key: String): String? {
        checkValid()
        return try {
            source.readRecord(key)
        } catch (retryable: DocumentDraftReadRetryableException) {
            throw retryable
        } catch (failure: Exception) {
            throw DocumentDraftReadRetryableException(failure)
        }
    }

    fun invalidate() {
        valid = false
    }

    private fun checkValid() = check(valid) {
        "Document draft record source escaped its read callback"
    }
}
internal class DocumentDraftConsumerFailure(val original: Throwable) : RuntimeException(null, original)

internal enum class DesktopLeaseUseState { COMPLETED, STALE, UNAVAILABLE }

internal data class DesktopLeaseUse<T>(
    val state: DesktopLeaseUseState,
    val value: T? = null,
)

internal fun desktopDraftFlushTimeoutNanos(timeoutMillis: Long): Long {
    require(timeoutMillis > 0L) { "Document draft flush timeout must be positive" }
    return TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
}

internal data class DesktopProcessPendingPayload(
    val sequence: Long,
    val admittedEpoch: Long,
    val payload: () -> DocumentDraftPayload,
)

internal data class ProcessPendingDrain(
    val latestPublishedSequence: Long?,
    val latestRejectedSequence: Long?,
) {
    val latestUnrecoveredRejectedSequence: Long?
        get() = latestRejectedSequence?.takeIf { rejected ->
            latestPublishedSequence?.let { published -> rejected > published } != false
        }
}

internal class DesktopDocumentDraftHandoffPendingException(
    val processSequence: Long,
) : IllegalStateException("Document draft write is awaiting successor handoff")

internal class DesktopDocumentDraftRejectedException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal class DesktopDocumentDraftLeaseCapacityException(
    val maxNamespaces: Int,
) : IllegalStateException(
    "Document draft writer coordinator cannot retain more than $maxNamespaces namespaces",
)

internal class DesktopDocumentDraftPendingCapacityException(
    val maxPendingPayloads: Int,
) : IllegalStateException(
    "Document draft writer coordinator cannot retain more than $maxPendingPayloads pending payloads",
)

internal class DesktopDocumentDraftLease(
    val ioLock: ReentrantLock,
    private val epoch: Long,
    private val current: () -> Boolean,
    private val admitPending: ((() -> DocumentDraftPayload) -> DesktopProcessPendingPayload?),
    private val findPending: (Long?) -> DesktopProcessPendingPayload?,
    private val completePending: (Long) -> Unit,
    private val discardPending: (Long) -> Unit,
    private val releaseOwner: () -> Unit,
) : AutoCloseable {
    private val writerReleased = AtomicBoolean(false)
    private val dispositionReleased = AtomicBoolean(false)

    fun isCurrent(): Boolean = isDispositionCurrent()
    fun isWriterCurrent(): Boolean = !writerReleased.get() && current()
    fun isDispositionCurrent(): Boolean = !dispositionReleased.get() && current()
    fun admit(payload: () -> DocumentDraftPayload): DesktopProcessPendingPayload? = if (writerReleased.get()) {
        null
    } else {
        admitPending(payload)
    }

    fun latestPending(): DesktopProcessPendingPayload? =
        if (isFullyReleased()) null else findPending(null)?.takeIf { it.admittedEpoch <= epoch }
    fun pending(sequence: Long): DesktopProcessPendingPayload? =
        if (isFullyReleased()) null else findPending(sequence)

    fun complete(sequence: Long) {
        if (!isFullyReleased()) completePending(sequence)
    }

    fun discardPendingThroughEpoch() {
        if (!isFullyReleased()) discardPending(epoch)
    }

    fun releaseWriter() {
        if (writerReleased.compareAndSet(false, true)) releaseOwner()
    }

    fun releaseDisposition() {
        if (dispositionReleased.compareAndSet(false, true)) releaseOwner()
    }

    override fun close() {
        releaseDisposition()
        releaseWriter()
    }

    private fun isFullyReleased(): Boolean = writerReleased.get() && dispositionReleased.get()
}

internal fun interface DesktopDocumentDraftLeaseSource {
    fun acquire(identity: Any): DesktopDocumentDraftLease
}

internal data class DesktopDocumentDraftLeaseRegistrySnapshot(
    val namespaceCount: Int,
    val retainedReferenceCount: Int,
    val pendingPayloadCount: Int,
)

/**
 * 带有固定 namespace 上限的进程内 writer 注册表。
 *
 * 每次 acquire 原子地保留一个 writer 引用和一个最终处置引用，另外至多保留一个最新的 payload 闭包。
 * writer 只有在其 executor 终止后才释放；处置引用只有在终结会话原因无法再升级后才释放。
 * 因此移除最后一个空条目也不会让任何一方脱离其 I/O 锁与单调递增的 epoch。
 */
internal class DesktopDocumentDraftLeaseRegistry(
    private val maxNamespaces: Int,
    private val maxPendingPayloads: Int = maxNamespaces,
) : DesktopDocumentDraftLeaseSource {
    private class Entry {
        val ioLock = ReentrantLock()
        var epoch = 0L
        var nextPendingSequence = 0L
        var retainedReferences = 0
        var pending: DesktopProcessPendingPayload? = null
    }

    private val lock = Any()
    private val entries = mutableMapOf<Any, Entry>()

    init {
        require(maxNamespaces > 0) { "Document draft writer namespace limit must be positive" }
        require(maxPendingPayloads in 1..maxNamespaces) {
            "Document draft pending payload limit must fit the namespace limit"
        }
    }

    override fun acquire(identity: Any): DesktopDocumentDraftLease = synchronized(lock) {
        val entry = entries[identity] ?: run {
            if (entries.size >= maxNamespaces) {
                throw DesktopDocumentDraftLeaseCapacityException(maxNamespaces)
            }
            Entry().also { entries[identity] = it }
        }
        check(entry.epoch < Long.MAX_VALUE) { "Document draft writer lease exhausted" }
        check(entry.retainedReferences <= Int.MAX_VALUE - REFERENCES_PER_LEASE) {
            "Document draft writer reference count exhausted"
        }
        entry.retainedReferences += REFERENCES_PER_LEASE
        val epoch = ++entry.epoch
        DesktopDocumentDraftLease(
            ioLock = entry.ioLock,
            epoch = epoch,
            current = {
                synchronized(lock) {
                    entries[identity] === entry && entry.epoch == epoch
                }
            },
            admitPending = { payload ->
                synchronized(lock) {
                    if (entries[identity] !== entry || entry.epoch != epoch) {
                        null
                    } else {
                        if (entry.pending == null &&
                            entries.values.count { it.pending != null } >= maxPendingPayloads
                        ) {
                            throw DesktopDocumentDraftPendingCapacityException(maxPendingPayloads)
                        }
                        check(entry.nextPendingSequence < Long.MAX_VALUE) {
                            "Document draft pending sequence exhausted"
                        }
                        DesktopProcessPendingPayload(
                            sequence = ++entry.nextPendingSequence,
                            admittedEpoch = epoch,
                            payload = payload,
                        ).also { entry.pending = it }
                    }
                }
            },
            findPending = { sequence ->
                synchronized(lock) {
                    if (entries[identity] !== entry) {
                        null
                    } else {
                        entry.pending?.takeIf { sequence == null || it.sequence == sequence }
                    }
                }
            },
            completePending = { sequence ->
                synchronized(lock) {
                    if (entries[identity] === entry && entry.pending?.sequence == sequence) {
                        entry.pending = null
                        pruneEntry(identity, entry)
                    }
                }
            },
            discardPending = { throughEpoch ->
                synchronized(lock) {
                    if (entries[identity] === entry &&
                        entry.pending?.admittedEpoch?.let { it <= throughEpoch } == true
                    ) {
                        entry.pending = null
                        pruneEntry(identity, entry)
                    }
                }
            },
            releaseOwner = {
                synchronized(lock) {
                    if (entries[identity] === entry) {
                        check(entry.retainedReferences > 0) {
                            "Document draft writer reference underflow"
                        }
                        entry.retainedReferences -= 1
                        pruneEntry(identity, entry)
                    }
                }
            },
        )
    }

    internal fun snapshot(): DesktopDocumentDraftLeaseRegistrySnapshot = synchronized(lock) {
        DesktopDocumentDraftLeaseRegistrySnapshot(
            namespaceCount = entries.size,
            retainedReferenceCount = entries.values.sumOf { it.retainedReferences },
            pendingPayloadCount = entries.values.count { it.pending != null },
        )
    }

    private fun pruneEntry(identity: Any, entry: Entry) {
        if (entry.retainedReferences == 0 && entry.pending == null) {
            entries.remove(identity, entry)
        }
    }

    private companion object {
        const val REFERENCES_PER_LEASE = 2
    }
}

/** 后继会话会单调地栅栏隔离同一存储 namespace 下所有更旧的 worker。 */
internal object DesktopDocumentDraftLeaseCoordinator : DesktopDocumentDraftLeaseSource {
    private const val MAX_COORDINATED_NAMESPACES = 64
    private const val MAX_RETAINED_PENDING_PAYLOADS = 8
    private val registry = DesktopDocumentDraftLeaseRegistry(
        maxNamespaces = MAX_COORDINATED_NAMESPACES,
        maxPendingPayloads = MAX_RETAINED_PENDING_PAYLOADS,
    )

    override fun acquire(identity: Any): DesktopDocumentDraftLease = registry.acquire(identity)
}
