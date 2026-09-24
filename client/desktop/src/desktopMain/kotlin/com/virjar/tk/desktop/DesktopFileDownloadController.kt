package com.virjar.tk.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.desktop.media.DesktopMediaCacheQuotaException
import com.virjar.tk.desktop.media.DesktopMediaDownloadSizeException
import com.virjar.tk.desktop.media.DesktopMediaFileLease
import com.virjar.tk.desktop.media.DesktopMediaSupersededCredentialException
import com.virjar.tk.desktop.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.desktop.media.DesktopSessionUnavailableException
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.log.AppLog
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.UserFeedbackNotice
import com.virjar.tk.app.telemetry.classifyMediaFailure
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadCore
import com.virjar.tk.app.ui.component.FileDownloadCoreAdapter
import com.virjar.tk.app.ui.component.FileDownloadPendingAction
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.app.ui.component.TextAttachmentPreviewPlan
import com.virjar.tk.app.ui.component.textAttachmentPreviewPlan
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop 文件附件控制器：编排委托三端共享的 [FileDownloadCore]，平台侧保留
 * 文本附件预览窗口管线、系统打开与"另存为"对话框。网络与落盘统一委托给会话媒体缓存。
 */
internal class DesktopFileDownloadController(
    private val resources: DesktopSessionResources,
    uiScope: CoroutineScope,
    private val actionAdmission: UiActionAdmission,
    private val onDownloaded: (File) -> Unit,
    private val onTextAttachmentPreview: ((DesktopTextAttachmentPreviewEvent) -> Deferred<Boolean>?)? = null,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    private val telemetryPage: ClientUiPage = ClientUiPage.CHAT,
    /** 本地用户反馈出口（Snackbar）；遥测只承担服务端可诊断性，不承担本地呈现。 */
    private val onUserNotice: (UserFeedbackNotice) -> Unit = {},
    ownerThreadPredicate: () -> Boolean = EventQueue::isDispatchThread,
) : FileDownloadController {

    /** 预览 Loading 事件的投递确认等待；与核心工作域独立取消。 */
    private val entryScope = resources.childScope("file-download-entry")
    private val copyScope = resources.childScope("attachment-copy")
    private val closed = AtomicBoolean(false)
    private val core = FileDownloadCore<DesktopMediaFileLease>(
        DesktopAdapter(uiScope, ownerThreadPredicate),
        telemetry,
        telemetryPage,
    )

    override val states: SnapshotStateMap<String, FileDownloadState>
        get() = core.states
    override val automaticDownloadLedger: AutomaticFileDownloadLedger
        get() = core.automaticDownloadLedger

    override fun ensure(attachment: Attachment) = core.ensure(attachment)

    override fun download(attachment: Attachment) = core.download(attachment)

    override fun openOrDownload(attachment: Attachment) {
        if (onTextAttachmentPreview != null &&
            desktopAttachmentOpenTarget(attachment, previewEnabled = true) == DesktopAttachmentOpenTarget.PREVIEW
        ) {
            val previewPlan = textAttachmentPreviewPlan(attachment)
            if (previewPlan !is TextAttachmentPreviewPlan.Preview) return
            val loadingDelivery = publishTextPreview(DesktopTextAttachmentPreviewEvent.Loading(attachment)) ?: return
            entryScope.launch {
                if (loadingDelivery.await() && !closed.get() && resources.canDeliverUiResult()) {
                    core.act(attachment, FileDownloadPendingAction.PREVIEW)
                }
            }
            return
        }
        core.act(attachment, FileDownloadPendingAction.OPEN)
    }

    fun openExternally(attachment: Attachment) {
        core.act(attachment, FileDownloadPendingAction.OPEN)
    }

    /**
     * 另存为：已下载则直接弹保存对话框；未下载先走既有认证下载，完成后弹窗（T007）。
     * 返回 true 只表示动作被接受；导出结果通过对话框与错误提示呈现。
     */
    override fun exportToUserLocation(attachment: Attachment): Boolean {
        if (!resources.canDeliverUiResult()) return false
        core.act(attachment, FileDownloadPendingAction.EXPORT)
        return true
    }

    override fun copyAttachmentImage(attachment: Attachment, onResult: (Boolean) -> Unit) {
        if (!resources.canDeliverUiResult()) {
            AppLog.fault("ImageCopy", "copy rejected: session cannot deliver UI result", null)
            onResult(false)
            return
        }
        copyScope.launch {
            val ok = try {
                copyImageToSystemClipboard(attachment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.fault(
                    "ImageCopy",
                    "copy crashed: ${failure.javaClass.simpleName}: ${failure.message}",
                    failure,
                )
                false
            }
            onResult(ok)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        entryScope.cancel()
        copyScope.cancel()
        core.close()
    }

    /** 本地优先：缓存缺失即认证下载；位图进系统剪贴板（macOS/Windows 原生图片格式）。 */
    private suspend fun copyImageToSystemClipboard(attachment: Attachment): Boolean {
        resources.ensureOpen()
        val lease = try {
            resources.mediaCache.ensureDownloadedLease(attachment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.fault(
                "ImageCopy",
                "lease/download failed: ${failure.javaClass.simpleName}: ${failure.message}",
                failure,
            )
            return false
        }
        lease.use {
            resources.ensureOpen()
            if (!lease.file.isFile || lease.file.length() != attachment.size) {
                AppLog.fault(
                    "ImageCopy",
                    "cached file mismatch: isFile=${lease.file.isFile} " +
                        "length=${lease.file.length()} expected=${attachment.size}",
                    null,
                )
                return false
            }
            val image = try {
                withContext(Dispatchers.IO) {
                    decodeAwtImageForClipboard(lease.file, resources.diagnostics)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.fault(
                    "ImageCopy",
                    "decode crashed: ${failure.javaClass.simpleName}: ${failure.message}",
                    failure,
                )
                return false
            }
            if (image == null) {
                AppLog.fault("ImageCopy", "decode returned null: ${lease.file.name}", null)
                return false
            }
            val written = withContext(Dispatchers.Swing) { DesktopImageClipboard.write(image) }
            if (!written) {
                AppLog.fault("ImageCopy", "clipboard setContents returned failure", null)
            }
            return written
        }
    }

    /**
     * 保存失败经应用内 Snackbar 反馈，不弹 Swing 对话框；任意异常文本不进
     * 用户可见文案，只发经过审查的稳定文案，完整上下文落本地日志并上报遥测。
     */
    private suspend fun notifyAttachmentExportFailure(failure: Exception) {
        if (closed.get() || !resources.canDeliverUiResult()) return
        AppLog.fault(
            "AttachmentExport",
            "attachment export failed: ${failure.javaClass.simpleName}: ${failure.message}",
            failure,
        )
        val notice = UserFeedbackNotice(
            feedbackCode = UserFeedbackCode.MEDIA_IO_FAILED,
            page = telemetryPage,
            action = ClientUiAction.DOWNLOAD_MEDIA,
            origin = FeedbackOrigin.INLINE,
        )
        telemetry.recordUserNotice(notice)
        onUserNotice(notice)
    }

    private fun publishTextPreview(event: DesktopTextAttachmentPreviewEvent): Deferred<Boolean>? {
        var delivery: Deferred<Boolean>? = null
        try {
            delivery = onTextAttachmentPreview?.invoke(event)
            return delivery
        } finally {
            if (delivery == null) event.releaseLease()
        }
    }

    private suspend fun exactCachedLease(attachment: Attachment): DesktopMediaFileLease? {
        val lease = resources.mediaCache.cachedLease(attachment) ?: return null
        return if (lease.file.isFile && lease.file.length() == attachment.size) {
            lease
        } else {
            lease.close()
            null
        }
    }

    private inner class DesktopAdapter(
        private val ownerUiScope: CoroutineScope,
        private val ownerThread: () -> Boolean,
    ) : FileDownloadCoreAdapter<DesktopMediaFileLease> {
        override val uiScope: CoroutineScope
            get() = ownerUiScope

        override fun createWorkerScope(): CoroutineScope = resources.childScope("file-download")

        override fun isOwnerThread(): Boolean = ownerThread()

        override fun isOwnerCurrent(): Boolean = !closed.get() && resources.canDeliverUiResult()

        override fun runIfOpen(action: () -> Unit): Boolean =
            actionAdmission.runIfOpen {
                if (resources.canDeliverUiResult()) action()
            }

        override suspend fun probeCached(attachment: Attachment): Boolean =
            resources.mediaCache.cachedFile(attachment) != null

        override suspend fun cachedLease(attachment: Attachment): DesktopMediaFileLease? =
            exactCachedLease(attachment)

        override suspend fun downloadToCache(
            attachment: Attachment,
            onProgress: (Float) -> Unit,
        ): DesktopMediaFileLease = resources.mediaCache.ensureDownloadedLease(attachment, onProgress)

        override fun closeLease(lease: DesktopMediaFileLease) = lease.close()

        override suspend fun performPendingAction(
            action: FileDownloadPendingAction,
            lease: DesktopMediaFileLease,
            attachment: Attachment,
        ): Boolean = when (action) {
            FileDownloadPendingAction.OPEN -> {
                if (!lease.file.isFile || lease.file.length() != attachment.size) {
                    throw DesktopMediaDownloadSizeException("缓存文件大小与附件声明不一致")
                }
                onDownloaded(lease.file)
                false
            }
            FileDownloadPendingAction.PREVIEW -> {
                if (!lease.file.isFile || lease.file.length() != attachment.size) {
                    throw DesktopMediaDownloadSizeException("缓存文件大小与附件声明不一致")
                }
                val event = DesktopTextAttachmentPreviewEvent.Ready(attachment, lease)
                val accepted = try {
                    publishTextPreview(event)?.await() == true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    event.releaseLease()
                    throw cancelled
                } catch (_: Exception) {
                    event.releaseLease()
                    false
                }
                if (!accepted) event.releaseLease()
                true
            }
            FileDownloadPendingAction.EXPORT -> {
                exportDesktopAttachment(
                    lease = lease,
                    attachment = attachment,
                    ensureOpen = {
                        resources.ensureOpen()
                        check(!closed.get() && resources.canDeliverUiResult()) { "Attachment export owner is closed" }
                    },
                    onFailure = ::notifyAttachmentExportFailure,
                )
                true
            }
        }

        override fun classifyFailure(failure: Throwable) = classifyDesktopMediaFailure(failure)

        override fun warn(message: String) {
            // Desktop 失败经会话诊断事件与对话框呈现，不落全局日志。
        }

        override fun onDownloadFailedDiagnostic() {
            resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED)
        }

        override fun onOpenFailedDiagnostic() {
            resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_OPEN_FAILED)
        }

        override fun onPendingActionFailed(
            action: FileDownloadPendingAction,
            attachment: Attachment,
            message: String,
        ) {
            if (action == FileDownloadPendingAction.PREVIEW) {
                publishTextPreview(DesktopTextAttachmentPreviewEvent.Failed(attachment, message))
            }
        }
    }
}

/** 仅按类型分类：异常文本、URL 与文件路径绝不会进入 telemetry；公共分类核心在 app 层共享。 */
internal fun classifyDesktopMediaFailure(failure: Throwable): MediaFailureReason = classifyMediaFailure(failure) { error ->
    when (error) {
        is DesktopMediaCacheQuotaException -> MediaFailureReason.CACHE_QUOTA
        is DesktopMediaDownloadSizeException -> MediaFailureReason.SIZE_VALIDATION
        is DesktopSessionUnavailableException,
        is DesktopMediaSupersededCredentialException,
        is AppError.AuthExpired,
        -> MediaFailureReason.SESSION
        else -> null
    }
}

/** Desktop 下载层只负责把现有缓存文件交给预览窗口；内容分类和解码由 commonMain 统一。 */
internal sealed interface DesktopTextAttachmentPreviewEvent {
    val attachment: Attachment

    data class Loading(override val attachment: Attachment) : DesktopTextAttachmentPreviewEvent
    data class Ready(
        override val attachment: Attachment,
        val lease: DesktopMediaFileLease,
    ) : DesktopTextAttachmentPreviewEvent {
        val file: File get() = lease.file
    }
    data class Failed(
        override val attachment: Attachment,
        val message: String,
    ) : DesktopTextAttachmentPreviewEvent
}

internal fun DesktopTextAttachmentPreviewEvent?.releaseLease() {
    (this as? DesktopTextAttachmentPreviewEvent.Ready)?.lease?.close()
}

/**
 * 持有 Compose host 渲染的那一个预览事件。Ready 事件携带其缓存租约；
 * 替换、关闭、被忽略的迟到投递以及 host 销毁都会且仅会释放租约一次。
 */
internal class DesktopTextAttachmentPreviewOwner(
    private val scope: CoroutineScope,
    private val state: MutableState<DesktopTextAttachmentPreviewEvent?>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ownershipLock = Any()
    private var requestedPath: String? = state.value?.attachment?.path

    fun offer(event: DesktopTextAttachmentPreviewEvent): Deferred<Boolean> {
        val admitted = synchronized(ownershipLock) {
            if (closed.get()) return@synchronized false
            if (event is DesktopTextAttachmentPreviewEvent.Loading) {
                requestedPath = event.attachment.path
            }
            true
        }
        if (!admitted) {
            event.releaseLease()
            return CompletableDeferred(false)
        }
        val adopted = AtomicBoolean(false)
        val delivery = scope.async {
            var previous: DesktopTextAttachmentPreviewEvent? = null
            val accepted = synchronized(ownershipLock) {
                if (closed.get() || requestedPath != event.attachment.path) return@synchronized false
                previous = state.value
                state.value = event
                adopted.set(true)
                true
            }
            if (accepted) previous.releaseLease()
            accepted
        }
        delivery.invokeOnCompletion {
            if (!adopted.get()) event.releaseLease()
        }
        return delivery
    }

    fun clear() {
        val previous = synchronized(ownershipLock) {
            requestedPath = null
            val current = state.value
            state.value = null
            current
        }
        previous.releaseLease()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clear()
    }
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
