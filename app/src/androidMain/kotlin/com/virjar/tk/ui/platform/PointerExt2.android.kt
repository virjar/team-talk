package com.virjar.tk.ui.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier

/** Android：触屏无右键，长按弹上下文菜单。 */
@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.contextLongPress(onLongPress: () -> Unit): Modifier =
    this.combinedClickable(onClick = {}, onLongClick = onLongPress)
