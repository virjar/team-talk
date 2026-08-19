package com.virjar.tk

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.window.WindowPlacement
import java.awt.event.MouseEvent

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
