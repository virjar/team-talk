package com.virjar.tk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.JvmPrivateDataDirectory
import com.virjar.tk.client.createDesktopLocalCache
import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.client.rememberAuthController
import com.virjar.tk.keepawake.KeepAwake
import com.virjar.tk.media.DesktopSessionResources
import com.virjar.tk.tray.AppTray
import com.virjar.tk.tray.DesktopNotificationManager
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.screen.LoginScreen
import com.virjar.tk.ui.screen.RegisterScreen
import java.io.File
import java.util.UUID

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
    val deploymentIdentity = remember(config) { config.deploymentIdentity() }
    val tokenStore = remember(deploymentIdentity) { DesktopTokenStore(dataDir, deploymentIdentity) }
    val deviceId = remember(dataDir) { desktopInstallationDeviceId(dataDir) }

    // 启动 UI 自动化测试 HTTP 服务（通过反射隔离，production 打包删除 test 包也不报错）
    LaunchedEffect(Unit) { TestServiceBridge.startIfEnabled() }

    // 跨平台认证控制器（app 全局层，管理 UserSession 生命周期）
    val auth = rememberAuthController(
        tokenStore = tokenStore,
        deploymentIdentity = deploymentIdentity,
        tcpHost = config.tcpHost,
        tcpPort = config.tcpPort,
        deviceId = deviceId,
        deviceName = "Desktop",
        deviceModel = System.getProperty("os.name"),
        deviceFlag = 2,
        createCache = { identity, uid -> createDesktopLocalCache(identity, uid, dataDir) },
    )

    // ════════════════════════════════════════════════════════════
    // 窗口1：登录窗口（app 全局层）
    // 未登录时可见，登录成功后隐藏。登出后重新显示。
    // §2.3：420×480 无装饰（窗口即卡片），注册态多一字段拉高到 560。
    // ════════════════════════════════════════════════════════════
    val loginWindowState = rememberWindowState(
        width = 420.dp,
        height = 480.dp,
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
        undecorated = true,
        resizable = false,
    ) {
        TestServiceBridge.registerWindowIfEnabled(window)
        setTeamTalkIcon()

        var showRegister by remember { mutableStateOf(false) }
        var loginLoading by remember { mutableStateOf(false) }
        var registerLoading by remember { mutableStateOf(false) }

        // 注册模式多一个输入字段：无装饰窗口不可拉伸，按模式切高度
        LaunchedEffect(showRegister) {
            val height = if (showRegister) 560.dp else 480.dp
            // WindowState 使用 dp；AWT setSize 使用逻辑像素。先 roundToPx 会在 Retina 屏上再放大一倍。
            loginWindowState.size = DpSize(width = 420.dp, height = height)
        }

        // DISCONNECTED 时清 loading 和注册页状态
        val connectionState by auth.connectionState.collectAsState()
        LaunchedEffect(connectionState) {
            if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.AUTH_FAILED) {
                loginLoading = false; registerLoading = false; showRegister = false
            }
        }

        AppTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                // 无装饰窗口顶栏：拖拽区 + 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WindowDraggableArea(modifier = Modifier.weight(1f).fillMaxHeight())
                    IconButton(
                        onClick = {
                            locker.release()
                            exitApplication()
                        },
                        modifier = Modifier.size(36.dp).testTag("login.close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (auth.autoLoggingIn && !auth.isLoggedIn) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
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
                            windowStyle = true,
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
                            windowStyle = true,
                        )
                    }
                }
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
        val desktopResources = remember(session) {
            DesktopSessionResources(
                ownerUid = session.userSession.uid,
                deploymentIdentity = session.deploymentIdentity,
                credentialProvider = session::httpCredentialsSnapshot,
                dataDir = dataDir,
                diagnosticLogger = session.diagnosticLogger("DesktopSession"),
            )
        }
        DisposableEffect(desktopResources) {
            onDispose { desktopResources.close() }
        }
        // 主窗口可见性：true=显示，false=隐藏到托盘
        var windowVisible by remember { mutableStateOf(true) }

        // ── 连接状态 → KeepAwake + 托盘 tooltip ──
        val connectionState by auth.connectionState.collectAsState()
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
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.SYNCHRONIZING -> "连接中…"
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
            title = "",
            state = mainWindowState,
        ) {
            TestServiceBridge.registerWindowIfEnabled(window)
            // macOS：保留原生红黄绿窗口按钮，但让应用 Surface 延伸进标题栏，
            // 消除“系统灰标题栏 + 应用内容”两套视觉语言的拼接感。
            DisposableEffect(window) {
                if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                window.minimumSize = java.awt.Dimension(880, 600)
                val fullScreenContentSync = installMacFullScreenContentSync(window)
                onDispose { fullScreenContentSync.close() }
            }
            // 同步 AWT window focus 状态到 AppState
            LaunchedEffect(Unit) {
                window.isVisible = windowVisible
            }

            setTeamTalkIcon()
            AppTheme {
                MainAppContent(
                    session = session,
                    resources = desktopResources,
                    mainWindow = window,
                    connectionState = connectionState,
                    onToggleWindowZoom = {
                        mainWindowState.placement = nextTitleBarPlacement(mainWindowState.placement)
                    },
                    onLogout = { auth.onLogout() },
                    onAuthExpired = { auth.onAuthExpired() },
                )
            }
        }
    }
}

/** One durable identity per Desktop data directory (different profiles remain different devices). */
internal fun desktopInstallationDeviceId(dataDir: File): String {
    val identityStore = JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(fileName = "device-id")
    identityStore.readText(MAX_DEVICE_ID_FILE_BYTES)?.trim()?.let { stored ->
        require(DESKTOP_DEVICE_ID.matches(stored)) { "Stored Desktop device identity is invalid" }
        return stored
    }
    val generated = "desktop-${UUID.randomUUID()}"
    identityStore.replaceText(generated, MAX_DEVICE_ID_FILE_BYTES)
    check(identityStore.readText(MAX_DEVICE_ID_FILE_BYTES) == generated) {
        "Desktop device identity was not persisted"
    }
    return generated
}

private val DESKTOP_DEVICE_ID = Regex("[A-Za-z0-9._-]{8,256}")
private const val MAX_DEVICE_ID_FILE_BYTES = 1024L
