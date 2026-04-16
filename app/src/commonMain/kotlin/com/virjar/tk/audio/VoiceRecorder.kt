package com.virjar.tk.audio

import kotlinx.coroutines.flow.StateFlow

enum class RecordingState { IDLE, RECORDING, ERROR }

data class RecordingResult(val bytes: ByteArray, val durationSeconds: Int)

expect class VoiceRecorder() {
    val state: StateFlow<RecordingState>
    val amplitude: StateFlow<Float>
    fun start()
    fun stop(): RecordingResult?
    fun cancel()
    fun release()
}
