package com.virjar.tk

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.media.DesktopSessionResources
import com.virjar.tk.model.Attachment
import com.virjar.tk.ui.component.FileDownloadController
import com.virjar.tk.ui.component.FileDownloadState
import com.virjar.tk.ui.component.TextAttachmentPreviewPlan
import com.virjar.tk.ui.component.textAttachmentPreviewPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Desktop 文件附件控制器：只管理页面状态，网络与落盘统一委托给会话媒体缓存。 */
internal class DesktopFileDownloadController(
    private val resources: DesktopSessionResources,
    private val onDownloaded: (File) -> Unit,
    private val onTextAttachmentPreview: ((DesktopTextAttachmentPreviewEvent) -> Unit)? = null,
) : FileDownloadController {

    override val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()

    private val scope: CoroutineScope = resources.childScope("file-download")
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableMapOf<String, PendingOpenMode>()
    private val mutex = Mutex()

    override fun ensure(attachment: Attachment) {
        if (states.containsKey(attachment.path)) return
        states[attachment.path] = if (cachedFile(attachment)?.isFile == true) {
            FileDownloadState.Done
        } else {
            FileDownloadState.Idle
        }
    }

    override fun download(attachment: Attachment) {
        scope.launch { downloadInternal(attachment) }
    }

    override fun openOrDownload(attachment: Attachment) {
        val previewPlan = textAttachmentPreviewPlan(attachment)
        if (desktopAttachmentOpenTarget(attachment, onTextAttachmentPreview != null) ==
            DesktopAttachmentOpenTarget.PREVIEW
        ) {
            onTextAttachmentPreview?.invoke(DesktopTextAttachmentPreviewEvent.Loading(attachment))
            if (previewPlan !is TextAttachmentPreviewPlan.Preview) return
            val cached = cachedFile(attachment)
            if (cached?.isFile == true) {
                states[attachment.path] = FileDownloadState.Done
                openCached(cached, attachment, PendingOpenMode.PREVIEW)
                return
            }
            scope.launch { downloadInternal(attachment, PendingOpenMode.PREVIEW) }
            return
        }
        val cached = cachedFile(attachment)
        if (cached?.isFile == true) {
            states[attachment.path] = FileDownloadState.Done
            openCached(cached, attachment, PendingOpenMode.EXTERNAL)
            return
        }
        scope.launch { downloadInternal(attachment, PendingOpenMode.EXTERNAL) }
    }

    fun openExternally(attachment: Attachment) {
        val cached = cachedFile(attachment)
        if (cached?.isFile == true) {
            states[attachment.path] = FileDownloadState.Done
            openCached(cached, attachment, PendingOpenMode.EXTERNAL)
            return
        }
        scope.launch { downloadInternal(attachment, PendingOpenMode.EXTERNAL) }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun downloadInternal(
        attachment: Attachment,
        openWhenDone: PendingOpenMode? = null,
    ) {
        val key = attachment.path
        val shouldStart = mutex.withLock {
            if (openWhenDone != null) openAfterDownload[key] = openWhenDone
            inFlight.add(key)
        }
        if (!shouldStart) return

        try {
            states[key] = FileDownloadState.Downloading(0f)
            var lastEmit = 0L
            val target = resources.mediaCache.ensureDownloaded(
                reference = attachment.path,
                suggestedFileName = attachment.name,
            ) { progress ->
                val now = System.currentTimeMillis()
                if (progress >= 1f || now - lastEmit >= 100) {
                    lastEmit = now
                    states[key] = FileDownloadState.Downloading(progress)
                }
            }
            resources.ensureOpen()
            states[key] = FileDownloadState.Done
            val pendingOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (pendingOpen != null) openCached(target, attachment, pendingOpen)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (runCatching { resources.ensureOpen() }.isFailure) return
            com.virjar.tk.util.AppLog.fault("FileDownload", "download failed path=$key: ${e.message}")
            states[key] = FileDownloadState.Failed(e.message)
            val pendingOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (pendingOpen == PendingOpenMode.PREVIEW) {
                onTextAttachmentPreview?.invoke(
                    DesktopTextAttachmentPreviewEvent.Failed(
                        attachment,
                        e.message ?: "附件下载失败，请稍后重试",
                    ),
                )
            }
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    private fun openCached(file: File, attachment: Attachment, mode: PendingOpenMode) {
        if (mode == PendingOpenMode.PREVIEW && onTextAttachmentPreview != null) {
            onTextAttachmentPreview.invoke(DesktopTextAttachmentPreviewEvent.Ready(attachment, file))
            return
        }
        runCatching { onDownloaded(file) }
            .onFailure { com.virjar.tk.util.AppLog.fault("FileDownload", "open failed ${file.name}: ${it.message}") }
    }

    private fun cachedFile(attachment: Attachment): File? =
        resources.mediaCache.cachedFile(attachment.path, attachment.name)

    private enum class PendingOpenMode { PREVIEW, EXTERNAL }
}

/** Desktop 下载层只负责把现有缓存文件交给预览窗口；内容分类和解码由 commonMain 统一。 */
internal sealed interface DesktopTextAttachmentPreviewEvent {
    val attachment: Attachment

    data class Loading(override val attachment: Attachment) : DesktopTextAttachmentPreviewEvent
    data class Ready(override val attachment: Attachment, val file: File) : DesktopTextAttachmentPreviewEvent
    data class Failed(
        override val attachment: Attachment,
        val message: String,
    ) : DesktopTextAttachmentPreviewEvent
}

internal enum class DesktopAttachmentOpenTarget { PREVIEW, EXTERNAL }

internal fun desktopAttachmentOpenTarget(
    attachment: Attachment,
    previewEnabled: Boolean,
): DesktopAttachmentOpenTarget = if (
    previewEnabled && textAttachmentPreviewPlan(attachment) !is TextAttachmentPreviewPlan.UseExternalApplication
) {
    DesktopAttachmentOpenTarget.PREVIEW
} else {
    DesktopAttachmentOpenTarget.EXTERNAL
}
