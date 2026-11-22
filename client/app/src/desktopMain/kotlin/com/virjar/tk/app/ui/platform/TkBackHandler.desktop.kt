package com.virjar.tk.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Component
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/** 桌面端线程/局部覆层返回处理：仅消费所属 Frame 上的 ESC 按键，不干扰 Dialog 或 Popup 的自有关闭逻辑。 */
@Composable
internal actual fun TkBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_ESCAPE) {
                return@KeyEventDispatcher false
            }
            val source = event.source as? Component ?: return@KeyEventDispatcher false
            if (SwingUtilities.getWindowAncestor(source) !is Frame) {
                return@KeyEventDispatcher false
            }
            currentOnBack()
            true
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose {
            focusManager.removeKeyEventDispatcher(dispatcher)
        }
    }
}
