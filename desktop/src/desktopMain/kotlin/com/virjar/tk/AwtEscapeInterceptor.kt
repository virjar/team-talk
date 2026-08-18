package com.virjar.tk

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.util.WeakHashMap
import javax.swing.SwingUtilities

private val escapeHandlers = WeakHashMap<Window, () -> Boolean>()

/** 供进程内测试控制服务按窗口确定性触发 ESC，不依赖系统焦点或 Robot。 */
internal fun dispatchWindowEscape(owner: Window): Boolean {
    val handler = synchronized(escapeHandlers) { escapeHandlers[owner] }
    return handler?.invoke() ?: false
}

/**
 * 窗口级 ESC 拦截器（AWT KeyboardFocusManager 层）。
 *
 * Compose 的 onPreviewKeyEvent 依赖焦点节点存在：无焦点时（例如刚点完非
 * focusable 的列表行）按键事件不派发进 Compose 场景，ESC 静默丢失——这是
 * 旧版「面板/子窗口 ESC 关闭不可靠」的根因。AWT dispatcher 在焦点派发前
 * 拦截，与 Compose 焦点状态无关。
 *
 * 按事件源归属窗口分流：弹层（Popup）/对话框是独立 Window，不会命中本拦截器。
 */
class AwtEscapeInterceptor(
    private val owner: Window,
    private val onEscape: () -> Boolean,
) : KeyEventDispatcher {

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.id != KeyEvent.KEY_PRESSED || e.keyCode != KeyEvent.VK_ESCAPE) return false
        val source = e.source as? Component ?: return false
        if (SwingUtilities.getWindowAncestor(source) !== owner) return false
        return onEscape()
    }
}

/** 注册窗口级 ESC 拦截器，返回注销函数（调用方在 onDispose 中执行）。 */
fun registerEscapeInterceptor(owner: Window, onEscape: () -> Boolean): () -> Unit {
    val dispatcher = AwtEscapeInterceptor(owner, onEscape)
    synchronized(escapeHandlers) { escapeHandlers[owner] = onEscape }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        synchronized(escapeHandlers) {
            if (escapeHandlers[owner] === onEscape) escapeHandlers.remove(owner)
        }
    }
}
