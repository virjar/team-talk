package com.virjar.tk.app.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * 桌面：右键弹上下文菜单。
 *
 * 必须在 Initial pass 的按下瞬间消费并打开：SelectionContainer 内部对次键按下
 * 同样"按下即弹"系统复制菜单，若等到 Release 再被动观察，平台菜单已抢占鼠标，
 * 应用菜单会被立即顶掉（表现为此前"右键只剩系统复制"的回归）。
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier {
    return this
        // 不绑定鼠标左键长按，仅导出语义动作供无障碍和进程内测试服务调用。
        // 真实桌面交互由下面的 Initial 按下处理触发。
        .semantics {
            onLongClick(label = "打开上下文菜单") {
                onClick()
                true
            }
        }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
}
