package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.MediaGallery
import com.virjar.tk.media.DesktopSessionResources
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.CancellationException

/**
 * Desktop 全屏媒体画廊窗口。
 *
 * 包装 commonMain 的 [MediaGallery] 组件，注入 Desktop 平台渲染器：
 * - 图片：统一会话缓存下载 + Skia 解码 + Compose Image 显示
 * - 视频：统一会话缓存下载后交给内嵌播放器
 */
@Composable
internal fun MediaGalleryWindow(
    visible: Boolean,
    items: List<GalleryItem>,
    initialIndex: Int,
    resources: DesktopSessionResources,
    onDismiss: () -> Unit,
) {
    if (!visible || items.isEmpty()) return

    Window(
        onCloseRequest = onDismiss,
        title = "媒体预览",
        state = rememberWindowState(
            position = WindowPosition.PlatformDefault,
        ),
        undecorated = true,  // 无边框，沉浸式
    ) {
        MediaGallery(
            visible = true,
            items = items,
            initialIndex = initialIndex,
            onDismiss = onDismiss,
            imageRenderer = { url, modifier ->
                // 原图按需：缓存命中直接渲染；未命中画廊内进度覆盖层，下载完成才展示
                com.virjar.tk.media.CachedImageContent(
                    url = url,
                    resources = resources,
                    modifier = modifier,
                    progressOverlay = true,
                )
            },
            videoRenderer = { url, _, modifier ->
                DesktopVideoPage(url, resources, modifier)
            },
        )
    }
}

/**
 * Desktop 视频渲染：先下载到本地缓存再播放（避免播放器直接处理网络URL导致数据错乱）。
 */
@Composable
private fun DesktopVideoPage(
    url: String,
    resources: DesktopSessionResources,
    modifier: Modifier = Modifier,
) {
    val playerState = rememberVideoPlayerState()
    var state by remember { mutableStateOf<VideoLoadState>(VideoLoadState.Downloading(0f)) }

    LaunchedEffect(url, resources) {
        state = VideoLoadState.Downloading(0f)
        try {
            // 媒体缓存体系：按需下载原图到本地（播放器始终播本地文件）
            val localFile = resources.mediaCache.ensureDownloaded(url) { p ->
                state = VideoLoadState.Downloading(p)
            }
            resources.ensureOpen()
            playerState.apply {
                openUri(localFile.absolutePath)
            }
            state = VideoLoadState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            state = VideoLoadState.Error(e.message ?: "Unknown error")
        }
    }

    DisposableEffect(Unit) {
        onDispose { playerState.dispose() }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is VideoLoadState.Downloading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("视频下载中 ${(s.progress * 100).toInt()}%", color = Color.White)
            }
            is VideoLoadState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("视频加载失败", color = Color.White)
                    Text(s.message, color = Color.White.copy(alpha = 0.6f), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
            }
            is VideoLoadState.Ready -> VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private sealed interface VideoLoadState {
    data class Downloading(val progress: Float) : VideoLoadState
    data object Ready : VideoLoadState
    data class Error(val message: String) : VideoLoadState
}
