package com.virjar.tk.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.window.WindowPlacement
import java.awt.event.MouseEvent

/**
 * macOS 统一沉浸式窗口：应用 Surface 延伸进标题栏并使其透明（仅保留系统红黄绿按钮
 * 或完全无装饰）。不设置时 undecorated/decorated 窗口会露出原生标题栏底色
 * （浅色系统白条 / 深色系统黑条），与 AppTheme 不搭。非 macOS 平台为 no-op。
 */
internal fun ComposeWindow.applyMacImmersiveChrome(hideTitle: Boolean = true) {
    if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) return
    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    if (hideTitle) rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
}

/**
 * 自绘标题栏双击后的窗口位置。
 *
 * [WindowPlacement.Maximized] 会占满当前屏幕的可用区域，但保留 macOS 菜单栏和 Dock；
 * 它不是沉浸式 [WindowPlacement.Fullscreen]。全屏状态由系统自己的入口管理，这里不改写。
 */
internal fun nextTitleBarPlacement(current: WindowPlacement): WindowPlacement = when (current) {
    WindowPlacement.Floating -> WindowPlacement.Maximized
    WindowPlacement.Maximized -> WindowPlacement.Floating
    WindowPlacement.Fullscreen -> WindowPlacement.Fullscreen
}

/**
 * 监听 AWT 已判定的主键双击，不消费 Compose 指针事件。
 *
 * 这样可以继续复用 [androidx.compose.foundation.window.WindowDraggableArea] 的拖拽行为，
 * 同时沿用操作系统的双击时间和距离判定，避免两个手势识别器争抢按下事件。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.onTitleBarDoubleClick(onDoubleClick: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Release) { event ->
        val mouseEvent = event.awtEventOrNull ?: return@onPointerEvent
        if (
            mouseEvent.button == MouseEvent.BUTTON1 &&
            mouseEvent.clickCount == 2 &&
            event.changes.none { it.isConsumed }
        ) {
            onDoubleClick()
        }
    }
