package com.virjar.tk.app.ui.component

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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.platform.PlatformAtomicBoolean
import com.virjar.tk.shared.platform.PlatformLock
import com.virjar.tk.shared.platform.synchronized
import com.virjar.tk.shared.platform.platformCurrentTimeMillis
import com.virjar.tk.shared.platform.PlatformAtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 下载完成后待执行的平台动作；下载前登记、完成时在同一线性化点消费。 */
enum class FileDownloadPendingAction { PREVIEW, OPEN, EXPORT }

/**
 * 三端共用的文件下载编排核心：缓存探针去重、准入/代次状态机、inFlight 下载去重、
 * 待处理动作消费、AuthExpired 终态降级与遥测/反馈发布。机制提炼自 Android 已验证
 * 实现；平台差异（缓存租约、打开/导出动作、会话门禁、线程归属）全部经
 * [FileDownloadCoreAdapter] 注入。
 */
class FileDownloadCore<L>(
    private val adapter: FileDownloadCoreAdapter<L>,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    private val telemetryPage: ClientUiPage = ClientUiPage.CHAT,
) : AutoCloseable {
    private class FileOperationAdmission(val key: String) {
        val terminalClaimed = PlatformAtomicBoolean(false)
        val retired = PlatformAtomicBoolean(false)
        val ownerGeneration = PlatformAtomicLong(0L)
    }

    private val scope = adapter.createWorkerScope()
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableMapOf<String, FileDownloadPendingAction>()
    private val downloadLock = PlatformLock()
    private val currentFileOperationGenerations = mutableMapOf<String, Long>()
    private var nextFileOperationGeneration = 0L
    private val cacheProbeLock = PlatformLock()
    private val cacheProbes = mutableMapOf<String, Any>()

    /**
     * 每个键在派发到工作线程之前准入的任务计数。它弥补了这样一个间隙：
     * 点击已使缓存探针失效，但其工作线程尚未发布下载状态。
     */
    private val activeFileOperations = mutableMapOf<String, MutableSet<FileOperationAdmission>>()
    private val closed = PlatformAtomicBoolean(false)

    /** 所有者认领与最终 Snapshot 写入的加锁顺序为 publicationLock -> downloadLock。 */
    private val publicationLock = PlatformLock()
    private val statePublisher = FileDownloadStatePublisher(
        ownerScope = adapter.uiScope,
        publicationGate = { publication ->
            adapter.runIfOpen {
                synchronized(publicationLock) {
                    if (!closed.get()) publication()
                }
            }
        },
        ownerThreadPredicate = adapter::isOwnerThread,
    )
    val states: SnapshotStateMap<String, FileDownloadState>
        get() = statePublisher.states
    val automaticDownloadLedger = AutomaticFileDownloadLedger()

    fun ensure(attachment: Attachment) {
        if (closed.get() || !adapter.isOwnerCurrent()) return
        val key = attachment.path
        // Checking 拥有可靠的终态发布。重新运行它的探针，会让一个在操作完成之后
        // 才准入的探针替换掉该操作排队中的终态结果。
        if (states.containsKey(key)) return
        val probe = synchronized(cacheProbeLock) {
            if (closed.get() || activeFileOperations.containsKey(key) || cacheProbes.containsKey(key)) null
            else Any().also { cacheProbes[key] = it }
        } ?: return
        publishState(
            key = key,
            state = FileDownloadState.Checking,
            isStillCurrent = { isCurrentCacheProbe(key, probe) },
        )
        scope.launch {
            val state = try {
                if (adapter.probeCached(attachment)) FileDownloadState.Done else FileDownloadState.Idle
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

    fun download(attachment: Attachment) {
        if (closed.get() || !adapter.isOwnerCurrent()) return
        launchFileOperation(attachment.path) { admission ->
            downloadInternal(attachment, pendingWhenDone = null, admission = admission)
        }
    }

    /**
     * 缓存命中先执行平台动作；未命中走认证下载并在完成后消费登记的动作。
     * 打开/导出/预览共用同一条认领与终态链路；需要下载前额外裁决的入口
     * （如文本预览窗口）由平台控制器自行预处理后再进入。
     */
    fun act(attachment: Attachment, action: FileDownloadPendingAction) {
        if (closed.get()) return
        if (!adapter.isOwnerCurrent()) {
            recordMedia(MediaOperation.OPEN, ClientActionOutcome.STARTED)
            publishFailure(
                key = attachment.path,
                reason = MediaFailureReason.SESSION,
                operation = MediaOperation.OPEN,
            )
            return
        }
        launchFileOperation(attachment.path) { admission ->
            val cachedLease = adapter.cachedLease(attachment)
            cachedLease?.let { lease ->
                claimFileOperationGeneration(admission)
                if (dispatchPendingAction(action, lease, attachment, operationAlreadyStarted = false, admission)) {
                    publishOperationTerminal(admission, FileDownloadState.Done)
                }
                return@launchFileOperation
            }
            downloadInternal(attachment, pendingWhenDone = action, admission = admission)
        }
    }

    override fun close() {
        val claimed = synchronized(publicationLock) {
            if (!closed.compareAndSet(false, true)) return@synchronized false
            statePublisher.close()
            adapter.onClosed()
            true
        }
        if (!claimed) return
        synchronized(cacheProbeLock) {
            cacheProbes.clear()
            activeFileOperations.clear()
        }
        scope.cancel()
    }

    private suspend fun downloadInternal(
        attachment: Attachment,
        pendingWhenDone: FileDownloadPendingAction?,
        admission: FileOperationAdmission,
    ) {
        val key = attachment.path
        var actionStarted = false
        val shouldStart = synchronized(publicationLock) {
            synchronized(downloadLock) {
                if (pendingWhenDone != null && !openAfterDownload.containsKey(key)) {
                    openAfterDownload[key] = pendingWhenDone
                    actionStarted = true
                }
                inFlight.add(key).also { admitted ->
                    if (admitted) claimFileOperationGenerationLocked(admission)
                }
            }
        }
        if (actionStarted) recordActionStarted(pendingWhenDone)
        if (!shouldStart) return
        var ownerFinished = false

        fun finishOwner(): FileDownloadPendingAction? {
            check(!ownerFinished) { "File download owner already finished: $key" }
            return synchronized(downloadLock) {
                check(inFlight.remove(key)) { "File download owner missing: $key" }
                openAfterDownload.remove(key)
            }.also { ownerFinished = true }
        }

        var downloadedLease: L? = null
        try {
            telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                ClientActionOutcome.STARTED,
            )
            publishOperationState(admission, FileDownloadState.Downloading(0f))
            var lastProgressEmit = 0L
            downloadedLease = adapter.downloadToCache(attachment) { progress ->
                val now = platformCurrentTimeMillis()
                if (progress >= 1f || now - lastProgressEmit >= 100) {
                    lastProgressEmit = now
                    publishOperationState(admission, FileDownloadState.Downloading(progress))
                }
            }
            if (closed.get() || !adapter.isOwnerCurrent()) {
                finishOwner()?.let { recordActionCancelled(it) }
                return
            }
            telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                ClientActionOutcome.SUCCEEDED,
            )
            // 消费待处理的动作并退役 inFlight 是同一个线性化点：迟到的打开者要么在这里被消费，
            // 要么在锁释放之后成为下一个所有者。
            val pendingAction = finishOwner()
            if (pendingAction != null) {
                val lease = checkNotNull(downloadedLease)
                downloadedLease = null
                if (dispatchPendingAction(pendingAction, lease, attachment, operationAlreadyStarted = true, admission)) {
                    publishOperationTerminal(admission, FileDownloadState.Done)
                }
            } else {
                publishOperationTerminal(admission, FileDownloadState.Done)
            }
        } catch (cancelled: CancellationException) {
            if (ownerFinished) {
                throw cancelled
            }
            finishOwner()?.let { recordActionCancelled(it) }
            throw cancelled
        } catch (expired: AppError.AuthExpired) {
            // HTTP/会话边界已经上报了确切的当前凭证。防止控制器把终态认证失败
            // 降级成可重试的文件错误。
            if (!ownerFinished) finishOwner()
            publishOperationTerminal(admission, FileDownloadState.Idle)
        } catch (e: Exception) {
            val pendingAction = if (ownerFinished) null else finishOwner()
            if (closed.get() || !adapter.isOwnerCurrent()) {
                pendingAction?.let { recordActionCancelled(it) }
                return
            }
            val reason = adapter.classifyFailure(e)
            adapter.warn("附件下载失败: ${reason.code}")
            publishFailure(key, reason, MediaOperation.DOWNLOAD, admission = admission)
            if (pendingAction != null) {
                recordAction(pendingAction, ClientActionOutcome.FAILED, reason)
                adapter.onPendingActionFailed(pendingAction, attachment, e)
            }
        } finally {
            downloadedLease?.let(adapter::closeLease)
            if (!ownerFinished) {
                finishOwner()?.let { recordActionCancelled(it) }
            }
        }
    }

    /** 执行下载完成后的平台动作；返回 false 表示动作未受理（终态仍由调用方发布）。 */
    private suspend fun dispatchPendingAction(
        action: FileDownloadPendingAction,
        lease: L,
        attachment: Attachment,
        operationAlreadyStarted: Boolean,
        admission: FileOperationAdmission,
    ): Boolean {
        var pendingLease: L? = lease
        try {
            if (!operationAlreadyStarted) recordActionStarted(action)
            if (closed.get() || !adapter.isOwnerCurrent()) {
                recordActionCancelled(action)
                return false
            }
            val retained = try {
                adapter.performPendingAction(action, lease, attachment)
            } catch (cancelled: CancellationException) {
                recordActionCancelled(action)
                throw cancelled
            } catch (_: Exception) {
                if (closed.get() || !adapter.isOwnerCurrent()) {
                    recordActionCancelled(action)
                    return false
                }
                adapter.warn("附件打开失败: unsupported")
                publishFailure(
                    key = attachment.path,
                    reason = MediaFailureReason.UNSUPPORTED,
                    operation = MediaOperation.OPEN,
                    feedbackCode = UserFeedbackCode.MEDIA_OPEN_FAILED,
                    admission = admission,
                )
                return false
            }
            if (retained) pendingLease = null
            recordAction(
                action,
                if (closed.get() || !adapter.isOwnerCurrent()) {
                    ClientActionOutcome.CANCELLED
                } else {
                    ClientActionOutcome.SUCCEEDED
                },
            )
            return true
        } finally {
            pendingLease?.let(adapter::closeLease)
        }
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
        synchronized(publicationLock) {
            synchronized(downloadLock) { claimFileOperationGenerationLocked(admission) }
        }
    }

    private fun claimFileOperationGenerationLocked(admission: FileOperationAdmission) {
        check(admission.ownerGeneration.get() == 0L) {
            "File operation already owns a generation: ${admission.key}"
        }
        check(nextFileOperationGeneration < Long.MAX_VALUE) {
            "File operation generation exhausted"
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
            "File operation published more than one terminal: ${admission.key}"
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

    private fun recordActionStarted(action: FileDownloadPendingAction?) {
        recordAction(action, ClientActionOutcome.STARTED)
    }

    private fun recordActionCancelled(action: FileDownloadPendingAction) {
        recordAction(action, ClientActionOutcome.CANCELLED)
    }

    private fun recordAction(
        action: FileDownloadPendingAction?,
        outcome: ClientActionOutcome,
        reason: MediaFailureReason? = null,
    ) {
        when (action) {
            FileDownloadPendingAction.EXPORT -> telemetry.recordMedia(
                telemetryPage,
                ClientMediaKind.FILE,
                MediaOperation.DOWNLOAD,
                outcome,
                reason,
            )
            else -> recordMedia(MediaOperation.OPEN, outcome, reason)
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

/** 平台差异注入面：缓存租约、打开/导出动作、会话门禁与线程归属。 */
interface FileDownloadCoreAdapter<L> {
    /** 状态发布的目标线程域（Compose 主线程/EventQueue 等）。 */
    val uiScope: CoroutineScope

    /** 核心创建并持有生命周期的工作协程域；close 时由核心取消。 */
    fun createWorkerScope(): CoroutineScope

    fun isOwnerThread(): Boolean

    /** 当前会话仍是权威所有者；入口与终态裁决使用。 */
    fun isOwnerCurrent(): Boolean

    /** 状态发布门禁：会话仍开放时在锁内执行发布并返回 true。 */
    fun runIfOpen(action: () -> Unit): Boolean

    /** 探测缓存是否已有完整有效的附件（Checking 探针）。 */
    suspend fun probeCached(attachment: Attachment): Boolean

    /** 缓存命中时取得租约；未命中返回 null。 */
    suspend fun cachedLease(attachment: Attachment): L?

    /** 认证下载到账号缓存并返回租约；进度回调在工作线程。 */
    suspend fun downloadToCache(attachment: Attachment, onProgress: (Float) -> Unit): L

    fun closeLease(lease: L)

    /**
     * 执行下载完成后的平台动作（预览/打开/导出）。返回 true 表示租约已被平台
     * 接管（如系统打开期间保留、UIKit 回调关闭），核心不再关闭。
     */
    suspend fun performPendingAction(action: FileDownloadPendingAction, lease: L, attachment: Attachment): Boolean

    fun classifyFailure(failure: Throwable): MediaFailureReason

    fun warn(message: String)

    /** 核心关闭时释放平台持有的跨动作资源（如系统打开租约）。 */
    fun onClosed() {}

    /** 待处理动作失败时的平台侧通知（如桌面预览窗口失败事件）。 */
    fun onPendingActionFailed(action: FileDownloadPendingAction, attachment: Attachment, failure: Throwable) {}
}
