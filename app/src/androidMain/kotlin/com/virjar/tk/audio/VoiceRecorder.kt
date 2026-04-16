package com.virjar.tk.audio

import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

actual class VoiceRecorder actual constructor() {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    actual val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    actual val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime = 0L
    private var amplitudeThread: Thread? = null

    actual fun start() {
        if (_state.value == RecordingState.RECORDING) return
        try {
            val tmpFile = File.createTempFile("voice_", ".aac")
            outputFile = tmpFile
            startTime = System.currentTimeMillis()

            val mr = MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(64000)
            mr.setOutputFile(tmpFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            _state.value = RecordingState.RECORDING
            _amplitude.value = 0f

            amplitudeThread = Thread({
                while (_state.value == RecordingState.RECORDING) {
                    try {
                        val amp = mr.maxAmplitude
                        _amplitude.value = (amp / 32768f).coerceIn(0f, 1f)
                        Thread.sleep(100)
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "VoiceAmplitude").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            _state.value = RecordingState.ERROR
        }
    }

    actual fun stop(): RecordingResult? {
        val mr = recorder ?: return null
        val file = outputFile ?: return null
        val durationMs = System.currentTimeMillis() - startTime
        if (durationMs < 1000) {
            cancel()
            return null
        }
        _state.value = RecordingState.IDLE
        _amplitude.value = 0f
        amplitudeThread = null

        try {
            mr.stop()
        } catch (_: Exception) {
            // stop() can throw if start() was never called or recording was too short
        }
        mr.release()
        recorder = null

        val bytes = file.readBytes()
        file.delete()
        outputFile = null
        return RecordingResult(bytes, (durationMs / 1000).toInt())
    }

    actual fun cancel() {
        _state.value = RecordingState.IDLE
        _amplitude.value = 0f
        amplitudeThread = null
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    actual fun release() {
        cancel()
    }
}
