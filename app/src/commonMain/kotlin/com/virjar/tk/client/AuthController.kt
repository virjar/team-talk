package com.virjar.tk.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first

/**
 * 认证状态。由 [rememberAuthController] 返回，供 UI 消费。
 */
class AuthState(
    val isLoggedIn: Boolean,
    val autoLoggingIn: Boolean,
    val authError: String?,
    val session: ClientSession?,
    val userSession: UserSession,
    val imClient: ImClient,
    val onLogin: (username: String, password: String) -> Unit,
    val onRegister: (username: String, password: String, name: String) -> Unit,
    val onLogout: () -> Unit,
    val clearError: () -> Unit,
)

/**
 * 跨平台认证控制器（UI 层 Compose 包装）。
 *
 * 分层说明：本文件是 app/UI 层唯一的 Compose 认证入口；认证的底层能力
 * （ImClient 连接、createSession、UserSession 三级状态）在 shared（IM SDK）。
 * 包名保持 `com.virjar.tk.client` 以兼容既有 import。
 *
 * 封装 Android/Desktop 重复的认证状态机 + 三级状态管理：
 *
 * 1. 创建 [UserSession]（用户层）+ [ImClient]（连接层，注入认证回调）
 * 2. 启动时检查 token → 自动登录（connect → authenticate）
 * 3. 监听 connectionState → AUTHENTICATED 时创建 session + 保存 token
 * 4. AUTH_FAILED 时清除 token + 显示错误
 * 5. onLogout 时清理 session + 清除 token
 *
 * ImClient 的认证结果通过回调写入 UserSession（三级状态隔离），
 * UserSession 生命周期独立于 TCP 连接。
 *
 * @param tokenStore 登录态持久化
 * @param tcpHost TCP 主机
 * @param tcpPort TCP 端口
 * @param deviceId 设备 ID（如 "android-device" / "desktop-device"）
 * @param deviceName 设备名（如 "Android" / "Desktop"）
 * @param createCache 平台 LocalCache 工厂
 * @param onAuthenticated 认证成功后的额外回调（如 Android 的 upsertUser）
 */
@Composable
fun rememberAuthController(
    tokenStore: TokenStore,
    tcpHost: String,
    tcpPort: Int,
    deviceId: String,
    deviceName: String,
    createCache: (uid: String) -> LocalCache,
    onAuthenticated: ((ClientSession) -> Unit)? = null,
): AuthState {
    // 用户层状态（独立于 TCP 连接）
    val userSession = remember { UserSession() }

    // 连接层（认证结果回调写入 userSession）
    val imClient = remember {
        ImClient(
            onAuthResult = { success, uid, username, name, refreshToken, accessToken, failureReason ->
                if (success) {
                    userSession.onAuthSuccess(uid ?: "", username, name, refreshToken, accessToken)
                } else {
                    userSession.onAuthFailed(failureReason)
                }
            },
        )
    }

    var isLoggedIn by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var autoLoggingIn by remember { mutableStateOf(tokenStore.hasSavedLogin()) }
    var session by remember { mutableStateOf<ClientSession?>(null) }

    // 自动登录：有已保存的 uid + token 时，启动即用 token 认证（connectAndAuth 原子化）
    LaunchedEffect(Unit) {
        if (tokenStore.hasSavedLogin()) {
            val uid = tokenStore.savedUid ?: return@LaunchedEffect
            val token = tokenStore.savedToken ?: return@LaunchedEffect
            imClient.authenticate(uid, token, deviceId, deviceName, tcpHost, tcpPort)
        }
    }

    val connectionState by imClient.state.collectAsState()
    LaunchedEffect(connectionState) {
        when (connectionState) {
            ConnectionState.AUTHENTICATED -> {
                // 重连不重建 session：组件（RpcClient/EventProcessor）自治重启监听，
                // 这里重复 createSession 会泄漏旧 session + 重复打开同一 SQLite。
                if (session == null) {
                    session = createSession(imClient, userSession, createCache, deviceId)
                    onAuthenticated?.invoke(session!!)
                    isLoggedIn = true
                    autoLoggingIn = false
                }
                // token 持久化在【每次】认证成功后执行（F26）：refresh token 一次一换，
                // 曾只在首认证（session==null）时保存——重连后再认证（token 已轮换）不落盘，
                // 进程随后退出时磁盘上是已作废的旧 token，下次启动静默登录失败
                userSession.refreshToken?.let { tokenStore.save(userSession.uid, it) }
                SessionContext.accessToken = userSession.accessToken
                authError = null
            }
            ConnectionState.AUTH_FAILED -> {
                authError = userSession.authFailureReason ?: "认证失败"
                autoLoggingIn = false
                // token 失效必须回到登录页；级联关闭会话（uploader/watcher/AppLog 全局引用）
                isLoggedIn = false
                session?.close()
                session = null
                tokenStore.clear()
                SessionContext.accessToken = null
            }
            else -> {}
        }
    }

    return AuthState(
        isLoggedIn = isLoggedIn,
        autoLoggingIn = autoLoggingIn,
        authError = authError,
        session = session,
        userSession = userSession,
        imClient = imClient,
        onLogin = { username, password ->
            authError = null
            // login 内部调 connectAndAuth（原子化：pendingAuth + connect 在同一 EventLoop 任务）
            imClient.login(username, password, "$deviceId-${java.util.UUID.randomUUID()}", deviceName, tcpHost, tcpPort)
        },
        onRegister = { username, password, name ->
            authError = null
            imClient.register(username, password, name, "$deviceId-${java.util.UUID.randomUUID()}", deviceName, tcpHost, tcpPort)
        },
        onLogout = {
            isLoggedIn = false
            SessionContext.accessToken = null
            session?.close()
            session = null
            authError = null
            tokenStore.clear()
        },
        clearError = { authError = null },
    )
}
