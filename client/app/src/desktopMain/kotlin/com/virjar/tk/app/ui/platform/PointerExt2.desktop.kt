package com.virjar.tk.app.ui.platform

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

/** 桌面：长按不弹菜单（菜单只走右键），此处空实现——保留拖选文字能力。 */
actual fun Modifier.contextLongPress(onLongPress: () -> Unit): Modifier = this

/** Desktop：主点击进入；上下文菜单仍只走 secondaryClick 的右键处理。 */
actual fun Modifier.primaryClickWithContextLongPress(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val actions = PrimaryContextGestureActions(onClick, onLongPress)
    return this.clickable(onClick = actions::click)
}
