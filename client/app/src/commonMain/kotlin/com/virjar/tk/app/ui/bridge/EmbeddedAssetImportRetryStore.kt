package com.virjar.tk.app.ui.bridge

import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import kotlinx.coroutines.Job

/**
 * 可重试内嵌资源导入的同进程所有权。
 *
 * 此 store 有意比离线 outbox 更小：仅为当前会话保留一份平台本地选择和一份稳定的 HTTP 身份。
 * 平台网关仍负责准备并上传该选择。可选的平台 [R] 快照可以在准备完成后挂接，使精确的 HTTP
 * 重放永不重新读取可变的内容 URI。[releaseSelection] 与 [releaseSource] 在 READY、取消/移除
 * 或 store 关闭之后恰好调用一次；FAILED 会保留两者以便重试。
 */
class EmbeddedAssetImportRetryStore<R : Any>(
    private val releaseSelection: (EmbeddedAssetLocalSelection) -> Unit = {},
    private val releaseSource: (R) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    fun create(
        binding: EmbeddedAssetImportBinding,
        selection: EmbeddedAssetLocalSelection,
        placement: EmbeddedAssetImportPlacement,
        job: PendingAssetJob,
        identity: AttachmentUploadIdentity,
    ): Attempt<R>? = synchronized(lock) {
        if (closed) return@synchronized null
        check(job.jobId !in entries) { "Embedded asset import job is already registered: ${job.jobId}" }
        Entry(
            binding = binding,
            selection = selection,
            placement = placement,
            identity = identity,
            generation = 1L,
            job = job,
        ).also { entry -> entries[job.jobId] = entry }.toAttempt()
    }

    /** 挂接尚未启动/被门控的任务，让 cancel/retry 拥有单一线性 owner。 */
    fun attach(attempt: Attempt<R>, task: Job): Boolean = synchronized(lock) {
        val entry = currentEntryLocked(attempt) ?: return@synchronized false
        if (entry.task != null) return@synchronized false
        entry.task = task
        true
    }

    /** 挂接第一份不可变/已准备的 source 快照；调用方负责释放被拒绝的 source。 */
    fun attachSource(attempt: Attempt<R>, source: R): Boolean = synchronized(lock) {
        val entry = currentEntryLocked(attempt) ?: return@synchronized false
        if (entry.retainedSource != null) return@synchronized false
        entry.retainedSource = source
        true
    }

    fun source(attempt: Attempt<R>): R? = synchronized(lock) {
        currentEntryLocked(attempt)?.retainedSource
    }

    fun state(jobId: String): PendingAssetJobState? = synchronized(lock) {
        entries[jobId]?.job?.state
    }

    fun transition(
        attempt: Attempt<R>,
        transform: (PendingAssetJob) -> PendingAssetJob,
    ): PendingAssetJob? = synchronized(lock) {
        val entry = currentEntryLocked(attempt) ?: return@synchronized null
        transform(entry.job).also { next ->
            require(next.jobId == entry.job.jobId && next.assetId == entry.job.assetId) {
                "Embedded asset transition cannot replace job identity"
            }
            entry.job = next
        }
    }

    fun fail(attempt: Attempt<R>, reason: String): PendingAssetJob? = synchronized(lock) {
        val entry = currentEntryLocked(attempt) ?: return@synchronized null
        entry.job.markFailed(reason).also { failed ->
            entry.job = failed
            // 失败中的任务已在回退。下一次任务槽位归 retry 所有。
            entry.task = null
        }
    }

    /** FAILED -> PREPARING 在任务启动前提交，因此并发点击重试只有一个胜者。 */
    fun retry(jobId: String): Attempt<R>? = synchronized(lock) {
        val entry = entries[jobId]
            ?.takeIf { it.job.state == PendingAssetJobState.FAILED }
            ?: return@synchronized null
        entry.generation += 1L
        entry.job = entry.job.beginPreparing()
        entry.task = null
        entry.toAttempt()
    }

    fun completeReady(attempt: Attempt<R>): PendingAssetJob? {
        val retired = synchronized(lock) {
            val entry = currentEntryLocked(attempt) ?: return@synchronized null
            entry.job.markReady().let { ready ->
                entry.job = ready
                entries.remove(ready.jobId)
                Retired(entry.selection, entry.retainedSource, ready)
            }
        } ?: return null
        release(retired.selection, retired.source)
        return retired.job
    }

    /** 同时覆盖主动取消与移除被保留的 FAILED source。 */
    fun cancel(jobId: String): Cancelled? {
        val retired = synchronized(lock) {
            val entry = entries.remove(jobId) ?: return@synchronized null
            RetiredCancellation(
                result = Cancelled(
                    binding = entry.binding,
                    job = entry.job.cancel(),
                ),
                task = entry.task,
                selection = entry.selection,
                source = entry.retainedSource,
            )
        } ?: return null
        cancelAndRelease(retired.task, retired.selection, retired.source)
        return retired.result
    }

    /** 重新绑定只恢复当前状态。平台必须以 placement = null 重放。 */
    fun replay(ownerKey: String): List<PendingAssetJob> = synchronized(lock) {
        entries.values
            .filter { it.binding.ownerKey == ownerKey }
            .map { it.job }
    }

    /** 拒绝来自更早 attempt 或当前 attempt 更早状态的迟到帧。 */
    fun isCurrent(attempt: Attempt<R>, job: PendingAssetJob): Boolean = synchronized(lock) {
        currentEntryLocked(attempt)?.job == job
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.toList().also { entries.clear() }
        }
        retired.forEach { entry ->
            cancelAndRelease(entry.task, entry.selection, entry.retainedSource)
        }
    }

    private fun currentEntryLocked(attempt: Attempt<R>): Entry? = entries[attempt.jobId]
        ?.takeIf { entry -> entry.generation == attempt.generation }

    private fun release(selection: EmbeddedAssetLocalSelection, source: R?) {
        if (source != null) {
            try {
                releaseSource(source)
            } catch (_: Exception) {
                // 即使平台快照清理失败，仍继续释放选择。
            }
        }
        try {
            releaseSelection(selection)
        } catch (_: Exception) {
            // Source 的释放是尽力而为的，绝不能把已完成的上传变成 FAILED。
        }
    }

    private fun cancelAndRelease(
        task: Job?,
        selection: EmbeddedAssetLocalSelection,
        source: R?,
    ) {
        if (task == null) {
            release(selection, source)
            return
        }
        task.invokeOnCompletion { release(selection, source) }
        task.cancel()
    }

    class Attempt<R : Any> internal constructor(
        val jobId: String,
        val assetId: String,
        val generation: Long,
        val binding: EmbeddedAssetImportBinding,
        val selection: EmbeddedAssetLocalSelection,
        val placement: EmbeddedAssetImportPlacement,
        val identity: AttachmentUploadIdentity,
        val job: PendingAssetJob,
        val source: R?,
    )

    class Cancelled internal constructor(
        val binding: EmbeddedAssetImportBinding,
        val job: PendingAssetJob,
    )

    private inner class Entry(
        val binding: EmbeddedAssetImportBinding,
        val selection: EmbeddedAssetLocalSelection,
        val placement: EmbeddedAssetImportPlacement,
        val identity: AttachmentUploadIdentity,
        var generation: Long,
        var job: PendingAssetJob,
        var task: Job? = null,
        var retainedSource: R? = null,
    ) {
        fun toAttempt(): Attempt<R> = Attempt(
            jobId = job.jobId,
            assetId = job.assetId,
            generation = generation,
            binding = binding,
            selection = selection,
            placement = placement,
            identity = identity,
            job = job,
            source = retainedSource,
        )
    }

    private inner class Retired(
        val selection: EmbeddedAssetLocalSelection,
        val source: R?,
        val job: PendingAssetJob,
    )

    private inner class RetiredCancellation(
        val result: Cancelled,
        val task: Job?,
        val selection: EmbeddedAssetLocalSelection,
        val source: R?,
    )
}
