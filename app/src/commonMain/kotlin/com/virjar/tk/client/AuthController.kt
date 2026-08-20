package com.virjar.tk.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * 认证状态。由 [rememberAuthController] 返回，供 UI 消费。
 */
class AuthState(
    val isLoggedIn: Boolean,
    val autoLoggingIn: Boolean,
    val authError: String?,
    val requiresProtocolUpgrade: Boolean,
    val session: ClientSession?,
    val userSession: UserSession,
    val imClient: ImClient,
    val onLogin: (username: String, password: String) -> Unit,
    val onRegister: (username: String, password: String, name: String) -> Unit,
    val onLogout: () -> Unit,
    val onAuthExpired: () -> Unit,
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
 * 3. 身份认证成功后先创建 session + 保存 token，由 EventProcessor 完成持久事件分页同步
 * 4. 同步完成进入 AUTHENTICATED 后才开放业务 UI
 * 5. AUTH_FAILED 时清除 token + 显示错误
 * 6. onLogout 时清理 session + 清除 token
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
    deviceModel: String? = null,
    deviceFlag: Int = 0,
    createCache: (uid: String) -> LocalCache,
    onAuthenticated: ((ClientSession) -> Unit)? = null,
): AuthState {
    // claimOwner 是认证根的持久化租约。Android 外部 Activity 返回或窗口重建时，
    // 新 controller 会先接管世代，从此旧 controller 的延迟回调无权改写凭据。
    val tokenOwner = remember(tokenStore) { tokenStore.claimOwner() }
    var ownedStoredLogin by remember(tokenStore, tokenOwner.generation) {
        mutableStateOf(tokenOwner.savedLogin)
    }

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
    var requiresProtocolUpgrade by remember { mutableStateOf(false) }
    var autoLoggingIn by remember { mutableStateOf(tokenOwner.savedLogin != null) }
    var session by remember { mutableStateOf<ClientSession?>(null) }
    var authGeneration by remember { mutableStateOf(0L) }
    var retiringSession by remember { mutableStateOf<ClientSession?>(null) }
    var logoutJob by remember { mutableStateOf<Job?>(null) }
    val controllerScope = rememberCoroutineScope()

    fun beginAuthAttempt() {
        // If the user starts another login before the best-effort logout RPC returns, close the
        // old transport first and invalidate that RPC's finally block. EventLoop task ordering then
        // guarantees disconnect is queued before connectAndAuth for the new attempt.
        val abandonedSession = retiringSession
        retiringSession = null
        authGeneration += 1
        logoutJob?.cancel()
        logoutJob = null
        abandonedSession?.close()
    }

    fun endAuthenticatedSession(message: String?, clearStoredLogin: Boolean) {
        authGeneration += 1
        logoutJob?.cancel()
        logoutJob = null
        retiringSession?.close(disconnectTransport = false)
        retiringSession = null
        isLoggedIn = false
        autoLoggingIn = false
        session?.close()
        session = null
        if (clearStoredLogin) {
            // uid + token + owner generation 全部匹配才会清除。旧 Activity 的
            // AUTH_FAILED/401 即使延迟到达，也不能删除新 owner 已轮换的 token。
            ownedStoredLogin?.let(tokenStore::compareAndClear)
            ownedStoredLogin = null
        }
        // The controller can stay composed on the login screen, so do not leave the previous
        // account's identity or bearer credentials resident in its long-lived UserSession.
        userSession.onAuthFailed(message)
        authError = message
    }

    // The platform shell may be recreated while the process stays alive (notably Android
    // Activity recreation). The controller owns the transport it created, so disposing the
    // composition must also release the old session/EventLoop rather than leaving a ghost client.
    DisposableEffect(imClient) {
        onDispose {
            authGeneration += 1
            logoutJob?.cancel()
            logoutJob = null
            retiringSession?.close(disconnectTransport = false)
            retiringSession = null
            session?.close(disconnectTransport = false)
            session = null
            imClient.destroy()
        }
    }

    // 自动登录：有已保存的 uid + token 时，启动即用 token 认证（connectAndAuth 原子化）
    LaunchedEffect(Unit) {
        ownedStoredLogin?.let { savedLogin ->
            imClient.authenticate(
                savedLogin.uid,
                savedLogin.refreshToken,
                deviceId,
                deviceName,
                tcpHost,
                tcpPort,
                deviceModel,
                deviceFlag,
            )
        }
    }

    val connectionState by imClient.state.collectAsState()
    val eventSyncCursor by imClient.eventSyncCursor.collectAsState()
    val authenticationFailure by imClient.authenticationFailure.collectAsState()

    // 自动登录分为“连接/身份认证”与“持久事件同步”两个阶段。前者保留
    // 12s 快速失败；后者按已落盘 cursor 续期 35s 无进度窗口，避免合法多页 replay
    // 被旧的固定 12s 计时主动断开。保留 token，短暂网络故障不破坏持久登录态。
    LaunchedEffect(autoLoggingIn, connectionState, eventSyncCursor) {
        if (!autoLoggingIn) return@LaunchedEffect
        val timeoutMs = autoLoginTimeoutMillis(connectionState) ?: return@LaunchedEffect
        val observedState = connectionState
        val observedCursor = eventSyncCursor
        delay(timeoutMs)
        if (
            autoLoggingIn &&
            !isLoggedIn &&
            imClient.state.value == observedState &&
            (observedState != ConnectionState.SYNCHRONIZING || imClient.eventSyncCursor.value == observedCursor)
        ) {
            imClient.disconnect()
            autoLoggingIn = false
            authError = if (observedState == ConnectionState.SYNCHRONIZING) {
                "数据同步暂无进展，请检查网络或稍后重试"
            } else {
                "服务器暂时无法连接，请检查网络或稍后重试"
            }
        }
    }

    LaunchedEffect(connectionState, authenticationFailure) {
        if (requiresForcedProtocolUpgrade(authenticationFailure)) {
            if (!requiresProtocolUpgrade) {
                // Keep the refresh token: after installing a compatible client the user should be
                // able to resume automatic login. The old session and transport must still stop now.
                requiresProtocolUpgrade = true
                endAuthenticatedSession(message = null, clearStoredLogin = false)
                imClient.disconnect()
            }
            return@LaunchedEffect
        }

        fun ensureSessionForAuthenticatedIdentity(): Boolean {
            requiresProtocolUpgrade = false
            val authenticatedUid = userSession.uid
            val rotatedRefreshToken = userSession.refreshToken
            if (authenticatedUid.isBlank() || rotatedRefreshToken.isNullOrBlank()) {
                endAuthenticatedSession(
                    message = "认证响应缺少持久凭据",
                    clearStoredLogin = false,
                )
                imClient.disconnect()
                return false
            }
            val persistedLogin = tokenStore.save(
                tokenOwner.generation,
                authenticatedUid,
                rotatedRefreshToken,
            )
            if (persistedLogin == null) {
                // 这个 controller 已被新 Activity/窗口取代。旧 owner 不得覆盖新凭据。
                isLoggedIn = false
                autoLoggingIn = false
                session?.close()
                session = null
                userSession.onAuthFailed(null)
                imClient.disconnect()
                return false
            }
            ownedStoredLogin = persistedLogin
            if (session == null) {
                session = createSession(imClient, userSession, createCache, deviceId)
                onAuthenticated?.invoke(checkNotNull(session))
            }
            authError = null
            return true
        }

        when (connectionState) {
            ConnectionState.SYNCHRONIZING -> {
                // LocalCache/EventProcessor must exist before the client can send its persisted
                // cursor. Initial login remains on the loading surface until SYNC_READY.
                ensureSessionForAuthenticatedIdentity()
            }
            ConnectionState.AUTHENTICATED -> {
                if (ensureSessionForAuthenticatedIdentity()) {
                    isLoggedIn = true
                    autoLoggingIn = false
                }
            }
            ConnectionState.AUTH_FAILED -> {
                // token 失效必须回到登录页；级联关闭会话（uploader/watcher/AppLog 全局引用）
                endAuthenticatedSession(userSession.authFailureReason ?: "认证失败", clearStoredLogin = true)
            }
            else -> {}
        }
    }

    return AuthState(
        isLoggedIn = isLoggedIn,
        autoLoggingIn = autoLoggingIn,
        authError = authError,
        requiresProtocolUpgrade = requiresProtocolUpgrade,
        session = session,
        userSession = userSession,
        imClient = imClient,
        onLogin = { username, password ->
            beginAuthAttempt()
            authError = null
            // login 内部调 connectAndAuth（原子化：pendingAuth + connect 在同一 EventLoop 任务）
            try {
                imClient.login(
                    username,
                    password,
                    deviceId,
                    deviceName,
                    tcpHost,
                    tcpPort,
                    deviceModel,
                    deviceFlag,
                )
            } catch (e: IllegalArgumentException) {
                authError = e.message ?: "登录信息不合法"
            }
        },
        onRegister = { username, password, name ->
            beginAuthAttempt()
            authError = null
            try {
                imClient.register(
                    username,
                    password,
                    name,
                    deviceId,
                    deviceName,
                    tcpHost,
                    tcpPort,
                    deviceModel,
                    deviceFlag,
                )
            } catch (e: IllegalArgumentException) {
                authError = e.message ?: "注册信息不合法"
            }
        },
        onLogout = {
            val closingSession = session
            val retirementGeneration = authGeneration + 1
            authGeneration = retirementGeneration
            // Leave the authenticated UI immediately, but let the session-owned RPC finish before
            // closing its transport. The controller composition itself remains mounted at app root.
            isLoggedIn = false
            autoLoggingIn = false
            session = null
            authError = null
            ownedStoredLogin?.let(tokenStore::compareAndClear)
            ownedStoredLogin = null
            userSession.onAuthFailed(null)
            if (closingSession == null) {
                imClient.disconnect()
            } else {
                retiringSession = closingSession
                logoutJob = controllerScope.launch {
                    try {
                        closingSession.userRepo.logout().getOrThrow()
                    } catch (_: Exception) {
                        // Local logout must remain available offline; the server token will still
                        // expire and a later device-management action can revoke the installation.
                    } finally {
                        // A newer login may already be using the same ImClient. Always release the
                        // retiring repositories/jobs, but only the still-current generation owns
                        // the right to disconnect the transport.
                        closingSession.close(
                            disconnectTransport = authGeneration == retirementGeneration,
                        )
                        if (retiringSession === closingSession) retiringSession = null
                    }
                }
            }
        },
        onAuthExpired = {
            endAuthenticatedSession(message = "认证失效，请重新登录", clearStoredLogin = true)
        },
        clearError = { authError = null },
    )
}

private const val IDENTITY_AUTO_LOGIN_TIMEOUT_MS = 12_000L
private const val SYNC_NO_PROGRESS_TIMEOUT_MS = 35_000L

/** Null means this terminal/ready state is handled by the authentication state machine itself. */
internal fun autoLoginTimeoutMillis(state: ConnectionState): Long? = when (state) {
    ConnectionState.SYNCHRONIZING -> SYNC_NO_PROGRESS_TIMEOUT_MS
    ConnectionState.AUTHENTICATED,
    ConnectionState.AUTH_FAILED,
    -> null
    ConnectionState.DISCONNECTED,
    ConnectionState.CONNECTING,
    ConnectionState.CONNECTED,
    -> IDENTITY_AUTO_LOGIN_TIMEOUT_MS
}

internal fun requiresForcedProtocolUpgrade(failure: AuthenticationFailure?): Boolean =
    failure?.kind == AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED
