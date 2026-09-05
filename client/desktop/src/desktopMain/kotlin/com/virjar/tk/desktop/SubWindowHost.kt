package com.virjar.tk.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.UserFeedbackCode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.app.ui.AppTheme
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.app.ui.component.LocalScreenHeaderBackButtonVisible
import com.virjar.tk.app.ui.component.LocalScreenHeaderLeadingInset
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    onClose: () -> Unit,
) {
    if (!presentationGate.isOpen || !nav.acceptsRendering) return

    val shortId = "sub-" + (screen::class.simpleName ?: "window")
    val integratedMacTitleBar = remember {
        System.getProperty("os.name").contains("Mac", ignoreCase = true)
    }
    Window(
        onCloseRequest = onClose,
        title = if (integratedMacTitleBar) "" else "TeamTalk",
        state = rememberWindowState(width = 460.dp, height = screen.windowHeight),
    ) {
        if (!presentationGate.isOpen || !nav.acceptsRendering) return@Window
        TestServiceBridge.registerWindowWithId(shortId, window)
        DisposableEffect(shortId) {
            onDispose { TestServiceBridge.unregisterWindow(shortId) }
        }
        // 子窗口也带 TeamTalk 图标（与主窗口一致）
        setTeamTalkIcon()
        DisposableEffect(window, integratedMacTitleBar) {
            // applyMacImmersiveChrome 内部已含 hideTitle；非 Mac 平台为 no-op
            if (integratedMacTitleBar) window.applyMacImmersiveChrome()
            onDispose { }
        }
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                SubWindowBody(
                    initial = screen,
                    nav = nav,
                    presentationGate = presentationGate,
                    resources = resources,
                    window = window,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun SubWindowBody(
    initial: SubScreen,
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    window: java.awt.Window,
    onClose: () -> Unit,
) {
    if (!presentationGate.isOpen || !nav.acceptsRendering) return

    var stack by remember(initial) { mutableStateOf(listOf(initial)) }
    val current = stack.last()
    val telemetryPage = desktopTelemetryPage(current)
    DesktopWindowTelemetry(
        window = window,
        page = telemetryPage,
        telemetry = nav.telemetry,
        disposalExitReason = {
            desktopWindowDisposalExitReason(presentationGate.isOpen)
        },
    )
    val back: () -> Unit = {
        presentationGate.runIfOpen {
            if (stack.size > 1) stack = stack.dropLast(1) else onClose()
        }
    }

    // ESC 逐级返回：AWT 层拦截（Compose 无焦点时按键不派发，见 AwtEscapeInterceptor）
    DisposableEffect(window) {
        val unregister = registerEscapeInterceptor(window) {
            presentationGate.runIfOpen(back)
        }
        onDispose { unregister() }
    }

    // action 失败提示（此前子窗口内错误完全静默）
    val snackbarHostState = remember { SnackbarHostState() }
    val ownsFeedbackSurface = nav.windowScreen == initial
    val presentationMutex = remember(nav, window) { Mutex() }
    val errorHost = remember(nav, window) { Any() }
    LaunchedEffect(nav, presentationGate, ownsFeedbackSurface) {
        if (!ownsFeedbackSurface) return@LaunchedEffect
        snapshotFlow { nav.errorSignal }.filterNotNull().collect {
            presentationMutex.withLock {
                if (nav.windowScreen != initial) return@withLock
                var lease: com.virjar.tk.app.navigation.UiEventLease<String>? = null
                if (!presentationGate.runIfOpen { lease = nav.acquireError(errorHost) }) return@withLock
                val delivery = lease ?: return@withLock
                try {
                    val message = if (nav.markErrorDisplayed(delivery)) {
                        nav.feedbackReporter.displayed(
                            feedbackCode = UserFeedbackCode.forDisplayedMessage(delivery.value),
                            page = desktopTelemetryPage(stack.last()),
                            action = ClientUiAction.SHOW_FEEDBACK,
                            origin = FeedbackOrigin.SNACKBAR,
                        )
                    } else {
                        delivery.value
                    }
                    snackbarHostState.showSnackbar(message)
                    nav.completeError(delivery)
                } finally {
                    nav.releaseError(delivery)
                }
            }
        }
    }
    val noticeHost = remember(nav, window) { Any() }
    LaunchedEffect(nav, presentationGate, ownsFeedbackSurface) {
        if (!ownsFeedbackSurface) return@LaunchedEffect
        snapshotFlow { nav.noticeSignal }.filterNotNull().collect {
            presentationMutex.withLock {
                if (nav.windowScreen != initial) return@withLock
                var lease: com.virjar.tk.app.navigation.UiEventLease<com.virjar.tk.app.telemetry.UserFeedbackNotice>? = null
                if (!presentationGate.runIfOpen { lease = nav.acquireNotice(noticeHost) }) return@withLock
                val delivery = lease ?: return@withLock
                try {
                    val message = if (nav.markNoticeDisplayed(delivery)) {
                        nav.feedbackReporter.displayed(delivery.value)
                    } else {
                        delivery.value.publicMessage
                    }
                    snackbarHostState.showSnackbar(message)
                    nav.completeNotice(delivery)
                } finally {
                    nav.releaseNotice(delivery)
                }
            }
        }
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
                presentationGate = presentationGate,
                resources = resources,
                navigate = presentationGate.guard { screen: SubScreen -> stack = stack + screen },
                back = back,
                openChatAndClose = { chatId ->
                    presentationGate.runIfOpen {
                        nav.openChat(chatId)
                        onClose()
                    }
                },
                openMessageAndClose = { chatId, serverSeq ->
                    presentationGate.runIfOpen {
                        nav.openMessage(chatId, serverSeq)
                        onClose()
                    }
                },
                openUserProfile = { uid ->
                    presentationGate.runIfOpen {
                        onClose()
                        nav.openProfile(uid)
                    }
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
