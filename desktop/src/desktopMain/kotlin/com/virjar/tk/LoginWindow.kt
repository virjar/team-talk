package com.virjar.tk

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.createDesktopLocalCache
import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.client.rememberAuthController
import com.virjar.tk.keepawake.KeepAwake
import com.virjar.tk.tray.AppTray
import com.virjar.tk.tray.DesktopNotificationManager
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.screen.LoginScreen
import com.virjar.tk.ui.screen.RegisterScreen
import java.io.File

/** 显示"已有实例运行"对话框后退出。 */
internal fun showAlreadyRunningDialog(dataDir: File) = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "TeamTalk - Already Running",
        state = rememberWindowState(width = 450.dp, height = 220.dp),
    ) {
        setTeamTalkIcon()
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Another instance is already running", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Data directory: ${dataDir.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use -Dteamtalk.data.dir=<path> to start with a different data directory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = ::exitApplication) { Text("OK") }
                }
            }
        }
    }
}

/**
 * 主 Compose 应用。提取为独立函数，保持 main() 的一次性初始化逻辑清晰。
 */
internal fun teamTalkApplication(dataDir: File, locker: FileLocker) = application {
    val config = defaultServerConfig()
    val tokenStore = remember { DesktopTokenStore(dataDir) }

    // 启动 UI 自动化测试 HTTP 服务（通过反射隔离，production 打包删除 test 包也不报错）
    LaunchedEffect(Unit) { TestServiceBridge.startIfEnabled() }

    // 跨平台认证控制器（app 全局层，管理 UserSession 生命周期）
    val auth = rememberAuthController(
        tokenStore = tokenStore,
        tcpHost = config.tcpHost,
        tcpPort = config.tcpPort,
        deviceId = "desktop-device",
        deviceName = "Desktop",
        createCache = { uid -> createDesktopLocalCache(uid) },
    )

    // ════════════════════════════════════════════════════════════
    // 窗口1：登录窗口（app 全局层）
    // 未登录时可见，登录成功后隐藏。登出后重新显示。
    // ════════════════════════════════════════════════════════════
    val loginWindowState = rememberWindowState(
        width = 400.dp,
        height = 600.dp,
        position = WindowPosition(Alignment.Center),
    )

    Window(
        visible = !auth.isLoggedIn,
        onCloseRequest = {
            locker.release()
            exitApplication()
        },
        title = "TeamTalk",
        state = loginWindowState,
    ) {
        TestServiceBridge.registerWindowIfEnabled(window)
        setTeamTalkIcon()
        AppTheme {
            var showRegister by remember { mutableStateOf(false) }
            var loginLoading by remember { mutableStateOf(false) }
            var registerLoading by remember { mutableStateOf(false) }

            // DISCONNECTED 时清 loading 和注册页状态
            val connectionState by auth.imClient.state.collectAsState()
            LaunchedEffect(connectionState) {
                if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.AUTH_FAILED) {
                    loginLoading = false; registerLoading = false; showRegister = false
                }
            }

            if (auth.autoLoggingIn && !auth.isLoggedIn) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (showRegister) {
                RegisterScreen(
                    onRegister = { username, password, name ->
                        registerLoading = true; auth.clearError()
                        auth.onRegister(username, password, name)
                    },
                    onNavigateBack = { showRegister = false; auth.clearError() },
                    error = auth.authError,
                    loading = registerLoading,
                )
            } else {
                LoginScreen(
                    onLogin = { username, password ->
                        loginLoading = true; auth.clearError()
                        auth.onLogin(username, password)
                    },
                    onNavigateToRegister = { showRegister = true; auth.clearError() },
                    error = auth.authError,
                    loading = loginLoading,
                    allowCustomServer = false,
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 窗口2：主窗口（用户层，绑定 UserSession）
    // ════════════════════════════════════════════════════════════
    val mainWindowState = rememberWindowState(
        width = 1000.dp,
        height = 720.dp,
        position = WindowPosition(Alignment.Center),
    )

    val session = auth.session
    if (auth.isLoggedIn && session != null) {
        // 主窗口可见性：true=显示，false=隐藏到托盘
        var windowVisible by remember { mutableStateOf(true) }

        // ── 连接状态 → KeepAwake + 托盘 tooltip ──
        val connectionState by auth.imClient.state.collectAsState()
        LaunchedEffect(connectionState) {
            when (connectionState) {
                ConnectionState.AUTHENTICATED -> KeepAwake.start()
                ConnectionState.DISCONNECTED -> KeepAwake.stop()
                else -> {}
            }
        }

        // ── 未读消息数 → 托盘 tooltip ──
        val conversations by session.localCache.observeConversations().collectAsState(initial = emptyList())
        val unreadTotal = remember(conversations) {
            conversations.filter { !it.isMuted && it.unreadCount > 0 }.sumOf { it.unreadCount }
        }
        LaunchedEffect(connectionState, unreadTotal) {
            val status = when (connectionState) {
                ConnectionState.AUTHENTICATED -> "在线"
                ConnectionState.CONNECTING -> "连接中…"
                else -> "离线"
            }
            val suffix = if (unreadTotal > 0) " ($unreadTotal 条未读)" else ""
            AppTray.setTooltip("TeamTalk - $status$suffix")
        }

        // ── 新消息通知 ──
        // isWindowFocused 由 MainAppContent 通过 AppState 写入，
        // 但此处无法直接访问 AppState，故用简单条件：窗口可见且在前台
        LaunchedEffect(conversations) {
            DesktopNotificationManager.onConversationsChanged(
                conversations = conversations,
                isWindowFocused = windowVisible, // 近似：窗口显示 ≈ 用户在看
            )
        }

        // ── 系统托盘生命周期 ──
        LaunchedEffect(Unit) {
            AppTray.create(
                onShow = { windowVisible = true },
                onQuit = {
                    auth.onLogout()
                    locker.release()
                    exitApplication()
                },
            )
            DesktopNotificationManager.start { chatId ->
                // 通知点击：恢复窗口（后续可在 MainAppContent 中跳转到对应会话）
                windowVisible = true
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                AppTray.remove()
                DesktopNotificationManager.stop()
                KeepAwake.stop()
            }
        }

        // ── 主窗口 ──
        Window(
            visible = windowVisible,
            onCloseRequest = {
                // 关闭 → 隐藏到托盘而不是登出
                windowVisible = false
            },
            title = "TeamTalk",
            state = mainWindowState,
        ) {
            TestServiceBridge.registerWindowIfEnabled(window)
            // 同步 AWT window focus 状态到 AppState
            LaunchedEffect(Unit) {
                window.isVisible = windowVisible
            }

            setTeamTalkIcon()
            AppTheme {
                MainAppContent(
                    session = session,
                    onLogout = { auth.onLogout() },
                )
            }
        }
    }
}
