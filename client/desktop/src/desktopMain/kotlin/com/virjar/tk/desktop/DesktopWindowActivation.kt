package com.virjar.tk.desktop

import java.awt.Desktop
import java.awt.Frame
import java.awt.Window
import java.awt.desktop.AppReopenedListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.Closeable

/** 主窗口绑定 macOS Dock 的 reopen 事件；监听器随窗口销毁，不能跨登录会话保留。 */
internal fun installDesktopWindowActivation(
    window: Window,
    onReopen: () -> Unit,
    onShown: () -> Unit,
): Closeable {
    // Compose 延迟发布 visible。等 AWT 确认真正显示后再请求焦点，避免激活仍隐藏的窗口。
    val shownListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) = onShown()
    }
    window.addComponentListener(shownListener)

    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    val reopenDesktop = desktop?.takeIf { it.isSupported(Desktop.Action.APP_EVENT_REOPENED) }
    val reopenListener = AppReopenedListener { onReopen() }
    reopenDesktop?.addAppEventListener(reopenListener)

    return Closeable {
        reopenDesktop?.removeAppEventListener(reopenListener)
        window.removeComponentListener(shownListener)
    }
}

/** 只由真实打开动作或窗口显示回调调用；不改变最大化/全屏状态，也不替 Compose 管理可见性。 */
internal fun Frame.bringToForeground() {
    extendedState = extendedState and Frame.ICONIFIED.inv()
    if (Desktop.isDesktopSupported()) {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
            desktop.requestForeground(true)
        }
    }
    toFront()
    requestFocus()
}
