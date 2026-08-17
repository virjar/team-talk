package com.virjar.tk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.ui.AppTheme

/**
 * 子窗口宿主（§2.6）：宽统一 460、ESC 逐级返回（局部栈>1 弹栈，初始屏关窗）。
 *
 * 窗口内维护独立导航栈（SearchUsers→UserProfile 局部跳转可返回），
 * 不触碰 nav.windowScreen/panelStack；测试窗口注册用 onDispose 兜底注销
 * （入口切换时 key() 重建窗口不走 onCloseRequest，旧实现会泄漏注册项）。
 */
@Composable
internal fun SubWindow(
    screen: SubScreen,
    nav: DesktopNav,
    onClose: () -> Unit,
) {
    val shortId = "sub-" + (screen::class.simpleName ?: "window")
    Window(
        onCloseRequest = onClose,
        title = screen.title,
        state = rememberWindowState(width = 460.dp, height = screen.windowHeight),
    ) {
        TestServiceBridge.registerWindowWithId(shortId, window)
        DisposableEffect(shortId) {
            onDispose { TestServiceBridge.unregisterWindow(shortId) }
        }
        // 子窗口也带 TeamTalk 图标（与主窗口一致）
        setTeamTalkIcon()
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                SubWindowBody(screen, nav, window, onClose)
            }
        }
    }
}

@Composable
private fun SubWindowBody(
    initial: SubScreen,
    nav: DesktopNav,
    window: java.awt.Window,
    onClose: () -> Unit,
) {
    var stack by remember(initial) { mutableStateOf(listOf(initial)) }
    val current = stack.last()
    val back: () -> Unit = {
        if (stack.size > 1) stack = stack.dropLast(1) else onClose()
    }

    // ESC 逐级返回：AWT 层拦截（Compose 无焦点时按键不派发，见 AwtEscapeInterceptor）
    DisposableEffect(window) {
        val unregister = registerEscapeInterceptor(window) { back(); true }
        onDispose { unregister() }
    }

    // action 失败提示（此前子窗口内错误完全静默）
    val snackbarHostState = remember { SnackbarHostState() }
    val error = nav.error
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        nav.clearError()
        snackbarHostState.showSnackbar(msg)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SubScreenContent(
            screen = current,
            data = nav,
            navigate = { stack = stack + it },
            back = back,
            openChatAndClose = { chatId, name, chatType ->
                nav.openChat(chatId, name, chatType)
                onClose()
            },
            // 群详情是面板类屏幕，窗口内不会触达离开群组
            onLeaveGroup = {},
            showBack = true,
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
