package com.virjar.tk.app.ui.bridge

import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.shared.client.ChatAssetUpload
import com.virjar.tk.shared.repository.ChatAssetUploadCoordinator
import com.virjar.tk.shared.client.ChatAssetUploadState
import com.virjar.tk.shared.client.LocalChatAssetUploads
import com.virjar.tk.shared.client.LocalChatDrafts
import com.virjar.tk.shared.repository.UploadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/** Projects account-owned upload commands into the existing editor import events on Main. */
internal class DurableChatAssetImports(
    private val drafts: LocalChatDrafts,
    private val uploads: LocalChatAssetUploads,
    private val coordinator: Deferred<ChatAssetUploadCoordinator>,
    private val scope: CoroutineScope,
    private val localData: UiLocalDataBoundary,
    private val reportFailure: (Throwable, String) -> Unit,
) : ChatAssetImportDelegate {
    // These imports receive their initial placement from the live platform picker. A cold process
    // has no such delivery and can recover a command persisted just before its editor frame.
    private data class LiveImport(val chatId: String, val preparing: Boolean)
    private val liveImports = java.util.concurrent.ConcurrentHashMap<String, LiveImport>()

    override fun handles(ownerKey: String): Boolean = chatId(ownerKey) != null

    override fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink): EmbeddedAssetImportRegistration {
        val id = requireNotNull(chatId(ownerKey))
        val observer = scope.launch {
            try {
                coordinator.await()
                val delivered = mutableMapOf<String, ChatAssetUpload>()
                drafts.changes.collect {
                    val (draft, jobs) = localData.run { drafts.get(id) to uploads.jobs(id) }
                    val references = runCatching {
                        MarkdownAssetPolicy.recoveryReferences(draft?.markdown.orEmpty()).mapNotNull { it.assetId }.toSet()
                    }.getOrDefault(emptySet())
                    val jobIds = jobs.mapTo(hashSetOf()) { it.assetId }
                    liveImports.forEach { (assetId, live) ->
                        if (live.chatId == id && (assetId in references || (assetId !in jobIds && !live.preparing))) {
                            liveImports.remove(assetId, live)
                        }
                    }
                    delivered.keys.retainAll(jobIds)
                    for (upload in jobs) {
                        if (liveImports.containsKey(upload.assetId) && upload.assetId !in references) continue
                        val previous = delivered.put(upload.assetId, upload)
                        if (previous == upload) continue
                        val placement = upload.placement()
                        if (previous == null && upload.assetId !in references && !liveImports.containsKey(upload.assetId)) {
                            // A rapid rebind may precede persistence of the recovered placement. Track
                            // that delivery just like a live picker so a second binding cannot duplicate it.
                            liveImports[upload.assetId] = LiveImport(id, preparing = false)
                            sink.publish(EmbeddedAssetImportEvent.StateChanged(
                                PendingAssetJob(upload.assetId, upload.assetId), placement,
                            ))
                        }
                        val job = upload.pendingJob()
                        val asset = upload.asset
                        if (upload.state == ChatAssetUploadState.READY && asset != null) {
                            sink.publish(EmbeddedAssetImportEvent.Ready(job, asset, placement))
                        } else {
                            sink.publish(EmbeddedAssetImportEvent.StateChanged(job))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure, "恢复聊天附件失败，请重新打开会话")
            }
        }
        return EmbeddedAssetImportRegistration { observer.cancel() }
    }

    override suspend fun prepare(
        ownerKey: String,
        assetId: String,
        source: UploadSource,
        selection: EmbeddedAssetLocalSelection,
    ) {
        val id = requireNotNull(chatId(ownerKey))
        liveImports[assetId] = LiveImport(id, preparing = true)
        try {
            coordinator.await().registerPrepared(
                chatId = id,
                assetId = assetId,
                source = source,
                fileName = selection.displayName,
                contentType = selection.contentType,
                isImage = selection.presentation == EmbeddedAssetPresentation.IMAGE,
            )
        } catch (failure: Throwable) {
            liveImports.remove(assetId)
            throw failure
        } finally {
            liveImports.computeIfPresent(assetId) { _, live -> live.copy(preparing = false) }
        }
    }

    override fun cancel(assetId: String): Boolean = command("移除聊天附件失败") { it.remove(assetId) }

    override fun retry(assetId: String): Boolean = command("重试聊天附件失败") { it.retry(assetId) }

    override fun preparationFailed(ownerKey: String) {
        scope.launch {
            reportFailure(IllegalStateException("附件未能保存到本地，请检查存储空间后重新选择"), "保存聊天附件失败")
        }
    }

    private fun command(fallback: String, action: suspend (ChatAssetUploadCoordinator) -> Unit): Boolean {
        val task = scope.launch {
            try {
                action(coordinator.await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure, fallback)
            }
        }
        return !task.isCancelled
    }

    private fun chatId(ownerKey: String): String? = ownerKey
        .takeIf { it.startsWith("chat:") && it.endsWith(":draft") }
        ?.removePrefix("chat:")?.removeSuffix(":draft")?.takeIf(String::isNotBlank)
}

private fun ChatAssetUpload.placement() = EmbeddedAssetImportPlacement(
    fileName,
    if (isImage) EmbeddedAssetPresentation.IMAGE else EmbeddedAssetPresentation.FILE,
)

private fun ChatAssetUpload.pendingJob() = PendingAssetJob(
    jobId = assetId,
    assetId = assetId,
    state = when (state) {
        ChatAssetUploadState.QUEUED -> PendingAssetJobState.LOCAL
        ChatAssetUploadState.UPLOADING -> PendingAssetJobState.UPLOADING
        ChatAssetUploadState.READY -> PendingAssetJobState.READY
        ChatAssetUploadState.FAILED -> PendingAssetJobState.FAILED
    },
    progress = if (state == ChatAssetUploadState.READY) 1f else 0f,
    failureReason = if (state == ChatAssetUploadState.FAILED) failure ?: "附件上传失败" else null,
)
