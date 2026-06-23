package com.virjar.tk

import android.media.MediaPlayer
import kotlinx.coroutines.*
import java.io.File

/**
 * 语音播放器（单例，全局共享）。点击语音卡片 → 下载 → 播放，不弹窗。
 */
object VoicePlayer {

    private var currentPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var _isPlaying = false
    private var _isLoading = false
    private var _error: String? = null

    val isPlaying: Boolean get() = _isPlaying
    val isLoading: Boolean get() = _isLoading
    val error: String? get() = _error
    val playingUrl: String? get() = currentUrl

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun play(context: android.content.Context, url: String) {
        // 如果已经在播放同一个，则暂停/继续
        if (url == currentUrl && currentPlayer != null) {
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
        _isLoading = true
        _error = null

        scope.launch {
            try {
                val cacheDir = File(context.cacheDir, "media")
                val file = MediaHelper.downloadToCache(url, cacheDir)

                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        _isPlaying = false
                    }
                }
                currentPlayer = mp
                _isLoading = false
                _isPlaying = true
            } catch (e: Exception) {
                _isLoading = false
                _error = e.message
                currentUrl = null
            }
        }
    }

    fun stop() {
        currentPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        currentPlayer = null
        currentUrl = null
        _isPlaying = false
        _isLoading = false
        _error = null
    }
}
