package com.virjar.tk.android

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.virjar.tk.android.R
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal sealed interface GalleryVideoLoadState {
    data class Downloading(val progress: Float) : GalleryVideoLoadState
    data class Ready(val player: ExoPlayer) : GalleryVideoLoadState
    data class Failed(val refreshCacheOnRetry: Boolean) : GalleryVideoLoadState
}

internal enum class GalleryVideoPlaybackCommand { NONE, PLAY, PAUSE }

/**
 * 保存用户在被页面切换或 Activity 后台化抑制前的播放意图。
 *
 * 初次成为 currentPage 时自动播放；如果用户主动暂停，离页再回来仍保持暂停；如果原本在播放，
 * ON_STOP/离页会暂停并在重新可见时恢复。无论何种来源，非 currentPage 的 playWhenReady 都会被压回 false。
 */
internal class GalleryVideoPlaybackGate {
    private var wasAllowed = false
    private var resumeWhenAllowed = true

    fun update(
        allowedNow: Boolean,
        playerWantsPlayback: Boolean,
    ): GalleryVideoPlaybackCommand {
        if (!allowedNow) {
            if (wasAllowed) resumeWhenAllowed = playerWantsPlayback
            wasAllowed = false
            return if (playerWantsPlayback) {
                GalleryVideoPlaybackCommand.PAUSE
            } else {
                GalleryVideoPlaybackCommand.NONE
            }
        }

        if (wasAllowed) return GalleryVideoPlaybackCommand.NONE
        wasAllowed = true
        return if (resumeWhenAllowed) {
            GalleryVideoPlaybackCommand.PLAY
        } else {
            GalleryVideoPlaybackCommand.NONE
        }
    }
}

/**
 * Android 视频播放器（Media3 ExoPlayer + PlayerView）。
 *
 * 用于画廊全屏视频播放。认证传输层先把附件完整下载、校验大小并原子发布到账号隔离缓存，
 * ExoPlayer 只接收本地 file URI，永远不接触服务器 URL、Bearer 或 HTTP Range。保留页与后台页
 * 不持有下载或播放器；PlayerView 提供播放/暂停/进度条控制。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun rememberVideoPlayer(
    attachment: Attachment,
    mediaSession: AndroidMediaSession,
    isCurrentPage: Boolean,
    telemetry: ClientUiTelemetrySink,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Pager 会保留相邻页面，但只有当前前台页面可以下载或持有播放器。
    val mayOwnResources = isCurrentPage && lifecycleStarted
    var retryAttempt by remember(attachment, mediaSession.cacheNamespace) { mutableIntStateOf(0) }
    var forceRefresh by remember(attachment, mediaSession.cacheNamespace) { mutableStateOf(false) }
    // 失去页面所有权时丢弃页面状态；重新进入会再次调用 MediaHelper，并直接命中持久缓存。
    var loadState by remember(attachment, mediaSession, mediaSession.cacheNamespace, mayOwnResources) {
        mutableStateOf<GalleryVideoLoadState>(GalleryVideoLoadState.Downloading(0f))
    }
    val progressUpdates = remember(attachment, mediaSession, mayOwnResources) { MutableStateFlow(0f) }
    val playbackGate = remember(attachment, mediaSession.cacheNamespace) {
        GalleryVideoPlaybackGate()
    }
    var playerEventEpoch by remember(attachment, mediaSession.cacheNamespace) { mutableIntStateOf(0) }
    val latestTelemetry by rememberUpdatedState(telemetry)
    val retryLoad: () -> Unit = {
        forceRefresh = (loadState as? GalleryVideoLoadState.Failed)?.refreshCacheOnRetry == true
        loadState = GalleryVideoLoadState.Downloading(0f)
        retryAttempt++
    }

    // MediaHelper 从 Dispatchers.IO 上报。StateFlow 是线程安全的交接通道；只有这个
    // 组合持有的收集器会修改 Compose 状态。
    LaunchedEffect(progressUpdates, mayOwnResources) {
        if (!mayOwnResources) return@LaunchedEffect
        progressUpdates.collect { progress ->
            if (loadState is GalleryVideoLoadState.Downloading) {
                loadState = GalleryVideoLoadState.Downloading(progress.coerceIn(0f, 1f))
            }
        }
    }

    // 单个协程拥有完整链路：认证下载、固定的本地文件、原生播放器和最终释放。
    // 不存在第二个 effect 能在 player.release() 之前解除固定。
    LaunchedEffect(attachment, mediaSession, retryAttempt, mayOwnResources) {
        if (!mayOwnResources) return@LaunchedEffect
        progressUpdates.value = 0f
        loadState = GalleryVideoLoadState.Downloading(0f)
        var lease: AndroidMediaCacheFileLease? = null
        var player: ExoPlayer? = null
        var listener: Player.Listener? = null
        var attemptedLocalPlayback = false
        val refreshedCacheThisAttempt = forceRefresh
        try {
            val acquiredLease = MediaHelper.downloadToCacheLease(
                attachment = attachment,
                context = context,
                mediaSession = mediaSession,
                forceRefresh = refreshedCacheThisAttempt,
                onProgress = { progressUpdates.value = it },
            )
            lease = acquiredLease
            forceRefresh = false
            attemptedLocalPlayback = true
            val playbackFailure = CompletableDeferred<androidx.media3.common.PlaybackException>()
            val createdListener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    playerEventEpoch++
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playbackFailure.complete(error)
                }
            }
            listener = createdListener
            val createdPlayer = createAndroidNativeMediaResource(
                createResource = { ExoPlayer.Builder(context).build() },
                initializeResource = { created ->
                    created.playWhenReady = false
                    created.repeatMode = Player.REPEAT_MODE_OFF
                    created.addListener(createdListener)
                    created.setMediaItem(MediaItem.fromUri(Uri.fromFile(acquiredLease.file)))
                    created.prepare()
                },
                closeResource = { it.release() },
            )
            player = createdPlayer
            playerEventEpoch = 0
            loadState = GalleryVideoLoadState.Ready(createdPlayer)
            throw playbackFailure.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("VideoPlayer", "Failed to download or play local video: ${attachment.path}", failure)
            loadState = GalleryVideoLoadState.Failed(
                refreshCacheOnRetry = attemptedLocalPlayback && !refreshedCacheThisAttempt,
            )
        } finally {
            val ownedPlayer = player
            if (ownedPlayer != null) {
                playbackGate.update(
                    allowedNow = false,
                    playerWantsPlayback = ownedPlayer.playWhenReady,
                )
            }
            val lifecycleFault = AndroidPlatformLifecycleFaultReporter(
                telemetry = latestTelemetry,
                page = ClientUiPage.MEDIA_GALLERY,
            )
            val ownedListener = listener
            val ownedLease = lease
            disposeAndroidNativeMediaResources(
                closeResources = buildList {
                    if (ownedPlayer != null && ownedListener != null) {
                        add { ownedPlayer.removeListener(ownedListener) }
                    }
                    if (ownedPlayer != null) add(ownedPlayer::release)
                    if (ownedLease != null) add(ownedLease::close)
                },
                recordFailure = { failure ->
                    lifecycleFault.report()
                    Log.e("VideoPlayer", "Failed to dispose local video resources", failure)
                },
            )
        }
    }

    if (!mayOwnResources) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    when (val state = loadState) {
        is GalleryVideoLoadState.Downloading -> {
            GalleryVideoLoading(modifier, state.progress)
            return
        }
        is GalleryVideoLoadState.Failed -> {
            GalleryVideoRetry(modifier = modifier, onRetry = retryLoad)
            return
        }
        is GalleryVideoLoadState.Ready -> Unit
    }
    val ready = loadState as GalleryVideoLoadState.Ready
    val player = ready.player
    LaunchedEffect(player, isCurrentPage, lifecycleStarted, playerEventEpoch) {
        when (
            playbackGate.update(
                allowedNow = isCurrentPage && lifecycleStarted,
                playerWantsPlayback = player.playWhenReady,
            )
        ) {
            GalleryVideoPlaybackCommand.PLAY -> player.play()
            GalleryVideoPlaybackCommand.PAUSE -> player.pause()
            GalleryVideoPlaybackCommand.NONE -> Unit
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val policy = androidMediaGalleryPolicy
                val playerView = when (policy.videoSurfaceType) {
                    AndroidGalleryVideoSurfaceType.TEXTURE_VIEW ->
                        LayoutInflater.from(ctx).inflate(
                            R.layout.teamtalk_gallery_player_view,
                            null,
                            false,
                        ) as PlayerView
                }
                check(playerView.videoSurfaceView is TextureView) {
                    "Gallery PlayerView must use TextureView so it follows Compose layout coordinates"
                }
                playerView.apply {
                    this.player = player
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    useController = true       // 显示播放/暂停/进度条
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(false)
                    setShowRewindButton(false)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun GalleryVideoLoading(modifier: Modifier, progress: Float) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("media.gallery.video.loading"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("media.gallery.video.downloadProgress"),
        ) {
            CircularProgressIndicator(
                progress = { progress },
                color = Color.White,
            )
            Text("${(progress * 100).toInt()}%", color = Color.White)
        }
    }
}

@Composable
private fun GalleryVideoRetry(
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("media.gallery.video.error"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("视频加载失败，请重试", color = Color.White)
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("media.gallery.video.retry"),
            ) {
                Text("重试")
            }
        }
    }
}
