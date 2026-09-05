package com.virjar.tk.android

import com.virjar.tk.shared.AppError
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import com.virjar.tk.app.ui.component.AutomaticFileDownloadLedger
import com.virjar.tk.app.ui.component.FileDownloadController
import com.virjar.tk.app.ui.component.FileDownloadState
import com.virjar.tk.app.ui.component.textAttachmentPreviewKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android 文件附件下载控制器：会话隔离缓存 + 气泡进度动画数据源。 */
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
    workerDispatcher: CoroutineDispatcher,
) : FileDownloadController {
    private class FileOperationAdmission(val key: String) {
        val terminalClaimed = AtomicBoolean(false)
        val retired = AtomicBoolean(false)
        val ownerGeneration = AtomicLong(0L)
    }

    constructor(
        context: Context,
        mediaSession: AndroidMediaSession,
        uiScope: CoroutineScope,
        onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
        telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
        telemetryPage: ClientUiPage = ClientUiPage.CHAT,
    ) : this(
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

    /** 只由 [scope] 求值，绝不在组合构造此控制器期间求值。 */
    private val cacheRoot: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED, cacheRootProvider)
    private val scope = CoroutineScope(
        SupervisorJob() + workerDispatcher + CoroutineName("android-file-download-worker"),
    )
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableSetOf<String>()
    private val downloadLock = Any()
    private val currentFileOperationGenerations = mutableMapOf<String, Long>()
    private var nextFileOperationGeneration = 0L
    private val cacheProbeLock = Any()
    private val cacheProbes = mutableMapOf<String, Any>()
    /**
     * 每个键在派发到工作线程之前准入的任务计数。它弥补了这样一个间隙：
     * 点击已使缓存探针失效，但其工作线程尚未发布下载状态。
     */
    private val activeFileOperations = mutableMapOf<String, MutableSet<FileOperationAdmission>>()
    private val closed = AtomicBoolean(false)
    /** 所有者认领与最终 Snapshot 写入的加锁顺序为 publicationLock -> downloadLock。 */
    private val publicationLock = Any()
    private var externalOpenLease: AndroidMediaCacheFileLease? = null
    private val statePublisher = AndroidFileDownloadStatePublisher(
        ownerScope = uiScope,
        publicationGate = { publication ->
            mediaSession.runIfOpen {
                synchronized(publicationLock) {
                    if (!closed.get()) publication()
                }
            }
        },
        ownerThreadPredicate = ownerThreadPredicate,
    )
    override val states: SnapshotStateMap<String, FileDownloadState>
        get() = statePublisher.states
    override val automaticDownloadLedger = AutomaticFileDownloadLedger()

    override fun ensure(attachment: Attachment) {
        if (closed.get() || !mediaSession.isCurrentOwner()) return
        val key = attachment.path
        // Checking 拥有可靠的终态发布。重新运行它的探针，会让一个在操作完成之后
        // 才准入的探针替换掉该操作排队中的终态结果。
        if (states.containsKey(key)) return
        val probe = synchronized(cacheProbeLock) {
            if (closed.get() || activeFileOperations.containsKey(key) || cacheProbes.containsKey(key)) return
            Any().also { cacheProbes[key] = it }
        }
        publishState(
            key = key,
            state = FileDownloadState.Checking,
            isStillCurrent = { isCurrentCacheProbe(key, probe) },
        )
        scope.launch {
            val state = try {
                if (isValidAttachmentCacheFile(cachedFile(cacheRoot, attachment), attachment)) {
                    FileDownloadState.Done
                } else {
                    FileDownloadState.Idle
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FileDownloadState.Idle
            }
            publishState(
                key = key,
                state = state,
                isStillCurrent = { isCurrentCacheProbe(key, probe) },
                onDiscarded = { retireCacheProbe(key, probe) },
                onPublished = { retireCacheProbe(key, probe) },
            )
        }
    }

    override fun download(attachment: Attachment) {
        if (closed.get() || !mediaSession.isCurrentOwner()) return
        launchFileOperation(attachment.path) { admission ->
            downloadInternal(attachment, openWhenDone = false, admission = admission)
        }
    }

    override fun openOrDownload(attachment: Attachment) {
        if (closed.get()) return
        if (!mediaSession.isCurrentOwner()) {
            recordMedia(MediaOperation.OPEN, ClientActionOutcome.STARTED)
            publishFailure(
                key = attachment.path,
                reason = MediaFailureReason.SESSION,
                operation = MediaOperation.OPEN,
            )
            return
        }
        retireExternalOpenLease()
        if (onTextAttachmentPreview != null && textAttachmentPreviewKind(attachment) != null) {
            openTextPreview(attachment)
            return
        }
        launchFileOperation(attachment.path) { admission ->
            val cachedLease = try {
                val root = cacheRoot
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    cacheRoot = root,
                    file = cachedFile(root, attachment),
                    expectedBytes = attachment.size,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            cachedLease?.let { lease ->
                claimFileOperationGeneration(admission)
                if (openCached(lease, attachment, operationAlreadyStarted = false, admission)) {
                    publishOperationTerminal(admission, FileDownloadState.Done)
                }
                return@launchFileOperation
            }
            downloadInternal(attachment, openWhenDone = true, admission = admission)
        }
    }

    override fun close() {
        var leaseToClose: AndroidMediaCacheFileLease? = null
        val claimed = synchronized(publicationLock) {
            if (!closed.compareAndSet(false, true)) return@synchronized false
            statePublisher.close()
            leaseToClose = externalOpenLease
            externalOpenLease = null
            true
        }
        if (!claimed) return
        synchronized(cacheProbeLock) {
            cacheProbes.clear()
            activeFileOperations.clear()
        }
        scope.cancel()
        leaseToClose?.close()
    }

    /** 新准入的打开请求会在预留字节之前，替换掉上一个系统交接。 */
    private fun retireExternalOpenLease() {
        val lease = synchronized(publicationLock) {
            externalOpenLease.also { externalOpenLease = null }
        }
        lease?.close()
    }

    private suspend fun downloadInternal(
        attachment: Attachment,
        openWhenDone: Boolean,
        admission: FileOperationAdmission,
    ) {
        val key = attachment.path
        var openStarted = false
        beforeOwnerGenerationClaim()
        val shouldStart = synchronized(publicationLock) {
            synchronized(downloadLock) {
                if (openWhenDone) openStarted = openAfterDownload.add(key)
                inFlight.add(key).also { admitted ->
                    if (admitted) claimFileOperationGenerationLocked(admission)
                }
            }
        }
        if (openStarted) recordMedia(MediaOperation.OPEN, ClientActionOutcome.STARTED)
        if (!shouldStart) return
        var ownerFinished = false

        fun finishOwner(): Boolean {
            check(!ownerFinished) { "Android file download owner already finished: $key" }
            return synchronized(downloadLock) {
                check(inFlight.remove(key)) { "Android file download owner missing: $key" }
                openAfterDownload.remove(key)
            }.also { ownerFinished = true }
        }

        var downloadedLease: AndroidMediaCacheFileLease? = null
        try {
            telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                ClientActionOutcome.STARTED,
            )
            publishOperationState(admission, FileDownloadState.Downloading(0f))
            downloadedLease = downloadToCache(
                cacheRoot,
                mediaSession,
                attachment,
            ) { progress ->
                publishOperationState(admission, FileDownloadState.Downloading(progress))
            }
            if (closed.get() || !mediaSession.isCurrentOwner()) {
                val pendingOpen = finishOwner()
                if (pendingOpen) recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
                return
            }
            telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                ClientActionOutcome.SUCCEEDED,
            )
            // 消费待处理的打开请求并退役 inFlight 是同一个线性化点：迟到的打开者要么在这里被消费，
            // 要么在锁释放之后成为下一个所有者。
            val shouldOpen = finishOwner()
            if (shouldOpen) {
                val lease = checkNotNull(downloadedLease)
                downloadedLease = null
                if (openCached(lease, attachment, operationAlreadyStarted = true, admission)) {
                    publishOperationTerminal(admission, FileDownloadState.Done)
                }
            } else {
                publishOperationTerminal(admission, FileDownloadState.Done)
            }
        } catch (cancelled: CancellationException) {
            val pendingOpen = if (ownerFinished) false else finishOwner()
            if (pendingOpen) recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
            throw cancelled
        } catch (expired: AppError.AuthExpired) {
            // HTTP/会话边界已经上报了确切的当前凭证。防止控制器把终态认证失败
            // 降级成可重试的文件错误。
            val pendingOpen = if (ownerFinished) false else finishOwner()
            publishOperationTerminal(admission, FileDownloadState.Idle)
            if (pendingOpen) recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
        } catch (e: Exception) {
            val pendingOpen = if (ownerFinished) false else finishOwner()
            if (closed.get() || !mediaSession.isCurrentOwner()) {
                if (pendingOpen) recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
                return
            }
            val reason = classifyAndroidMediaFailure(e)
            warningLogger("FileDownload", "附件下载失败: ${reason.code}")
            publishFailure(key, reason, MediaOperation.DOWNLOAD, admission = admission)
            if (pendingOpen) {
                recordMedia(MediaOperation.OPEN, ClientActionOutcome.FAILED, reason)
            }
        } finally {
            downloadedLease?.close()
            if (!ownerFinished) {
                val abandonedOpen = finishOwner()
                if (abandonedOpen) {
                    recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
                }
            }
        }
    }

    private fun openCached(
        lease: AndroidMediaCacheFileLease,
        attachment: Attachment,
        operationAlreadyStarted: Boolean,
        admission: FileOperationAdmission,
    ): Boolean {
        var pendingLease: AndroidMediaCacheFileLease? = lease
        try {
            if (!operationAlreadyStarted) {
                recordMedia(MediaOperation.OPEN, ClientActionOutcome.STARTED)
            }
            if (closed.get() || !mediaSession.isCurrentOwner()) {
                recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
                return false
            }
            try {
                externalOpener(lease.file, attachment.contentType)
            } catch (cancelled: CancellationException) {
                recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
                throw cancelled
            } catch (_: Exception) {
                if (closed.get() || !mediaSession.isCurrentOwner()) {
                    recordMedia(MediaOperation.OPEN, ClientActionOutcome.CANCELLED)
                    return false
                }
                warningLogger("FileDownload", "附件打开失败: unsupported")
                publishFailure(
                    key = attachment.path,
                    reason = MediaFailureReason.UNSUPPORTED,
                    operation = MediaOperation.OPEN,
                    feedbackCode = UserFeedbackCode.MEDIA_OPEN_FAILED,
                    admission = admission,
                )
                return false
            }
            var previousLease: AndroidMediaCacheFileLease? = null
            var retained = false
            mediaSession.runIfOpen {
                synchronized(publicationLock) {
                    if (!closed.get()) {
                        previousLease = externalOpenLease
                        externalOpenLease = lease
                        retained = true
                    }
                }
            }
            if (retained) pendingLease = null
            previousLease?.close()
            recordMedia(
                MediaOperation.OPEN,
                if (closed.get() || !mediaSession.isCurrentOwner()) {
                    ClientActionOutcome.CANCELLED
                } else {
                    ClientActionOutcome.SUCCEEDED
                },
            )
            return retained
        } finally {
            pendingLease?.close()
        }
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
            recordMedia(MediaOperation.PREVIEW, outcome, MediaFailureReason.UNKNOWN.takeIf {
                outcome == ClientActionOutcome.FAILED
            })
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

    private fun cachedFile(cacheRoot: File, attachment: Attachment): File {
        return attachmentCacheFile(cacheRoot, mediaSession.cacheNamespace, attachment)
    }

    private fun isCurrentCacheProbe(key: String, probe: Any): Boolean =
        synchronized(cacheProbeLock) { cacheProbes[key] === probe }

    private fun retireCacheProbe(key: String, probe: Any) {
        synchronized(cacheProbeLock) {
            if (cacheProbes[key] === probe) cacheProbes.remove(key)
        }
    }

    private fun admitFileOperation(key: String): FileOperationAdmission {
        val admission = FileOperationAdmission(key)
        synchronized(cacheProbeLock) {
            cacheProbes.remove(key)
            activeFileOperations.getOrPut(key, ::mutableSetOf).add(admission)
        }
        return admission
    }

    private fun retireFileOperation(admission: FileOperationAdmission) {
        if (!admission.retired.compareAndSet(false, true)) return
        synchronized(cacheProbeLock) {
            val admissions = activeFileOperations[admission.key] ?: return
            admissions.remove(admission)
            if (admissions.isEmpty()) activeFileOperations.remove(admission.key)
        }
    }

    private fun claimFileOperationGeneration(admission: FileOperationAdmission) {
        beforeOwnerGenerationClaim()
        synchronized(publicationLock) {
            synchronized(downloadLock) { claimFileOperationGenerationLocked(admission) }
        }
    }

    private fun claimFileOperationGenerationLocked(admission: FileOperationAdmission) {
        check(admission.ownerGeneration.get() == 0L) {
            "Android file operation already owns a generation: ${admission.key}"
        }
        check(nextFileOperationGeneration < Long.MAX_VALUE) {
            "Android file operation generation exhausted"
        }
        val generation = ++nextFileOperationGeneration
        currentFileOperationGenerations[admission.key] = generation
        admission.ownerGeneration.set(generation)
    }

    private fun isCurrentFileOperation(admission: FileOperationAdmission): Boolean {
        val generation = admission.ownerGeneration.get()
        return generation > 0L && synchronized(downloadLock) {
            currentFileOperationGenerations[admission.key] == generation
        }
    }

    private fun clearFileOperationGeneration(admission: FileOperationAdmission) {
        val generation = admission.ownerGeneration.get()
        if (generation == 0L) return
        synchronized(publicationLock) {
            synchronized(downloadLock) {
                if (currentFileOperationGenerations[admission.key] == generation) {
                    currentFileOperationGenerations.remove(admission.key)
                }
            }
        }
    }

    private fun launchFileOperation(
        key: String,
        operation: suspend CoroutineScope.(FileOperationAdmission) -> Unit,
    ) {
        val admission = admitFileOperation(key)
        val job = try {
            scope.launch { operation(admission) }
        } catch (failure: Throwable) {
            clearFileOperationGeneration(admission)
            retireFileOperation(admission)
            throw failure
        }
        // 每次点击拥有一个独立的准入。没有终态结果的任务在此退役；
        // 否则可靠的终态发布会把所有权带过 UI 交接环节。
        job.invokeOnCompletion {
            if (!admission.terminalClaimed.get()) {
                clearFileOperationGeneration(admission)
                retireFileOperation(admission)
            }
        }
    }

    private fun publishOperationTerminal(
        admission: FileOperationAdmission,
        state: FileDownloadState,
        onPublished: (() -> Unit)? = null,
    ) {
        check(state !is FileDownloadState.Downloading && state !is FileDownloadState.Checking) {
            "Only a file operation terminal may claim its admission"
        }
        check(admission.terminalClaimed.compareAndSet(false, true)) {
            "Android file operation published more than one terminal: ${admission.key}"
        }
        try {
            publishState(
                key = admission.key,
                state = state,
                isStillCurrent = { isCurrentFileOperation(admission) },
                onDiscarded = {
                    clearFileOperationGeneration(admission)
                    retireFileOperation(admission)
                },
                onPublished = {
                    clearFileOperationGeneration(admission)
                    retireFileOperation(admission)
                    onPublished?.invoke()
                },
            )
        } catch (failure: Throwable) {
            clearFileOperationGeneration(admission)
            retireFileOperation(admission)
            throw failure
        }
    }

    private fun publishOperationState(
        admission: FileOperationAdmission,
        state: FileDownloadState.Downloading,
    ) {
        publishState(
            key = admission.key,
            state = state,
            isStillCurrent = { isCurrentFileOperation(admission) },
        )
    }

    private fun publishState(
        key: String,
        state: FileDownloadState,
        isStillCurrent: () -> Boolean = { true },
        onDiscarded: (() -> Unit)? = null,
        onPublished: (() -> Unit)? = null,
    ) {
        if (closed.get()) {
            onDiscarded?.invoke()
            return
        }
        statePublisher.publish(
            key = key,
            state = state,
            isStillCurrent = isStillCurrent,
            onDiscarded = onDiscarded,
            onPublished = onPublished,
        )
    }

    private fun publishFailure(
        key: String,
        reason: MediaFailureReason,
        operation: MediaOperation,
        feedbackCode: UserFeedbackCode = reason.downloadFeedbackCode,
        admission: FileOperationAdmission? = null,
    ) {
        val notice = UserFeedbackNotice(
            feedbackCode = feedbackCode,
            page = telemetryPage,
            action = when (operation) {
                MediaOperation.OPEN -> ClientUiAction.OPEN_MEDIA
                else -> ClientUiAction.DOWNLOAD_MEDIA
            },
            origin = FeedbackOrigin.INLINE,
        )
        recordMedia(operation, ClientActionOutcome.FAILED, reason)
        val failed = FileDownloadState.Failed(notice.publicMessage)
        if (admission == null) {
            publishState(
                key = key,
                state = failed,
                onPublished = { telemetry.recordUserNotice(notice) },
            )
        } else {
            publishOperationTerminal(
                admission = admission,
                state = failed,
                onPublished = { telemetry.recordUserNotice(notice) },
            )
        }
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
}

/** 仅按类型分类：不观察异常消息、URI 或本地文件名。 */
internal fun classifyAndroidMediaFailure(failure: Throwable): MediaFailureReason = when (failure) {
    is MediaCacheQuotaException -> MediaFailureReason.CACHE_QUOTA
    is MediaDownloadSizeException,
    is SelectedMediaTooLargeException,
    -> MediaFailureReason.SIZE_VALIDATION
    is AndroidMediaSupersededCredentialException,
    is AppError.AuthExpired,
    -> MediaFailureReason.SESSION
    is AppError.Business -> when (failure.code) {
        403 -> MediaFailureReason.HTTP_DENIED
        404 -> MediaFailureReason.HTTP_MISSING
        else -> MediaFailureReason.HTTP_STATUS
    }
    is AppError.Network,
    is AppError.Timeout,
    is ConnectException,
    is NoRouteToHostException,
    is SocketTimeoutException,
    is UnknownHostException,
    is SocketException,
    -> MediaFailureReason.NETWORK
    is IOException -> MediaFailureReason.IO
    is AppError.Unknown -> classifyAndroidMediaFailure(failure.cause)
    else -> MediaFailureReason.UNKNOWN
}
