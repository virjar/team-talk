package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientSystemEvent
import com.virjar.tk.app.telemetry.ClientSystemState
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.ClientPageExitReason
import com.virjar.tk.app.telemetry.PageDwellTracker
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.beans.PropertyChangeListener

/** 每个获得焦点的 desktop 窗口/页面组合对应一段精确的停留计时。 */
@Composable
internal fun DesktopWindowTelemetry(
    window: Window,
    page: ClientUiPage,
    telemetry: ClientUiTelemetrySink,
    disposalExitReason: () -> ClientPageExitReason,
) {
    var focused by remember(window) { mutableStateOf(window.isFocused) }
    val pageDwell = remember(window, telemetry) {
        PageDwellTracker(System::currentTimeMillis, telemetry::recordPageDwell)
    }
    var lastOpenedPage by remember(window, telemetry) { mutableStateOf<ClientUiPage?>(null) }

    LaunchedEffect(page, focused) {
        if (focused) {
            pageDwell.enter(page)
            if (lastOpenedPage != page) {
                lastOpenedPage = page
                telemetry.recordAction(
                    page,
                    ClientUiAction.OPEN_PAGE,
                    ClientActionOutcome.SUCCEEDED,
                )
            }
        } else {
            pageDwell.pause()
        }
        telemetry.recordSystem(
            if (focused) ClientSystemEvent.WINDOW_FOCUSED else ClientSystemEvent.WINDOW_UNFOCUSED,
            if (focused) ClientSystemState.FOCUSED else ClientSystemState.UNFOCUSED,
        )
    }

    DisposableEffect(window, pageDwell) {
        val listener = object : WindowFocusListener {
            override fun windowGainedFocus(event: WindowEvent?) {
                focused = true
            }

            override fun windowLostFocus(event: WindowEvent?) {
                focused = false
            }
        }
        window.addWindowFocusListener(listener)
        telemetry.recordSystem(ClientSystemEvent.WINDOW_OPENED, ClientSystemState.OPEN)
        onDispose {
            window.removeWindowFocusListener(listener)
            pageDwell.finish(disposalExitReason())
            telemetry.recordSystem(ClientSystemEvent.WINDOW_CLOSED, ClientSystemState.CLOSED)
        }
    }
}

/**
 * 为 [ownerWindow] 拥有的无装饰 common Dialog 提供页面 telemetry。Compose 不会公开暴露
 * 该 scene-layer JDialog，因此焦点改由 AWT 的活跃窗口 owner 链来推断。
 */
@Composable
internal fun DesktopOwnedModalTelemetry(
    ownerWindow: Window,
    page: ClientUiPage,
    telemetry: ClientUiTelemetrySink,
    disposalExitReason: () -> ClientPageExitReason,
) {
    val focusManager = remember { java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager() }
    var focused by remember(ownerWindow) {
        mutableStateOf(activeWindowBelongsTo(ownerWindow, focusManager.activeWindow))
    }
    val pageDwell = remember(ownerWindow, telemetry) {
        PageDwellTracker(System::currentTimeMillis, telemetry::recordPageDwell)
    }
    // 同一模态内的子视图（如设置的编辑资料页）切换页面时，OPEN_PAGE 也要按页记录。
    var lastOpenedPage by remember(ownerWindow, telemetry) { mutableStateOf<ClientUiPage?>(null) }

    LaunchedEffect(page, focused) {
        if (focused) {
            pageDwell.enter(page)
            if (lastOpenedPage != page) {
                lastOpenedPage = page
                telemetry.recordAction(
                    page,
                    ClientUiAction.OPEN_PAGE,
                    ClientActionOutcome.SUCCEEDED,
                )
            }
        } else {
            pageDwell.pause()
        }
        telemetry.recordSystem(
            if (focused) ClientSystemEvent.WINDOW_FOCUSED else ClientSystemEvent.WINDOW_UNFOCUSED,
            if (focused) ClientSystemState.FOCUSED else ClientSystemState.UNFOCUSED,
        )
    }

    DisposableEffect(ownerWindow, focusManager, pageDwell) {
        val listener = PropertyChangeListener {
            focused = activeWindowBelongsTo(ownerWindow, focusManager.activeWindow)
        }
        focusManager.addPropertyChangeListener("activeWindow", listener)
        telemetry.recordSystem(ClientSystemEvent.WINDOW_OPENED, ClientSystemState.OPEN)
        onDispose {
            focusManager.removePropertyChangeListener("activeWindow", listener)
            pageDwell.finish(disposalExitReason())
            telemetry.recordSystem(ClientSystemEvent.WINDOW_CLOSED, ClientSystemState.CLOSED)
        }
    }
}

internal fun activeWindowBelongsTo(ownerWindow: Window, activeWindow: Window?): Boolean {
    var current = activeWindow
    while (current != null) {
        if (current === ownerWindow) return true
        current = current.owner
    }
    return false
}

internal fun desktopWindowDisposalExitReason(
    sessionPresentationOpen: Boolean,
): ClientPageExitReason = if (sessionPresentationOpen) {
    ClientPageExitReason.NAVIGATION
} else {
    ClientPageExitReason.SESSION_END
}
