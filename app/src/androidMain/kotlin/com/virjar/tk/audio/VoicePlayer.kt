package com.virjar.tk.audio

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.Handler
import android.os.Looper

actual class VoicePlayer actual constructor() {
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    actual val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    actual val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    actual val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0)
    actual val duration: StateFlow<Int> = _duration.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val positionRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            try {
                if (mp.isPlaying) {
                    _currentPosition.value = mp.currentPosition
                    handler.postDelayed(this, 100)
                }
            } catch (_: Exception) {}
        }
    }

    actual fun play(url: String) {
        stop()

        _currentUrl.value = url
        _state.value = PlaybackState.PLAYING
        _currentPosition.value = 0

        try {
            val mp = MediaPlayer()
            mp.setDataSource(url)
            mediaPlayer = mp
            mp.setOnPreparedListener {
                _duration.value = it.duration
                it.start()
                handler.post(positionRunnable)
            }
            mp.setOnCompletionListener {
                _state.value = PlaybackState.COMPLETED
                _currentPosition.value = 0
                handler.removeCallbacks(positionRunnable)
            }
            mp.setOnErrorListener { _, _, _ ->
                _state.value = PlaybackState.COMPLETED
                handler.removeCallbacks(positionRunnable)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            _state.value = PlaybackState.COMPLETED
        }
    }

    actual fun stop() {
        handler.removeCallbacks(positionRunnable)
        _state.value = PlaybackState.IDLE
        _currentPosition.value = 0
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    actual fun release() {
        stop()
    }
}
