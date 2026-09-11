package com.virjar.tk.desktop

import com.virjar.tk.shared.AppError
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.desktop.media.DesktopMediaDownloadSizeException
import com.virjar.tk.desktop.media.DesktopMediaCacheQuotaException
import com.virjar.tk.desktop.media.DesktopMediaSupersededCredentialException
import com.virjar.tk.desktop.media.DesktopMediaFileLease
import com.virjar.tk.desktop.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.desktop.media.DesktopSessionUnavailableException
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.UserFeedbackNotice
import com.virjar.tk.app.telemetry.downloadFeedbackCode
import com.virjar.tk.app.telemetry.classifyMediaFailure
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.app.ui.component.FileDownloadStatePublisher
import com.virjar.tk.app.ui.component.TextAttachmentPreviewPlan
import com.virjar.tk.app.ui.component.textAttachmentPreviewPlan
import com.virjar.tk.app.ui.UiActionAdmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor

internal const val DESKTOP_ATTACHMENT_EXTERNAL_OPEN_FAILURE_MESSAGE =
    "无法打开文件，请检查是否安装了可处理此格式的应用"
/** Desktop 文件附件控制器：只管理页面状态，网络与落盘统一委托给会话媒体缓存。 */
internal class DesktopFileDownloadController(
    private val resources: DesktopSessionResources,
    uiScope: CoroutineScope,
    private val actionAdmission: UiActionAdmission,
    private val onDownloaded: (File) -> Unit,
    private val onTextAttachmentPreview: ((DesktopTextAttachmentPreviewEvent) -> Deferred<Boolean>?)? = null,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    private val telemetryPage: ClientUiPage = ClientUiPage.CHAT,
    ownerThreadPredicate: () -> Boolean = EventQueue::isDispatchThread,
) : FileDownloadController {

    private val scope: CoroutineScope = resources.childScope("file-download")
    override val automaticDownloadLedger = AutomaticFileDownloadLedger()
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableMapOf<String, PendingOpenMode>()
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val publicationLock = Any()
    private val statePublisher = FileDownloadStatePublisher(
        ownerScope = uiScope,
        publicationGate = { publication ->
            actionAdmission.runIfOpen {
                synchronized(publicationLock) {
                    if (!closed.get() && resources.canDeliverUiResult()) publication()
                }
            }
        },
        ownerThreadPredicate = ownerThreadPredicate,
    )
    override val states: SnapshotStateMap<String, FileDownloadState>
        get() = statePublisher.states

    override fun ensure(attachment: Attachment) {
        if (closed.get()) return
        if (states.containsKey(attachment.path)) return
        publishState(
            attachment.path,
            if (cachedFile(attachment) != null) FileDownloadState.Done else FileDownloadState.Idle,
        )
    }

    override fun download(attachment: Attachment) {
        if (closed.get()) return
        scope.launch { downloadInternal(attachment) }
    }

    override fun openOrDownload(attachment: Attachment) {
        if (closed.get()) return
        val previewPlan = textAttachmentPreviewPlan(attachment)
        if (desktopAttachmentOpenTarget(attachment, onTextAttachmentPreview != null) ==
            DesktopAttachmentOpenTarget.PREVIEW
        ) {
            val loadingDelivery = publishTextPreview(DesktopTextAttachmentPreviewEvent.Loading(attachment))
            if (previewPlan !is TextAttachmentPreviewPlan.Preview) return
            scope.launch {
                if (loadingDelivery?.await() != true || closed.get()) return@launch
                val cached = exactCachedLease(attachment)
                if (cached != null) {
                    recordView(PendingOpenMode.PREVIEW, ClientActionOutcome.STARTED)
                    publishState(attachment.path, FileDownloadState.Done)
                    openPreviewLease(cached, attachment, operationAlreadyStarted = true)
                } else {
                    downloadInternal(attachment, PendingOpenMode.PREVIEW)
                }
            }
            return
        }
        val cached = exactCachedLease(attachment)
        if (cached != null) {
            publishState(attachment.path, FileDownloadState.Done)
            openExternalLease(cached, attachment, operationAlreadyStarted = false)
            return
        }
        scope.launch { downloadInternal(attachment, PendingOpenMode.EXTERNAL) }
    }

    fun openExternally(attachment: Attachment) {
        if (closed.get()) return
        val cached = exactCachedLease(attachment)
        if (cached != null) {
            publishState(attachment.path, FileDownloadState.Done)
            openExternalLease(cached, attachment, operationAlreadyStarted = false)
            return
        }
        scope.launch { downloadInternal(attachment, PendingOpenMode.EXTERNAL) }
    }

    /**
     * 另存为：已下载则直接弹保存对话框；未下载先走既有认证下载，完成后弹窗（T007）。
     * 返回 true 只表示动作被接受；导出结果通过对话框与错误提示呈现。
     */
    override fun exportToUserLocation(attachment: Attachment): Boolean {
        if (closed.get()) return false
        val cached = exactCachedLease(attachment)
        if (cached != null) {
            publishState(attachment.path, FileDownloadState.Done)
            try {
                exportCachedFile(cached, attachment)
            } finally {
                cached.close()
            }
            return true
        }
        scope.launch { downloadInternal(attachment, PendingOpenMode.EXPORT) }
        return true
    }

    /** 校验缓存完整性后在用户选择的本地路径写出完整副本；文件名冲突由对话框内解决。 */
    private fun exportCachedFile(lease: DesktopMediaFileLease, attachment: Attachment) {
        if (!lease.file.isFile || lease.file.length() != attachment.size) {
            throw DesktopMediaDownloadSizeException("缓存文件大小与附件声明不一致")
        }
        val source = lease.file
        java.awt.EventQueue.invokeLater {
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = "保存到设备"
                selectedFile = java.io.File(attachment.name)
            }
            val chosen = chooser.showSaveDialog(null)
            if (chosen != javax.swing.JFileChooser.APPROVE_OPTION) return@invokeLater
            val target = chooser.selectedFile
            scope.launch {
                try {
                    java.nio.file.Files.copy(
                        source.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (e: Exception) {
                    if (!closed.get() && resources.canDeliverUiResult()) {
                        javax.swing.JOptionPane.showMessageDialog(
                            null,
                            "保存失败：${e.message ?: "无法写入所选位置"}",
                            "保存到设备",
                            javax.swing.JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            }
        }
    }

    override fun close() {
        val claimed = synchronized(publicationLock) {
            if (!closed.compareAndSet(false, true)) return@synchronized false
            statePublisher.close()
            true
        }
        if (!claimed) return
        scope.cancel()
    }

    private suspend fun downloadInternal(
        attachment: Attachment,
        openWhenDone: PendingOpenMode? = null,
    ) {
        if (closed.get()) return
        val key = attachment.path
        var viewStarted: PendingOpenMode? = null
        val shouldStart = mutex.withLock {
            if (openWhenDone != null && key !in openAfterDownload) {
                openAfterDownload[key] = openWhenDone
                viewStarted = openWhenDone
            }
            inFlight.add(key)
        }
        viewStarted?.let { recordView(it, ClientActionOutcome.STARTED) }
        if (!shouldStart) return

        var downloadLease: DesktopMediaFileLease? = null
        try {
            telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                ClientActionOutcome.STARTED,
            )
            publishState(key, FileDownloadState.Downloading(0f))
            var lastEmit = 0L
            val lease = resources.mediaCache.ensureDownloadedLease(attachment) { progress ->
                val now = System.currentTimeMillis()
                if (progress >= 1f || now - lastEmit >= 100) {
                    lastEmit = now
                    publishState(key, FileDownloadState.Downloading(progress))
                }
            }
            downloadLease = lease
            resources.ensureOpen()
            if (closed.get() || !resources.canDeliverUiResult()) {
                mutex.withLock { openAfterDownload.remove(key) }
                    ?.let { recordView(it, ClientActionOutcome.CANCELLED) }
                return
            }
            publishState(key, FileDownloadState.Done)
            telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                ClientActionOutcome.SUCCEEDED,
            )
            val pendingOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (pendingOpen != null) {
                downloadLease = null
                when (pendingOpen) {
                    PendingOpenMode.PREVIEW -> openPreviewLease(
                        lease,
                        attachment,
                        operationAlreadyStarted = true,
                    )
                    PendingOpenMode.EXTERNAL -> openExternalLease(
                        lease,
                        attachment,
                        operationAlreadyStarted = true,
                    )
                    PendingOpenMode.EXPORT -> exportCachedFile(lease, attachment)
                }
            } else {
                lease.close()
                downloadLease = null
            }
        } catch (cancelled: CancellationException) {
            mutex.withLock { openAfterDownload.remove(key) }
                ?.let { recordView(it, ClientActionOutcome.CANCELLED) }
            throw cancelled
        } catch (_: AppError.AuthExpired) {
            // 媒体缓存已经请求了精确会话退役。不要把这个终结信号变成可重试的下载诊断，也不要把 HTTP 错误暴露成文件文本。
            publishState(key, FileDownloadState.Idle)
            mutex.withLock { openAfterDownload.remove(key) }
                ?.let { recordView(it, ClientActionOutcome.CANCELLED) }
        } catch (e: Exception) {
            val pendingOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (!resources.canDeliverUiResult() || closed.get()) {
                pendingOpen?.let { recordView(it, ClientActionOutcome.CANCELLED) }
                return
            }
            val reason = classifyDesktopMediaFailure(e)
            val notice = UserFeedbackNotice(
                feedbackCode = reason.downloadFeedbackCode,
                page = telemetryPage,
                action = ClientUiAction.DOWNLOAD_MEDIA,
                origin = FeedbackOrigin.INLINE,
            )
            val noticeRecorded = AtomicBoolean(false)
            val confirmNotice = {
                if (noticeRecorded.compareAndSet(false, true)) telemetry.recordUserNotice(notice)
            }
            recordMedia(MediaOperation.DOWNLOAD, ClientActionOutcome.FAILED, reason)
            resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED)
            publishState(key, FileDownloadState.Failed(notice.publicMessage), confirmNotice)
            pendingOpen?.let { recordView(it, ClientActionOutcome.FAILED, reason) }
            if (pendingOpen == PendingOpenMode.PREVIEW) {
                val delivery = publishTextPreview(
                    DesktopTextAttachmentPreviewEvent.Failed(
                        attachment,
                        notice.publicMessage,
                    ),
                )
                if (delivery?.await() == true) confirmNotice()
            }
        } finally {
            downloadLease?.close()
            mutex.withLock { inFlight.remove(key) }
        }
    }

    private fun openCached(
        file: File,
        attachment: Attachment,
        operationAlreadyStarted: Boolean,
    ) {
        if (!operationAlreadyStarted) recordView(PendingOpenMode.EXTERNAL, ClientActionOutcome.STARTED)
        if (closed.get() || !resources.canDeliverUiResult()) {
            recordView(PendingOpenMode.EXTERNAL, ClientActionOutcome.CANCELLED)
            return
        }
        try {
            onDownloaded(file)
        } catch (cancelled: CancellationException) {
            recordView(PendingOpenMode.EXTERNAL, ClientActionOutcome.CANCELLED)
            throw cancelled
        } catch (_: AppError.AuthExpired) {
            // 认证终态由会话 owner 统一退休，不能降级成可重试的“打开失败”。
            recordView(PendingOpenMode.EXTERNAL, ClientActionOutcome.CANCELLED)
            return
        } catch (_: Exception) {
            resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_OPEN_FAILED)
            if (resources.canDeliverUiResult() && !closed.get()) {
                val notice = UserFeedbackNotice(
                    feedbackCode = UserFeedbackCode.MEDIA_OPEN_FAILED,
                    page = telemetryPage,
                    action = ClientUiAction.OPEN_MEDIA,
                    origin = FeedbackOrigin.INLINE,
                )
                recordView(
                    PendingOpenMode.EXTERNAL,
                    ClientActionOutcome.FAILED,
                    MediaFailureReason.UNSUPPORTED,
                )
                publishState(attachment.path, FileDownloadState.Failed(notice.publicMessage)) {
                    telemetry.recordUserNotice(notice)
                }
            } else {
                recordView(PendingOpenMode.EXTERNAL, ClientActionOutcome.CANCELLED)
            }
            return
        }
        recordView(
            PendingOpenMode.EXTERNAL,
            if (closed.get() || !resources.canDeliverUiResult()) {
                ClientActionOutcome.CANCELLED
            } else {
                ClientActionOutcome.SUCCEEDED
            },
        )
    }

    private fun cachedFile(attachment: Attachment): File? =
        resources.mediaCache.cachedFile(attachment)

    private fun exactCachedLease(attachment: Attachment): DesktopMediaFileLease? {
        val lease = resources.mediaCache.cachedLease(attachment) ?: return null
        return if (lease.file.isFile && lease.file.length() == attachment.size) {
            lease
        } else {
            lease.close()
            null
        }
    }

    private fun openExternalLease(
        lease: DesktopMediaFileLease,
        attachment: Attachment,
        operationAlreadyStarted: Boolean,
    ) {
        if (!lease.file.isFile || lease.file.length() != attachment.size) {
            lease.close()
            throw DesktopMediaDownloadSizeException("缓存文件大小与附件声明不一致")
        }
        lease.use { openCached(it.file, attachment, operationAlreadyStarted) }
    }

    private suspend fun openPreviewLease(
        lease: DesktopMediaFileLease,
        attachment: Attachment,
        operationAlreadyStarted: Boolean,
    ) {
        if (!operationAlreadyStarted) recordView(PendingOpenMode.PREVIEW, ClientActionOutcome.STARTED)
        if (!lease.file.isFile || lease.file.length() != attachment.size) {
            lease.close()
            recordView(
                PendingOpenMode.PREVIEW,
                ClientActionOutcome.FAILED,
                MediaFailureReason.SIZE_VALIDATION,
            )
            return
        }
        if (closed.get() || !resources.canDeliverUiResult()) {
            lease.close()
            recordView(PendingOpenMode.PREVIEW, ClientActionOutcome.CANCELLED)
            return
        }
        val event = DesktopTextAttachmentPreviewEvent.Ready(attachment, lease)
        try {
            val accepted = publishTextPreview(event)?.await() == true
            if (accepted && !closed.get() && resources.canDeliverUiResult()) {
                recordView(PendingOpenMode.PREVIEW, ClientActionOutcome.SUCCEEDED)
            } else {
                if (!accepted) event.releaseLease()
                recordView(PendingOpenMode.PREVIEW, ClientActionOutcome.CANCELLED)
            }
        } catch (cancelled: CancellationException) {
            event.releaseLease()
            recordView(PendingOpenMode.PREVIEW, ClientActionOutcome.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            event.releaseLease()
            recordView(
                PendingOpenMode.PREVIEW,
                if (closed.get() || !resources.canDeliverUiResult()) {
                    ClientActionOutcome.CANCELLED
                } else {
                    ClientActionOutcome.FAILED
                },
                MediaFailureReason.UNKNOWN,
            )
        }
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

    private fun publishState(
        key: String,
        state: FileDownloadState,
        onPublished: (() -> Unit)? = null,
    ) {
        if (closed.get()) return
        statePublisher.publish(key, state, onPublished = onPublished)
    }

    private fun recordView(
        mode: PendingOpenMode,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason? = null,
    ) {
        recordMedia(
            operation = when (mode) {
                PendingOpenMode.PREVIEW -> MediaOperation.PREVIEW
                PendingOpenMode.EXTERNAL -> MediaOperation.OPEN
                // 导出复用下载遥测；导出本身是用户可见文件操作，不占用预览/打开语义。
                PendingOpenMode.EXPORT -> MediaOperation.DOWNLOAD
            },
            outcome = outcome,
            reason = reason,
        )
    }

    private fun recordMedia(
        operation: MediaOperation,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason? = null,
    ) {
        telemetry.recordMedia(
            telemetryPage,
            ClientMediaKind.FILE,
            operation,
            outcome,
            reason,
        )
    }

    private enum class PendingOpenMode { PREVIEW, EXTERNAL, EXPORT }
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
