package com.virjar.tk.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.virjar.tk.DesktopMediaHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存感知图片渲染（doc/10-rich-messaging 媒体域）：
 * 1. 本地缓存命中 → 直接解码渲染（零网络）
 * 2. 未命中 → 下载（进度 UI）→ 渲染；缩略图气泡默认走此路径（小文件秒下）
 * 3. 画廊原图按需：同一组件，进度条为大覆盖层样式
 *
 * @param progressOverlay true=画廊式大进度覆盖（原图按需下载），false=气泡式小指示
 */
@Composable
fun CachedImageContent(url: String, modifier: Modifier = Modifier, progressOverlay: Boolean = false) {
    val state = produceState<CachedImageState>(CachedImageState.Loading(0f), url) {
        val cached = DesktopMediaCache.cachedPath(url)
        value = cached?.let { CachedImageState.Ready(it) }
            ?: try {
                DesktopMediaCache.ensureDownloaded(url) { p ->
                    value = CachedImageState.Loading(p)
                }.let {
                    CachedImageState.Ready(it)
                }
            } catch (e: Exception) {
                CachedImageState.Failed(e.message ?: "download failed")
            }
    }


    when (val s = state.value) {
        is CachedImageState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            if (progressOverlay) {
                CircularProgressIndicator(color = Color.White)
                Text(
                    "${(s.progress * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        is CachedImageState.Ready -> CachedBitmapImage(File(s.localPath), modifier)
        is CachedImageState.Failed -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("加载失败", color = Color.White)
        }
    }
}

private sealed interface CachedImageState {
    data class Loading(val progress: Float) : CachedImageState
    data class Ready(val localPath: String) : CachedImageState
    data class Failed(val reason: String) : CachedImageState
}

/** 本地文件解码（IO 线程）。 */
@Composable
private fun CachedBitmapImage(file: File, modifier: Modifier = Modifier) {
    val bitmap = produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { DesktopMediaHelper.decodeLocalImage(file) }
    }
    bitmap.value?.let { bmp ->
        Image(bitmap = bmp, contentDescription = "图片", modifier = modifier, contentScale = ContentScale.Crop)
    } ?: Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
