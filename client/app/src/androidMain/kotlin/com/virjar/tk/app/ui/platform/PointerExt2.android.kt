package com.virjar.tk.app.ui.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalTextToolbar

/**
 * Android：长按弹上下文菜单（触屏无右键）。
 *
 * 微信范式：长按同时全选气泡内文字（SelectionContainer 的默认长按行为）+
 * 弹 App 菜单。全选由 SelectionContainer 内建提供，此处只挂菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.contextLongPress(onLongPress: () -> Unit): Modifier =
    this.combinedClickable(onClick = {}, onLongClick = onLongPress)

/** Android：单个手势节点同时分发普通点击和长按，避免两个点击节点互相消费事件。 */
@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.primaryClickWithContextLongPress(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val actions = PrimaryContextGestureActions(onClick, onLongPress)
    return this.combinedClickable(
        onClick = actions::click,
        onLongClick = actions::longPress,
    )
}
