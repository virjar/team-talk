package com.virjar.tk.desktop

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
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.shared.client.ServerConfig
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.createDesktopLocalCache
import com.virjar.tk.shared.client.defaultServerConfig
import com.virjar.tk.app.client.rememberAuthController
import com.virjar.tk.app.client.AuthFormSubmissionState
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.desktop.tray.AppTray
import com.virjar.tk.desktop.tray.DesktopNotificationManager
import com.virjar.tk.app.ui.AppTheme
import com.virjar.tk.app.ui.screen.LoginScreen
import com.virjar.tk.app.ui.screen.RegisterScreen
import com.virjar.tk.app.telemetry.SessionClientUiTelemetrySink
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

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
    val allowCustomServer = com.virjar.tk.desktop.BuildConfig.ALLOW_CUSTOM_SERVER
    var activeConfig by remember(dataDir, packagedConfig) {
        mutableStateOf(readPersistedCustomServer(dataDir) ?: packagedConfig)
    }

    // 切换部署（仅登录窗口可见阶段）：整棵认证子树（identity/tokenStore/auth）
    // 随 key 重建——不同部署的凭据与缓存按 DeploymentIdentity 隔离。
    key(activeConfig) {
    val config = activeConfig
    val deploymentIdentity = remember(config) { config.deploymentIdentity() }
    val tokenStore = remember(deploymentIdentity) { DesktopTokenStore(dataDir, deploymentIdentity) }
    val deviceId = remember(dataDir) { desktopInstallationDeviceId(dataDir) }
    val sessionRetirementBridge = remember { DesktopAuthenticatedUiRetirementBridge() }
    val composeUiScope = rememberCoroutineScope()
    val desktopUiGate = remember(composeUiScope) {
        DesktopUiDispatcherGate(
            dispatchToUi = { action -> composeUiScope.launch { action() } },
        )
    }
    val shutdownRetirement = remember {
        AtomicReference<DesktopAuthenticatedUiRetirement?>(null)
    }
    val applicationExitActions = remember(desktopUiGate, shutdownRetirement) {
        DesktopApplicationExitActions(desktopUiGate) {
            // 保持单实例租约，直到最新会话的 owner 级草稿屏障完成。
            // 退出是 SHUTDOWN/保留（preserve），绝不伪装成账号登出。
            shutdownRetirement.get()?.retireFromComposition(SessionEndReason.SHUTDOWN)
            locker.release()
            exitApplication()
        }
    }
    DisposableEffect(desktopUiGate) {
        onDispose {
            applicationExitActions.close()
            desktopUiGate.close()
        }
    }

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
        deviceModel = System.getProperty("os.name")
            ?.takeIf { AuthRules.validateDeviceModel(it) == null },
        deviceFlag = AuthRules.DEVICE_FLAG_DESKTOP,
        createCache = { identity, datasetId, uid ->
            createDesktopLocalCache(identity, datasetId, uid, dataDir)
        },
        beforeSessionRetirement = sessionRetirementBridge::beforeSessionRetirement,
        afterSessionRetirement = sessionRetirementBridge::afterSessionRetirement,
        runtimeInfo = remember { desktopClientRuntimeInfo() },
        telemetrySpoolRoot = dataDir,
    )
    val session = auth.session
    val authenticationSurface = desktopAuthenticationSurface(
        hasLocalSession = auth.hasLocalSession,
        hasActiveSession = session?.isBusinessActive == true,
        requiresProtocolUpgrade = auth.requiresProtocolUpgrade,
    )
    val exitUnauthenticatedApplication: () -> Unit = {
        applicationExitActions.requestExit()
    }
    // 隐藏的 Window 会暂停内容重组；认证表单的去留必须由始终存活的应用组合观察。
    var showRegister by remember { mutableStateOf(false) }
    val loginSubmission = remember { AuthFormSubmissionState() }
    val registerSubmission = remember { AuthFormSubmissionState() }
    val authenticationConnectionState by auth.connectionState.collectAsState()
    LaunchedEffect(authenticationConnectionState, authenticationSurface) {
        loginSubmission.onConnectionStateChanged(authenticationConnectionState)
        registerSubmission.onConnectionStateChanged(authenticationConnectionState)
        if (authenticationSurface == DesktopAuthenticationSurface.AUTHENTICATED) showRegister = false
    }

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
        visible = authenticationSurface != DesktopAuthenticationSurface.AUTHENTICATED,
        onCloseRequest = exitUnauthenticatedApplication,
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
                        onClick = exitUnauthenticatedApplication,
                        modifier = Modifier.size(36.dp).testTag("login.close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (authenticationSurface) {
                        DesktopAuthenticationSurface.PROTOCOL_UPGRADE -> {
                            DesktopProtocolUpgradeSurface(
                                onExit = exitUnauthenticatedApplication,
                                message = com.virjar.tk.app.ui.component.forcedProtocolUpgradeMessage(
                                    auth.protocolCompatibility,
                                ),
                            )
                        }
                        DesktopAuthenticationSurface.AUTHENTICATED -> Unit
                        DesktopAuthenticationSurface.LOGIN -> if (auth.autoLoggingIn) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (showRegister) {
                            RegisterScreen(
                                onRegister = { username, password, name ->
                                    registerSubmission.submit { auth.onRegister(username, password, name) }
                                },
                                onNavigateBack = { showRegister = false; auth.clearError() },
                                error = auth.authError,
                                loading = registerSubmission.loading,
                                windowStyle = true,
                            )
                        } else {
                            LoginScreen(
                                onLogin = { username, password ->
                                    loginSubmission.submit { auth.onLogin(username, password) }
                                },
                                onNavigateToRegister = { showRegister = true; auth.clearError() },
                                error = auth.authError,
                                loading = loginSubmission.loading,
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
    }

    // ════════════════════════════════════════════════════════════
    // 窗口2：主窗口（用户层，绑定 UserSession）
    // ════════════════════════════════════════════════════════════
    val mainWindowState = rememberWindowState(
        width = 1000.dp,
        height = 720.dp,
        position = WindowPosition(Alignment.Center),
    )

    if (authenticationSurface == DesktopAuthenticationSurface.AUTHENTICATED && session != null) {
        // Session 回调可能来自 Netty/Default/AWT 线程。它们只能通过这个会话级租约，
        // 经由应用 UI 门禁到达可变 Compose 状态。
        var windowVisible by remember(session) { mutableStateOf(true) }
        // 新打开的主窗口在 AWT 发布第一个精确焦点边沿之前，被保守地视为活跃；
        // 这可以防止缓存的未读状态变成启动时的提醒。
        var windowFocused by remember(session) { mutableStateOf(true) }
        var trayAvailable by remember(session) { mutableStateOf(false) }
        val presentationGate = remember(session) { DesktopSessionPresentationGate() }
        val sessionUiActions = remember(
            session,
            desktopUiGate,
            presentationGate,
        ) {
            DesktopSessionUiActions(
                gate = desktopUiGate,
                onAuthExpired = { auth.onAuthExpiredForSession(session) },
                onLogout = { auth.onLogoutForSession(session) },
                onHttpAuthExpired = { rejectedAccessToken ->
                    auth.onHttpAuthExpiredForSession(session, rejectedAccessToken)
                },
                presentationGate = presentationGate,
            )
        }
        DisposableEffect(sessionUiActions) {
            onDispose { sessionUiActions.close() }
        }
        val resourceInstallation = remember(session, dataDir, sessionUiActions) {
            DesktopSessionResourcesInstallation {
                DesktopSessionResources(
                    ownerUid = session.userSession.uid,
                    datasetId = session.datasetId,
                    deploymentIdentity = session.deploymentIdentity,
                    credentialProvider = session::httpCredentialsSnapshot,
                    dataDir = dataDir,
                    diagnosticLogger = session.diagnosticLogger("DesktopSession"),
                    telemetry = SessionClientUiTelemetrySink(session.telemetryRecorder),
                    onAuthExpired = { rejectedAccessToken ->
                        sessionUiActions.requestHttpAuthExpired(rejectedAccessToken)
                    },
                )
            }
        }
        var resourceResult by remember(session) {
            mutableStateOf<DesktopSessionResourcesInstallationResult?>(null)
        }
        var resourceAttempt by remember(session) { mutableStateOf(0) }
        val resourceLogger = remember(session) { session.diagnosticLogger("DesktopSessionMount") }
        DisposableEffect(resourceInstallation) {
            onDispose {
                resourceInstallation.abandonIfUnbound()?.let { failure ->
                    resourceLogger.fault("Desktop 未发布资源关闭失败", failure)
                }
            }
        }
        LaunchedEffect(resourceInstallation, resourceAttempt) {
            resourceResult = null
            val result = resourceInstallation.install {
                auth.session === session && session.isBusinessActive && presentationGate.isOpen
            }
            val ownerCurrent =
                auth.session === session && session.isBusinessActive && presentationGate.isOpen
            when {
                result is DesktopSessionResourcesInstallationResult.Failed -> {
                    resourceLogger.fault("Desktop 本地资源加载失败", result.failure)
                    if (ownerCurrent) resourceResult = result
                }
                !ownerCurrent -> resourceInstallation.abandonIfUnbound()?.let { failure ->
                    resourceLogger.fault("Desktop 过期资源关闭失败", failure)
                }
                result is DesktopSessionResourcesInstallationResult.Ready -> resourceResult = result
            }
        }
        val desktopResources =
            (resourceResult as? DesktopSessionResourcesInstallationResult.Ready)?.resources
        var readyPresentation: DesktopReadyWindowPresentation? = null
        if (desktopResources != null) {
            val authenticatedUi = remember(session, desktopResources, presentationGate) {
                try {
                    DesktopAuthenticatedUiOwner(
                        session = session,
                        dataDir = dataDir,
                        presentationGate = presentationGate,
                        closePlatformResources = {
                            sessionUiActions.close()
                            resourceInstallation.close()
                        },
                        requestAuthExpired = { sessionUiActions.requestAuthExpired() },
                        requestHttpAuthExpired = sessionUiActions::requestHttpAuthExpired,
                    )
                } catch (failure: Throwable) {
                    try {
                        resourceInstallation.close()
                    } catch (closeFailure: Throwable) {
                        if (failure !== closeFailure && failure.suppressed.none { it === closeFailure }) {
                            failure.addSuppressed(closeFailure)
                        }
                    }
                    throw failure
                }
            }
            DisposableEffect(sessionRetirementBridge, session, authenticatedUi, resourceInstallation) {
                val binding = sessionRetirementBridge.bind(session, authenticatedUi.retirement)
                val lifecycleBound = resourceInstallation.markLifecycleBound(desktopResources)
                if (lifecycleBound) {
                    shutdownRetirement.set(authenticatedUi.retirement)
                    authenticatedUi.activateHttpAuthExpiredDelivery()
                } else {
                    binding.close()
                    authenticatedUi.retirement.retireFromComposition(SessionEndReason.SHUTDOWN)
                }
                onDispose {
                    try {
                        authenticatedUi.retirement.retireFromComposition(SessionEndReason.SHUTDOWN)
                    } finally {
                        shutdownRetirement.compareAndSet(authenticatedUi.retirement, null)
                        binding.close()
                    }
                }
            }

            if (presentationGate.isOpen) {
                val desktopNav = authenticatedUi.navigation
                val connectionState by auth.connectionState.collectAsState()
                LaunchedEffect(connectionState) {
                    when (connectionState) {
                        ConnectionState.AUTHENTICATED -> DesktopKeepAwake.start()
                        ConnectionState.DISCONNECTED -> DesktopKeepAwake.stop()
                        else -> {}
                    }
                }
                val conversations by desktopNav.conversationViewModel.conversations.collectAsState()
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
                val windowReadActive = isDesktopWindowActive(windowVisible, windowFocused)
                LaunchedEffect(conversations, windowReadActive) {
                    DesktopNotificationManager.onConversationsChanged(
                        conversations = conversations,
                        isWindowFocused = windowReadActive,
                    )
                }
                LaunchedEffect(Unit) {
                    trayAvailable = AppTray.create(
                        onShow = { sessionUiActions.dispatchUi { windowVisible = true } },
                        onQuit = { applicationExitActions.requestExit() },
                    )
                    DesktopNotificationManager.start()
                }
                DisposableEffect(Unit) {
                    onDispose {
                        AppTray.remove()
                        DesktopNotificationManager.stop()
                        DesktopKeepAwake.stop()
                    }
                }
                readyPresentation = DesktopReadyWindowPresentation(
                    resources = desktopResources,
                    navigation = desktopNav,
                    connectionState = connectionState,
                    windowReadActive = windowReadActive,
                )
            }
        }
        if (presentationGate.isOpen) {
            // ── 主窗口：资源扫描期间与就绪后复用同一个原生窗口 ──
            Window(
                visible = windowVisible,
                onCloseRequest = {
                    when (desktopMainWindowCloseAction(trayAvailable)) {
                        DesktopMainWindowCloseAction.HIDE_TO_TRAY -> windowVisible = false
                        DesktopMainWindowCloseAction.EXIT_APPLICATION -> applicationExitActions.requestExit()
                    }
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
                DisposableEffect(window, sessionUiActions) {
                    fun publishFocus() {
                        val focused = window.isFocused
                        sessionUiActions.dispatchUi { windowFocused = focused }
                    }
                    val focusListener = object : java.awt.event.WindowFocusListener {
                        override fun windowGainedFocus(event: java.awt.event.WindowEvent?) = publishFocus()

                        override fun windowLostFocus(event: java.awt.event.WindowEvent?) = publishFocus()
                    }
                    window.addWindowFocusListener(focusListener)
                    publishFocus()
                    onDispose { window.removeWindowFocusListener(focusListener) }
                }

                setTeamTalkIcon()
                AppTheme {
                    val ready = readyPresentation
                    when {
                        ready != null -> DesktopIdentityImageProvider(ready.resources, presentationGate) {
                            MainAppContent(
                                nav = ready.navigation,
                                presentationGate = presentationGate,
                                resources = ready.resources,
                                mainWindow = window,
                                mainWindowReadActive = ready.windowReadActive,
                                connectionState = ready.connectionState,
                                protocolCompatibility = auth.protocolCompatibility,
                                onToggleWindowZoom = {
                                    mainWindowState.placement = nextTitleBarPlacement(mainWindowState.placement)
                                },
                                onLogout = { sessionUiActions.requestLogout() },
                            )
                        }
                        resourceResult is DesktopSessionResourcesInstallationResult.Failed -> {
                            DesktopSessionResourcesFailureSurface {
                                admitDesktopSessionResourcesRetry(
                                    currentResult = resourceResult,
                                    clearFailure = { resourceResult = null },
                                    retry = { resourceAttempt += 1 },
                                )
                            }
                        }
                        else -> DesktopSessionResourcesLoadingSurface()
                    }
                }
            }
        }
    }
    } // key(activeConfig)
}

private data class DesktopReadyWindowPresentation(
    val resources: DesktopSessionResources,
    val navigation: DesktopNav,
    val connectionState: ConnectionState,
    val windowReadActive: Boolean,
)

@Composable
private fun DesktopSessionResourcesLoadingSurface() {
    Box(
        modifier = Modifier.fillMaxSize().testTag("main.sessionResources.loading"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("正在准备本地数据…", style = MaterialTheme.typography.titleMedium)
            Text(
                "正在加载媒体缓存，完成后会自动进入主界面",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DesktopSessionResourcesFailureSurface(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().testTag("main.sessionResources.error"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("本地资源加载失败", style = MaterialTheme.typography.titleMedium)
            Text(
                "可以直接重试，不需要重新登录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("main.sessionResources.retry"),
            ) {
                Text("重试")
            }
        }
    }
}

// ── 演示站自定义服务器（登录窗口阶段切换部署）──

/** 解析并应用自定义服务器地址：HTTP host 作 TCP host，端口沿用当前配置。 */
internal fun applyCustomServerUrl(
    rawUrl: String,
    current: com.virjar.tk.shared.client.ServerConfig,
    dataDir: File,
    apply: (com.virjar.tk.shared.client.ServerConfig) -> Unit,
) {
    val normalized = rawUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')
    val host = runCatching { java.net.URI(normalized).host }.getOrNull()
    if (host.isNullOrBlank()) return  // 无效输入保持原值
    val newConfig = com.virjar.tk.shared.client.ServerConfig(
        serverUrl = normalized,
        tcpHost = host,
        tcpPort = current.tcpPort,
    )
    persistCustomServer(dataDir, newConfig)
    apply(newConfig)
}

internal fun resetToPackagedServer(
    packagedConfig: com.virjar.tk.shared.client.ServerConfig,
    dataDir: File,
    apply: (com.virjar.tk.shared.client.ServerConfig) -> Unit,
) {
    persistCustomServer(dataDir, null)
    apply(packagedConfig)
}

private fun customServerStore(dataDir: File) =
    JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(fileName = "custom-server.properties")

private fun persistCustomServer(dataDir: File, config: com.virjar.tk.shared.client.ServerConfig?) {
    runCatching {
        val text = config?.let { "${it.serverUrl}\n${it.tcpHost}\n${it.tcpPort}" } ?: ""
        customServerStore(dataDir).replaceText(text)
    }
}

private fun readPersistedCustomServer(dataDir: File): com.virjar.tk.shared.client.ServerConfig? = runCatching {
    val text = customServerStore(dataDir).readText(MAX_CUSTOM_SERVER_FILE_BYTES) ?: return null
    val lines = text.lines()
    if (lines.size < 3) return null
    val port = lines[2].trim().toIntOrNull() ?: return null
    com.virjar.tk.shared.client.ServerConfig(serverUrl = lines[0].trim(), tcpHost = lines[1].trim(), tcpPort = port)
}.getOrNull()

private const val MAX_CUSTOM_SERVER_FILE_BYTES = 2048L

/** 每个 Desktop 数据目录一个持久身份（不同配置保持为不同设备）。 */
internal fun desktopInstallationDeviceId(dataDir: File): String {
    val identityStore = JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(fileName = "device-id")
    identityStore.readText(MAX_DEVICE_ID_FILE_BYTES)?.trim()?.let { stored ->
        require(AuthRules.validateDeviceId(stored) == null) { "Stored Desktop device identity is invalid" }
        return stored
    }
    val generated = "desktop-${UUID.randomUUID()}"
    identityStore.replaceText(generated, MAX_DEVICE_ID_FILE_BYTES)
    check(identityStore.readText(MAX_DEVICE_ID_FILE_BYTES) == generated) {
        "Desktop device identity was not persisted"
    }
    return generated
}

private const val MAX_DEVICE_ID_FILE_BYTES = 1024L
