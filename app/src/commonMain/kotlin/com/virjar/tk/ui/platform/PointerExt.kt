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
 * 同一个点击目标同时支持主点击和触屏长按。
 *
 * Android 必须由单个 combinedClickable 节点接管两个动作；若在 clickable 外再叠加一个
 * 仅处理长按的 combinedClickable，后者会消费普通点击。Desktop 则保留主点击，菜单继续
 * 由 [secondaryClick] 的右键语义触发。
 */
expect fun Modifier.primaryClickWithContextLongPress(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier

/** 可脱离 Compose 输入系统验证的手势动作分发器。 */
internal class PrimaryContextGestureActions(
    private val onClick: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    fun click() = onClick()

    fun longPress() = onLongPress()
}

/**
 * 次键（右键）点击：桌面上下文菜单的正确交互（F19）。
 * combinedClickable 的 onLongClick 桌面只由「按住左键」触发，右键无效。
 */
expect fun Modifier.secondaryClick(onClick: () -> Unit): Modifier
