package com.virjar.tk.viewmodel

import com.virjar.tk.audio.RecordingState
import com.virjar.tk.audio.VoiceRecorder
import com.virjar.tk.audio.RecordingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingInfo(
    val isRecording: Boolean = false,
    val duration: Int = 0,
    val amplitude: Float = 0f,
)

class RecordingController {
    private val voiceRecorder = VoiceRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var durationJob: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(RecordingInfo())
    val state: StateFlow<RecordingInfo> = _state.asStateFlow()

    fun start(onAutoStop: (RecordingController) -> Unit) {
        voiceRecorder.start()
        durationJob = scope.launch {
            while (true) {
                delay(1000)
                val current = _state.value.duration + 1
                _state.value = _state.value.copy(duration = current)
                if (current >= 60) {
                    stop()
                    onAutoStop(this@RecordingController)
                    break
                }
            }
        }
        scope.launch {
            voiceRecorder.state.collect { rs ->
                _state.value = _state.value.copy(isRecording = rs == RecordingState.RECORDING)
            }
        }
        scope.launch {
            voiceRecorder.amplitude.collect { amp ->
                _state.value = _state.value.copy(amplitude = amp)
            }
        }
    }

    fun stop(): RecordingResult? {
        durationJob?.cancel()
        durationJob = null
        _state.value = _state.value.copy(isRecording = false, duration = 0, amplitude = 0f)
        return voiceRecorder.stop()
    }

    fun cancel() {
        durationJob?.cancel()
        durationJob = null
        voiceRecorder.cancel()
        _state.value = _state.value.copy(isRecording = false, duration = 0, amplitude = 0f)
    }

    fun destroy() {
        scope.cancel()
    }
}
