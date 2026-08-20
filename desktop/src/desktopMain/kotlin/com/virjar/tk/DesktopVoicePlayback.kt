package com.virjar.tk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.virjar.tk.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.media.DesktopSessionResources
import com.virjar.tk.ui.component.VoicePlaybackController
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Desktop 语音应用内播放控制器。
 *
 * 引擎复用 compose-media-player 的 native 解码器（ffmpeg 系，自带全平台 dylib/so/dll，
 * AAC/MP3/WAV 全格式）——不渲染 VideoPlayerSurface，纯当音频引擎用。
 * 根治：javax.sound.sampled 只认 WAV/AIFF → AAC 语音 fallback 系统播放器弹窗的反模式。
 *
 * 语音先下载到本地缓存再播（与图片/文件一致的本地优先策略）。
 */
private class DesktopVoicePlaybackImpl(
    private val playerState: VideoPlayerState,
    private val scope: CoroutineScope,
    private val resources: DesktopSessionResources,
) : VoicePlaybackController {
    private var urlState = mutableStateOf<String?>(null)
    private var progressState = mutableFloatStateOf(0f)

    // 兜底计时：引擎对音频-only 文件不上报 duration/currentTime（实测恒 0），
    // 用消息时长做分母、引擎 isPlaying 驱动经过时间累计（暂停即冻结）
    private var durationHintSec = 1
    private var accumulatedMs = 0L
    private var resumeAtMs = 0L

    override val playingUrl: String? by urlState
    override val progress: Float by progressState

    override fun toggle(url: String, durationSec: Int) {
        scope.launch {
            val same = urlState.value == url && playerState.hasMedia
            when {
                // 同一条正在播 → 暂停（保留 url 维持暂停态，再点继续）
                same && playerState.isPlaying -> playerState.pause()
                // 同一条已暂停 → 继续
                same -> playerState.play()
                // 切换/新播放：本地缓存后交给 native 引擎
                else -> {
                    playerState.stop()
                    durationHintSec = durationSec.coerceAtLeast(1)
                    accumulatedMs = 0L
                    resumeAtMs = 0L
                    progressState.floatValue = 0f
                    urlState.value = url
                    try {
                        val file = resources.mediaCache.ensureDownloaded(url)
                        resources.ensureOpen()
                        resources.diagnostics.record(DesktopSessionDiagnosticEvent.VOICE_PLAYBACK_OPENING)
                        playerState.openUri(file.absolutePath, InitialPlayerState.PLAY)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 下载/打开失败：复位，不弹系统播放器
                        resources.diagnostics.record(DesktopSessionDiagnosticEvent.VOICE_PLAYBACK_FAILED)
                        reset()
                    }
                }
            }
        }
    }

    /**
     * 播放结束复位（onPlaybackEnded 可能在后台线程；snapshot state 写入线程安全）。
     * 引擎播完音频后会清 hasMedia，轮询侧同样兜底复位。
     */
    fun reset() {
        urlState.value = null
        progressState.floatValue = 0f
        accumulatedMs = 0L
        resumeAtMs = 0L
    }

    /** 由轮询驱动：引擎 isPlaying 驱动经过时间累计；duration 有真值时优先用真值。 */
    fun tick() {
        val now = System.currentTimeMillis()
        if (playerState.isPlaying) {
            if (resumeAtMs == 0L) resumeAtMs = now
        } else if (resumeAtMs != 0L) {
            accumulatedMs += now - resumeAtMs
            resumeAtMs = 0L
        }
        val engineDuration = playerState.duration
        progressState.floatValue = if (engineDuration > 0) {
            (playerState.currentTime / engineDuration).toFloat().coerceIn(0f, 1f)
        } else {
            val elapsed = accumulatedMs + if (resumeAtMs != 0L) now - resumeAtMs else 0L
            (elapsed / (durationHintSec * 1000f)).coerceIn(0f, 1f)
        }
        // 播完兜底复位：引擎对音频-only 播完既不触发 onPlaybackEnded、不清 hasMedia，
        // 连 isPlaying 也不回落（实测三个信号全缺失）。elapsed 为墙钟累计（暂停即冻结），
        // 走满即播完——暂停态 elapsed 冻结在 <1，不受影响
        if (progressState.floatValue >= 1f) {
            reset()
        }
    }
}

@Composable
internal fun rememberDesktopVoicePlayback(resources: DesktopSessionResources): VoicePlaybackController {
    val playerState = rememberVideoPlayerState()
    val scope = rememberCoroutineScope()
    val impl = remember(playerState, scope, resources) {
        DesktopVoicePlaybackImpl(playerState, scope, resources)
    }

    DisposableEffect(impl) {
        playerState.onPlaybackEnded = { impl.reset() }
        onDispose {
            playerState.onPlaybackEnded = null
            playerState.dispose()
        }
    }

    // 100ms 轮询驱动波形着色与进度文字；引擎清空媒体（播完）时复位
    LaunchedEffect(impl) {
        var hadMedia = false
        while (true) {
            if (playerState.hasMedia) {
                hadMedia = true
                impl.tick()
            } else if (hadMedia) {
                hadMedia = false
                impl.reset()
            }
            delay(100)
        }
    }

    return impl
}
