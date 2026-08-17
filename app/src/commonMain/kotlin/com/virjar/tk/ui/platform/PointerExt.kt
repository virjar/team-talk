package com.virjar.tk.ui.platform

import androidx.compose.ui.Modifier

/**
 * 上下文菜单的平台化长按入口。
 *
 * 桌面（F29）：**长按不弹菜单**——菜单只由右键（secondaryClick）触发。曾两端共用
 * combinedClickable.onLongClick，鼠标按住拖选文字时（按下超 500ms 未抬）被误判为
 * 长按弹菜单，与 SelectionContainer 拖选冲突。微信/飞书桌面同此范式：右键=菜单，拖动=选择。
 * Android：长按弹菜单（触屏无右键）。
 */
expect fun Modifier.contextLongPress(onLongPress: () -> Unit): Modifier

/**
 * 次键（右键）点击：桌面上下文菜单的正确交互（F19）。
 * combinedClickable 的 onLongClick 桌面只由「按住左键」触发，右键无效。
 */
expect fun Modifier.secondaryClick(onClick: () -> Unit): Modifier
