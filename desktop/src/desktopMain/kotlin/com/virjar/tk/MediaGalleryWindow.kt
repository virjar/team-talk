package com.virjar.tk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.MediaGallery
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import kotlinx.coroutines.withContext
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop 全屏媒体画廊窗口。
 *
 * 包装 commonMain 的 [MediaGallery] 组件，注入 Desktop 平台渲染器：
 * - 图片：[DesktopImagePage] — 从 URL 下载 + Skia 解码 + Compose Image 显示
 * - 视频：系统播放器打开（后续可升级为内嵌播放器）
 */
@Composable
fun MediaGalleryWindow(
    visible: Boolean,
    items: List<GalleryItem>,
    initialIndex: Int,
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
                DesktopImagePage(url, modifier)
            },
            videoRenderer = { url, modifier ->
                DesktopVideoPage(url, modifier)
            },
        )
    }
}

/**
 * Desktop 图片渲染：异步下载 + Skia 解码 + Compose Image。
 */
@Composable
private fun DesktopImagePage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        try {
            val result = withContext(Dispatchers.IO) {
                DesktopMediaHelper.loadImageBitmap(url)
            }
            bitmap = result
            isLoading = false
        } catch (_: Exception) {
            error = true
            isLoading = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            error -> Text("加载失败", color = Color.White)
            isLoading -> CircularProgressIndicator(color = Color.White)
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = "图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Desktop 视频渲染：先下载到本地缓存再播放（避免播放器直接处理网络URL导致数据错乱）。
 */
@Composable
private fun DesktopVideoPage(url: String, modifier: Modifier = Modifier) {
    val playerState = rememberVideoPlayerState()
    var state by remember { mutableStateOf<VideoLoadState>(VideoLoadState.Loading) }

    LaunchedEffect(url) {
        state = VideoLoadState.Loading
        try {
            // 先下载到本地缓存（本地优先策略，和图片/文件一致）
            val localFile = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.virjar.tk.DesktopMediaHelper.downloadToCache(url)
            }
            playerState.apply {
                openUri(localFile.absolutePath)
            }
            state = VideoLoadState.Ready
        } catch (e: Exception) {
            state = VideoLoadState.Error(e.message ?: "Unknown error")
        }
    }

    DisposableEffect(Unit) {
        onDispose { playerState.dispose() }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is VideoLoadState.Loading -> CircularProgressIndicator(color = Color.White)
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
    data object Loading : VideoLoadState
    data object Ready : VideoLoadState
    data class Error(val message: String) : VideoLoadState
}
