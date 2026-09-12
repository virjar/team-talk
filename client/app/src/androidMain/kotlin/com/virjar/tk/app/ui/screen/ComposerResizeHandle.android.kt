package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/* Android 输入区高度随内容与输入法自适应，不提供拖拽手柄与持久化。 */

internal actual fun savedComposerMaxHeight(): Dp? = null

internal actual fun persistComposerMaxHeight(value: Dp) = Unit

internal actual val composerManualResizeSupported: Boolean = false
