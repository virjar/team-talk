package com.virjar.tk.audio

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackState { IDLE, PLAYING, COMPLETED }

expect class VoicePlayer() {
    val state: StateFlow<PlaybackState>
    val currentUrl: StateFlow<String>
    val currentPosition: StateFlow<Int>  // ms
    val duration: StateFlow<Int>         // ms
    fun play(url: String)
    fun stop()
    fun release()
}
