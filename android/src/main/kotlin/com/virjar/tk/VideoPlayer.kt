package com.virjar.tk

import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.CancellationException

internal sealed interface GalleryVideoLoadState {
    data object Loading : GalleryVideoLoadState
    data class Ready(val localPath: String) : GalleryVideoLoadState
    data object Failed : GalleryVideoLoadState
}

internal enum class GalleryVideoLoadPresentation { LOADING, PLAYER, RETRY }

internal fun galleryVideoLoadPresentation(state: GalleryVideoLoadState): GalleryVideoLoadPresentation =
    when (state) {
        GalleryVideoLoadState.Loading -> GalleryVideoLoadPresentation.LOADING
        is GalleryVideoLoadState.Ready -> GalleryVideoLoadPresentation.PLAYER
        GalleryVideoLoadState.Failed -> GalleryVideoLoadPresentation.RETRY
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
 * 用于画廊全屏视频播放。下载到缓存后用 ExoPlayer 播放，PlayerView 自带
 * 播放/暂停/进度条控制。离开组合时自动释放 player。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun rememberVideoPlayer(
    url: String,
    accessToken: String?,
    cacheNamespace: String,
    isCurrentPage: Boolean,
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

    var retryAttempt by remember(url, cacheNamespace) { mutableIntStateOf(0) }
    var loadState by remember(url, cacheNamespace) {
        mutableStateOf<GalleryVideoLoadState>(GalleryVideoLoadState.Loading)
    }

    // 下载视频到缓存
    LaunchedEffect(url, accessToken, cacheNamespace, retryAttempt) {
        loadState = GalleryVideoLoadState.Loading
        try {
            val file = MediaHelper.downloadToCache(
                url = url,
                cacheDir = context.cacheDir,
                accessToken = accessToken,
                cacheNamespace = cacheNamespace,
            )
            loadState = GalleryVideoLoadState.Ready(file.absolutePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e("VideoPlayer", "Failed to download video: $url", e)
            loadState = GalleryVideoLoadState.Failed
        }
    }

    when (galleryVideoLoadPresentation(loadState)) {
        GalleryVideoLoadPresentation.LOADING -> {
            GalleryVideoLoading(modifier)
            return
        }
        GalleryVideoLoadPresentation.RETRY -> {
            GalleryVideoRetry(
                modifier = modifier,
                onRetry = { retryAttempt++ },
            )
            return
        }
        GalleryVideoLoadPresentation.PLAYER -> Unit
    }
    val path = (loadState as GalleryVideoLoadState.Ready).localPath

    // 创建 ExoPlayer，离开组合时释放；初始必须暂停，播放权由 currentPage + lifecycle gate 发放。
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("file://$path"))
            prepare()
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    val playbackGate = remember(player) { GalleryVideoPlaybackGate() }
    var playerEventEpoch by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playerEventEpoch++
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
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
        modifier = modifier,
    )
}

@Composable
private fun GalleryVideoLoading(modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("media.gallery.video.loading"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
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
            Text("视频加载失败，请检查网络后重试", color = Color.White)
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("media.gallery.video.retry"),
            ) {
                Text("重试")
            }
        }
    }
}
