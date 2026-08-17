package com.virjar.tk.ui.platform

import androidx.compose.ui.Modifier

/** 桌面：长按不弹菜单（菜单只走右键），此处空实现——保留拖选文字能力。 */
actual fun Modifier.contextLongPress(onLongPress: () -> Unit): Modifier = this
