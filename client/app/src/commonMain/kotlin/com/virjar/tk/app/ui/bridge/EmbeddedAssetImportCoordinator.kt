package com.virjar.tk.app.ui.bridge

import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.shared.platform.PlatformAtomicBoolean
import com.virjar.tk.shared.platform.platformCurrentTimeMillis
import com.virjar.tk.shared.platform.platformRandomUuid
import com.virjar.tk.shared.repository.UploadSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Platform code prepares/owns sources; this owner orders import, retry, and editor delivery. */
class EmbeddedAssetImportCoordinator<S : Any>(
    private val launch: (suspend () -> Unit) -> Job?,
    private val publishOnUi: (() -> Unit) -> Unit,
    private val ensureOpen: () -> Unit,
    private val prepare: suspend (EmbeddedAssetLocalSelection) -> S,
    private val source: suspend (S) -> UploadSource,
    private val upload: suspend (S, EmbeddedAssetLocalSelection, AttachmentUploadIdentity, (Float) -> Unit) -> UploadResult,
    private val releaseSource: (S) -> Unit = {},
    private val releaseSelection: (EmbeddedAssetLocalSelection) -> Unit = {},
    private val durableImports: ChatAssetImportDelegate? = null,
) : AutoCloseable {
    private val bindings = EmbeddedAssetImportBindingRouter()
    private val retryStore = EmbeddedAssetImportRetryStore(releaseSelection, releaseSource)
    private val closed = PlatformAtomicBoolean(false)
    // Unknown-length content providers need a temporary snapshot before entering the bounded spool.
    private val durablePreparation = Semaphore(1)

    fun capture() = bindings.capture()
    fun captureForImport() = bindings.captureForImport()
    fun isCurrent(binding: EmbeddedAssetImportBinding) = bindings.isCurrent(binding)

    fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink, acceptNewImports: Boolean): EmbeddedAssetImportRegistration {
        val registration = bindings.bind(ownerKey, sink, acceptNewImports)
        val binding = checkNotNull(bindings.capture())
        val durable = durableImports?.takeIf { it.handles(ownerKey) }?.bind(ownerKey,
            EmbeddedAssetImportEventSink { publish(binding, it) })
        retryStore.replay(ownerKey).forEach { sink.publish(EmbeddedAssetImportEvent.StateChanged(it)) }
        return EmbeddedAssetImportRegistration { durable?.close(); registration.close() }
    }

    fun import(selection: EmbeddedAssetLocalSelection, binding: EmbeddedAssetImportBinding? = captureForImport()) {
        com.virjar.tk.shared.log.AppLog.trace(
            "ChatMedia",
            "asset import: name=${selection.displayName} type=${selection.contentType} size=${selection.size} ref=${selection.localReference} binding=${binding != null}",
        )
        if (binding == null || closed.get()) {
            com.virjar.tk.shared.log.AppLog.fault("ChatMedia", "asset import dropped: no binding or closed")
            releaseSelection(selection); return
        }
        durableImports?.takeIf { it.handles(binding.ownerKey) }?.let {
            beginDurableImport(binding, selection, it)
            return
        }
        val job = PendingAssetJob(platformRandomUuid(), platformRandomUuid())
        val placement = EmbeddedAssetImportPlacement(selection.displayName, selection.presentation)
        val attempt = retryStore.create(binding, selection, placement, job,
            AttachmentUploadIdentity(platformRandomUuid(), platformCurrentTimeMillis()))
        if (attempt == null) {
            com.virjar.tk.shared.log.AppLog.fault("ChatMedia", "asset import attempt rejected by retry store")
            releaseSelection(selection); return
        }
        publish(binding, EmbeddedAssetImportEvent.StateChanged(job, placement))
        launchAttempt(attempt)
    }

    fun cancel(jobId: String): Boolean {
        val cancelled = retryStore.cancel(jobId) ?: return durableImports?.cancel(jobId) == true
        publish(cancelled.binding, EmbeddedAssetImportEvent.StateChanged(cancelled.job))
        return true
    }

    fun retry(jobId: String): Boolean {
        if (retryStore.state(jobId) == null) return durableImports?.retry(jobId) == true
        val attempt = retryStore.retry(jobId) ?: return retryStore.state(jobId) in
            setOf(PendingAssetJobState.PREPARING, PendingAssetJobState.UPLOADING)
        publishCurrent(attempt, attempt.job)
        launchAttempt(attempt)
        return true
    }

    private fun beginDurableImport(binding: EmbeddedAssetImportBinding, selection: EmbeddedAssetLocalSelection,
        durable: ChatAssetImportDelegate) {
        val assetId = platformRandomUuid()
        val preparation = launch {
            durablePreparation.withPermit {
                var prepared: S? = null
                try {
                    ensureOpen()
                    val candidate = prepare(selection)
                    prepared = candidate
                    // The durable delegate commits its own immutable spool before this source is released.
                    durable.prepare(binding.ownerKey, assetId, source(candidate), selection)
                    publish(binding, EmbeddedAssetImportEvent.StateChanged(PendingAssetJob(assetId, assetId),
                        EmbeddedAssetImportPlacement(selection.displayName, selection.presentation)))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { publishOnUi { if (!closed.get()) durable.preparationFailed(binding.ownerKey) } }
                finally { prepared?.let(releaseSource) }
            }
        }
        if (preparation == null) {
            releaseSelection(selection)
            publishOnUi { if (!closed.get()) durable.preparationFailed(binding.ownerKey) }
        } else preparation.invokeOnCompletion { releaseSelection(selection) }
    }

    private fun launchAttempt(attempt: EmbeddedAssetImportRetryStore.Attempt<S>) {
        val start = CompletableDeferred<Unit>()
        val task = launch {
            // Also handles UNDISPATCHED platform launchers: attach ownership before reading any source.
            start.await()
            try {
                if (attempt.job.state == PendingAssetJobState.LOCAL) {
                    val preparing = retryStore.transition(attempt, PendingAssetJob::beginPreparing) ?: return@launch
                    publishCurrent(attempt, preparing)
                }
                ensureOpen()
                val prepared = attempt.source ?: retryStore.source(attempt) ?: prepare(attempt.selection).let { candidate ->
                    if (!retryStore.attachSource(attempt, candidate)) { releaseSource(candidate); return@launch }
                    candidate
                }
                currentCoroutineContext().ensureActive()
                val uploading = retryStore.transition(attempt, PendingAssetJob::beginUploading) ?: return@launch
                publishCurrent(attempt, uploading)
                var progress = 0f
                val result = upload(prepared, attempt.selection, attempt.identity) { next ->
                    progress = next.coerceIn(progress, 1f)
                    retryStore.transition(attempt) { it.updateUploadProgress(progress) }?.let { publishCurrent(attempt, it) }
                }
                currentCoroutineContext().ensureActive()
                ensureOpen()
                val ready = retryStore.completeReady(attempt) ?: return@launch
                publish(attempt.binding, EmbeddedAssetImportEvent.Ready(ready,
                    EmbeddedAsset(attempt.assetId, result.file, result.thumbnail, result.width, result.height), attempt.placement))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                retryStore.fail(attempt, failure.message?.takeIf(String::isNotBlank) ?: "上传失败")
                    ?.let { publishCurrent(attempt, it) }
            }
        }
        if (task == null) {
            retryStore.cancel(attempt.jobId)?.let { publish(it.binding, EmbeddedAssetImportEvent.StateChanged(it.job)) }
        } else if (retryStore.attach(attempt, task)) start.complete(Unit) else task.cancel()
    }

    private fun publishCurrent(attempt: EmbeddedAssetImportRetryStore.Attempt<S>, job: PendingAssetJob) {
        publishOnUi {
            if (!closed.get() && retryStore.isCurrent(attempt, job)) {
                bindings.publish(attempt.binding, EmbeddedAssetImportEvent.StateChanged(job))
            }
        }
    }

    private fun publish(binding: EmbeddedAssetImportBinding, event: EmbeddedAssetImportEvent) {
        publishOnUi { if (!closed.get()) bindings.publish(binding, event) }
    }

    override fun close() {
        closed.set(true)
        bindings.close()
        retryStore.close()
    }
}
