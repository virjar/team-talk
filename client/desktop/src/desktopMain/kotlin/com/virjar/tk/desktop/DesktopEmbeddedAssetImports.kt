package com.virjar.tk.desktop

import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportBinding
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportBindingRouter
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportRegistration
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportRetryStore
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSource
import com.virjar.tk.app.ui.bridge.EmbeddedAssetLocalSelection
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun desktopEmbeddedAssetSelection(
    file: File,
    presentation: EmbeddedAssetPresentation,
    source: EmbeddedAssetImportSource,
    deleteAfterImport: Boolean = false,
): EmbeddedAssetLocalSelection {
    require(file.isFile) { "文件不存在: ${file.name}" }
    return EmbeddedAssetLocalSelection(
        localReference = file.absolutePath,
        displayName = file.name,
        contentType = desktopContentType(file.name),
        size = file.length(),
        presentation = presentation,
        source = source,
        deleteAfterImport = deleteAfterImport,
    )
}

/** 释放 adapter 持有的剪贴板副本，绝不删除选择器/拖放的原始文件。 */
internal fun releaseDesktopEmbeddedAssetSelection(selection: EmbeddedAssetLocalSelection) {
    if (selection.deleteAfterImport) runCatching { File(selection.localReference).delete() }
}

/** 只转换本地 file:// 的拖放，并把每个被接纳的文件路由到共享导入器。 */
internal fun importDesktopDroppedAssetUris(
    uris: List<String>,
    gateway: EmbeddedAssetImportGateway,
): Boolean {
    var imported = false
    uris.forEach { rawUri ->
        val file = runCatching {
            val uri = java.net.URI(rawUri)
            uri.takeIf { it.scheme.equals("file", ignoreCase = true) }?.let(::File)
        }.getOrNull()?.takeIf(File::isFile) ?: return@forEach
        val presentation = if (desktopContentType(file.name).startsWith("image/", ignoreCase = true)) {
            EmbeddedAssetPresentation.IMAGE
        } else {
            EmbeddedAssetPresentation.FILE
        }
        gateway.import(
            desktopEmbeddedAssetSelection(
                file = file,
                presentation = presentation,
                source = EmbeddedAssetImportSource.DESKTOP_DROP,
            ),
        )
        imported = true
    }
    return imported
}

/** 只消费二进制剪贴板内容；普通文本粘贴仍由编辑器负责。 */
internal fun importDesktopClipboardAsset(gateway: EmbeddedAssetImportGateway): Boolean = runCatching {
    val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@runCatching false
    if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        @Suppress("UNCHECKED_CAST")
        val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            .orEmpty()
            .filterIsInstance<File>()
            .filter(File::isFile)
        files.forEach { file ->
            val presentation = if (desktopContentType(file.name).startsWith("image/", ignoreCase = true)) {
                EmbeddedAssetPresentation.IMAGE
            } else {
                EmbeddedAssetPresentation.FILE
            }
            gateway.import(
                desktopEmbeddedAssetSelection(
                    file = file,
                    presentation = presentation,
                    source = EmbeddedAssetImportSource.DESKTOP_CLIPBOARD,
                ),
            )
        }
        return@runCatching files.isNotEmpty()
    }
    if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return@runCatching false
    val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
        ?: return@runCatching false
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return@runCatching false
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = buffered.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    val temporary = File.createTempFile("teamtalk-clipboard-", ".png")
    if (!ImageIO.write(buffered, "png", temporary)) {
        temporary.delete()
        return@runCatching false
    }
    gateway.import(
        desktopEmbeddedAssetSelection(
            file = temporary,
            presentation = EmbeddedAssetPresentation.IMAGE,
            source = EmbeddedAssetImportSource.DESKTOP_CLIPBOARD,
            deleteAfterImport = true,
        ),
    )
    true
}.getOrDefault(false)

/** 共享认证上传传输的 Desktop picker/drop/clipboard 适配器。 */
internal class DesktopEmbeddedAssetImportGateway(
    private val resources: DesktopSessionResources,
    private val transfer: DesktopFileTransfer,
    private val publishOnUi: (() -> Unit) -> Unit,
) : EmbeddedAssetImportGateway, Closeable {
    private val scope = resources.childScope("embedded-asset-import")
    private val bindings = EmbeddedAssetImportBindingRouter()
    private val retryStore = EmbeddedAssetImportRetryStore<Unit>(
        releaseSelection = ::releaseDesktopEmbeddedAssetSelection,
    )

    override fun bind(
        ownerKey: String,
        sink: EmbeddedAssetImportEventSink,
        acceptNewImports: Boolean,
    ): EmbeddedAssetImportRegistration {
        val registration = bindings.bind(ownerKey, sink, acceptNewImports)
        retryStore.replay(ownerKey).forEach { job ->
            sink.publish(EmbeddedAssetImportEvent.StateChanged(job = job, placement = null))
        }
        return registration
    }

    override fun select(presentation: EmbeddedAssetPresentation) {
        val binding = bindings.captureForImport() ?: return
        val file = when (presentation) {
            EmbeddedAssetPresentation.IMAGE -> DesktopFilePicker.chooseImage()
            EmbeddedAssetPresentation.FILE -> DesktopFilePicker.chooseFile("插入文件")
        } ?: return
        import(
            desktopEmbeddedAssetSelection(
                file = file,
                presentation = presentation,
                source = EmbeddedAssetImportSource.DESKTOP_PICKER,
            ),
            binding,
        )
    }

    override fun import(selection: EmbeddedAssetLocalSelection) {
        val binding = bindings.captureForImport() ?: run {
            releaseDesktopEmbeddedAssetSelection(selection)
            return
        }
        import(selection, binding)
    }

    override fun cancel(jobId: String): Boolean {
        val cancelled = retryStore.cancel(jobId) ?: return false
        publishTerminal(
            cancelled.binding,
            EmbeddedAssetImportEvent.StateChanged(cancelled.job, placement = null),
        )
        return true
    }

    override fun retry(jobId: String): Boolean {
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
        val assetId = UUID.randomUUID().toString()
        val job = PendingAssetJob(jobId = UUID.randomUUID().toString(), assetId = assetId)
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
                uploadId = UUID.randomUUID().toString(),
                issuedAt = System.currentTimeMillis(),
            ),
        ) ?: run {
            releaseDesktopEmbeddedAssetSelection(selection)
            return
        }
        publishInitial(binding, EmbeddedAssetImportEvent.StateChanged(job, placement))
        launchAttempt(attempt)
    }

    private fun launchAttempt(attempt: EmbeddedAssetImportRetryStore.Attempt<Unit>) {
        val file = File(attempt.selection.localReference)
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

    /** Placement 是一次性的，并且总是先于 Desktop UI 队列上的每次 attempt 帧发布。 */
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
