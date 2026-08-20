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
import com.virjar.tk.media.DesktopSessionResources
import com.virjar.tk.ui.component.LocalScreenHeaderBackButtonVisible
import com.virjar.tk.ui.component.LocalScreenHeaderLeadingInset

/**
 * 子窗口宿主（§2.6）：宽统一 460、ESC 逐级返回（局部栈>1 弹栈，初始屏关窗）。
 *
 * 窗口内维护独立导航栈；用户资料不进入该栈，关闭任务窗口后由主窗口显示资料弹窗。
 * 不触碰 nav.windowScreen/mainPaneScreen/inspectorStack；测试窗口注册用 onDispose 兜底注销
 * （入口切换时 key() 重建窗口不走 onCloseRequest，旧实现会泄漏注册项）。
 */
@Composable
internal fun SubWindow(
    screen: SubScreen,
    nav: DesktopNav,
    resources: DesktopSessionResources,
    onClose: () -> Unit,
) {
    val shortId = "sub-" + (screen::class.simpleName ?: "window")
    val integratedMacTitleBar = remember {
        System.getProperty("os.name").contains("Mac", ignoreCase = true)
    }
    Window(
        onCloseRequest = onClose,
        title = if (integratedMacTitleBar) "" else "TeamTalk",
        state = rememberWindowState(width = 460.dp, height = screen.windowHeight),
    ) {
        TestServiceBridge.registerWindowWithId(shortId, window)
        DisposableEffect(shortId) {
            onDispose { TestServiceBridge.unregisterWindow(shortId) }
        }
        // 子窗口也带 TeamTalk 图标（与主窗口一致）
        setTeamTalkIcon()
        DisposableEffect(window, integratedMacTitleBar) {
            if (integratedMacTitleBar) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            onDispose { }
        }
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                SubWindowBody(screen, nav, resources, window, onClose)
            }
        }
    }
}

@Composable
private fun SubWindowBody(
    initial: SubScreen,
    nav: DesktopNav,
    resources: DesktopSessionResources,
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
        CompositionLocalProvider(
            LocalScreenHeaderBackButtonVisible provides (stack.size > 1),
            LocalScreenHeaderLeadingInset provides if (
                System.getProperty("os.name").contains("Mac", ignoreCase = true)
            ) 72.dp else 0.dp,
        ) {
            SubScreenContent(
                screen = current,
                data = nav,
                resources = resources,
                navigate = { stack = stack + it },
                back = back,
                openChatAndClose = { chatId, name, chatType ->
                    nav.openChat(chatId, name, chatType)
                    onClose()
                },
                openUserProfile = { uid ->
                    onClose()
                    nav.openProfile(uid)
                },
                // 群详情是面板类屏幕，窗口内不会触达离开群组
                onLeaveGroup = {},
                // 保留功能性 back 回调（保存成功/ESC 关窗）；根页面只隐藏移动端返回图标。
                showBack = true,
            )
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
