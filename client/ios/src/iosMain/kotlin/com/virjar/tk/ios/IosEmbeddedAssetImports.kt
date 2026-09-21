package com.virjar.tk.ios

import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.bridge.*
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.asUploadSource
import kotlinx.coroutines.*

internal fun releaseIosEmbeddedAssetSelection(selection: EmbeddedAssetLocalSelection) {
    if (selection.deleteAfterImport) PlatformFile(selection.localReference).delete()
}

/** 共享认证上传传输的 iOS picker/clipboard 适配器。 */
internal class IosEmbeddedAssetImportGateway(
    private val resources: IosMediaResources,
    private val transfer: IosFileTransfer,
    private val native: IosNativeMedia,
    private val publishOnUi: (() -> Unit) -> Unit,
    private val durableImports: ChatAssetImportDelegate? = null,
) : EmbeddedAssetImportGateway, AutoCloseable {
    private val scope = resources.childScope("embedded-asset-import")
    private val bindings = EmbeddedAssetImportBindingRouter()
    private val retryStore = EmbeddedAssetImportRetryStore<Unit>(
        releaseSelection = ::releaseIosEmbeddedAssetSelection,
    )

    override fun bind(
        ownerKey: String,
        sink: EmbeddedAssetImportEventSink,
        acceptNewImports: Boolean,
    ): EmbeddedAssetImportRegistration {
        val registration = bindings.bind(ownerKey, sink, acceptNewImports)
        val binding = checkNotNull(bindings.capture())
        val durableRegistration = durableImports?.takeIf { it.handles(ownerKey) }?.bind(
            ownerKey,
            EmbeddedAssetImportEventSink { event -> publishTerminal(binding, event) },
        )
        retryStore.replay(ownerKey).forEach { job ->
            sink.publish(EmbeddedAssetImportEvent.StateChanged(job = job, placement = null))
        }
        return EmbeddedAssetImportRegistration {
            durableRegistration?.close()
            registration.close()
        }
    }

    override fun select(presentation: EmbeddedAssetPresentation) {
        val binding = bindings.captureForImport() ?: return
        native.select(presentation) { selection ->
            if (resources.canDeliverUiResult() && bindings.isCurrent(binding)) import(selection, binding)
            else releaseIosEmbeddedAssetSelection(selection)
        }
    }

    override fun import(selection: EmbeddedAssetLocalSelection) {
        val binding = bindings.captureForImport() ?: run {
            releaseIosEmbeddedAssetSelection(selection)
            return
        }
        import(selection, binding)
    }

    override fun cancel(jobId: String): Boolean {
        val cancelled = retryStore.cancel(jobId) ?: return durableImports?.cancel(jobId) == true
        publishTerminal(
            cancelled.binding,
            EmbeddedAssetImportEvent.StateChanged(cancelled.job, placement = null),
        )
        return true
    }

    override fun retry(jobId: String): Boolean {
        if (retryStore.state(jobId) == null) return durableImports?.retry(jobId) == true
        val attempt = retryStore.retry(jobId) ?: return when (retryStore.state(jobId)) {
            PendingAssetJobState.PREPARING,
            PendingAssetJobState.UPLOADING,
            -> true
            else -> false
        }
        publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(attempt.job, placement = null))
        launchAttempt(attempt)
        return true
    }

    private fun import(
        selection: EmbeddedAssetLocalSelection,
        binding: EmbeddedAssetImportBinding,
    ) {
        durableImports?.takeIf { it.handles(binding.ownerKey) }?.let { durable ->
            beginDurableImport(binding, selection, durable)
            return
        }
        val assetId = platformRandomUuid()
        val job = PendingAssetJob(jobId = platformRandomUuid(), assetId = assetId)
        val placement = com.virjar.tk.app.ui.bridge.EmbeddedAssetImportPlacement(
            label = selection.displayName,
            presentation = selection.presentation,
        )
        val attempt = retryStore.create(
            binding = binding,
            selection = selection,
            placement = placement,
            job = job,
            identity = AttachmentUploadIdentity(
                uploadId = platformRandomUuid(),
                issuedAt = platformCurrentTimeMillis(),
            ),
        ) ?: run {
            releaseIosEmbeddedAssetSelection(selection)
            return
        }
        publishInitial(binding, EmbeddedAssetImportEvent.StateChanged(job, placement))
        launchAttempt(attempt)
    }

    private fun beginDurableImport(
        binding: EmbeddedAssetImportBinding,
        selection: EmbeddedAssetLocalSelection,
        durable: ChatAssetImportDelegate,
    ) {
        val assetId = platformRandomUuid()
        val preparation = scope.launch {
            try {
                resources.ensureOpen()
                // Freeze once. All HTTP retries use the private spool, never this mutable user path.
                durable.prepare(
                    binding.ownerKey, assetId, PlatformFile(selection.localReference).asUploadSource(), selection,
                )
                publishInitial(
                    binding,
                    EmbeddedAssetImportEvent.StateChanged(
                        PendingAssetJob(assetId, assetId),
                        com.virjar.tk.app.ui.bridge.EmbeddedAssetImportPlacement(
                            selection.displayName, selection.presentation,
                        ),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishOnUi {
                    if (resources.canDeliverUiResult()) durable.preparationFailed(binding.ownerKey)
                }
            }
        }
        // Also runs if session shutdown cancels this job before its first instruction.
        preparation.invokeOnCompletion { releaseIosEmbeddedAssetSelection(selection) }
    }

    private fun launchAttempt(attempt: EmbeddedAssetImportRetryStore.Attempt<Unit>) {
        val file = PlatformFile(attempt.selection.localReference)
        val uploadTask = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var job = attempt.job
                if (job.state == PendingAssetJobState.LOCAL) {
                    job = retryStore.transition(attempt, PendingAssetJob::beginPreparing)
                        ?: return@launch
                    publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(job))
                }
                resources.ensureOpen()
                require(file.isFile) { "文件不存在: ${attempt.selection.displayName}" }
                job = retryStore.transition(attempt, PendingAssetJob::beginUploading)
                    ?: return@launch
                publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(job))
                var latestProgress = 0f
                val metadata = transfer.uploadWithMeta(
                    file = file,
                    contentType = attempt.selection.contentType,
                    identity = attempt.identity,
                    displayName = attempt.selection.displayName,
                ) { progress ->
                    val monotonic = progress.coerceIn(latestProgress, 1f)
                    latestProgress = monotonic
                    val progressed = retryStore.transition(attempt) { current ->
                        current.updateUploadProgress(monotonic)
                    } ?: return@uploadWithMeta
                    job = progressed
                    publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(progressed))
                }
                resources.ensureOpen()
                job = retryStore.completeReady(attempt) ?: return@launch
                publishTerminal(
                    attempt.binding,
                    EmbeddedAssetImportEvent.Ready(
                        job = job,
                        asset = EmbeddedAsset(
                            assetId = attempt.assetId,
                            attachment = metadata.file,
                            thumbnail = metadata.thumbnail,
                            width = metadata.width,
                            height = metadata.height,
                        ),
                        placement = attempt.placement,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = failure.message?.takeIf(String::isNotBlank) ?: "上传失败"
                retryStore.fail(attempt, reason)?.let { failed ->
                    publishCurrent(attempt, EmbeddedAssetImportEvent.StateChanged(failed))
                }
            }
        }
        if (retryStore.attach(attempt, uploadTask)) {
            uploadTask.start()
        } else {
            uploadTask.cancel()
        }
    }

    /** Placement 是一次性的，并且总是先于 iOS UI 队列上的每次 attempt 帧发布。 */
    private fun publishInitial(
        binding: EmbeddedAssetImportBinding,
        event: EmbeddedAssetImportEvent,
    ) {
        publishOnUi {
            if (resources.canDeliverUiResult()) bindings.publish(binding, event)
        }
    }

    private fun publishCurrent(
        attempt: EmbeddedAssetImportRetryStore.Attempt<Unit>,
        event: EmbeddedAssetImportEvent.StateChanged,
    ) {
        publishOnUi {
            if (
                resources.canDeliverUiResult() &&
                retryStore.isCurrent(attempt, event.job)
            ) {
                bindings.publish(attempt.binding, event)
            }
        }
    }

    private fun publishTerminal(
        binding: EmbeddedAssetImportBinding,
        event: EmbeddedAssetImportEvent,
    ) {
        publishOnUi {
            if (resources.canDeliverUiResult()) bindings.publish(binding, event)
        }
    }

    override fun close() {
        bindings.close()
        retryStore.close()
        scope.cancel()
    }
}
