package com.virjar.tk.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** iOS 渲染占位：ObjC WebRTC 帧句柄接入时实现（阶段 5）。 */
@Composable
actual fun CallVideoSurface(handle: Any?, mirror: Boolean, modifier: Modifier) {
    Box(modifier.background(Color(0xFF101010)), contentAlignment = Alignment.Center) {
        Text("视频画面", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
    }
}
