package com.virjar.tk

import androidx.compose.foundation.background
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
import com.virjar.tk.client.ServerConfig
import com.virjar.tk.client.configureServerConfig
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
        DisposableEffect(window) {
            window.applyMacImmersiveChrome()
            onDispose { }
        }
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
    // 打包默认部署（DeploymentConfig 注入的系统属性），先于任何运行期覆盖取值
    val packagedConfig = remember { defaultServerConfig() }
    // 演示站体验入口开关：deployment.json → BuildConfig 编译期常量（生产部署 false）
    val allowCustomServer = com.virjar.tk.BuildConfig.ALLOW_CUSTOM_SERVER
    readPersistedCustomServer(dataDir)?.let { configureServerConfig(it) }
    var activeConfig by remember { mutableStateOf(defaultServerConfig()) }

    // 切换部署（仅登录窗口可见阶段）：整棵认证子树（identity/tokenStore/auth）
    // 随 key 重建——不同部署的凭据与缓存按 DeploymentIdentity 隔离。
    key(activeConfig) {
    val config = activeConfig
    val deploymentIdentity = remember(config) { config.deploymentIdentity() }
    val tokenStore = remember(deploymentIdentity) { DesktopTokenStore(dataDir, deploymentIdentity) }
    val deviceId = remember(dataDir) { desktopInstallationDeviceId(dataDir) }
    val sessionRetirementBridge = remember { DesktopAuthenticatedUiRetirementBridge() }

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
        beforeSessionRetirement = sessionRetirementBridge::beforeSessionRetirement,
        afterSessionRetirement = sessionRetirementBridge::afterSessionRetirement,
    )

    // ════════════════════════════════════════════════════════════
    // 窗口1：登录窗口（app 全局层）
    // 未登录时可见，登录成功后隐藏。登出后重新显示。
    // §2.3：420×560 无装饰（窗口即卡片）；登录/注册同尺寸（注册内容更高，
    // 统一取其所需高度，登录态居中留白），切换不跳动。
    // ════════════════════════════════════════════════════════════
    val loginWindowState = rememberWindowState(
        width = 420.dp,
        height = 560.dp,
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
        DisposableEffect(window) {
            // undecorated 窗口同样统一沉浸式处理：消除原生标题栏底色残留
            window.applyMacImmersiveChrome()
            onDispose { }
        }
        setTeamTalkIcon()

        var showRegister by remember { mutableStateOf(false) }
        var loginLoading by remember { mutableStateOf(false) }
        var registerLoading by remember { mutableStateOf(false) }

        // DISCONNECTED 时清 loading 和注册页状态
        val connectionState by auth.connectionState.collectAsState()
        LaunchedEffect(connectionState) {
            if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.AUTH_FAILED) {
                loginLoading = false; registerLoading = false; showRegister = false
            }
        }

        AppTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                // 无装饰窗口顶栏：拖拽区 + 关闭按钮。
                // 背景取渐变首色（primary）：与 LoginScreen/RegisterScreen 的
                // verticalGradient(primary→background) 在 y=0 处无缝衔接
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.primary),
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
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
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
                            allowCustomServer = allowCustomServer,
                            serverUrl = activeConfig.serverUrl,
                            onServerUrlChange = { raw -> applyCustomServerUrl(raw, activeConfig, dataDir) { activeConfig = it } },
                            onResetServerUrl = { resetToPackagedServer(packagedConfig, dataDir) { activeConfig = it } },
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
        val authenticatedUi = remember(session, desktopResources) {
            DesktopAuthenticatedUiOwner(
                session = session,
                closePlatformResources = desktopResources::close,
                requestAuthExpired = { auth.onAuthExpired() },
            )
        }
        val desktopNav = rememberDesktopNav(authenticatedUi.navigation)
        DisposableEffect(sessionRetirementBridge, session, authenticatedUi) {
            val retirementBinding = sessionRetirementBridge.bind(session, authenticatedUi.retirement)
            onDispose { retirementBinding.close() }
        }
        DisposableEffect(desktopResources) {
            onDispose {
                disposeDesktopAuthenticatedResources(desktopResources::close)
            }
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
                window.applyMacImmersiveChrome()
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
                    nav = desktopNav,
                    resources = desktopResources,
                    mainWindow = window,
                    connectionState = connectionState,
                    onToggleWindowZoom = {
                        mainWindowState.placement = nextTitleBarPlacement(mainWindowState.placement)
                    },
                    onLogout = { auth.onLogout() },
                )
            }
        }
    }
    } // key(activeConfig)
}

// ── 演示站自定义服务器（登录窗口阶段切换部署）──

/** 解析并应用自定义服务器地址：HTTP host 作 TCP host，端口沿用当前配置。 */
internal fun applyCustomServerUrl(
    rawUrl: String,
    current: com.virjar.tk.client.ServerConfig,
    dataDir: File,
    apply: (com.virjar.tk.client.ServerConfig) -> Unit,
) {
    val normalized = rawUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')
    val host = runCatching { java.net.URI(normalized).host }.getOrNull()
    if (host.isNullOrBlank()) return  // 无效输入保持原值
    val newConfig = com.virjar.tk.client.ServerConfig(
        serverUrl = normalized,
        tcpHost = host,
        tcpPort = current.tcpPort,
    )
    configureServerConfig(newConfig)
    persistCustomServer(dataDir, newConfig)
    apply(newConfig)
}

internal fun resetToPackagedServer(
    packagedConfig: com.virjar.tk.client.ServerConfig,
    dataDir: File,
    apply: (com.virjar.tk.client.ServerConfig) -> Unit,
) {
    configureServerConfig(packagedConfig)
    persistCustomServer(dataDir, null)
    apply(packagedConfig)
}

private fun customServerStore(dataDir: File) =
    JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(fileName = "custom-server.properties")

private fun persistCustomServer(dataDir: File, config: com.virjar.tk.client.ServerConfig?) {
    runCatching {
        val text = config?.let { "${it.serverUrl}\n${it.tcpHost}\n${it.tcpPort}" } ?: ""
        customServerStore(dataDir).replaceText(text)
    }
}

private fun readPersistedCustomServer(dataDir: File): com.virjar.tk.client.ServerConfig? = runCatching {
    val text = customServerStore(dataDir).readText(MAX_CUSTOM_SERVER_FILE_BYTES) ?: return null
    val lines = text.lines()
    if (lines.size < 3) return null
    val port = lines[2].trim().toIntOrNull() ?: return null
    com.virjar.tk.client.ServerConfig(serverUrl = lines[0].trim(), tcpHost = lines[1].trim(), tcpPort = port)
}.getOrNull()

private const val MAX_CUSTOM_SERVER_FILE_BYTES = 2048L

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
