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
 * 跨平台认证控制器。封装 Android/Desktop 重复的认证状态机 + 三级状态管理：
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
            onAuthResult = { success, uid, username, name, refreshToken, failureReason ->
                if (success) {
                    userSession.onAuthSuccess(uid ?: "", username, name, refreshToken)
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
                session = createSession(imClient, userSession, createCache, deviceId)
                onAuthenticated?.invoke(session!!)
                isLoggedIn = true
                authError = null
                autoLoggingIn = false
                // token 持久化从 userSession 读（三级状态：用户层持有 refreshToken）
                userSession.refreshToken?.let { tokenStore.save(userSession.uid, it) }
            }
            ConnectionState.AUTH_FAILED -> {
                authError = userSession.authFailureReason ?: "认证失败"
                autoLoggingIn = false
                // token 失效必须回到登录页，否则用户卡在未认证状态无法操作（发消息报未认证）
                isLoggedIn = false
                session = null
                tokenStore.clear()
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
            session?.close()
            session = null
            authError = null
            tokenStore.clear()
        },
        clearError = { authError = null },
    )
}
