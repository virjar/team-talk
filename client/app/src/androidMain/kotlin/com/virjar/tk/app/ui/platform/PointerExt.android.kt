package com.virjar.tk.app.ui.platform

import androidx.compose.ui.Modifier

/** Android：无右键，长按承担上下文菜单（combinedClickable.onLongClick），此处空实现。 */
actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = this
