package com.virjar.tk.app.ui.call

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.skia.Bitmap

/** 桌面视频帧句柄：引擎推送的 Skia 位图（见 DesktopCallEngine）。 */
class DesktopVideoHandle(val bitmap: Bitmap)

/**
 * 桌面通话视频渲染：webrtc-java 帧泵推送的 Skia 位图直接转为 Compose ImageBitmap。
 * 位图由引擎在帧到达时新建，Compose 侧只读不持有原生生命周期；本地预览水平镜像。
 */
@Composable
actual fun CallVideoSurface(handle: Any?, mirror: Boolean, modifier: Modifier) {
    if (handle is DesktopVideoHandle) {
        // 官方 AnimatedImage 同款模式：复用 bitmap 每次组合直接转换，不缓存包装
        // （remember 缓存 ImageBitmap 会命中 Compose 绘制缓存，复用后画面不更新）
        val image: ImageBitmap = handle.bitmap.asComposeImageBitmap()
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier.then(if (mirror) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(Color(0xFF101010)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("视频画面（等待帧）", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
