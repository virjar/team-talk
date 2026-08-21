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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow

/**
 * 认证状态。由 [rememberAuthController] 返回，供 UI 消费。
 */
class AuthState(
    val isLoggedIn: Boolean,
    val autoLoggingIn: Boolean,
    val authError: String?,
    val requiresProtocolUpgrade: Boolean,
    val session: ClientSession?,
    /** Read-only transport status; UI code never receives the raw protocol/connection owner. */
    val connectionState: StateFlow<ConnectionState>,
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
    deploymentIdentity: DeploymentIdentity,
    tcpHost: String,
    tcpPort: Int,
    deviceId: String,
    deviceName: String,
    deviceModel: String? = null,
    deviceFlag: Int = 0,
    createCache: (deploymentIdentity: DeploymentIdentity, uid: String) -> LocalCache,
    onAuthenticated: ((ClientSession) -> Unit)? = null,
): AuthState {
    // claimOwner 是认证根的持久化租约。Android 外部 Activity 返回或窗口重建时，
    // 新 controller 会先接管世代，从此旧 controller 的延迟回调无权改写凭据。
    val tokenOwner = remember(tokenStore, deploymentIdentity, tcpHost, tcpPort) {
        require(tokenStore.deploymentIdentity == deploymentIdentity) {
            "TokenStore belongs to a different TCP+HTTP deployment"
        }
        require(DeploymentIdentity.from(tcpHost, tcpPort, deploymentIdentity.httpBaseUrl) == deploymentIdentity) {
            "Authentication transport does not match the bound deployment"
        }
        tokenStore.claimOwner()
    }
    val credentialSnapshot = remember(tokenStore, tokenOwner.generation) {
        AuthCredentialSnapshotHolder(tokenOwner.generation, tokenOwner.savedLogin)
    }
    val authResultAdmission = remember(tokenStore, tokenOwner.generation) {
        AuthResultAdmissionGate(initiallyActive = tokenOwner.savedLogin != null)
    }

    // 用户层状态（独立于 TCP 连接）
    val userSession = remember { UserSession() }

    // 连接层（认证结果回调写入 userSession）
    val imClient = remember {
        ImClient(
            onAuthResult = { success, uid, username, name, refreshToken, accessToken, failureReason ->
                if (success) {
                    val authenticatedUid = uid?.takeIf(String::isNotBlank)
                        ?: error("认证响应缺少 uid")
                    val rotatedRefreshToken = refreshToken?.takeIf(String::isNotBlank)
                        ?: error("认证响应缺少 refresh token")
                    authResultAdmission.use {
                        userSession.onAuthSuccess(
                            authenticatedUid,
                            username,
                            name,
                            rotatedRefreshToken,
                            accessToken,
                        ) {
                            val persisted = tokenStore.save(
                                tokenOwner.generation,
                                authenticatedUid,
                                rotatedRefreshToken,
                            ) ?: error("认证 owner 已被替代，拒绝持久化轮换凭据")
                            credentialSnapshot.publish(persisted)
                        }
                    }
                } else {
                    authResultAdmission.runIfActive {
                        userSession.onAuthFailed(failureReason)
                    }
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

    fun beginAuthAttempt(): Throwable? {
        // A previous AUTH callback may already be durably committing on the EventLoop. Drain it,
        // then erase every provisional artifact before reopening admission for the explicit new
        // attempt. This makes rapid A -> B submissions linearizable without carrying A into B.
        return authResultAdmission.replaceAttempt {
            // If the user starts another login before the best-effort logout RPC returns, close the
            // old transport first and invalidate that RPC's finally block. EventLoop task ordering
            // guarantees disconnect is queued before connectAndAuth for the new attempt.
            val abandonedSession = retiringSession
            retiringSession = null
            authGeneration += 1
            logoutJob?.cancel()
            logoutJob = null
            val failures = mutableListOf<Throwable>()
            fun release(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            release { abandonedSession?.close(reason = SessionEndReason.PROCESS_REPLACED) }
            release { session?.close(reason = SessionEndReason.PROCESS_REPLACED) }
            session = null
            release { credentialSnapshot.snapshot()?.let(tokenStore::compareAndClear) }
            credentialSnapshot.clear()
            release { userSession.onAuthFailed(null) }
            // Deletion failure must not permanently brick this controller. A successful new AUTH
            // atomically overwrites the same owner generation before synchronization can start.
            failures.firstOrNull()
        }
    }

    fun endAuthenticatedSession(
        message: String?,
        clearStoredLogin: Boolean,
        reason: SessionEndReason,
    ) {
        // Wait for a credential callback which already won admission, or make every later callback
        // fail before TokenStore/UserSession mutation. This is the logout/auth-result linearization
        // point; compare-and-clear below therefore cannot be followed by same-owner resurrection.
        authResultAdmission.retire()
        val closingRetiring = retiringSession
        val closingSession = session
        authGeneration += 1
        logoutJob?.cancel()
        logoutJob = null
        retiringSession = null
        session = null
        isLoggedIn = false
        autoLoggingIn = false
        authError = message

        val failures = mutableListOf<Pair<String, Throwable>>()
        fun release(owner: String, block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                failures += owner to failure
            }
        }
        release("retiring session") {
            closingRetiring?.close(reason = reason, disconnectTransport = false)
        }
        release("active session") { closingSession?.close(reason = reason) }
        if (clearStoredLogin) {
            release("stored login") {
                // uid + token + owner generation 全部匹配才会清除。旧 Activity 的
                // AUTH_FAILED/401 即使延迟到达，也不能删除新 owner 已轮换的 token。
                credentialSnapshot.snapshot()?.let(tokenStore::compareAndClear)
            }
            credentialSnapshot.clear()
        }
        release("user identity") {
            // The controller can stay composed on the login screen, so do not leave the previous
            // account's identity or bearer credentials resident in its long-lived UserSession.
            userSession.onAuthFailed(message)
        }
        if (failures.isNotEmpty()) {
            (closingSession ?: closingRetiring)?.recordRetirementFailure(
                "Authentication retirement completed with ${failures.size} cleanup failure(s)",
                failures.first().second,
            )
        }
    }

    // The platform shell may be recreated while the process stays alive (notably Android
    // Activity recreation). The controller owns the transport it created, so disposing the
    // composition must also release the old session/EventLoop rather than leaving a ghost client.
    DisposableEffect(imClient) {
        onDispose {
            authResultAdmission.retire()
            authGeneration += 1
            logoutJob?.cancel()
            logoutJob = null
            retiringSession?.close(reason = SessionEndReason.SHUTDOWN, disconnectTransport = false)
            retiringSession = null
            session?.close(reason = SessionEndReason.SHUTDOWN, disconnectTransport = false)
            session = null
            imClient.destroy()
        }
    }

    // 自动登录：有已保存的 uid + token 时，启动即用 token 认证（connectAndAuth 原子化）
    LaunchedEffect(Unit) {
        credentialSnapshot.snapshot()?.let { savedLogin ->
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
            val timeoutMessage = if (observedState == ConnectionState.SYNCHRONIZING) {
                "数据同步暂无进展，请检查网络或稍后重试"
            } else {
                "服务器暂时无法连接，请检查网络或稍后重试"
            }
            // Preserve the durable refresh credential for a later retry, but retire every live
            // callback/session owner before disconnecting. A timeout is a terminal in-memory edge.
            endAuthenticatedSession(
                message = timeoutMessage,
                clearStoredLogin = false,
                reason = SessionEndReason.SHUTDOWN,
            )
            imClient.disconnect()
        }
    }

    LaunchedEffect(connectionState, authenticationFailure) {
        if (requiresForcedProtocolUpgrade(authenticationFailure)) {
            if (!requiresProtocolUpgrade) {
                // Keep the refresh token: after installing a compatible client the user should be
                // able to resume automatic login. The old session and transport must still stop now.
                requiresProtocolUpgrade = true
                endAuthenticatedSession(
                    message = null,
                    clearStoredLogin = false,
                    reason = SessionEndReason.PROTOCOL_UPGRADE,
                )
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
                    reason = SessionEndReason.AUTH_REVOKED,
                )
                imClient.disconnect()
                return false
            }
            val existingSession = session
            if (existingSession != null && existingSession.ownerUid != authenticatedUid) {
                // A refresh/reconnect callback may never retarget an existing cache/repository
                // graph. Fail closed before persisting the unexpected identity.
                endAuthenticatedSession(
                    message = "认证身份与当前会话不一致，请重新登录",
                    clearStoredLogin = true,
                    reason = SessionEndReason.AUTH_REVOKED,
                )
                imClient.disconnect()
                return false
            }
            // The Netty callback only publishes to this synchronized holder; this Compose effect
            // reads it on its own thread, so EventLoop never touches mutableState.
            val persistedLogin = credentialSnapshot.snapshot()
            if (
                persistedLogin == null ||
                persistedLogin.ownerGeneration != tokenOwner.generation ||
                persistedLogin.deploymentFingerprint != deploymentIdentity.fingerprint ||
                persistedLogin.uid != authenticatedUid ||
                persistedLogin.refreshToken != rotatedRefreshToken ||
                !tokenStore.isCurrentOwner(tokenOwner.generation)
            ) {
                // 这个 controller 已被新 Activity/窗口取代。旧 owner 不得覆盖新凭据。
                isLoggedIn = false
                autoLoggingIn = false
                session?.close(reason = SessionEndReason.PROCESS_REPLACED)
                session = null
                credentialSnapshot.clear()
                userSession.onAuthFailed(null)
                imClient.disconnect()
                return false
            }
            if (session == null) {
                val createdSession = try {
                    val candidate = createSession(
                        imClient = imClient,
                        userSession = userSession,
                        deploymentIdentity = deploymentIdentity,
                        createCache = createCache,
                        deviceId = deviceId,
                    )
                    try {
                        onAuthenticated?.invoke(candidate)
                        candidate
                    } catch (failure: Throwable) {
                        runCatching { candidate.close(reason = SessionEndReason.SHUTDOWN) }
                            .exceptionOrNull()
                            ?.let(failure::addSuppressed)
                        throw failure
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException || failure !is Exception) throw failure
                    // createSession rolls back every resource it acquired, but it deliberately does
                    // not own the shared transport or identity. The composition root retires both.
                    endAuthenticatedSession(
                        message = "会话资源初始化失败，请重新登录",
                        clearStoredLogin = true,
                        reason = SessionEndReason.SHUTDOWN,
                    )
                    imClient.disconnect()
                    return false
                }
                session = createdSession
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
                retireAuthFailureAndDisconnect(
                    endSession = {
                        // token 失效必须回到登录页；级联关闭会话（uploader/watcher/AppLog 全局引用）
                        endAuthenticatedSession(
                            message = userSession.authFailureReason ?: "认证失败",
                            clearStoredLogin = true,
                            reason = SessionEndReason.AUTH_REVOKED,
                        )
                    },
                    disconnectTransport = imClient::disconnect,
                )
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
        connectionState = imClient.state,
        onLogin = { username, password ->
            val cleanupFailure = beginAuthAttempt()
            authError = cleanupFailure?.let { "旧登录态清理不完整，将在认证成功后覆盖" }
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
                authResultAdmission.retire()
                authError = e.message ?: "登录信息不合法"
            }
        },
        onRegister = { username, password, name ->
            val cleanupFailure = beginAuthAttempt()
            authError = cleanupFailure?.let { "旧登录态清理不完整，将在认证成功后覆盖" }
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
                authResultAdmission.retire()
                authError = e.message ?: "注册信息不合法"
            }
        },
        onLogout = {
            val closingSession = session
            val retirementGeneration = authGeneration + 1
            authGeneration = retirementGeneration
            // Leave the authenticated UI immediately, but let the session-owned RPC finish before
            // closing its transport. The controller composition itself remains mounted at app root.
            closingSession?.beginUserLogoutRetirement()
            isLoggedIn = false
            autoLoggingIn = false
            session = null
            authError = null
            authResultAdmission.retire()
            val localCleanupFailures = mutableListOf<Throwable>()
            try {
                credentialSnapshot.snapshot()?.let(tokenStore::compareAndClear)
            } catch (failure: Throwable) {
                localCleanupFailures += failure
            } finally {
                credentialSnapshot.clear()
                try {
                    userSession.onAuthFailed(null)
                } catch (failure: Throwable) {
                    localCleanupFailures += failure
                }
            }
            if (localCleanupFailures.isNotEmpty()) {
                closingSession?.recordRetirementFailure(
                    "User logout local cleanup completed with ${localCleanupFailures.size} failure(s)",
                    localCleanupFailures.first(),
                )
            }
            if (closingSession == null) {
                imClient.disconnect()
            } else {
                retiringSession = closingSession
                val retirementJob = controllerScope.launchRetirementWithFallback(
                    fallback = {
                        closingSession.close(
                            reason = SessionEndReason.USER_LOGOUT,
                            disconnectTransport = authGeneration == retirementGeneration,
                        )
                    },
                ) {
                    try {
                        closingSession.completeUserLogoutRetirement {
                            authGeneration == retirementGeneration
                        }.getOrThrow()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Local logout remains terminal offline. The sealed capability has already
                        // closed the raw RPC owner in finally.
                    } finally {
                        if (retiringSession === closingSession) retiringSession = null
                    }
                }
                // Covers a scope cancelled before dispatch/start. Ordinary completion has already
                // closed in the sealed capability's finally; this idempotent fallback then no-ops.
                logoutJob = retirementJob
            }
        },
        onAuthExpired = {
            endAuthenticatedSession(
                message = "认证失效，请重新登录",
                clearStoredLogin = true,
                reason = SessionEndReason.AUTH_REVOKED,
            )
        },
        clearError = { authError = null },
    )
}

/**
 * Enters retirement before the first dispatcher hop and always runs an idempotent close fallback,
 * including when the owning Compose scope was already cancelled before launch.
 */
internal fun kotlinx.coroutines.CoroutineScope.launchRetirementWithFallback(
    fallback: () -> Unit,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
): Job {
    val job = launch(start = CoroutineStart.UNDISPATCHED, block = block)
    job.invokeOnCompletion { runCatching(fallback) }
    return job
}

/**
 * Non-Compose owner for the durable credential snapshot published by the transport EventLoop.
 * Generation checks make a stale controller fail locally even if a TokenStore implementation is
 * accidentally permissive; Compose only reads this holder from its own effect thread.
 */
internal class AuthCredentialSnapshotHolder(
    private val ownerGeneration: Long,
    initial: StoredLogin?,
) {
    private val lock = Any()
    private var value: StoredLogin? = initial?.also(::requireOwned)

    fun publish(next: StoredLogin) = synchronized(lock) {
        requireOwned(next)
        value = next
    }

    fun snapshot(): StoredLogin? = synchronized(lock) { value }

    fun clear(): StoredLogin? = synchronized(lock) {
        val previous = value
        value = null
        previous
    }

    private fun requireOwned(login: StoredLogin) {
        require(login.ownerGeneration == ownerGeneration) {
            "Credential snapshot belongs to a different TokenStore owner"
        }
    }
}

/**
 * Serializes AUTH-result durable commit against logout/disposal. Once retired, callbacks fail
 * before touching TokenStore or UserSession; only an explicit new authentication attempt reopens
 * admission. Transport owner generations separately reject responses from superseded attempts.
 */
internal class AuthResultAdmissionGate(initiallyActive: Boolean = false) {
    private val lock = Any()
    private var active = initiallyActive

    fun begin() = synchronized(lock) { active = true }

    fun retire() = synchronized(lock) { active = false }

    fun <T> replaceAttempt(cleanup: () -> T): T {
        retire()
        return try {
            cleanup()
        } finally {
            begin()
        }
    }

    fun <T> use(block: () -> T): T = synchronized(lock) {
        check(active) { "Authentication result owner is retired" }
        block()
    }

    fun runIfActive(block: () -> Unit): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        block()
        true
    }
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

/** AUTH_FAILED is terminal even before a ClientSession exists; always release the raw socket. */
internal inline fun retireAuthFailureAndDisconnect(
    endSession: () -> Unit,
    disconnectTransport: () -> Unit,
) {
    try {
        endSession()
    } finally {
        disconnectTransport()
    }
}
