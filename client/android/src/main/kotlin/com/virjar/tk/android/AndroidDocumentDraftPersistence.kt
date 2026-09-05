package com.virjar.tk.android

import android.content.Context
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPersistence
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadRetryableException
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadStatus
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecordSource
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 进程所有的私有、不参与备份的未保存文档正文存储。
 *
 * AtomicFile 通过临时文件写入，并且只有在完整的 UTF-8 载荷同步完成后才会重命名。
 * 文件名使用部署-数据集-账户 owner 的哈希，因此标识符永远不会变成路径。
 * 一个实例归 [TeamTalkApp] 所有，并由 Activity/会话级别的 [DocumentDraftStore] 共享。
 */
internal class AndroidDocumentDraftPersistence internal constructor(
    private val storage: DocumentDraftStorage,
    private val queueCapacity: Int = DEFAULT_TASK_QUEUE_CAPACITY,
) : DocumentDraftPersistence, AutoCloseable {
    constructor(context: Context) : this(androidDocumentDraftStorage(context))

    private val admissionLock = Any()
    private val stateLock = Any()
    private val ioLock = Any()
    private val pendingWrites = mutableMapOf<DocumentDraftOwnerKey, PendingWrite>()
    private val ownerGenerations = mutableMapOf<DocumentDraftOwnerKey, Long>()
    private val retryableReadOwners = mutableSetOf<DocumentDraftOwnerKey>()
    private var nextGeneration = 0L
    private var drainEpoch = 0L
    private var scheduledDrainEpoch: Long? = null
    private var pendingDrainHandoff: DrainHandoff? = null
    private var deferredDurabilityFailure: Throwable? = null
    private var durabilityBarrierCache: CompletableFuture<Boolean>? = null
    private var closed = false
    private var taskQueue = newTaskQueue()
    private var closeCompletion: CompletableFuture<Boolean>? = null

    override fun read(
        ownerKey: DocumentDraftOwnerKey,
        consume: (DocumentDraftRecordSource) -> Unit,
    ): DocumentDraftReadStatus = synchronized(admissionLock) {
        val status = try {
            when (awaitPendingWrites()) {
                // 失败的新一代从未发布其清单。一旦它的失败队列被观察/轮换之后，
                // 完整的前一代仍然安全。
                FlushResult.COMPLETE,
                FlushResult.FAILED -> synchronized(ioLock) { storage.read(ownerKey, consume) }
                FlushResult.INCOMPLETE -> DocumentDraftReadStatus.RETRYABLE
            }
        } catch (retryable: DocumentDraftReadRetryableException) {
            synchronized(stateLock) { retryableReadOwners += ownerKey }
            throw retryable
        }
        synchronized(stateLock) {
            if (scheduledDrainEpoch == null && pendingWrites.isEmpty()) {
                ownerGenerations.keys.retainAll(setOf(ownerKey))
                retryableReadOwners.retainAll(setOf(ownerKey))
            }
            if (status == DocumentDraftReadStatus.RETRYABLE) retryableReadOwners += ownerKey
            else retryableReadOwners -= ownerKey
        }
        status
    }

    override fun write(
        ownerKey: DocumentDraftOwnerKey,
        payload: () -> DocumentDraftPayload,
    ): Boolean = synchronized(admissionLock) admission@ {
        var handoffToArm: DrainHandoff? = null
        val accepted = synchronized(stateLock) state@ {
            if (closed || ownerKey in retryableReadOwners) {
                return@state false
            }
            if (scheduledDrainEpoch == null && pendingWrites.isEmpty()) ownerGenerations.clear()
            if (ownerKey !in ownerGenerations &&
                ownerGenerations.size >= MAX_TRACKED_PENDING_OWNERS
            ) return@state false
            val generation = nextWriteGenerationLocked(
                preserveHandoffBarrier = pendingDrainHandoff != null,
            )
            ownerGenerations[ownerKey] = generation
            val pending = PendingWrite(ownerKey, generation, payload)
            pendingWrites[ownerKey] = pending
            handoffToArm = scheduleDrainLocked()
            true
        }
        handoffToArm?.let(::armDrainHandoff)
        accepted
    }

    override fun flush(): Boolean = synchronized(admissionLock) {
        awaitPendingWrites() == FlushResult.COMPLETE
    }

    override fun tombstone(
        ownerKey: DocumentDraftOwnerKey,
        recoveryKeys: Set<String>,
    ): Boolean = synchronized(admissionLock) admission@ {
        if (synchronized(stateLock) { closed }) return@admission false
        when (awaitPendingWrites()) {
            FlushResult.INCOMPLETE -> false
            FlushResult.COMPLETE,
            FlushResult.FAILED -> runSynchronousControl { storage.tombstone(ownerKey, recoveryKeys) }
        }
    }

    override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean = synchronized(admissionLock) admission@ {
        val sealed = try {
            storage.sealDeletion(ownerKey)
        } catch (_: Exception) {
            false
        }
        var controlEpoch = 0L
        val accepted = synchronized(stateLock) state@ {
            if (closed) return@state false
            ownerGenerations[ownerKey] = nextWriteGenerationLocked()
            pendingWrites.remove(ownerKey)
            retryableReadOwners.remove(ownerKey)
            controlEpoch = reserveControlDrainLocked()
            taskQueue.execute {
                runControlAndDrain(controlEpoch, "delete") { storage.delete(ownerKey) }
            }.also { queued ->
                if (!queued && scheduledDrainEpoch == controlEpoch) scheduledDrainEpoch = null
            }
        }
        if (accepted && sealed) return@admission true
        if (controlEpoch == 0L) return@admission false

        // 队列饱和或墓碑失败属于异常情况。在显式注销被允许返回之前，
        // 先排空所有更早的已接受写入，并同步移除字节。
        awaitAcceptedTasksForFallback()
        val deleted = runSynchronousControl { storage.delete(ownerKey) }
        val drained = drainAfterSynchronousControl(controlEpoch)
        deleted && drained
    }

    override fun clearAll(): Boolean = synchronized(admissionLock) admission@ {
        var controlEpoch = 0L
        val accepted = synchronized(stateLock) state@ {
            if (closed) return@state false
            nextWriteGenerationLocked()
            ownerGenerations.clear()
            pendingWrites.clear()
            retryableReadOwners.clear()
            controlEpoch = reserveControlDrainLocked()
            taskQueue.execute {
                runControlAndDrain(controlEpoch, "clear") { storage.clearAll() }
            }.also { queued ->
                if (!queued && scheduledDrainEpoch == controlEpoch) scheduledDrainEpoch = null
            }
        }
        if (accepted) return@admission true
        if (controlEpoch == 0L) return@admission false

        awaitAcceptedTasksForFallback()
        val cleared = runSynchronousControl(storage::clearAll)
        val drained = drainAfterSynchronousControl(controlEpoch)
        cleared && drained
    }

    /** 非阻塞的持久化标记，同时也覆盖队列拒绝时的交接。 */
    fun requestFlush(): CompletableFuture<Boolean> =
        synchronized(admissionLock) { durabilityBarrier() }

    /** 阻塞式读取屏障；草稿恢复已经在后台调度器上调用 [read]。 */
    private fun awaitPendingWrites(): FlushResult = try {
        durabilityBarrier().get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        FlushResult.COMPLETE
    } catch (_: TimeoutException) {
        FlushResult.INCOMPLETE
    } catch (_: ExecutionException) {
        synchronized(stateLock) {
            deferredDurabilityFailure = null
            durabilityBarrierCache = null
        }
        replaceFailedTaskQueue()
        FlushResult.FAILED
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        FlushResult.INCOMPLETE
    } catch (_: Exception) {
        FlushResult.FAILED
    }

    /** 优雅地排空已接受的写入和任何交接，且不阻塞 close 的调用方。 */
    fun closeAsync(): CompletableFuture<Boolean> = synchronized(admissionLock) {
        closeCompletion?.let { return@synchronized it }
        synchronized(stateLock) { closed = true }
        val acceptedWork = durabilityBarrier()
        CompletableFuture<Boolean>().also { completion ->
            closeCompletion = completion
            acceptedWork.whenComplete { _, acceptedFailure ->
                val queueClose = synchronized(stateLock) { taskQueue.closeAsync() }
                queueClose.whenComplete { _, closeFailure ->
                    completeFuture(
                        completion,
                        combineFailures(
                            futureFailure(acceptedFailure),
                            futureFailure(closeFailure),
                        ),
                    )
                }
            }
        }
    }

    override fun close() {
        closeAsync()
    }

    /**
     * 为排队的 delete/clear 及其后的合并写入预留新的 epoch。控制任务拥有该 epoch 的排空工作，
     * 因此永远不需要第二个执行器槽位。
     */
    private fun reserveControlDrainLocked(): Long {
        check(drainEpoch < Long.MAX_VALUE) { "Document draft drain epoch exhausted" }
        drainEpoch += 1L
        val epoch = drainEpoch
        scheduledDrainEpoch = epoch
        return epoch
    }

    /** 必须在 [stateLock] 下运行；包裹式调用会让已退役的写入重新获得权限。 */
    private fun nextWriteGenerationLocked(preserveHandoffBarrier: Boolean = false): Long {
        check(nextGeneration < Long.MAX_VALUE) { "Document draft write generation exhausted" }
        nextGeneration += 1L
        if (!preserveHandoffBarrier) durabilityBarrierCache = null
        return nextGeneration
    }

    /**
     * 必须在 [stateLock] 下运行。当固定执行器无法再接受任务时，返回一个在释放状态锁之后
     * 才需要武装的交接对象。这样的交接对象最多存在一个；之后的写入只会替换其 owner 的
     * 待处理值，并且会在该交接完成之前被取走。
     */
    private fun scheduleDrainLocked(): DrainHandoff? {
        if (pendingWrites.isEmpty() || scheduledDrainEpoch != null) return null
        if (pendingDrainHandoff != null) return null
        val epoch = drainEpoch
        scheduledDrainEpoch = epoch
        if (taskQueue.execute { drainWrites(epoch) }) return null

        val rejectedQueue = taskQueue
        return DrainHandoff(epoch).also { handoff ->
            pendingDrainHandoff = handoff
            handoff.nextArm = DrainHandoffArm(
                queue = rejectedQueue,
                barrier = rejectedQueue.barrier(),
            )
        }
    }

    /** 在 [stateLock] 之外武装唯一的有界交接对象，让未来的回调可以安全地重入。 */
    private fun armDrainHandoff(handoff: DrainHandoff) {
        val arm = synchronized(stateLock) {
            if (pendingDrainHandoff !== handoff) return
            handoff.nextArm.also { handoff.nextArm = null }
        } ?: return
        arm.barrier.whenComplete { _, failure ->
            resumeDrainHandoff(handoff, arm.queue, futureFailure(failure))
        }
    }

    /**
     * 在被拒绝队列接受的所有任务都离开其工作线程之后运行。失败的队列只会在此异步边界处
     * 被替换；仅仅已满的队列会在容量可用之后重试。两条路径都保持 FIFO 的磁盘所有权，
     * 且不会占用 UI 调用方线程。
     */
    private fun resumeDrainHandoff(
        handoff: DrainHandoff,
        precedingQueue: CloseableSerialTaskQueue,
        precedingFailure: Throwable?,
    ) {
        var retiredQueue: CloseableSerialTaskQueue? = null
        var followBarrier: CompletableFuture<Boolean>? = null
        var retry = false
        synchronized(stateLock) {
            if (pendingDrainHandoff !== handoff) return
            handoff.failure = combineFailures(handoff.failure, precedingFailure)
            if (precedingFailure != null && taskQueue === precedingQueue) {
                retiredQueue = precedingQueue
                taskQueue = newTaskQueue()
            }

            if (handoff.epoch != drainEpoch) {
                // 较晚的 delete/clear 拥有当前 epoch，并排空合并后的最新写入。
                // 等待它的队列屏障可以保持那种更新的顺序。
                followBarrier = taskQueue.barrier()
            } else {
                scheduledDrainEpoch = handoff.epoch
                if (taskQueue.execute { drainWrites(handoff.epoch) }) {
                    followBarrier = taskQueue.barrier()
                } else {
                    val rejectedQueue = taskQueue
                    handoff.nextArm = DrainHandoffArm(
                        queue = rejectedQueue,
                        barrier = rejectedQueue.barrier(),
                    )
                    retry = true
                }
            }
        }
        retiredQueue?.closeAsync()
        if (retry) {
            armDrainHandoff(handoff)
            return
        }
        requireNotNull(followBarrier).whenComplete { _, failure ->
            finishDrainHandoff(handoff, futureFailure(failure))
        }
    }

    /** 先发布失败，然后链接处理任何与排空收尾竞态到达的写入。 */
    private fun finishDrainHandoff(handoff: DrainHandoff, followFailure: Throwable?) {
        var continuationRequired = false
        var nextHandoff: DrainHandoff? = null
        var completionFailure: Throwable? = null
        synchronized(stateLock) {
            if (pendingDrainHandoff !== handoff) return
            completionFailure = combineFailures(handoff.failure, followFailure)
            pendingDrainHandoff = null
            if (completionFailure != null) {
                deferredDurabilityFailure = combineFailures(
                    deferredDurabilityFailure,
                    completionFailure,
                )
            }
            if (pendingWrites.isNotEmpty() && scheduledDrainEpoch == null) {
                continuationRequired = true
                // 缓存的外部屏障可能依赖此交接的完成。续接流程必须构建全新的下游屏障，
                // 而不是等待它自己。
                durabilityBarrierCache = null
                nextHandoff = scheduleDrainLocked()
            }
        }
        nextHandoff?.let(::armDrainHandoff)
        if (!continuationRequired) {
            completeFuture(handoff.completion, completionFailure)
            return
        }

        // 旧交接代表了在它完成之前准入的所有写入。如果某个写入方在其最终空检查处竞态到达，
        // 就通过后续流程继续保持同一条持久化承诺。
        durabilityBarrier().whenComplete { _, continuationFailure ->
            completeFuture(
                handoff.completion,
                combineFailures(completionFailure, futureFailure(continuationFailure)),
            )
        }
    }

    private fun durabilityBarrier(): CompletableFuture<Boolean> = synchronized(stateLock) {
        durabilityBarrierCache?.let { return@synchronized it }
        val snapshot = DurabilityBarrierSnapshot(
            queue = taskQueue.barrier(),
            handoff = pendingDrainHandoff?.completion,
            deferredFailure = deferredDurabilityFailure,
        )
        if (snapshot.handoff == null && snapshot.deferredFailure == null) {
            durabilityBarrierCache = snapshot.queue
            return@synchronized snapshot.queue
        }

        val awaited = listOfNotNull(snapshot.queue, snapshot.handoff).toTypedArray()
        CompletableFuture<Boolean>().also { completion ->
            durabilityBarrierCache = completion
            CompletableFuture.allOf(*awaited).whenComplete { _, failure ->
                completeFuture(
                    completion,
                    combineFailures(snapshot.deferredFailure, futureFailure(failure)),
                )
            }
        }
    }

    private fun drainWrites(epoch: Long) {
        var firstFailure: Throwable? = null
        while (true) {
            val pending = synchronized(stateLock) {
                if (epoch != drainEpoch) {
                    if (scheduledDrainEpoch == epoch) scheduledDrainEpoch = null
                    null
                } else {
                    val entry = pendingWrites.entries.firstOrNull()
                    if (entry == null) {
                        if (scheduledDrainEpoch == epoch) scheduledDrainEpoch = null
                        null
                    } else {
                        pendingWrites.remove(entry.key)
                        entry.value
                    }
                }
            } ?: break

            try {
                persistPendingWrite(pending)
            } catch (failure: Throwable) {
                firstFailure = combineFailures(firstFailure, failure)
            }
            val finished = synchronized(stateLock) {
                (epoch != drainEpoch || pendingWrites.isEmpty()).also { shouldStop ->
                    if (shouldStop && scheduledDrainEpoch == epoch) scheduledDrainEpoch = null
                }
            }
            if (finished) break
        }
        firstFailure?.let { throw it }
    }

    private fun persistPendingWrite(pending: PendingWrite) {
        try {
            val payload = try {
                pending.payload()
            } catch (failure: Throwable) {
                if (failedWriteIsCurrent(pending)) throw failure
                return
            }

            synchronized(ioLock) {
                if (!isCurrentWrite(pending)) return
                val failure = try {
                    if (storage.write(pending.ownerKey, payload)) null
                    else IllegalStateException("Document draft write failed")
                } catch (thrown: Throwable) {
                    thrown
                }
                if (failure != null) {
                    throw failure
                }
            }
        } finally {
            completeWriteGeneration(pending)
        }
    }

    private fun completeWriteGeneration(pending: PendingWrite) = synchronized(stateLock) {
        if (ownerGenerations[pending.ownerKey] == pending.generation &&
            pendingWrites[pending.ownerKey]?.generation != pending.generation
        ) {
            ownerGenerations.remove(pending.ownerKey)
        }
    }

    private fun failedWriteIsCurrent(pending: PendingWrite): Boolean =
        synchronized(ioLock) {
            if (!isCurrentWrite(pending)) return@synchronized false
            // 之前的 AtomicFile 仍然是一个完整的最后已知良好快照。某一较新一代的失败
            // 绝不能删除无关的可恢复文档标签页。
            true
        }

    private fun isCurrentWrite(pending: PendingWrite): Boolean = synchronized(stateLock) {
        ownerGenerations[pending.ownerKey] == pending.generation
    }

    private fun runControlAndDrain(epoch: Long, operation: String, control: () -> Boolean) {
        var failure: Throwable? = try {
            val succeeded = synchronized(ioLock) { control() }
            if (succeeded) null else IllegalStateException("Document draft $operation failed")
        } catch (thrown: Throwable) {
            thrown
        }
        try {
            drainWrites(epoch)
        } catch (drainFailure: Throwable) {
            failure = combineFailures(failure, drainFailure)
        }
        failure?.let { throw it }
    }

    private fun runSynchronousControl(control: () -> Boolean): Boolean = try {
        synchronized(ioLock) { control() }
    } catch (_: Exception) {
        false
    }

    private fun drainAfterSynchronousControl(epoch: Long): Boolean {
        synchronized(stateLock) { scheduledDrainEpoch = epoch }
        return try {
            drainWrites(epoch)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 控制操作仍然使用与普通写入分离的阻塞式持久化边界。 */
    private fun awaitAcceptedTasksForFallback(): Boolean {
        val completion = durabilityBarrier()
        var interrupted = false
        var succeeded = true
        while (true) {
            try {
                completion.get()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (_: ExecutionException) {
                succeeded = false
                break
            } catch (_: Exception) {
                succeeded = false
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (!succeeded) replaceFailedTaskQueue()
        return succeeded
    }

    /**
     * [CloseableSerialTaskQueue] 有意为生命周期观察者记住失败。一旦调用方观察并排空了
     * 那个失败的队列，新队列就会准入后续各代；因此一次瞬时的磁盘/编码失败
     * 不会让进程在重启之前一直处于中毒状态。
     */
    private fun replaceFailedTaskQueue() {
        synchronized(stateLock) {
            if (closed) return
            val failedQueue = taskQueue
            if (!failedQueue.barrier().isCompletedExceptionally) return
            taskQueue = newTaskQueue()
            failedQueue.closeAsync()
        }
    }

    private fun newTaskQueue() = CloseableSerialTaskQueue("document-draft-io", queueCapacity)

    private fun combineFailures(current: Throwable?, next: Throwable?): Throwable? {
        if (next == null) return current
        if (current == null) return next
        if (current !== next &&
            current.suppressed.size < MAX_COMBINED_DURABILITY_FAILURES &&
            current.suppressed.none { it === next }
        ) {
            current.addSuppressed(next)
        }
        return current
    }

    private fun futureFailure(failure: Throwable?): Throwable? {
        var current = failure
        while (current is CompletionException || current is ExecutionException) {
            current = current.cause ?: return current
        }
        return current
    }

    private fun completeFuture(completion: CompletableFuture<Boolean>, failure: Throwable?) {
        if (failure == null) completion.complete(true)
        else completion.completeExceptionally(failure)
    }

    private data class PendingWrite(
        val ownerKey: DocumentDraftOwnerKey,
        val generation: Long,
        val payload: () -> DocumentDraftPayload,
    )

    private class DrainHandoff(
        val epoch: Long,
        val completion: CompletableFuture<Boolean> = CompletableFuture(),
        var failure: Throwable? = null,
        var nextArm: DrainHandoffArm? = null,
    )

    private data class DrainHandoffArm(
        val queue: CloseableSerialTaskQueue,
        val barrier: CompletableFuture<Boolean>,
    )

    private data class DurabilityBarrierSnapshot(
        val queue: CompletableFuture<Boolean>,
        val handoff: CompletableFuture<Boolean>?,
        val deferredFailure: Throwable?,
    )

    private enum class FlushResult {
        COMPLETE,
        FAILED,
        INCOMPLETE,
    }

    companion object {
        /** 一条独立的标签页/命令记录；不再一次性分配聚合的工作区大小。 */
        internal const val MAX_RECORD_PAYLOAD_BYTES = 16 * 1024 * 1024
        private const val MAX_TRACKED_PENDING_OWNERS = 2
        private const val MAX_COMBINED_DURABILITY_FAILURES = 3
        private const val DEFAULT_TASK_QUEUE_CAPACITY = 64
        private const val FLUSH_TIMEOUT_SECONDS = 5L

        internal fun draftFileName(ownerKey: DocumentDraftOwnerKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(
                buildString {
                    append("teamtalk-android-document-draft-owner-v3\u0000")
                    append(ownerKey.deploymentFingerprint)
                    append('\u0000')
                    append(ownerKey.datasetId)
                    append('\u0000')
                    append(ownerKey.uid)
                }.toByteArray(Charsets.UTF_8),
            )
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) } + ".json"
        }
    }
}
