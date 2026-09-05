package com.virjar.tk.app.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * 桌面：右键弹上下文菜单。Press 记录次键、Release 触发——
 * Release 时刻按钮已释放（isSecondaryPressed=false），不能在 Release 里判断。
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier {
    var secondaryArmed = false
    return this
        // 不绑定鼠标左键长按，仅导出语义动作供无障碍和进程内测试服务调用。
        // 真实桌面交互仍只由下面的 secondary Press/Release 触发。
        .semantics {
            onLongClick(label = "打开上下文菜单") {
                onClick()
                true
            }
        }
        .onPointerEvent(PointerEventType.Press) { event ->
            secondaryArmed = event.buttons.isSecondaryPressed
        }
        .onPointerEvent(PointerEventType.Release) {
            if (secondaryArmed) {
                secondaryArmed = false
                onClick()
            }
        }
}
