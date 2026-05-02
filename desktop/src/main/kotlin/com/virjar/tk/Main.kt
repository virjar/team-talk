package com.virjar.tk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.client.ApiClient
import com.virjar.tk.client.ServerConfig
import com.virjar.tk.client.UserContext
import com.virjar.tk.client.getBuildProfile
import com.virjar.tk.client.isShowAdvancedSettings
import com.virjar.tk.dto.UserDto
import com.virjar.tk.ThemeMode
import com.virjar.tk.keepawake.KeepAwake
import com.virjar.tk.keepawake.createKeepAwake
import com.virjar.tk.storage.resolveDataDir
import com.virjar.tk.tray.AppTray
import com.virjar.tk.ui.screen.LoginScreen
import com.virjar.tk.ui.screen.RegisterScreen
import com.virjar.tk.ui.theme.TeamTalkTheme
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.ImageCache
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.material.MaterialTitleBar
import kotlinx.serialization.json.Json
import java.awt.Dimension
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.io.File
import java.util.jar.JarFile

private val appJson = Json { ignoreUnknownKeys = true }

private fun setupForceLogout(ctx: UserContext, onForceLogout: () -> Unit) {
    ctx.onForceLogout = onForceLogout
}

/**
 * 开发模式下预加载所有类到 JVM 内存，防止 `gradle clean` 删除 .class 文件后
 * 正在运行的客户端出现 `NoClassDefFoundError`。
 *
 * 通过检测 classpath 中是否包含 `build/` 判断开发模式。
 * 同时支持两种启动方式：
 * - `./gradlew :desktop:run`：classpath 为目录结构，扫描 build/classes/ 下的 .class 文件
 * - IDEA 直接运行：classpath 为 JAR 包，扫描 build/libs/ 下的 JAR 内 .class 条目
 *
 * 使用 `Class.forName(name, false, classLoader)` 只加载字节码不执行静态初始化器。
 * 打包发布版本 classpath 中不含 `build/`，此函数静默跳过。
 */
private fun preloadDevClasses() {
    val classpath = System.getProperty("java.class.path") ?: return
    if (!classpath.contains("build/")) return

    val classLoader = Thread.currentThread().contextClassLoader
    val entries = classpath.split(File.pathSeparatorChar).map { File(it) }
    var loaded = 0

    // 扫描目录条目（./gradlew :desktop:run）
    for (dir in entries.filter { it.isDirectory }) {
        val rootPath = dir.toPath()
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .forEach { file ->
                loaded += loadClass(rootPath, file, classLoader)
            }
    }

    // 扫描项目 JAR 条目（IDEA：build/libs/*.jar）
    for (jar in entries.filter { it.isFile && it.name.endsWith(".jar") && it.absolutePath.contains("build") }) {
        try {
            JarFile(jar).use { jf ->
                jf.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        loaded += loadClassFromJar(entry, classLoader)
                    }
            }
        } catch (_: Throwable) {
            // 跳过无法读取的 JAR
        }
    }

    if (loaded > 0) {
        println("[TeamTalk] Preloaded $loaded classes (development mode)")
    }
}

private fun loadClass(rootPath: java.nio.file.Path, file: File, classLoader: ClassLoader): Int {
    val className = rootPath.relativize(file.toPath())
        .toString()
        .removeSuffix(".class")
        .replace('/', '.')
        .replace('\\', '.')
    return try {
        Class.forName(className, false, classLoader)
        1
    } catch (_: Throwable) {
        0
    }
}

private fun loadClassFromJar(entry: java.util.jar.JarEntry, classLoader: ClassLoader): Int {
    val className = entry.name.removeSuffix(".class").replace('/', '.')
    return try {
        Class.forName(className, false, classLoader)
        1
    } catch (_: Throwable) {
        0
    }
}

fun main() {
    // 开发模式下预加载所有类，防止 gradle clean 导致 NoClassDefFoundError
    preloadDevClasses()

    // --- One-time initialization (pure JVM, never recomposed) ---

    // 在 logback 初始化前设置数据目录系统属性，使日志写入对应数据目录
    val dataDir = resolveDataDir()
    System.setProperty("teamtalk.data.dir", dataDir.absolutePath)

    // Initialize image cache
    ImageCache.init(File(dataDir, "cache"))

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        AppLog.e("Uncaught", "Uncaught exception in thread: ${thread.name}", throwable)
    }
    val locker = FileLocker(dataDir)

    if (!locker.tryLock()) {
        // Another instance is using this data directory — show error and exit
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "TeamTalk - Already Running",
                state = rememberWindowState(width = 450.dp, height = 200.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Another instance is already running",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
        return
    }

    // Lock acquired — launch the main application
    teamTalkApplication(dataDir, locker)
}

/**
 * Main Compose application, extracted so that one-time init stays outside `application {}`.
 */
private fun teamTalkApplication(dataDir: File, locker: FileLocker) = application {
    val keepAwake = remember { createKeepAwake() }

    // Build ServerConfig from profile defaults, overridden by saved config
    val apiClient = remember {
        val defaultConfig = ServerConfig()
        val storage = com.virjar.tk.storage.TokenStorage()
        val savedUrl = storage.loadSavedServerBaseUrl()
        val savedHost = storage.loadSavedTcpHost()
        val savedPort = storage.loadSavedTcpPort()
        val config = if (savedUrl != null && savedHost != null) {
            ServerConfig(
                baseUrl = savedUrl,
                tcpHost = savedHost,
                tcpPort = savedPort ?: defaultConfig.tcpPort,
            )
        } else {
            defaultConfig
        }
        ApiClient(config)
    }
    var userContext by remember { mutableStateOf<UserContext?>(null) }
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var totalUnread by remember { mutableIntStateOf(0) }
    var mainWindow by remember { mutableStateOf<AwtWindow?>(null) }

    val showAdvanced = remember { isShowAdvancedSettings() }

    // Session restore
    LaunchedEffect(Unit) {
        val (token, uid, userJson) = apiClient.restoreSession() ?: return@LaunchedEffect
        try {
            val user = appJson.decodeFromString<UserDto>(userJson)
            val ctx = UserContext(token, uid, user, apiClient, apiClient.getTokenStorage())
            setupForceLogout(ctx) {
                userContext?.destroy()
                userContext = null
                mainWindow = null
            }
            ctx.persistSession()
            ctx.connectTcp()
            userContext = ctx
            AppLog.i("Main", "Session restored for uid=${user.uid}")
        } catch (e: Exception) {
            AppLog.e("Main", "Failed to restore session", e)
            apiClient.getTokenStorage().clear()
        }
    }

    // Keep OS awake while user is logged in (protects TCP PING from being delayed)
    LaunchedEffect(userContext) {
        if (userContext != null) keepAwake.keepAwake() else keepAwake.allowSleep()
    }

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // --- Login window (fixed size, not resizable) ---
    if (userContext == null) {
        TeamTalkTheme(darkTheme = darkTheme) {
            val loginWindowState = rememberWindowState(width = 400.dp, height = 520.dp)
            MaterialDecoratedWindow(
                onCloseRequest = {
                    keepAwake.allowSleep()
                    locker.release()
                    exitApplication()
                },
                title = "TeamTalk",
                state = loginWindowState,
                resizable = false,
            ) {
                var isRegister by remember { mutableStateOf(false) }

                MaterialTitleBar { state ->
                    Text(
                        "TeamTalk",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isRegister) {
                    LoginScreen(
                        onLoginSuccess = {
                            // TCP connect & session persist are done in onLogin below,
                            // so by the time this is called everything is ready.
                        },
                        onNavigateToRegister = { isRegister = true },
                        onLogin = { user, pass ->
                            val result = apiClient.login(user, pass)
                            // Complete all post-login work BEFORE setting userContext,
                            // because setting userContext triggers window disposal.
                            val ctx = UserContext(result.accessToken, result.uid, result.user, apiClient, apiClient.getTokenStorage())
                            setupForceLogout(ctx) {
                                userContext?.destroy()
                                userContext = null
                                mainWindow = null
                            }
                            ctx.persistSession()
                            ctx.connectTcp()
                            userContext = ctx
                            true
                        },
                        showServerSettings = showAdvanced,
                        currentServerUrl = apiClient.baseUrl,
                        currentTcpHost = apiClient.tcpHost,
                        onServerConfigChange = { baseUrl, host ->
                            apiClient.updateConfig(baseUrl, host)
                            apiClient.getTokenStorage().saveServerConfig(baseUrl, host, apiClient.tcpPort)
                        },
                    )
                } else {
                    RegisterScreen(
                        onRegisterSuccess = {
                            // TCP connect & session persist are done in onRegister below.
                        },
                        onNavigateBack = { isRegister = false },
                        onRegister = { user, pass, name ->
                            val result = apiClient.register(user, pass, name)
                            // Complete all post-register work BEFORE setting userContext.
                            val ctx = UserContext(result.accessToken, result.uid, result.user, apiClient, apiClient.getTokenStorage())
                            setupForceLogout(ctx) {
                                userContext?.destroy()
                                userContext = null
                                mainWindow = null
                            }
                            ctx.persistSession()
                            ctx.connectTcp()
                            userContext = ctx
                            true
                        },
                    )
                }
            }
        }
    }

    // --- Main window (resizable with minimum size) ---
    val ctx = userContext
    if (ctx != null) {
        val connectionState by ctx.connectionState.collectAsState()

        TeamTalkTheme(darkTheme = darkTheme) {
            val mainWindowState = rememberWindowState(width = 1000.dp, height = 700.dp)
            MaterialDecoratedWindow(
                onCloseRequest = {
                    mainWindow?.isVisible = false
                },
                title = "TeamTalk",
                state = mainWindowState,
            ) {
                LaunchedEffect(Unit) {
                    window.minimumSize = Dimension(800, 600)
                    mainWindow = window
                }

                DesktopMainAppContent(
                    userContext = ctx,
                    onLogout = {
                        ctx.destroy()
                        userContext = null
                        mainWindow = null
                    },
                    themeMode = themeMode,
                    onToggleTheme = { themeMode = it },
                    onTotalUnreadChange = { totalUnread = it },
                )
            }
        }

        // System tray (sibling of Window, within application {} scope)
        AppTray(
            isConnected = connectionState == UserContext.ConnectionState.CONNECTED,
            unreadCount = totalUnread,
            onRestore = {
                val w = mainWindow ?: return@AppTray
                (w as? Frame)?.let { frame ->
                    if (frame.extendedState == Frame.ICONIFIED) {
                        frame.extendedState = Frame.NORMAL
                    }
                }
                w.isVisible = true
                w.toFront()
            },
            onExit = {
                keepAwake.allowSleep()
                locker.release()
                exitApplication()
            },
        )
    }
}
