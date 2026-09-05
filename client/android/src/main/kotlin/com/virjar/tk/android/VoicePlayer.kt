package com.virjar.tk.android

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.virjar.tk.protocol.model.Attachment

/**
 * 语音播放器（进程单例）。所有请求、播放器和展示状态都由 [ownership] 线性化；下载协程只持有
 * 一次性的世代能力，不能在页面或认证会话退休后重新发布播放器。
 */
object VoicePlayer {
    private const val TAG = "VoicePlayer"

    private val ownership = VoicePlaybackOwnershipGate()
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    val isPlaying: Boolean get() = ownership.snapshot().isPlaying
    val isLoading: Boolean get() = ownership.snapshot().isLoading
    val error: String? get() = ownership.snapshot().error
    val playingUrl: String? get() = ownership.snapshot().url

    /** 播放进度 0..1（供 UI 波形着色轮询；无播放时 0）。 */
    val progress: Float
        get() = ownership.progress { failure ->
            Log.w(TAG, "Failed to read voice playback progress", failure)
        }

    fun play(
        context: android.content.Context,
        attachment: Attachment,
        mediaSession: AndroidMediaSession,
    ) {
        if (!ownership.acceptsNewRequests() || !mediaSession.isCurrentOwner()) return

        when (
            val toggle = ownership.toggleIfCurrent(
                owner = mediaSession,
                cacheNamespace = mediaSession.cacheNamespace,
                url = attachment.path,
            )
        ) {
            VoicePlaybackToggleResult.Miss -> Unit
            VoicePlaybackToggleResult.Toggled -> return
            is VoicePlaybackToggleResult.Failed -> {
                val cleanupFailure = retirePlayback(toggle.retirement)
                handleSynchronousFailure(toggle.failure, cleanupFailure)
                return
            }
        }

        val start = ownership.begin(
            owner = mediaSession,
            cacheNamespace = mediaSession.cacheNamespace,
            url = attachment.path,
        ) { capability ->
            scope.launch(start = CoroutineStart.LAZY) {
                executeRequest(
                    capability = capability,
                    context = context,
                    attachment = attachment,
                    mediaSession = mediaSession,
                )
            }
        }

        when (start) {
            VoicePlaybackBeginResult.Closed -> return
            is VoicePlaybackBeginResult.Failed -> {
                val cleanupFailure = retirePlayback(start.previous)
                handleSynchronousFailure(start.failure, cleanupFailure)
            }

            is VoicePlaybackBeginResult.Started -> {
                val cleanupFailure = retirePlayback(start.previous)
                if (cleanupFailure != null) {
                    val abandoned = ownership.abandon(start.capability)
                    val abandonedFailure = retirePlayback(abandoned)
                    throw requireNotNull(
                        mergeVoicePlaybackFailures(cleanupFailure, abandonedFailure),
                    )
                }
                start.task.start()
            }
        }
    }

    private suspend fun executeRequest(
        capability: VoicePlaybackCapability,
        context: android.content.Context,
        attachment: Attachment,
        mediaSession: AndroidMediaSession,
    ) {
        var unpublishedPlayer: VoicePlaybackHandle? = null
        var pendingLease: AndroidMediaCacheFileLease? = null
        var requestFailure: Throwable? = null
        var published = false
        try {
            val lease = MediaHelper.downloadToCacheLease(
                attachment = attachment,
                cacheDir = context.cacheDir,
                mediaSession = mediaSession,
            )
            pendingLease = lease
            currentCoroutineContext().ensureActive()
            if (ownership.isAdmitted(capability) && mediaSession.isCurrentOwner()) {
                val player = AndroidVoicePlaybackHandle(MediaPlayer(), lease)
                unpublishedPlayer = player
                pendingLease = null
                player.setDataSource(lease.file.absolutePath)
                player.prepare()
                currentCoroutineContext().ensureActive()
                player.setCompletionListener {
                    onPlaybackCompleted(capability, player)
                }

                var publication: VoicePlaybackPublishResult = VoicePlaybackPublishResult.Rejected
                if (mediaSession.runIfOpen {
                        publication = ownership.publish(capability, player)
                    }
                ) {
                    when (val result = publication) {
                        VoicePlaybackPublishResult.Published -> {
                            published = true
                            unpublishedPlayer = null
                        }

                        VoicePlaybackPublishResult.Rejected -> Unit
                        is VoicePlaybackPublishResult.Failed -> requestFailure = result.failure
                    }
                }
            }
        } catch (failure: Throwable) {
            requestFailure = failure
        }

        val cleanupFailure = unpublishedPlayer?.let { player ->
            disposeVoicePlaybackHandle(
                player = player,
                stopFirst = false,
                reportOrdinaryFailure = ::reportOrdinaryCleanupFailure,
            )
        }
        val leaseFailure = try {
            pendingLease?.close()
            null
        } catch (failure: Throwable) {
            failure
        }
        val terminal = mergeVoicePlaybackFailures(
            mergeVoicePlaybackFailures(requestFailure, cleanupFailure),
            leaseFailure,
        )
        when {
            terminal == null && !published -> ownership.abandon(capability)
            terminal == null -> Unit
            terminal is CancellationException -> {
                ownership.abandon(capability)
                throw terminal
            }

            terminal !is Exception -> {
                ownership.abandon(capability)
                throw terminal
            }

            else -> {
                ownership.fail(capability, terminal.message ?: "语音播放失败")
                Log.e(TAG, "Failed to prepare or start voice playback", terminal)
            }
        }
    }

    private fun onPlaybackCompleted(
        capability: VoicePlaybackCapability,
        player: VoicePlaybackHandle,
    ) {
        val completed = ownership.complete(capability, player) ?: return
        disposeVoicePlaybackHandle(
            player = completed,
            stopFirst = false,
            reportOrdinaryFailure = ::reportOrdinaryCleanupFailure,
        )?.let { throw it }
    }

    fun stop(mediaSession: AndroidMediaSession, onStopped: (() -> Unit)? = null) {
        when (val result = ownership.stop(mediaSession)) {
            VoicePlaybackStopResult.OwnerMismatch -> onStopped?.invoke()
            is VoicePlaybackStopResult.Retired -> {
                val failure = retirePlayback(result.retirement, onStopped)
                failure?.let { throw it }
            }
        }
    }

    /** 进程所有者级别的拆除。页面级调用方必须继续使用精确会话的 [stop]。 */
    fun close() {
        val retirement = ownership.close()
        val retirementFailure = retirePlayback(retirement)
        val scopeFailure = captureVoicePlaybackFailure { scopeJob.cancel() }
        mergeVoicePlaybackFailures(retirementFailure, scopeFailure)?.let { failure ->
            if (isFatalVoicePlaybackFailure(failure)) throw failure
            Log.w(TAG, "Voice playback scope close failed", failure)
        }
    }

    private fun retirePlayback(
        retirement: VoicePlaybackRetirement,
        onStopped: (() -> Unit)? = null,
    ): Throwable? {
        val cancelFailure = captureVoicePlaybackFailure { retirement.task?.cancel() }
        if (cancelFailure is Exception && cancelFailure !is CancellationException) {
            reportOrdinaryCleanupFailure("cancel loading task", cancelFailure)
        }
        val playerFailure = retirement.player?.let { player ->
            disposeVoicePlaybackHandle(
                player = player,
                stopFirst = true,
                reportOrdinaryFailure = ::reportOrdinaryCleanupFailure,
            )
        }

        // 只有在同步持有的播放器被释放之后才注册。如果协程仍在准备尚未发布的播放器，
        // 其清理会先于 Job 完成执行。
        notifyAfterVoicePlaybackTask(retirement.task, onStopped)
        return mergeVoicePlaybackFailures(
            cancelFailure?.takeIf(::isFatalVoicePlaybackFailure),
            playerFailure,
        )
    }

    private fun handleSynchronousFailure(primary: Throwable, cleanup: Throwable?) {
        val terminal = requireNotNull(mergeVoicePlaybackFailures(primary, cleanup))
        if (isFatalVoicePlaybackFailure(terminal)) throw terminal
        Log.e(TAG, "Voice playback request failed", terminal)
    }

    private fun reportOrdinaryCleanupFailure(action: String, failure: Exception) {
        Log.w(TAG, "Failed to $action", failure)
    }
}

/** 保持可替换的小适配器，让所有权竞态可以在没有 Android 原生状态的情况下进行测试。 */
internal interface VoicePlaybackHandle {
    val durationMs: Int
    val currentPositionMs: Int

    fun setDataSource(path: String)
    fun prepare()
    fun setCompletionListener(listener: () -> Unit)
    fun start()
    fun pause()
    fun stop()
    fun release()
}

private class AndroidVoicePlaybackHandle(
    private val player: MediaPlayer,
    private val lease: AndroidMediaCacheFileLease,
) : VoicePlaybackHandle {
    override val durationMs: Int get() = player.duration
    override val currentPositionMs: Int get() = player.currentPosition

    override fun setDataSource(path: String) = player.setDataSource(path)
    override fun prepare() = player.prepare()
    override fun setCompletionListener(listener: () -> Unit) {
        player.setOnCompletionListener { listener() }
    }

    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun stop() = player.stop()
    override fun release() {
        var failure: Throwable? = null
        try {
            player.release()
        } catch (thrown: Throwable) {
            failure = thrown
        }
        try {
            lease.close()
        } catch (thrown: Throwable) {
            failure = mergeVoicePlaybackFailures(failure, thrown)
        }
        failure?.let { throw it }
    }
}
