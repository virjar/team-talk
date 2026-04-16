package com.virjar.tk.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

actual class VoiceRecorder actual constructor() {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    actual val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    actual val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var targetLine: javax.sound.sampled.TargetDataLine? = null
    private var recordingThread: Thread? = null
    private val pcmBuffer = ByteArrayOutputStream()
    private var startTime = 0L

    private val format = AudioFormat(16000f, 16, 1, true, false)

    actual fun start() {
        if (_state.value == RecordingState.RECORDING) return
        try {
            val line = AudioSystem.getTargetDataLine(format)
            line.open(format)
            line.start()
            targetLine = line
            pcmBuffer.reset()
            startTime = System.currentTimeMillis()
            _state.value = RecordingState.RECORDING
            _amplitude.value = 0f

            recordingThread = Thread({
                val buf = ByteArray(1024)
                while (line.isOpen && _state.value == RecordingState.RECORDING) {
                    val count = line.read(buf, 0, buf.size)
                    if (count > 0) {
                        pcmBuffer.write(buf, 0, count)
                        // Calculate RMS amplitude
                        var sum = 0.0
                        for (i in 0 until count - 1 step 2) {
                            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / (count / 2))
                        _amplitude.value = (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()
                    }
                }
            }, "VoiceRecorder").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            _state.value = RecordingState.ERROR
        }
    }

    actual fun stop(): RecordingResult? {
        val line = targetLine ?: return null
        val durationMs = System.currentTimeMillis() - startTime
        // Minimum 1 second
        if (durationMs < 1000) {
            cancel()
            return null
        }
        _state.value = RecordingState.IDLE
        _amplitude.value = 0f

        line.stop()
        line.close()
        targetLine = null
        recordingThread = null

        val pcm = pcmBuffer.toByteArray()
        val wav = wrapWav(pcm)
        return RecordingResult(wav, (durationMs / 1000).toInt())
    }

    actual fun cancel() {
        _state.value = RecordingState.IDLE
        _amplitude.value = 0f
        targetLine?.let {
            it.stop()
            it.close()
        }
        targetLine = null
        recordingThread = null
        pcmBuffer.reset()
    }

    actual fun release() {
        cancel()
    }

    /** Wrap raw PCM data in a WAV header (16-bit mono 16kHz). */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val dataLength = pcm.size
        val totalLength = 36 + dataLength
        val out = ByteArrayOutputStream(44 + dataLength)
        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToLe(totalLength))
        out.write("WAVE".toByteArray())
        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToLe(16)) // chunk size
        out.write(shortToLe(1)) // PCM format
        out.write(shortToLe(1)) // mono
        out.write(intToLe(16000)) // sample rate
        out.write(intToLe(32000)) // byte rate (16000 * 2 * 1)
        out.write(shortToLe(2)) // block align
        out.write(shortToLe(16)) // bits per sample
        // data chunk
        out.write("data".toByteArray())
        out.write(intToLe(dataLength))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortToLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )
}
