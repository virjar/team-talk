package com.virjar.tk.android

import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.app.ui.component.FileDownloadState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 待处理键的压力阈值；为满足该阈值，正确性终态绝不会被丢弃。 */
internal const val MAX_ANDROID_FILE_DOWNLOAD_PENDING_KEYS = 64
/**
 * ChatFileDownloadEffect 最多只能看到默认分页器常驻的 200 行 FileBody。另外，
 * 一个富文本或文档内容最多准入 256 个唯一资源。此上限足以覆盖任意一种完整的调用方批次；
 * 它与更大的持久消息保留策略有意无关。
 */
internal const val MAX_ANDROID_FILE_DOWNLOAD_RESIDENT_STATES = 256

/**
 * 把工作线程的下载结果串行化到组合持有的调度器上。
 *
 * 进度按附件键合并，且最多只调度一次排空。在压力之下，可替换的 Downloading 采样会被丢弃
 * 或拒绝；Checking/Idle/Done/Failed 是正确性状态，会保持排队直到所有者排空它们。
 * 调用方准入有界的 UI 批次，而可观察表本身也有 LRU 上限。
 */
internal class AndroidFileDownloadStatePublisher(
    ownerScope: CoroutineScope,
    private val publicationGate: ((() -> Unit) -> Boolean),
    private val maxPendingKeys: Int = MAX_ANDROID_FILE_DOWNLOAD_PENDING_KEYS,
    private val maxResidentStates: Int = MAX_ANDROID_FILE_DOWNLOAD_RESIDENT_STATES,
    private val ownerThreadPredicate: () -> Boolean = {
        Looper.myLooper() === Looper.getMainLooper()
    },
) : AutoCloseable {
    private class PendingPublication(
        val state: FileDownloadState,
        val isStillCurrent: () -> Boolean,
        private val onDiscarded: (() -> Unit)?,
        val onPublished: (() -> Unit)?,
    ) {
        private val finalized = AtomicBoolean(false)

        fun finalize(published: Boolean) {
            if (!finalized.compareAndSet(false, true)) return
            if (published) onPublished?.invoke() else onDiscarded?.invoke()
        }
    }

    val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()

    private val ownerContext = ownerScope.coroutineContext.also { context ->
        requireNotNull(context[ContinuationInterceptor] as? CoroutineDispatcher) {
            "Android file download UI scope must have a dispatcher"
        }
    }
    private val publicationJob = SupervisorJob(ownerContext[Job])
    private val publicationScope = CoroutineScope(
        ownerContext.minusKey(Job) + publicationJob + CoroutineName("android-file-download-state"),
    )
    private val lock = Any()
    private val pending = linkedMapOf<String, PendingPublication>()
    private val residentOrder = linkedSetOf<String>()
    private var drainScheduled = false
    private var closed = false

    init {
        require(maxPendingKeys > 0) { "Android file download pending capacity must be positive" }
        require(maxResidentStates > 0) { "Android file download state capacity must be positive" }
        publicationJob.invokeOnCompletion {
            val discarded = synchronized(lock) {
                closed = true
                drainScheduled = false
                pending.values.toList().also { pending.clear() }
            }
            finalizeDiscarded(discarded)
        }
    }

    /** 既可能从组合调度器调用，也可能从下载工作线程调用。 */
    fun publish(
        key: String,
        state: FileDownloadState,
        isStillCurrent: () -> Boolean = { true },
        onDiscarded: (() -> Unit)? = null,
        onPublished: (() -> Unit)? = null,
    ): Boolean {
        val publication = PendingPublication(state, isStillCurrent, onDiscarded, onPublished)
        if (key.isBlank()) {
            publication.finalize(published = false)
            return false
        }
        var accepted = true
        var admissionFailure: Throwable? = null
        val discarded = mutableListOf<PendingPublication>()
        val shouldSchedule = synchronized(lock) {
            val current = try {
                !closed && publication.isStillCurrent()
            } catch (failure: Throwable) {
                admissionFailure = failure
                false
            }
            if (!current) {
                accepted = false
                false
            } else {
                pending.remove(key)?.let(discarded::add)
                if (pending.size >= maxPendingKeys) {
                    val progressKey = pending.entries
                        .firstOrNull { it.value.state is FileDownloadState.Downloading }
                        ?.key
                    when {
                        progressKey != null -> pending.remove(progressKey)?.let(discarded::add)
                        state is FileDownloadState.Downloading -> accepted = false
                        // 正确性状态可能暂时超过进度压力阈值。丢弃一个正确性状态
                        // 会让 UI 卡在较旧的 Downloading 状态上。
                        else -> Unit
                    }
                }
                if (accepted) {
                    pending[key] = publication
                    if (drainScheduled) {
                        false
                    } else {
                        drainScheduled = true
                        true
                    }
                } else {
                    false
                }
            }
        }
        finalizeDiscarded(discarded)
        if (!accepted) {
            publication.finalize(published = false)
            admissionFailure?.let { throw it }
            return false
        }

        // FileCard 期望在 ensure 返回之前就能看到 Checking，然后再观察工作线程的 Idle/Done 结果。
        // 保留这种同步的所有者调度器交接，同时绝不允许工作线程直接修改 Snapshot 状态。
        if (isOnOwnerThread()) {
            // 如果某个工作线程已经入队了唯一的排空任务，就为调用方同步冲刷，
            // 但保持其调度令牌被占用，直到那个排队中的排空任务获得它的 UI 轮次。
            drainOnOwnerDispatcher(releaseScheduleWhenEmpty = shouldSchedule)
        } else if (shouldSchedule) {
            publicationScope.launch(start = CoroutineStart.DEFAULT) {
                drainOnOwnerDispatcher(releaseScheduleWhenEmpty = true)
            }
        }
        return true
    }

    private fun drainOnOwnerDispatcher(releaseScheduleWhenEmpty: Boolean) {
        check(isOnOwnerThread()) {
            "Android file download state drain escaped its composition owner thread"
        }
        try {
            while (true) {
                var discarded = emptyList<PendingPublication>()
                val batch = synchronized(lock) {
                    if (closed) {
                        discarded = pending.values.toList()
                        pending.clear()
                        drainScheduled = false
                        null
                    } else if (pending.isEmpty()) {
                        if (releaseScheduleWhenEmpty) drainScheduled = false
                        null
                    } else {
                        pending.entries.map { it.key to it.value }.also { pending.clear() }
                    }
                }
                finalizeDiscarded(discarded)
                if (batch == null) return
                batch.forEachIndexed { index, (key, publication) ->
                    try {
                        publishOneOnOwnerDispatcher(key, publication)
                    } catch (failure: Throwable) {
                        finalizeDiscarded(batch.drop(index + 1).map { it.second }, failure)
                        throw failure
                    }
                }
            }
        } catch (failure: Throwable) {
            val discarded = synchronized(lock) {
                if (releaseScheduleWhenEmpty) drainScheduled = false
                pending.values.toList().also { pending.clear() }
            }
            finalizeDiscarded(discarded, failure)
            throw failure
        }
    }

    private fun publishOneOnOwnerDispatcher(key: String, publication: PendingPublication) {
        var published = false
        try {
            publicationGate {
                // close() 和发布由这把锁线性化。如果 close 在 Snapshot 写入之前胜出，
                // 已复制进本地批次的结果仍然会被丢弃。
                synchronized(lock) {
                    if (!closed && publication.isStillCurrent()) {
                        states[key] = publication.state
                        residentOrder.remove(key)
                        residentOrder.add(key)
                        while (residentOrder.size > maxResidentStates) {
                            val oldest = residentOrder.iterator().next()
                            residentOrder.remove(oldest)
                            states.remove(oldest)
                        }
                        published = true
                    }
                }
                if (published) publication.finalize(published = true)
            }
        } finally {
            if (!published) publication.finalize(published = false)
        }
    }

    private fun finalizeDiscarded(
        publications: Collection<PendingPublication>,
        precedingFailure: Throwable? = null,
    ) {
        var firstFailure = precedingFailure
        publications.forEach { publication ->
            try {
                publication.finalize(published = false)
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else if (failure !== firstFailure) {
                    firstFailure.addSuppressed(failure)
                }
            }
        }
        if (precedingFailure == null) firstFailure?.let { throw it }
    }

    private fun isOnOwnerThread(): Boolean = ownerThreadPredicate()

    override fun close() {
        var shouldCancel = false
        val discarded = synchronized(lock) {
            if (!closed) {
                closed = true
                drainScheduled = false
                shouldCancel = true
                pending.values.toList().also { pending.clear() }
            } else {
                emptyList()
            }
        }
        try {
            finalizeDiscarded(discarded)
        } finally {
            if (shouldCancel) publicationJob.cancel()
        }
    }
}
