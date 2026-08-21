package com.virjar.tk

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

/**
 * 语音播放器（单例，全局共享）。点击语音卡片 → 下载 → 播放，不弹窗。
 */
object VoicePlayer {

    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentCacheNamespace: String? = null
    @Volatile private var currentRequestId: String? = null
    private var loadingJob: Job? = null
    private var _isPlaying = false
    private var _isLoading = false
    private var _error: String? = null

    val isPlaying: Boolean get() = _isPlaying
    val isLoading: Boolean get() = _isLoading
    val error: String? get() = _error
    val playingUrl: String? get() = currentUrl

    /** 播放进度 0..1（供 UI 波形着色轮询；无播放时 0） */
    val progress: Float
        get() = currentPlayer?.let { mp ->
            val d = try { mp.duration } catch (_: IllegalStateException) { 0 }
            if (d > 0) mp.currentPosition.toFloat() / d else 0f
        } ?: 0f

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob +
        CoroutineExceptionHandler { _, throwable ->
            Log.e("VoicePlayer", "Scope unhandled exception", throwable)
        })

    fun play(
        context: android.content.Context,
        url: String,
        mediaSession: AndroidMediaSession,
    ) {
        if (!mediaSession.isCurrentOwner()) return
        // 如果已经在播放同一个，则暂停/继续
        if (url == currentUrl && mediaSession.cacheNamespace == currentCacheNamespace && currentPlayer != null) {
            val mp = currentPlayer!!
            if (mp.isPlaying) {
                mp.pause()
                _isPlaying = false
            } else {
                mp.start()
                _isPlaying = true
            }
            return
        }

        // 停止旧的
        stop()

        currentUrl = url
        currentCacheNamespace = mediaSession.cacheNamespace
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        _isLoading = true
        _error = null

        loadingJob = scope.launch {
            try {
                val file = MediaHelper.downloadToCache(
                    url = url,
                    cacheDir = context.cacheDir,
                    mediaSession = mediaSession,
                )
                if (currentRequestId != requestId || !mediaSession.isCurrentOwner()) return@launch

                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        _isPlaying = false
                    }
                }
                if (currentRequestId != requestId) {
                    runCatching { mp.stop() }
                    runCatching { mp.release() }
                    return@launch
                }
                currentPlayer = mp
                _isLoading = false
                _isPlaying = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("VoicePlayer", "Failed to play voice: $url", e)
                if (currentRequestId == requestId) {
                    _isLoading = false
                    _error = e.message
                    currentUrl = null
                    currentCacheNamespace = null
                    currentRequestId = null
                }
            }
        }
    }

    fun stop(cacheNamespace: String? = null, onStopped: (() -> Unit)? = null) {
        if (cacheNamespace != null && currentCacheNamespace != cacheNamespace) {
            onStopped?.invoke()
            return
        }
        val job = loadingJob
        loadingJob = null
        currentRequestId = null
        currentPlayer?.apply {
            try { stop() } catch (e: Exception) { Log.w("VoicePlayer", "Stop failed", e) }
            try { release() } catch (e: Exception) { Log.w("VoicePlayer", "Release failed", e) }
        }
        currentPlayer = null
        currentUrl = null
        currentCacheNamespace = null
        _isPlaying = false
        _isLoading = false
        _error = null

        // 清理回调必须晚于下载任务真实结束和 MediaPlayer 释放；旧任务即便无法立刻中断，
        // 也不能在会话目录清理之后把缓存文件重新写回来。
        if (job == null || job.isCompleted) {
            onStopped?.invoke()
        } else {
            job.invokeOnCompletion { onStopped?.invoke() }
            job.cancel()
        }
    }

    /** Process-owner teardown. Page-level callers must continue to use namespace-scoped [stop]. */
    fun close() {
        stop()
        scopeJob.cancel()
    }
}
