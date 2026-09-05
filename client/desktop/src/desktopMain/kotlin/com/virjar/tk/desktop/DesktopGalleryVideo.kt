package com.virjar.tk.desktop

import com.virjar.tk.shared.AppError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.virjar.tk.desktop.media.DesktopMediaProgressHandoff
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.Attachment
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Desktop 视频渲染：只有当前页创建 native player。媒体必须先通过会话缓存完整下载、
 * 大小校验和原子发布；播放器只读取持有租约的本地文件，不直接访问服务端 URL。
 */
@Composable
internal fun DesktopVideoPage(
    attachment: Attachment,
    isCurrentPage: Boolean,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var playerGeneration by remember(attachment.path, resources) { mutableIntStateOf(0) }
    var forceRefresh by remember(attachment.path, resources) { mutableStateOf(false) }
    if (!isCurrentPage) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    key(attachment.path, playerGeneration) {
        DesktopVideoPageInstance(
            attachment = attachment,
            presentationGate = presentationGate,
            resources = resources,
            forceRefreshCache = forceRefresh,
            onForceRefreshConsumed = { forceRefresh = false },
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onRestartFromBeginning = {
                forceRefresh = false
                playerGeneration++
            },
            onRetry = { refreshCache ->
                forceRefresh = refreshCache
                playerGeneration++
            },
            modifier = modifier,
        )
    }
}

/** 一个 generation 拥有一个原生播放器。重放会替换它，因此旧的异步 seek/play 命令无法
 * 作用于新的播放会话或恢复过期位置。 */
@Composable
private fun DesktopVideoPageInstance(
    attachment: Attachment,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    forceRefreshCache: Boolean,
    onForceRefreshConsumed: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onRestartFromBeginning: () -> Unit,
    onRetry: (refreshCache: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 使用非组合工厂，让本页成为唯一的 dispose owner。库自带的
    // rememberVideoPlayerState() 会安装自己的 DisposableEffect，在快速翻页时
    // 会与我们有序的 player-dispose -> cache-lease 释放路径竞争。
    val playerState = remember { createVideoPlayerState() }
    var state by remember(attachment.path, resources) {
        mutableStateOf<DesktopGalleryVideoLoadState>(DesktopGalleryVideoLoadState.Downloading(0f))
    }
    val leaseOwner = remember(resources, attachment.path) { DesktopGalleryVideoLeaseOwner() }
    val playbackScope = rememberCoroutineScope()
    var wantsPlayback by remember(playerState) { mutableStateOf(true) }
    var playbackEnded by remember(playerState) { mutableStateOf(false) }
    var pausedSeekPosition by remember(playerState) { mutableStateOf<Float?>(null) }
    val playbackWasActive = remember(playerState) { booleanArrayOf(false) }
    val playerRetired = remember(playerState) { booleanArrayOf(false) }
    val refreshedCacheForPlayer = remember(playerState) { forceRefreshCache }
    val retirePlayer = {
        if (!playerRetired[0]) {
            playerRetired[0] = true
            closeDesktopGalleryVideoPlayer(
                stopPlayer = playerState::stop,
                disposePlayer = playerState::dispose,
                releaseLease = leaseOwner::close,
                reportFailure = {
                    resources.diagnostics.record(
                        com.virjar.tk.desktop.media.DesktopSessionDiagnosticEvent.VIDEO_PLAYER_DISPOSE_FAILED,
                    )
                },
            )
        }
    }

    LaunchedEffect(attachment, resources, presentationGate, playerState) {
        leaseOwner.clear()
        val publicationGate: ((() -> Unit) -> Boolean) = { publication ->
            var delivered = false
            presentationGate.runIfOpen {
                if (resources.canDeliverUiResult()) {
                    publication()
                    delivered = true
                }
            }
            delivered
        }
        if (!publicationGate { state = DesktopGalleryVideoLoadState.Downloading(0f) }) {
            return@LaunchedEffect
        }
        val progressHandoff = DesktopMediaProgressHandoff(
            ownerScope = this,
            publicationGate = publicationGate,
            publish = { progress ->
                state = DesktopGalleryVideoLoadState.Downloading(progress.coerceIn(0f, 1f))
            },
        )
        var pendingLease: AutoCloseable? = null
        var attemptedLocalOpen = false
        val refreshThisAttempt = forceRefreshCache
        if (refreshThisAttempt) onForceRefreshConsumed()
        try {
            val lease = resources.mediaCache.cachedLease(attachment, refreshThisAttempt)
                ?: resources.mediaCache.ensureDownloadedLease(attachment) { progress ->
                    progressHandoff.offer(progress)
                }
            pendingLease = lease
            attemptedLocalOpen = true
            val opened = publicationGate {
                resources.ensureOpen()
                if (leaseOwner.replace(lease)) {
                    pendingLease = null
                    playerState.openUri(
                        desktopGalleryVideoLocalSource(lease.file, attachment.size),
                        InitialPlayerState.PAUSE,
                    )
                    state = DesktopGalleryVideoLoadState.Opening
                }
            }
            if (!opened) return@LaunchedEffect

            // macOS 后端可能接受损坏的本地文件而不发布 `error`：它永远报告 hasMedia=true，
            // 而 duration 始终为零。在拿到有限的本地元数据之前保持画面隐藏，
            // 然后在失败时转入常规的缓存刷新重试路径，而不是呈现一个永久的 00:00 黑屏播放器。
            val readiness = withTimeoutOrNull(VIDEO_OPEN_TIMEOUT_MILLIS) {
                snapshotFlow { playerState.error to playerState.duration }
                    .first { (error, duration) -> error != null || duration > 0.0 }
            }
            check(readiness != null && readiness.first == null && readiness.second > 0.0) {
                "Local video player did not publish valid metadata"
            }
            publicationGate { state = DesktopGalleryVideoLoadState.Ready }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AppError.AuthExpired) {
            // 认证退役拥有 UI 切换；不要把它暴露成可重试的 IO 错误。
            retirePlayer()
        } catch (_: Exception) {
            publicationGate {
                state = DesktopGalleryVideoLoadState.Error(
                    message = "视频下载或本地打开失败",
                    refreshCacheOnRetry = attemptedLocalOpen && !refreshThisAttempt,
                )
                retirePlayer()
            }
        } finally {
            pendingLease?.close()
            progressHandoff.close()
        }
    }

    LaunchedEffect(playerState.error, state) {
        if (state is DesktopGalleryVideoLoadState.Ready && playerState.error != null) {
            state = DesktopGalleryVideoLoadState.Error(
                message = "播放器无法读取视频",
                refreshCacheOnRetry = !refreshedCacheForPlayer,
            )
            retirePlayer()
        }
    }

    // Desktop 上的播放操作是异步的。由明确的同步用户意图来驱动，并且当持有权被撤销时
    // 始终排队 PAUSE，即使之前的 PLAY 还没有反映到 playerState.isPlaying 上。
    LaunchedEffect(
        state,
        playerState.hasMedia,
        wantsPlayback,
        playbackEnded,
        playerState.isPlaying,
    ) {
        val mayPlay = shouldDesktopGalleryVideoPlay(
            isReady = state is DesktopGalleryVideoLoadState.Ready,
            isCurrentPage = true,
            hasMedia = playerState.hasMedia,
            wantsPlayback = wantsPlayback && !playbackEnded,
        )
        val action = desktopGalleryPlaybackAction(
            shouldPlay = mayPlay,
            isPlaying = playerState.isPlaying,
            wasPlaying = playbackWasActive[0],
            isAtNaturalEnd = isDesktopGalleryVideoAtNaturalEnd(
                currentTime = playerState.currentTime,
                duration = playerState.duration,
            ),
        )
        when {
            !mayPlay -> playbackWasActive[0] = false
            playerState.isPlaying -> playbackWasActive[0] = true
        }
        when (action) {
            DesktopGalleryPlaybackAction.PLAY -> playerState.play()
            // 即使观测到的状态已经是暂停，也刻意重新发出 PAUSE。
            // 该观测背后可能还排着一个过期的异步 PLAY；如果它获胜，isPlaying 会变化，
            // 本 effect 会再次让原生播放器收敛回 PAUSE。
            DesktopGalleryPlaybackAction.PAUSE -> playerState.pause()
            DesktopGalleryPlaybackAction.WAIT_FOR_END -> {
                // 原生 Desktop 实现会在 onPlaybackEnded 之前发布 isPlaying=false。
                // 给这个有序回调一个有界窗口。如果它始终没有到达，说明这是一次过期的 PAUSE
                // 而非 EOF，因此恢复仍然有效的播放意图。
                delay(VIDEO_END_CALLBACK_GRACE_MILLIS)
                val stillMayPlay = shouldDesktopGalleryVideoPlay(
                    isReady = state is DesktopGalleryVideoLoadState.Ready,
                    isCurrentPage = true,
                    hasMedia = playerState.hasMedia,
                    wantsPlayback = wantsPlayback && !playbackEnded,
                )
                if (shouldRetryDesktopGalleryPlaybackAfterEndGrace(
                        shouldPlay = stillMayPlay,
                        isPlaying = playerState.isPlaying,
                        playbackEnded = playbackEnded,
                    )
                ) {
                    playerState.play()
                }
            }
            DesktopGalleryPlaybackAction.NONE -> Unit
        }
    }

    DisposableEffect(playerState, playbackScope) {
        val previous = playerState.onPlaybackEnded
        val handler: () -> Unit = {
            previous?.invoke()
            playbackScope.launch {
                playbackWasActive[0] = false
                playbackEnded = true
                wantsPlayback = false
            }
        }
        playerState.onPlaybackEnded = handler
        onDispose {
            if (playerState.onPlaybackEnded === handler) {
                playerState.onPlaybackEnded = previous
            }
        }
    }

    DisposableEffect(playerState, leaseOwner) {
        onDispose {
            retirePlayer()
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("media.gallery.video"),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is DesktopGalleryVideoLoadState.Downloading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.testTag("media.gallery.video.loading"),
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    "正在下载视频 ${(s.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.testTag("media.gallery.video.downloadProgress"),
                )
            }
            DesktopGalleryVideoLoadState.Opening -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.testTag("media.gallery.video.loading"),
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("正在准备视频", color = Color.White)
            }
            is DesktopGalleryVideoLoadState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.testTag("media.gallery.video.error"),
                ) {
                    Text("视频加载失败", color = Color.White)
                    Text(s.message, color = Color.White.copy(alpha = 0.6f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            retryDesktopGalleryVideo(
                                currentState = state,
                                updateState = { state = it },
                                onRestart = onRetry,
                            )
                        },
                        modifier = Modifier.testTag("media.gallery.video.retry"),
                    ) {
                        Text("重试")
                    }
                }
            }
            is DesktopGalleryVideoLoadState.Ready -> {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("media.gallery.video.surface"),
                    contentScale = ContentScale.Fit,
                )
                DesktopVideoControls(
                    playerState = playerState,
                    wantsPlayback = wantsPlayback,
                    playbackEnded = playbackEnded,
                    onWantsPlaybackChange = { requested ->
                        // 明确的用户意图开启一个新的观测 epoch。特别是，
                        // 在接近 EOF 时恢复播放不能被误认为原生的播放结束序列。
                        playbackWasActive[0] = false
                        playbackEnded = false
                        if (requested) pausedSeekPosition = null
                        wantsPlayback = requested
                    },
                    onSeek = { requested ->
                        playbackEnded = false
                        // macOS 后端会在原生层应用暂停状态的 seek，但在播放恢复之前不会发布新的 positionText。
                        // 把已提交的目标保留为暂停态的 UI 位置，让普通用户与无障碍用户都能立即获得反馈，
                        // 同时把同一目标发送给原生播放器。
                        pausedSeekPosition = requested.takeUnless { wantsPlayback }
                        playerState.seekStart(requested)
                        playerState.seekFinished()
                    },
                    pausedSeekPosition = pausedSeekPosition,
                    onRestartFromBeginning = onRestartFromBeginning,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = onToggleFullscreen,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun DesktopVideoControls(
    playerState: VideoPlayerState,
    wantsPlayback: Boolean,
    playbackEnded: Boolean,
    onWantsPlaybackChange: (Boolean) -> Unit,
    onSeek: (Float) -> Unit,
    pausedSeekPosition: Float?,
    onRestartFromBeginning: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .testTag("media.gallery.video.controls"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("media.gallery.video.progress")
                .semantics {
                    val current = playerState.sliderPos.coerceIn(
                        VIDEO_PROGRESS_MIN,
                        VIDEO_PROGRESS_MAX,
                    )
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = current,
                        range = VIDEO_PROGRESS_MIN..VIDEO_PROGRESS_MAX,
                    )
                    // Material Slider 内置的 SetProgress 只会派发 onValueChange，
                    // 因此无障碍路径永远不会触发播放器的 seekFinished 回调。在这里接管合并后的
                    // semantics，并直接执行完整的 seek。
                    setProgress { requested ->
                        onSeek(
                            requested.coerceIn(
                                VIDEO_PROGRESS_MIN,
                                VIDEO_PROGRESS_MAX,
                            ),
                        )
                        true
                    }
                },
        ) {
            Slider(
                value = playerState.sliderPos.coerceIn(VIDEO_PROGRESS_MIN, VIDEO_PROGRESS_MAX),
                onValueChange = { position -> playerState.seekStart(position) },
                onValueChangeFinished = {
                    onSeek(playerState.sliderPos)
                },
                valueRange = VIDEO_PROGRESS_MIN..VIDEO_PROGRESS_MAX,
                enabled = playerState.hasMedia,
                // 父级已导出完整的无障碍滑杆契约。保留 Material 的动作会遮蔽它，
                // 让无障碍 seek 无法提交。
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    when {
                        wantsPlayback && !playbackEnded -> {
                            onWantsPlaybackChange(false)
                        }
                        playbackEnded || shouldRestartGalleryVideo(
                            sliderPosition = playerState.sliderPos,
                            currentTime = playerState.currentTime,
                            duration = playerState.duration,
                        ) -> {
                            onRestartFromBeginning()
                        }
                        else -> onWantsPlaybackChange(true)
                    }
                },
                enabled = playerState.hasMedia,
                modifier = Modifier.testTag("media.gallery.video.playPause"),
            ) {
                val showsPause = wantsPlayback && !playbackEnded
                Icon(
                    imageVector = if (showsPause) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = if (showsPause) {
                        "暂停视频"
                    } else {
                        "播放视频"
                    },
                    tint = Color.White,
                )
            }
            Text(
                text = "${desktopGalleryVideoPositionText(playerState, pausedSeekPosition)} / ${playerState.durationText}",
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("media.gallery.video.time"),
            )
            Spacer(Modifier.weight(1f))
            if (playerState.isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.testTag("media.gallery.video.fullscreen"),
            ) {
                Icon(
                    imageVector = if (isFullscreen) {
                        Icons.Filled.FullscreenExit
                    } else {
                        Icons.Filled.Fullscreen
                    },
                    contentDescription = if (isFullscreen) "退出全屏" else "全屏播放",
                    tint = Color.White,
                )
            }
        }
    }
}

internal fun desktopGalleryVideoPositionText(
    playerState: VideoPlayerState,
    pausedSeekPosition: Float?,
): String {
    val seekPosition = pausedSeekPosition ?: return playerState.positionText
    val seconds = playerState.duration *
        (seekPosition.coerceIn(VIDEO_PROGRESS_MIN, VIDEO_PROGRESS_MAX) / VIDEO_PROGRESS_MAX)
    return formatDesktopGalleryVideoTime(seconds)
}

internal fun formatDesktopGalleryVideoTime(seconds: Double): String {
    val wholeSeconds = seconds.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L
    val hours = wholeSeconds / 3_600L
    val minutes = (wholeSeconds % 3_600L) / 60L
    val remainingSeconds = wholeSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(minutes, remainingSeconds)
    }
}

internal fun shouldDesktopGalleryVideoPlay(
    isReady: Boolean,
    isCurrentPage: Boolean,
    hasMedia: Boolean,
    wantsPlayback: Boolean,
): Boolean = isReady && isCurrentPage && hasMedia && wantsPlayback

internal enum class DesktopGalleryPlaybackAction {
    PLAY,
    PAUSE,
    WAIT_FOR_END,
    NONE,
}

/**
 * 选择让异步原生播放器收敛到图库意图所需的命令。
 * 撤销持有权时总是发出 PAUSE，包括最后观测到的状态已是暂停的情况，
 * 因为较旧的 PLAY 协程可能在那次观测之后才完成。
 */
internal fun desktopGalleryPlaybackAction(
    shouldPlay: Boolean,
    isPlaying: Boolean,
    wasPlaying: Boolean,
    isAtNaturalEnd: Boolean,
): DesktopGalleryPlaybackAction = when {
    !shouldPlay -> DesktopGalleryPlaybackAction.PAUSE
    // 原生播放器在调用 onPlaybackEnded 之前会先报告 isPlaying=false。
    // 不要把这种有序的 EOF 过渡变成一次过期 PAUSE 的恢复 PLAY。
    !isPlaying && wasPlaying && isAtNaturalEnd -> DesktopGalleryPlaybackAction.WAIT_FOR_END
    !isPlaying -> DesktopGalleryPlaybackAction.PLAY
    else -> DesktopGalleryPlaybackAction.NONE
}

/** 匹配 Desktop 播放器的自然结束窗口，而显式恢复由
 * [desktopGalleryPlaybackAction] 的 wasPlaying 观测 epoch 来区分。 */
internal fun isDesktopGalleryVideoAtNaturalEnd(
    currentTime: Double,
    duration: Double,
): Boolean = duration > 0.0 &&
    currentTime >= duration - VIDEO_NATURAL_END_TIME_TOLERANCE_SECONDS

internal fun shouldRetryDesktopGalleryPlaybackAfterEndGrace(
    shouldPlay: Boolean,
    isPlaying: Boolean,
    playbackEnded: Boolean,
): Boolean = shouldPlay && !isPlaying && !playbackEnded
internal fun shouldRestartGalleryVideo(
    sliderPosition: Float,
    currentTime: Double,
    duration: Double,
): Boolean = sliderPosition >= VIDEO_RESTART_SLIDER_THRESHOLD ||
    (duration > 0.0 && currentTime >= duration - VIDEO_RESTART_TIME_TOLERANCE_SECONDS)

/**
 * 在解除已验证缓存文件的固定之前，先释放当前页的原生播放器。
 *
 * [VideoPlayerState.stop] 在 macOS 上只会排队平台工作，而 [VideoPlayerState.dispose]
 * 持有原生指针。因此图库显式调用两者，并保持缓存租约直到 dispose 请求已发出。
 * 即使前面的平台调用失败，每一步也都会尝试，这样损坏的播放器就无法永久钉住缓存条目。
 */
internal fun closeDesktopGalleryVideoPlayer(
    stopPlayer: () -> Unit,
    disposePlayer: () -> Unit,
    releaseLease: () -> Unit,
    reportFailure: (Throwable) -> Unit = {},
): Throwable? {
    val failures = buildList {
        listOf(stopPlayer, disposePlayer, releaseLease).forEach { release ->
            try {
                release()
            } catch (failure: Throwable) {
                add(failure)
            }
        }
    }
    if (failures.isEmpty()) return null
    failures.firstOrNull(::isFatalDesktopGalleryVideoFailure)?.let { fatal ->
        failures.forEach { failure -> addSuppressedDesktopGalleryVideoFailure(fatal, failure) }
        try {
            reportFailure(fatal)
        } catch (diagnosticFailure: Throwable) {
            addSuppressedDesktopGalleryVideoFailure(fatal, diagnosticFailure)
        }
        throw fatal
    }
    val aggregate = IllegalStateException(
        "Desktop gallery video player retirement failed in ${failures.size} step(s)",
    ).apply {
        failures.forEach(::addSuppressed)
    }
    try {
        reportFailure(aggregate)
    } catch (diagnosticFailure: Throwable) {
        if (isFatalDesktopGalleryVideoFailure(diagnosticFailure)) {
            addSuppressedDesktopGalleryVideoFailure(diagnosticFailure, aggregate)
            throw diagnosticFailure
        }
        addSuppressedDesktopGalleryVideoFailure(aggregate, diagnosticFailure)
    }
    return aggregate
}

private fun isFatalDesktopGalleryVideoFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

private fun addSuppressedDesktopGalleryVideoFailure(primary: Throwable, additional: Throwable) {
    if (primary !== additional && primary.suppressed.none { it === additional }) {
        primary.addSuppressed(additional)
    }
}

/** 在当前原生播放器的生命周期内，至多固定一个已完成的缓存文件。 */
internal class DesktopGalleryVideoLeaseOwner : AutoCloseable {
    private val lock = Any()
    private var current: AutoCloseable? = null
    private var closed = false

    fun replace(next: AutoCloseable): Boolean {
        var previous: AutoCloseable? = null
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                previous = current
                current = next
                true
            }
        }
        if (!accepted) {
            next.close()
            return false
        }
        if (previous !== next) previous?.close()
        return true
    }

    fun clear() {
        val previous = synchronized(lock) {
            val owned = current
            current = null
            owned
        }
        previous?.close()
    }

    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return@synchronized null
            closed = true
            val owned = current
            current = null
            owned
        }
        previous?.close()
    }
}

internal sealed interface DesktopGalleryVideoLoadState {
    data class Downloading(val progress: Float) : DesktopGalleryVideoLoadState
    data object Opening : DesktopGalleryVideoLoadState
    data object Ready : DesktopGalleryVideoLoadState
    data class Error(
        val message: String,
        val refreshCacheOnRetry: Boolean = false,
    ) : DesktopGalleryVideoLoadState
}

/** 在替换 generation 之前，把出错的播放器移入不可点击的下载状态。 */
internal fun retryDesktopGalleryVideo(
    currentState: DesktopGalleryVideoLoadState,
    updateState: (DesktopGalleryVideoLoadState) -> Unit,
    onRestart: (refreshCache: Boolean) -> Unit,
): Boolean {
    if (currentState !is DesktopGalleryVideoLoadState.Error) return false
    updateState(DesktopGalleryVideoLoadState.Downloading(0f))
    onRestart(currentState.refreshCacheOnRetry)
    return true
}

/** 原生播放被有意限制为只读取已验证、已完成的本地缓存文件。 */
internal fun desktopGalleryVideoLocalSource(file: File, expectedBytes: Long): String {
    require(file.isAbsolute) { "Desktop gallery video cache file must be absolute" }
    require(file.isFile) { "Desktop gallery video cache file must exist" }
    require(file.length() == expectedBytes) {
        "Desktop gallery video cache file size does not match attachment metadata"
    }
    return file.absolutePath
}

private const val VIDEO_PROGRESS_MIN = 0f
private const val VIDEO_PROGRESS_MAX = 1_000f
private const val VIDEO_RESTART_SLIDER_THRESHOLD = 999f
private const val VIDEO_RESTART_TIME_TOLERANCE_SECONDS = 0.25
private const val VIDEO_NATURAL_END_TIME_TOLERANCE_SECONDS = 0.5
private const val VIDEO_END_CALLBACK_GRACE_MILLIS = 500L
private const val VIDEO_OPEN_TIMEOUT_MILLIS = 5_000L
