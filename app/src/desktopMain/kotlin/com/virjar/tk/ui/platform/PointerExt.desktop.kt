package com.virjar.tk.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * 桌面：右键弹上下文菜单。Press 记录次键、Release 触发——
 * Release 时刻按钮已释放（isSecondaryPressed=false），不能在 Release 里判断。
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier {
    var secondaryArmed = false
    return this
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
