package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.virjar.tk.desktop.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.desktop.media.DesktopMediaFileLease
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.MediaOperationAttemptTracker
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.component.VoicePlaybackController
import com.virjar.tk.protocol.model.Attachment
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
    private val actionAdmission: UiActionAdmission,
) : VoicePlaybackController {
    private var urlState = mutableStateOf<String?>(null)
    private var progressState = mutableFloatStateOf(0f)

    // 兜底计时：引擎对音频-only 文件不上报 duration/currentTime（实测恒 0），
    // 用消息时长做分母、引擎 isPlaying 驱动经过时间累计（暂停即冻结）
    private var durationHintSec = 1
    private var accumulatedMs = 0L
    private var resumeAtMs = 0L
    private var mediaLease: DesktopMediaFileLease? = null
    private val playbackRuns = DesktopVoicePlaybackRunGate()
    private val playbackAttempt = MediaOperationAttemptTracker { outcome, reason ->
        resources.telemetry.recordMedia(
            ClientUiPage.CHAT,
            ClientMediaKind.AUDIO,
            MediaOperation.PLAY,
            outcome,
            reason,
        )
    }
    private var playbackAttemptGeneration: Long? = null

    override val playingUrl: String? by urlState
    override val progress: Float by progressState

    override fun toggle(attachment: Attachment, durationSec: Int) {
        scope.launch {
            var downloadGeneration: Long? = null
            val admitted = actionAdmission.runIfOpen {
                val url = attachment.path
                val same = urlState.value == url && playerState.hasMedia
                when {
                    // 同一条正在播 → 暂停（保留 url 维持暂停态，再点继续）
                    same && playerState.isPlaying -> playerState.pause()
                    // 同一条已暂停 → 继续
                    same -> playerState.play()
                    // 切换/新播放：本地缓存后交给 native 引擎
                    else -> {
                        val generation = playbackRuns.beginLoading()
                        playbackAttempt.start()
                        playbackAttemptGeneration = generation
                        playerState.stop()
                        mediaLease?.close()
                        mediaLease = null
                        durationHintSec = durationSec.coerceAtLeast(1)
                        accumulatedMs = 0L
                        resumeAtMs = 0L
                        progressState.floatValue = 0f
                        urlState.value = url
                        downloadGeneration = generation
                    }
                }
            }
            val generation = downloadGeneration
            if (!admitted || generation == null) return@launch
            var pendingLease: DesktopMediaFileLease? = null
            try {
                val lease = resources.mediaCache.ensureDownloadedLease(attachment)
                pendingLease = lease
                val published = actionAdmission.runIfOpen {
                    if (
                        urlState.value != attachment.path ||
                        !playbackRuns.isCurrent(generation)
                    ) return@runIfOpen
                    try {
                        resources.ensureOpen()
                        resources.diagnostics.record(DesktopSessionDiagnosticEvent.VOICE_PLAYBACK_OPENING)
                        playerState.openUri(lease.file.absolutePath, InitialPlayerState.PLAY)
                        check(playbackRuns.markPlaying(generation)) {
                            "Voice playback generation changed while opening media"
                        }
                        mediaLease?.close()
                        mediaLease = lease
                        pendingLease = null
                        succeedPlaybackAttempt(generation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    }
                }
                if (!published || pendingLease != null) cancelPlaybackAttempt(generation)
            } catch (cancelled: CancellationException) {
                cancelPlaybackAttempt(generation)
                throw cancelled
            } catch (failure: Exception) {
                if (!resources.canDeliverUiResult()) {
                    cancelPlaybackAttempt(generation)
                    return@launch
                }
                actionAdmission.runIfOpen {
                    if (!playbackRuns.isCurrent(generation)) return@runIfOpen
                    // 下载/打开失败：复位，不弹系统播放器
                    failPlaybackAttempt(generation, classifyDesktopMediaFailure(failure))
                    resources.diagnostics.record(DesktopSessionDiagnosticEvent.VOICE_PLAYBACK_FAILED)
                    reset()
                }
            } finally {
                pendingLease?.close()
            }
        }
    }

    /** 播放结束复位只允许由 Compose owner scope 调用；轮询侧同样兜底复位。 */
    fun reset() {
        cancelPendingPlaybackAttempt()
        playbackRuns.retire()
        urlState.value = null
        progressState.floatValue = 0f
        accumulatedMs = 0L
        resumeAtMs = 0L
        mediaLease?.close()
        mediaLease = null
    }

    /** 后台原生回调只能捕获这个不可变的 generation 票据。 */
    fun capturePlayingGeneration(): Long? = playbackRuns.capturePlayingGeneration()

    /** UI owner 会丢弃为已被替换的播放运行排队的完成事件。 */
    fun resetIfPlaying(generation: Long) {
        if (playbackRuns.isPlaying(generation)) reset()
    }

    /** 组合销毁时释放缓存租约，且不再发布已退役的 UI 状态。 */
    fun close() {
        cancelPendingPlaybackAttempt()
        playbackRuns.retire()
        mediaLease?.close()
        mediaLease = null
    }

    private fun succeedPlaybackAttempt(generation: Long) {
        if (playbackAttemptGeneration != generation) return
        playbackAttemptGeneration = null
        playbackAttempt.succeed()
    }

    private fun failPlaybackAttempt(generation: Long, reason: MediaFailureReason) {
        if (playbackAttemptGeneration != generation) return
        playbackAttemptGeneration = null
        playbackAttempt.fail(reason)
    }

    private fun cancelPlaybackAttempt(generation: Long) {
        if (playbackAttemptGeneration != generation) return
        playbackAttemptGeneration = null
        playbackAttempt.cancel()
    }

    private fun cancelPendingPlaybackAttempt() {
        playbackAttemptGeneration = null
        playbackAttempt.cancel()
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

/**
 * 原生播放器可能在解码线程上调用其终结回调。刻意使用 DEFAULT 启动方式：
 * UNDISPATCHED 会在进入 UI owner 的 dispatcher 之前就内联发布。
 * 准入在发布时刻检查，因此排队的回调无法修改已退役的窗口。
 */
internal fun dispatchDesktopVoicePlaybackEnded(
    ownerScope: CoroutineScope,
    actionAdmission: UiActionAdmission,
    generation: Long,
    resetIfPlaying: (Long) -> Unit,
): Job = ownerScope.launch(start = CoroutineStart.DEFAULT) {
    actionAdmission.runIfOpen { resetIfPlaying(generation) }
}

internal class DesktopVoicePlaybackRunGate {
    private val nextGeneration = AtomicLong(0L)
    private val current = AtomicReference<DesktopVoicePlaybackRun?>(null)

    fun beginLoading(): Long {
        val generation = nextGeneration.incrementAndGet()
        current.set(DesktopVoicePlaybackRun(generation, DesktopVoicePlaybackPhase.LOADING))
        return generation
    }

    fun markPlaying(generation: Long): Boolean {
        while (true) {
            val observed = current.get()
            if (
                observed?.generation != generation ||
                observed.phase != DesktopVoicePlaybackPhase.LOADING
            ) return false
            if (
                current.compareAndSet(
                    observed,
                    DesktopVoicePlaybackRun(generation, DesktopVoicePlaybackPhase.PLAYING),
                )
            ) return true
        }
    }

    fun isCurrent(generation: Long): Boolean = current.get()?.generation == generation

    fun isPlaying(generation: Long): Boolean =
        current.get() == DesktopVoicePlaybackRun(generation, DesktopVoicePlaybackPhase.PLAYING)

    fun capturePlayingGeneration(): Long? = current.get()
        ?.takeIf { it.phase == DesktopVoicePlaybackPhase.PLAYING }
        ?.generation

    fun retire() {
        current.set(null)
    }
}

private data class DesktopVoicePlaybackRun(
    val generation: Long,
    val phase: DesktopVoicePlaybackPhase,
)

private enum class DesktopVoicePlaybackPhase { LOADING, PLAYING }

@Composable
internal fun rememberDesktopVoicePlayback(
    resources: DesktopSessionResources,
    actionAdmission: UiActionAdmission,
): VoicePlaybackController {
    val playerState = rememberVideoPlayerState()
    val scope = rememberCoroutineScope()
    val impl = remember(playerState, scope, resources, actionAdmission) {
        DesktopVoicePlaybackImpl(playerState, scope, resources, actionAdmission)
    }

    DisposableEffect(impl) {
        playerState.onPlaybackEnded = {
            impl.capturePlayingGeneration()?.let { generation ->
                dispatchDesktopVoicePlaybackEnded(
                    ownerScope = scope,
                    actionAdmission = actionAdmission,
                    generation = generation,
                    resetIfPlaying = impl::resetIfPlaying,
                )
            }
        }
        onDispose {
            playerState.onPlaybackEnded = null
            impl.close()
        }
    }

    // 100ms 轮询驱动波形着色与进度文字；引擎清空媒体（播完）时复位
    LaunchedEffect(impl) {
        var hadMedia = false
        while (true) {
            val admitted = actionAdmission.runIfOpen {
                if (playerState.hasMedia) {
                    hadMedia = true
                    impl.tick()
                } else if (hadMedia) {
                    hadMedia = false
                    impl.reset()
                }
            }
            if (!admitted) break
            delay(100)
        }
    }

    return impl
}
