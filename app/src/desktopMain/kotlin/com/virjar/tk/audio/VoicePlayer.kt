package com.virjar.tk.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.net.URL
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine

actual class VoicePlayer actual constructor() {
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    actual val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    actual val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    actual val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0)
    actual val duration: StateFlow<Int> = _duration.asStateFlow()

    private var playThread: Thread? = null
    private var sourceLine: javax.sound.sampled.SourceDataLine? = null

    actual fun play(url: String) {
        // Stop any current playback
        stop()

        _currentUrl.value = url
        _state.value = PlaybackState.PLAYING
        _currentPosition.value = 0

        playThread = Thread({
            try {
                val bytes = URL(url).readBytes()
                val audioInputStream: AudioInputStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
                val format = audioInputStream.format
                val info = DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
                val line = AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
                sourceLine = line
                line.open(format)
                line.start()

                // Calculate duration in ms
                val frameLength = audioInputStream.frameLength
                val frameRate = format.frameRate
                if (frameLength > 0 && frameRate > 0) {
                    _duration.value = ((frameLength / frameRate) * 1000).toInt()
                }

                val buf = ByteArray(4096)
                var totalBytesRead = 0L
                val bytesPerMs = format.frameSize * format.frameRate / 1000f

                while (_state.value == PlaybackState.PLAYING) {
                    val count = audioInputStream.read(buf, 0, buf.size)
                    if (count < 0) break
                    line.write(buf, 0, count)
                    totalBytesRead += count
                    if (bytesPerMs > 0) {
                        _currentPosition.value = (totalBytesRead / bytesPerMs).toInt()
                    }
                }

                line.drain()
                line.close()
                audioInputStream.close()

                if (_state.value == PlaybackState.PLAYING) {
                    _state.value = PlaybackState.COMPLETED
                }
            } catch (_: InterruptedException) {
                // Stopped by user
            } catch (e: Exception) {
                if (_state.value == PlaybackState.PLAYING) {
                    _state.value = PlaybackState.COMPLETED
                }
            } finally {
                sourceLine = null
            }
        }, "VoicePlayer").apply {
            isDaemon = true
            start()
        }
    }

    actual fun stop() {
        _state.value = PlaybackState.IDLE
        _currentPosition.value = 0
        sourceLine?.let {
            try {
                it.stop()
                it.close()
            } catch (_: Exception) {}
        }
        sourceLine = null
        playThread?.let {
            it.interrupt()
        }
        playThread = null
    }

    actual fun release() {
        stop()
    }
}
