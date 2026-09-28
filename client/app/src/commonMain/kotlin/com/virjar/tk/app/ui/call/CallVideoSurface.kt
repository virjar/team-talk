package com.virjar.tk.app.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 通话视频渲染入口：handle 为平台引擎推送的渲染句柄（Android 为
 * AndroidRemote/LocalVideoHandle，桌面为 ImageBitmap，iOS 为平台帧句柄）。
 * mirror 标记本地预览镜像；handle 为 null 时渲染占位。
 */
@Composable
expect fun CallVideoSurface(handle: Any?, mirror: Boolean, modifier: Modifier)
