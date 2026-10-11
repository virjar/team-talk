package com.virjar.tk.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** iOS 导航宿主在弹出可见屏幕前会先询问这个注册表。 */
class IosBackDispatcher {
    private val handlers = mutableListOf<() -> Boolean>()
    fun onBack(): Boolean = handlers.toList().asReversed().any { it() }
    fun register(handler: () -> Boolean): () -> Unit {
        handlers += handler
        return { handlers.remove(handler) }
    }
}
val LocalIosBackDispatcher = staticCompositionLocalOf<IosBackDispatcher?> { null }

@Composable
internal actual fun TkBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalIosBackDispatcher.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher) {
        val unregister = dispatcher?.register {
            if (currentEnabled) { currentOnBack(); true } else false
        }
        onDispose { unregister?.invoke() }
    }
}
