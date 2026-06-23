package com.virjar.tk

import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var localPath by remember { mutableStateOf<String?>(null) }

    // 下载视频到缓存
    LaunchedEffect(url) {
        try {
            val cacheDir = File(context.cacheDir, "media")
            val file = MediaHelper.downloadToCache(url, cacheDir)
            localPath = file.absolutePath
        } catch (_: Exception) {
        }
    }

    val path = localPath
    if (path == null) {
        // 下载中：黑色占位
        AndroidView(
            factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) } },
            modifier = modifier,
        )
        return
    }

    // 创建 ExoPlayer，离开组合时释放
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("file://$path"))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true       // 显示播放/暂停/进度条
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowFastForwardButton(false)
                setShowRewindButton(false)
            }
        },
        modifier = modifier,
    )
}
