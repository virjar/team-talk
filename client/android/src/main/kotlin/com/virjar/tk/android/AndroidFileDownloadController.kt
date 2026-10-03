package com.virjar.tk.android

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.classifyMediaFailure
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadCore
import com.virjar.tk.app.ui.component.FileDownloadCoreAdapter
import com.virjar.tk.app.ui.component.FileDownloadPendingAction
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.app.ui.component.FileOpenBehavior
import com.virjar.tk.app.ui.component.platformFileOpenBehavior
import com.virjar.tk.app.ui.component.textAttachmentPreviewKind
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 附件控制器只拥有 Android 缓存/会话适配和系统文件动作。
 * 准入、缓存探测、下载去重、代次状态与终态发布统一由 app 的 FileDownloadCore 管理。
 */
class AndroidFileDownloadController private constructor(
    cacheRootProvider: () -> File,
    private val mediaSession: AndroidMediaSession,
    uiScope: CoroutineScope,
    private val onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    private val telemetryPage: ClientUiPage = ClientUiPage.CHAT,
    private val externalOpener: (File, String) -> Unit,
    private val downloadToCache: suspend (
        File,
        AndroidMediaSession,
        Attachment,
        ((Float) -> Unit)?,
    ) -> AndroidMediaCacheFileLease,
    private val warningLogger: (String, String) -> Unit,
    private val beforeOwnerGenerationClaim: () -> Unit,
    ownerThreadPredicate: () -> Boolean,
    private val workerDispatcher: CoroutineDispatcher,
    /** 应用上下文；仅在真实客户端注入，用于把附件导出到相册/下载。测试替身保持 null。 */
    private val appContext: Context? = null,
    private val requestExportPermission: suspend () -> Boolean = { false },
) : FileDownloadController {
    constructor(
        context: Context,
        mediaSession: AndroidMediaSession,
        uiScope: CoroutineScope,
        onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
        telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
        telemetryPage: ClientUiPage = ClientUiPage.CHAT,
        requestExportPermission: suspend () -> Boolean,
    ) : this(
        appContext = context.applicationContext,
        requestExportPermission = requestExportPermission,
        cacheRootProvider = context.applicationContext.let { applicationContext ->
            { applicationContext.cacheDir }
        },
        mediaSession = mediaSession,
        uiScope = uiScope,
        onTextAttachmentPreview = onTextAttachmentPreview,
        telemetry = telemetry,
        telemetryPage = telemetryPage,
        externalOpener = context.applicationContext.let { applicationContext ->
            { file, contentType -> MediaHelper.openFile(applicationContext, file, contentType) }
        },
        downloadToCache = { root, session, attachment, onProgress ->
            downloadAttachmentToCacheLease(root, session, attachment, onProgress = onProgress)
        },
        warningLogger = { tag, message -> Log.w(tag, message) },
        beforeOwnerGenerationClaim = {},
        ownerThreadPredicate = { Looper.myLooper() === Looper.getMainLooper() },
        workerDispatcher = Dispatchers.IO,
    )

    internal constructor(
        testCacheRootProvider: () -> File,
        mediaSession: AndroidMediaSession,
        uiScope: CoroutineScope,
        onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
        telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
        telemetryPage: ClientUiPage = ClientUiPage.CHAT,
        externalOpener: (File, String) -> Unit,
        downloadToCache: suspend (
            File,
            AndroidMediaSession,
            Attachment,
            ((Float) -> Unit)?,
        ) -> AndroidMediaCacheFileLease = { root, session, attachment, onProgress ->
            downloadAttachmentToCacheLease(root, session, attachment, onProgress = onProgress)
        },
        warningLogger: (String, String) -> Unit = { _, _ -> },
        beforeOwnerGenerationClaim: () -> Unit = {},
        ownerThreadPredicate: () -> Boolean,
        workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        cacheRootProvider = testCacheRootProvider,
        mediaSession = mediaSession,
        uiScope = uiScope,
        onTextAttachmentPreview = onTextAttachmentPreview,
        telemetry = telemetry,
        telemetryPage = telemetryPage,
        externalOpener = externalOpener,
        downloadToCache = downloadToCache,
        warningLogger = warningLogger,
        beforeOwnerGenerationClaim = beforeOwnerGenerationClaim,
        ownerThreadPredicate = ownerThreadPredicate,
        workerDispatcher = workerDispatcher,
    )

    /** 只由工作协程求值，不在控制器构造期间访问平台缓存目录。 */
    private val cacheRoot: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED, cacheRootProvider)
    private val ownerUiScope = uiScope
    private val closed = AtomicBoolean(false)
    private val externalOpenLeaseLock = Any()
    private var externalOpenLease: AndroidMediaCacheFileLease? = null

    private val core = FileDownloadCore(
        adapter = AndroidAdapter(ownerThreadPredicate),
        telemetry = telemetry,
        telemetryPage = telemetryPage,
    )

    override val states: SnapshotStateMap<String, FileDownloadState>
        get() = core.states

    override val automaticDownloadLedger: AutomaticFileDownloadLedger
        get() = core.automaticDownloadLedger

    override fun ensure(attachment: Attachment) = core.ensure(attachment)

    override fun download(attachment: Attachment) = core.download(attachment)

    override fun openOrDownload(attachment: Attachment) {
        if (closed.get()) return
        if (!mediaSession.isCurrentOwner()) {
            core.act(attachment, FileDownloadPendingAction.OPEN)
            return
        }

        retireExternalOpenLease()
        if (onTextAttachmentPreview != null && textAttachmentPreviewKind(attachment) != null) {
            openTextPreview(attachment)
            return
        }
        // apk 的“打开”即拉起安装器；zip/exe 等没有可靠处理器，直接走另存为落位。
        if (platformFileOpenBehavior(attachment) == FileOpenBehavior.SAVE_ONLY) {
            exportToUserLocation(attachment)
            return
        }
        core.act(attachment, FileDownloadPendingAction.OPEN)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        core.close()
    }

    /** 新准入的打开请求会在预留字节之前，替换掉上一个系统交接。 */
    private fun retireExternalOpenLease() {
        val lease = synchronized(externalOpenLeaseLock) {
            externalOpenLease.also { externalOpenLease = null }
        }
        lease?.close()
    }

    private fun retainExternalOpenLease(lease: AndroidMediaCacheFileLease): Boolean {
        var previousLease: AndroidMediaCacheFileLease? = null
        var retained = false
        mediaSession.runIfOpen {
            synchronized(externalOpenLeaseLock) {
                if (!closed.get()) {
                    previousLease = externalOpenLease
                    externalOpenLease = lease
                    retained = true
                }
            }
        }
        previousLease?.close()
        return retained
    }

    private fun releaseExternalOpenLease() {
        val lease = synchronized(externalOpenLeaseLock) {
            externalOpenLease.also { externalOpenLease = null }
        }
        lease?.close()
    }

    private fun openTextPreview(attachment: Attachment) {
        recordMedia(MediaOperation.PREVIEW, ClientActionOutcome.STARTED)
        try {
            checkNotNull(onTextAttachmentPreview).invoke(attachment)
        } catch (cancelled: CancellationException) {
            recordMedia(MediaOperation.PREVIEW, ClientActionOutcome.CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            val outcome = if (closed.get() || !mediaSession.isCurrentOwner()) {
                ClientActionOutcome.CANCELLED
            } else {
                ClientActionOutcome.FAILED
            }
            recordMedia(
                MediaOperation.PREVIEW,
                outcome,
                MediaFailureReason.UNKNOWN.takeIf { outcome == ClientActionOutcome.FAILED },
            )
            return
        }
        recordMedia(
            MediaOperation.PREVIEW,
            if (closed.get() || !mediaSession.isCurrentOwner()) {
                ClientActionOutcome.CANCELLED
            } else {
                ClientActionOutcome.SUCCEEDED
            },
        )
    }

    /**
     * 保存到设备：权限先于缓存探测/下载；完整本地文件导出到系统相册或下载目录。
     * 返回 true 表示动作已受理；结果通过系统 Toast 呈现。
     */
    override fun exportToUserLocation(attachment: Attachment): Boolean {
        val context = appContext ?: return false
        if (closed.get() || !mediaSession.isCurrentOwner()) return false

        return core.actWithLease(
            attachment = attachment,
            prepare = {
                if (!requestExportPermission()) {
                    showExportFeedback(context, "未获得存储权限，无法保存；可在系统应用设置中开启")
                    false
                } else {
                    mediaSession.ensureOpen()
                    !closed.get()
                }
            },
            perform = { lease, selectedAttachment ->
                val exportJob = checkNotNull(currentCoroutineContext()[Job])
                mediaSession.withRegisteredOperation(abort = { exportJob.cancel() }) {
                    val ok = exportAndroidAttachmentToUserLocation(
                        context = context,
                        file = lease.file,
                        attachment = selectedAttachment,
                        workerDispatcher = workerDispatcher,
                        ensureOwnerOpen = {
                            mediaSession.ensureOpen()
                            check(!closed.get()) { "Attachment controller is closed" }
                        },
                    )
                    val message = when {
                        ok &&
                            (selectedAttachment.contentType.startsWith("image/") ||
                                selectedAttachment.contentType.startsWith("video/")) -> "已保存到相册"
                        ok -> "已保存到下载"
                        else -> "保存失败"
                    }
                    showExportFeedback(context, message)
                }
                false
            },
        )
    }

    private fun showExportFeedback(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            mediaSession.runIfOpen {
                if (!closed.get()) {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 复制图片：本地优先（缺失即走既有认证下载），以 FileProvider 内容 URI 进系统剪贴板。
     * 动作完成前持有缓存租约和会话操作注册；失败回调由调用方回落复制文本摘要。
     */
    override fun copyAttachmentImage(attachment: Attachment, onResult: (Boolean) -> Unit) {
        val context = appContext
        if (context == null || closed.get() || !mediaSession.isCurrentOwner()) {
            onResult(false)
            return
        }
        val reported = AtomicBoolean(false)
        fun report(ok: Boolean) {
            if (reported.compareAndSet(false, true)) onResult(ok)
        }

        val accepted = core.actWithLease(
            attachment = attachment,
            prepare = {
                mediaSession.ensureOpen()
                !closed.get()
            },
            onAbandoned = { report(false) },
        ) { lease, selectedAttachment ->
            val copyJob = checkNotNull(currentCoroutineContext()[Job])
            mediaSession.withRegisteredOperation(abort = { copyJob.cancel() }) {
                mediaSession.ensureOpen()
                val file = lease.file.takeIf { it.length() == selectedAttachment.size }
                val ok = file != null && writeImageClip(context, checkNotNull(file), selectedAttachment.name)
                showExportFeedback(context, if (ok) "已复制图片" else "复制失败")
                report(ok)
            }
            false
        }
        if (!accepted) report(false)
    }

    private suspend fun writeImageClip(context: Context, file: File, label: String): Boolean = try {
        withContext(Dispatchers.Main.immediate) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val clip = android.content.ClipData.newUri(context.contentResolver, label, uri)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(clip)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun cachedFile(attachment: Attachment): File =
        attachmentCacheFile(cacheRoot, mediaSession.cacheNamespace, attachment)

    private fun recordMedia(
        operation: MediaOperation,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason? = null,
    ) {
        telemetry.recordMedia(telemetryPage, ClientMediaKind.FILE, operation, outcome, reason)
    }

    private inner class AndroidAdapter(
        private val ownerThreadPredicate: () -> Boolean,
    ) : FileDownloadCoreAdapter<AndroidMediaCacheFileLease> {
        override val uiScope: CoroutineScope
            get() = ownerUiScope

        override fun createWorkerScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + workerDispatcher + CoroutineName("android-file-download-worker"),
        )

        override fun isOwnerThread(): Boolean = ownerThreadPredicate()

        override fun beforeOwnerGenerationClaim() {
            this@AndroidFileDownloadController.beforeOwnerGenerationClaim()
        }

        override fun isOwnerCurrent(): Boolean = !closed.get() && mediaSession.isCurrentOwner()

        override fun runIfOpen(action: () -> Unit): Boolean {
            var published = false
            val open = mediaSession.runIfOpen {
                if (!closed.get()) {
                    action()
                    published = true
                }
            }
            return open && published
        }

        override suspend fun probeCached(attachment: Attachment): Boolean =
            isValidAttachmentCacheFile(cachedFile(attachment), attachment)

        override suspend fun cachedLease(attachment: Attachment): AndroidMediaCacheFileLease? = try {
            val root = cacheRoot
            AndroidMediaCacheCapacityRegistry.cachedLease(
                cacheRoot = root,
                file = cachedFile(attachment),
                expectedBytes = attachment.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        override suspend fun downloadToCache(
            attachment: Attachment,
            onProgress: (Float) -> Unit,
        ): AndroidMediaCacheFileLease = downloadToCache(
            cacheRoot,
            mediaSession,
            attachment,
            onProgress,
        )

        override fun closeLease(lease: AndroidMediaCacheFileLease) = lease.close()

        override suspend fun performPendingAction(
            action: FileDownloadPendingAction,
            lease: AndroidMediaCacheFileLease,
            attachment: Attachment,
        ): Boolean = when (action) {
            FileDownloadPendingAction.OPEN -> {
                externalOpener(lease.file, attachment.contentType)
                retainExternalOpenLease(lease)
            }
            FileDownloadPendingAction.PREVIEW -> {
                onTextAttachmentPreview?.invoke(attachment)
                false
            }
            // Android invokes exports through actWithLease so permission is requested before downloading.
            FileDownloadPendingAction.EXPORT -> error("Android export requires its permission preflight")
        }

        override fun classifyFailure(failure: Throwable): MediaFailureReason =
            classifyAndroidMediaFailure(failure)

        override fun warn(message: String) {
            warningLogger("FileDownload", message)
        }

        override fun onClosed() {
            releaseExternalOpenLease()
        }
    }
}

/** 仅按类型分类：不观察异常消息、URI 或本地文件名；公共分类核心在 app 层共享。 */
internal fun classifyAndroidMediaFailure(failure: Throwable): MediaFailureReason = classifyMediaFailure(failure) { error ->
    when (error) {
        is MediaCacheQuotaException -> MediaFailureReason.CACHE_QUOTA
        is MediaDownloadSizeException, is SelectedMediaTooLargeException -> MediaFailureReason.SIZE_VALIDATION
        is AndroidMediaSupersededCredentialException, is AppError.AuthExpired -> MediaFailureReason.SESSION
        else -> null
    }
}
