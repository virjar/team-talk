package com.virjar.tk.app.ui.screen

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 输入器按实际可用宽度响应，而不是按运行平台分叉。这样 Desktop 缩窄窗口、Android 横屏和
 * 平板都会得到与空间相符的布局。
 */
internal enum class ChatComposerLayout {
    COMPACT,
    WIDE,
}

// Desktop 的最小主窗口仍会给聊天栏约 524dp；断点需覆盖该宽度，否则“响应式”
// 紧凑布局只会在手机出现，Desktop 窄窗永远落不到这个分支。
internal val ChatComposerCompactBreakpoint = 560.dp

internal fun chatComposerLayout(maxWidth: Dp): ChatComposerLayout =
    if (maxWidth < ChatComposerCompactBreakpoint) ChatComposerLayout.COMPACT else ChatComposerLayout.WIDE
