package com.virjar.tk.ui.platform

import androidx.compose.ui.Modifier

/**
 * 次键（右键）点击：桌面上下文菜单的正确交互；移动端无此手势（保持长按）。
 * 背景（F19）：combinedClickable.onLongClick 在桌面只由「按住左键」触发，右键无效——
 * 曾据此误写交互文档。桌面右键由此扩展实现。
 */
expect fun Modifier.secondaryClick(onClick: () -> Unit): Modifier
