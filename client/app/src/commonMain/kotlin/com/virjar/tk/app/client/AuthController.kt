package com.virjar.tk.app.client
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.AuthenticationAttemptAdmission
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.createSession
import com.virjar.tk.shared.client.logUnhandledError
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** 控制器主体只有在阻塞式的持久 owner 认领在 UI 之外完成后才会进入。 */
@Composable
internal fun rememberClaimedAuthController(
    deploymentIdentity: DeploymentIdentity,
    tcpHost: String,
    tcpPort: Int,
    deviceId: String,
    deviceName: String,
    deviceModel: String?,
    deviceFlag: Int,
    createCache: (
        deploymentIdentity: DeploymentIdentity,
        datasetId: String,
        uid: String,
    ) -> LocalCache,
    onAuthenticated: ((ClientSession) -> Unit)?,
    beforeSessionRetirement: (ClientSession, SessionEndReason) -> Unit,
    afterSessionRetirement: (ClientSession, SessionEndReason) -> Unit,
    authenticationAttempts: AuthenticationAttemptAdmission,
    credentialOwner: AuthControllerCredentialOwner,
    ownerClaimLease: AuthCredentialOwnerClaimLease,
    sessionConstructionDispatcher: CoroutineDispatcher,
    runtimeInfo: ClientRuntimeInfo,
    telemetrySpoolRoot: File,
): AuthState {
    // 固定的 credential owner 是传输回调、离线缓存引导和最终退役的非 Compose 线性化边界。
    val userSession = credentialOwner.userSession
    val clientProtocolVersion = com.virjar.tk.protocol.ProtocolVersions.CURRENT_ID

    // 连接层（认证结果回调写入 userSession）
    val imClient = remember(credentialOwner) {
        ImClient(
            onAuthResult = { success, uid, username, name, refreshToken, accessToken, datasetId, failureReason ->
                credentialOwner.acceptAuthResult(
                    success = success,
                    uid = uid,
                    username = username,
                    name = name,
                    refreshToken = refreshToken,
                    accessToken = accessToken,
                    datasetId = datasetId,
                    failureReason = failureReason,
                )
            },
            onAuthenticationFailureObserved = { failure ->
                persistObservedProtocolRefusal(
                    credentialOwner = credentialOwner,
                    clientProtocolVersion = clientProtocolVersion,
                    failure = failure,
                )
            },
            authenticationAttempts = authenticationAttempts,
        )
    }
    val sessionInitialization = remember(credentialOwner, imClient) {
        AuthSessionInitializationGate<ClientSession>()
    }
    val controllerLifetime = remember(credentialOwner, imClient) { AuthControllerLifetime() }
    val rejectedProtocolVersions by credentialOwner.rejectedProtocolVersions.collectAsState()
    val observedProtocolUpgrade = clientProtocolVersion in rejectedProtocolVersions

    val authenticationPresentation = remember(imClient) {
        AuthenticationPresentationAdmission(
            initiallyShowingLogin = !credentialOwner.hasSavedLogin && !observedProtocolUpgrade,
        )
    }
    var publishedWorkspace by remember(imClient) { mutableStateOf<ClientSession?>(null) }
    var authError by remember(imClient) { mutableStateOf<String?>(null) }
    var displayedAuthenticationAttemptFailureReason by remember(imClient) {
        mutableStateOf<String?>(null)
    }
    var requiresProtocolUpgrade by remember(imClient) { mutableStateOf(observedProtocolUpgrade) }
    var autoLoggingIn by remember(imClient) {
        mutableStateOf(credentialOwner.hasSavedLogin && !observedProtocolUpgrade)
    }
    var localSessionBootstrapInProgress by remember(imClient) {
        mutableStateOf(credentialOwner.hasSavedLogin && !observedProtocolUpgrade)
    }
    var session by remember(imClient) { mutableStateOf<ClientSession?>(null) }
    var authGeneration by remember(imClient) { mutableStateOf(0L) }
    var retiringSession by remember(imClient) { mutableStateOf<ClientSession?>(null) }
    var logoutJob by remember(imClient) { mutableStateOf<Job?>(null) }
    val controllerScope = rememberCoroutineScope()
    val userLogoutRetirement = remember(controllerScope, credentialOwner, imClient) {
        AuthUserLogoutRetirement(
            controllerScope = controllerScope,
            identityOwner = credentialOwner,
            disconnectTransport = imClient::disconnect,
        )
    }

    fun publishWorkspace(activeSession: ClientSession) {
        publishedWorkspace = publishAuthenticationWorkspace(
            authenticationPresentation,
            session,
            activeSession,
        )
    }

    fun <T> retireWithPlatformBoundary(
        retiring: ClientSession,
        reason: SessionEndReason,
        retirement: () -> T,
    ): T = withAuthenticatedSessionRetirementBoundary(
        before = { beforeSessionRetirement(retiring, reason) },
        retirement = retirement,
        after = { afterSessionRetirement(retiring, reason) },
        onHookFailure = { stage, failure ->
            retiring.recordRetirementFailure(
                "Platform $stage session-retirement hook failed",
                failure,
            )
        },
    )

    fun beginAuthAttempt(): Throwable? {
        // 在清除临时产物之前，先排空正在提交的 AUTH 回调。准入保持关闭，
        // 直到 EventLoop 安装 B 的载荷和 owner，从而隔离排队的 A 响应。
        return credentialOwner.retireForAuthReplacement {
            // 一次新的登录会在安装其 owner 之前，使尚未完成的最大努力式注销失效。
            val abandonedSession = retiringSession
            retiringSession = null
            authGeneration += 1
            logoutJob?.cancel()
            logoutJob = null
            fun retirePreviousOwners(): Throwable? {
                publishedWorkspace = null
                val drain = AuthControllerRetirementDrain()
                drain.release("abandoned session") {
                    abandonedSession?.close(reason = SessionEndReason.PROCESS_REPLACED)
                }
                drain.release("active session") {
                    session?.close(reason = SessionEndReason.PROCESS_REPLACED)
                }
                session = null
                sessionInitialization.forgetAuthenticatedOwner()
                // 显式的登录/注册提交是用户授权的账号替换，而不是本地失败退役。
                // 在准入新的 AUTH 结果之前先移除旧账号，这样失败的切换就不会在同一图中留下两个 credential owner。
                drain.release("stored login", credentialOwner::clearStoredLogin)
                drain.release("user identity") { credentialOwner.clearUserIdentity(null) }
                // 删除失败绝不能永久性地废掉这个控制器。一次成功的新 AUTH
                // 会在同步开始之前原子性地覆盖同一 owner generation。
                drain.throwIfFatal()
                return drain.firstFailure
            }
            val platformOwner = session ?: abandonedSession
            if (platformOwner == null) {
                retirePreviousOwners()
            } else {
                retireWithPlatformBoundary(
                    platformOwner,
                    SessionEndReason.PROCESS_REPLACED,
                    ::retirePreviousOwners,
                )
            }
        }
    }

    fun endAuthenticatedSession(
        message: String?,
        cause: AuthControllerRetirementCause,
        authResultsAlreadyRetired: Boolean = false,
    ) {
        val reason = cause.sessionEndReason
        val closingRetiring = retiringSession
        val closingSession = session
        fun retireControllerState() {
            // 在修改 TokenStore/UserSession 之前先退役 AUTH 准入。比较并清除之后，
            // 同一 owner 就不可能再复活。
            if (!authResultsAlreadyRetired) credentialOwner.retireAuthResults()
            authGeneration += 1
            logoutJob?.cancel()
            logoutJob = null
            retiringSession = null
            publishedWorkspace = null
            session = null
            sessionInitialization.forgetAuthenticatedOwner()
            autoLoggingIn = false
            authError = message
            if (cause == AuthControllerRetirementCause.PROTOCOL_UPGRADE) {
                authenticationPresentation.retire()
            } else {
                authenticationPresentation.showLogin()
            }

            val drain = AuthControllerRetirementDrain()
            drain.release("retiring session") {
                closingRetiring?.close(reason = reason, disconnectTransport = false)
            }
            drain.release("active session") { closingSession?.close(reason = reason) }
            drain.release("stored login") {
                cause.retireStoredLogin {
                    // uid + token + owner generation 全部匹配才会清除。旧 Activity 的
                    // AUTH_FAILED/401 即使延迟到达，也不能删除新 owner 已确认的凭据。
                    credentialOwner.clearStoredLogin()
                }
            }
            drain.release("user identity") {
                // 控制器可能仍组合在登录界面上，因此不要把上一个账号的身份或
                // bearer 凭据留在其长期存活的 UserSession 中。
                credentialOwner.clearUserIdentity(message)
            }
            drain.diagnose { failureCount, firstFailure ->
                (closingSession ?: closingRetiring)?.recordRetirementFailure(
                    "Authentication retirement completed with $failureCount cleanup failure(s)",
                    firstFailure,
                )
            }
            drain.throwIfFatal()
        }

        val platformOwner = closingSession ?: closingRetiring
        if (platformOwner == null) {
            retireControllerState()
        } else {
            retireWithPlatformBoundary(platformOwner, reason, ::retireControllerState)
        }
    }

    // 销毁重新创建的平台外壳时必须释放其 session/EventLoop，而不是留下一个幽灵客户端。
    DisposableEffect(imClient) {
        onDispose {
            // 在排空可能阻塞的候选构造之前，先退役发布租约。
            controllerLifetime.retire()
            authenticationPresentation.retire()
            val closingRetiring = retiringSession
            val closingSession = session
            fun retireController() {
                val drain = AuthControllerRetirementDrain()
                drain.release("AUTH result admission", credentialOwner::retireAuthResults)
                authGeneration += 1
                drain.release("logout job") { logoutJob?.cancel() }
                logoutJob = null
                drain.release("retiring session") {
                    closingRetiring?.close(reason = SessionEndReason.SHUTDOWN, disconnectTransport = false)
                }
                retiringSession = null
                publishedWorkspace = null
                drain.release("active session") {
                    closingSession?.close(reason = SessionEndReason.SHUTDOWN, disconnectTransport = false)
                }
                session = null
                sessionInitialization.forgetAuthenticatedOwner()
                drain.release("transport", imClient::destroy)
                drain.diagnose { failureCount, firstFailure ->
                    (closingSession ?: closingRetiring)?.recordRetirementFailure(
                        "Controller disposal completed with $failureCount cleanup failure(s)",
                        firstFailure,
                    )
                }
                drain.throwIfFatal()
            }
            val platformOwner = closingSession ?: closingRetiring
            if (platformOwner == null) {
                retireController()
            } else {
                retireWithPlatformBoundary(platformOwner, SessionEndReason.SHUTDOWN, ::retireController)
            }
        }
    }

    fun createOwnedSession(): ClientSession = createSession(
        imClient = imClient,
        userSession = userSession,
        deploymentIdentity = deploymentIdentity,
        createCache = createCache,
        deviceId = deviceId,
        runtimeInfo = runtimeInfo,
        telemetrySpoolRoot = telemetrySpoolRoot,
    )

    // 冷启动先安装 dormant transport owner；LocalCache/session 发布后才允许 DNS/TCP/AUTH，
    // 因此远端可重试失败和网络 deadline 不能抢先终止本地数据库打开。
    LaunchedEffect(credentialOwner, imClient, requiresProtocolUpgrade) {
        // 服务器声明的协议拒绝对于这个确切的二进制协议是持久的。为较新的客户端保留凭据，
        // 但在 Activity/进程重建后或离线期间，绝不挂载被拒绝二进制的缓存图。
        if (requiresProtocolUpgrade) {
            localSessionBootstrapInProgress = false
            return@LaunchedEffect
        }
        val savedLogin = credentialOwner.savedLoginSnapshot()
        if (savedLogin == null) {
            localSessionBootstrapInProgress = false
            return@LaunchedEffect
        }
        localSessionBootstrapInProgress = true
        try {
            fun durableOwnerStillCurrent(): Boolean =
                controllerLifetime.isActive &&
                    !requiresProtocolUpgrade &&
                    credentialOwner.ownsPersistedIdentity(savedLogin.uid)

            bootstrapPersistedLocalSession(
                prepareRemoteOwner = {
                    imClient.prepareAuthentication(
                        savedLogin.uid,
                        savedLogin.refreshToken,
                        deviceId,
                        deviceName,
                        tcpHost,
                        tcpPort,
                        deviceModel,
                        deviceFlag,
                    )
                },
                awaitRemoteOwner = imClient::awaitTransportOwnerStart,
                installLocalSession = {
                    installPersistedClientSession(
                        initialization = sessionInitialization,
                        current = { session },
                        create = ::createOwnedSession,
                        ownerClaimLease = ownerClaimLease,
                        durableOwnerStillCurrent = ::durableOwnerStillCurrent,
                        publish = { session = it },
                        constructionDispatcher = sessionConstructionDispatcher,
                    )
                },
                publishLocalReady = { activeSession ->
                    publishWorkspace(activeSession)
                    autoLoggingIn = false
                    authError = null
                },
                startRemote = { prepared ->
                    ownerClaimLease.publishIfCurrent {
                        durableOwnerStillCurrent() && prepared.start()
                    }
                },
            )
        } catch (failure: Throwable) {
            if (isFatalClientLifecycleFailure(failure)) throw failure
            if (!controllerLifetime.isActive) {
                logUnhandledError("DisposedOfflineSessionBootstrap", failure)
                return@LaunchedEffect
            }
            retireFailedAuthSessionInitialization(
                failure = failure,
                message = "本地数据初始化失败，请重试",
                cause = AuthControllerRetirementCause.OFFLINE_SESSION_INITIALIZATION_FAILURE,
                endAuthenticatedSession = { message, cause ->
                    endAuthenticatedSession(message, cause)
                },
                disconnectTransport = imClient::disconnect,
            )
        } finally {
            localSessionBootstrapInProgress = false
        }
    }

    val connectionState by imClient.state.collectAsState()
    val authenticationFailure by imClient.authenticationFailure.collectAsState()
    val protocolCompatibility by imClient.protocolCompatibility.collectAsState()
    val authenticationAttemptFailure by imClient.authenticationAttemptFailure.collectAsState()

    // 密码/注册的传输失败是可重试的反馈，而不是凭据撤销。
    LaunchedEffect(connectionState, authenticationAttemptFailure) {
        val failure = authenticationAttemptFailure
        if (failure != null && connectionState == ConnectionState.DISCONNECTED) {
            authenticationPresentation.reopenInFlightAttempt()
            displayedAuthenticationAttemptFailureReason = failure.reason
            autoLoggingIn = false
            authError = failure.reason
        } else {
            val displayed = displayedAuthenticationAttemptFailureReason
            displayedAuthenticationAttemptFailureReason = null
            if (displayed != null && authError == displayed) authError = null
        }
    }

    // 来自退役中传输的迟到拒绝是部署事实，而不是账号 owner 事件。
    // 观察存活的 TokenStore 围栏，这样后继的 Activity/窗口就不会仅仅因为它在旧回调到达之前
    // 已认领了凭据而挂载或继续使用被拒绝的二进制。
    LaunchedEffect(observedProtocolUpgrade) {
        if (!observedProtocolUpgrade || requiresProtocolUpgrade) return@LaunchedEffect
        requiresProtocolUpgrade = true
        endAuthenticatedSession(
            message = null,
            cause = AuthControllerRetirementCause.PROTOCOL_UPGRADE,
        )
        imClient.disconnect()
    }

    // 持久化的工作区在本地图完成打开之前一直拥有冷启动。网络 deadline 跨越该边界时处于休眠状态；
    // 一旦图被发布，自动登录的加载已经完成，传输恢复由工作区内部表示。
    LaunchedEffect(
        autoLoggingIn,
        localSessionBootstrapInProgress,
        imClient,
    ) {
        if (!autoLoggingIn || localSessionBootstrapInProgress) return@LaunchedEffect
        val outcome = awaitAutoLoginWatchdog(
            connectionState = imClient.state,
            eventSyncCursor = imClient.eventSyncCursor,
            eventSyncProgress = imClient.eventSyncProgress,
        )
        if (outcome == AutoLoginWatchdogOutcome.AUTHENTICATION_TERMINATED) {
            return@LaunchedEffect
        }
        if (
            autoLoggingIn &&
            publishedWorkspace == null &&
            imClient.state.value != ConnectionState.AUTHENTICATED &&
            imClient.state.value != ConnectionState.AUTH_FAILED
        ) {
            val timeoutMessage = when (outcome) {
                AutoLoginWatchdogOutcome.IDENTITY_TIMEOUT ->
                    "服务器暂时无法连接，请检查网络或稍后重试"
                AutoLoginWatchdogOutcome.SYNC_NO_PROGRESS_TIMEOUT ->
                    "数据同步暂无进展，请检查网络或稍后重试"
                AutoLoginWatchdogOutcome.AUTHENTICATION_TERMINATED ->
                    error("Authentication terminal outcome returned past watchdog guard")
            }
            // 为之后的 retry 保留持久的 refresh 凭据，但在断开连接之前退役每一个
            // 存活的回调/session owner。超时是内存中的终止边界情形。
            endAuthenticatedSession(
                message = timeoutMessage,
                cause = AuthControllerRetirementCause.AUTO_LOGIN_TIMEOUT,
            )
            imClient.disconnect()
        }
    }

    LaunchedEffect(connectionState, authenticationFailure) {
        if (requiresForcedProtocolUpgrade(authenticationFailure)) {
            // 服务器自身落后没有持久围栏，也必须退役当前工作区。关闭本次壳后重新启动，
            // 可保留凭据并重新协商；已淘汰的旧客户端则仍由上面的持久围栏隔断冷启动。
            if (!requiresProtocolUpgrade) {
                requiresProtocolUpgrade = true
                endAuthenticatedSession(
                    message = null,
                    cause = AuthControllerRetirementCause.PROTOCOL_UPGRADE,
                )
                imClient.disconnect()
            }
            return@LaunchedEffect
        }

        fun retireSupersededCredentialOwner(): Boolean {
            // 这个 controller 已被新 Activity/窗口取代。旧 owner 不得覆盖新凭据。
            val staleSession = session
            fun retireStaleOwner() {
                val drain = AuthControllerRetirementDrain()
                publishedWorkspace = null
                autoLoggingIn = false
                authenticationPresentation.retire()
                drain.release("stale session") {
                    staleSession?.close(reason = SessionEndReason.PROCESS_REPLACED)
                }
                session = null
                sessionInitialization.forgetAuthenticatedOwner()
                credentialOwner.forgetCredentialSnapshot()
                drain.release("user identity") { credentialOwner.clearUserIdentity(null) }
                drain.release("transport", imClient::disconnect)
                drain.diagnose { failureCount, firstFailure ->
                    staleSession?.recordRetirementFailure(
                        "Stale controller retirement completed with $failureCount cleanup failure(s)",
                        firstFailure,
                    )
                }
                drain.throwIfFatal()
            }
            if (staleSession == null) {
                retireStaleOwner()
            } else {
                retireWithPlatformBoundary(
                    staleSession,
                    SessionEndReason.PROCESS_REPLACED,
                    ::retireStaleOwner,
                )
            }
            return false
        }

        /** 数据集切换退役：先退役旧数据集全部 owner（保留传输与新凭据），成功返回新 uid，失败终态返回 null。 */
        suspend fun retireSessionForDatasetSwitch(
            admission: AuthenticatedSessionAdmission.ExistingSessionDatasetMismatch,
        ): String? {
            val staleSession = checkNotNull(session)
            try {
                retireWithPlatformBoundary(staleSession, SessionEndReason.PROCESS_REPLACED) {
                    publishedWorkspace = null
                    session = null
                    sessionInitialization.forgetAuthenticatedOwner()
                    staleSession.close(
                        reason = SessionEndReason.PROCESS_REPLACED,
                        disconnectTransport = false,
                    )
                }
            } catch (failure: Throwable) {
                if (isFatalClientLifecycleFailure(failure)) throw failure
                retireFailedAuthSessionInitialization(
                    failure = failure,
                    message = "数据集切换时会话资源清理失败，请重试",
                    cause = AuthControllerRetirementCause.AUTHENTICATED_SESSION_INITIALIZATION_FAILURE,
                    endAuthenticatedSession = { message, cause ->
                        endAuthenticatedSession(message, cause)
                    },
                    disconnectTransport = imClient::disconnect,
                )
                return null
            }
            return admission.uid
        }
        /** 认证准入分派：终局失败完成退役/断开后返回 null；数据集切换先退役旧会话再返回新 uid。 */
        suspend fun admitAuthenticatedSessionUid(): String? {
            return when (val admission = credentialOwner.admitAuthenticatedSession(
                existingSessionUid = session?.ownerUid,
                existingSessionDatasetId = session?.datasetId,
            )) {
                AuthenticatedSessionAdmission.MissingDurableIdentity -> {
                    endAuthenticatedSession(
                        message = "认证响应缺少持久凭据",
                        cause = AuthControllerRetirementCause.MISSING_DURABLE_IDENTITY,
                    )
                    imClient.disconnect()
                    null
                }
                AuthenticatedSessionAdmission.ExistingSessionIdentityMismatch -> {
                    // 刷新/重连回调绝不能重新指向已有的 cache/repository 图。
                    // 在复用意外身份之前，先 fail closed。
                    endAuthenticatedSession(
                        message = "认证身份与当前会话不一致，请重新登录",
                        cause = AuthControllerRetirementCause.SERVER_AUTHENTICATION_REVOKED,
                    )
                    imClient.disconnect()
                    null
                }
                is AuthenticatedSessionAdmission.ExistingSessionDatasetMismatch ->
                    retireSessionForDatasetSwitch(admission)
                AuthenticatedSessionAdmission.SupersededCredentialOwner ->
                    retireSupersededCredentialOwner().let { null }
                is AuthenticatedSessionAdmission.Owned -> admission.uid
            }
        }

        suspend fun ensureSessionForAuthenticatedIdentity(): Boolean {
            requiresProtocolUpgrade = false
            if (!ownerClaimLease.isCurrent()) return retireSupersededCredentialOwner()
            val authenticatedUid = admitAuthenticatedSessionUid() ?: return false

            var localFailureCause =
                AuthControllerRetirementCause.AUTHENTICATED_SESSION_INITIALIZATION_FAILURE
            fun durableOwnerStillCurrent(): Boolean =
                controllerLifetime.isActive &&
                    credentialOwner.ownsPersistedIdentity(authenticatedUid)
            fun ownerStillCurrent(): Boolean =
                ownerClaimLease.isCurrent() && durableOwnerStillCurrent()
            val installation = try {
                withContext(NonCancellable) {
                    sessionInitialization.ensureAuthenticated(
                        current = { session },
                        create = {
                            localFailureCause =
                                AuthControllerRetirementCause.AUTHENTICATED_SESSION_INITIALIZATION_FAILURE
                            withContext(sessionConstructionDispatcher) { createOwnedSession() }
                        },
                        ownerStillCurrent = ::ownerStillCurrent,
                        publishIfOwnerCurrent = { candidate ->
                            ownerClaimLease.publishIfCurrent {
                                if (!durableOwnerStillCurrent()) {
                                    false
                                } else {
                                    session = candidate
                                    true
                                }
                            }
                        },
                        closeConcurrentLoser = { candidate ->
                            withContext(sessionConstructionDispatcher) {
                                candidate.close(
                                    reason = SessionEndReason.PROCESS_REPLACED,
                                    disconnectTransport = false,
                                )
                            }
                        },
                        closeStaleOwner = { candidate ->
                            withContext(sessionConstructionDispatcher) {
                                candidate.close(reason = SessionEndReason.PROCESS_REPLACED)
                            }
                        },
                        onAuthenticated = { active ->
                            onAuthenticated?.let { callback ->
                                localFailureCause =
                                    AuthControllerRetirementCause.PLATFORM_AUTHENTICATED_CALLBACK_FAILURE
                                withContext(sessionConstructionDispatcher) { callback(active) }
                            }
                        },
                    )
                }
            } catch (failure: Throwable) {
                if (isFatalClientLifecycleFailure(failure)) throw failure
                if (!controllerLifetime.isActive) {
                    logUnhandledError("DisposedAuthenticatedSessionBootstrap", failure)
                    return false
                }
                // createSession 会回滚它获取的每一个资源，但它刻意不拥有共享的传输或身份。
                // 组合根负责退役这两者。同样的终止规则也适用于平台创建后回调失败的情形。
                retireFailedAuthSessionInitialization(
                    failure = failure,
                    message = "会话资源初始化失败，请重试",
                    cause = localFailureCause,
                    endAuthenticatedSession = { message, cause ->
                        endAuthenticatedSession(message, cause)
                    },
                    disconnectTransport = imClient::disconnect,
                )
                return false
            }
            if (installation == OfflineSessionInstallation.OwnerLost) {
                if (!controllerLifetime.isActive) return false
                return retireSupersededCredentialOwner()
            }
            authError = null
            return true
        }

        when (connectionState) {
            ConnectionState.SYNCHRONIZING -> {
                // LocalCache/EventProcessor 必须先存在，客户端才能发送其持久化的游标。
                // 首次登录在 SYNC_READY 之前一直停留在加载界面。
                ensureSessionForAuthenticatedIdentity()
            }
            ConnectionState.AUTHENTICATED -> {
                if (ensureSessionForAuthenticatedIdentity()) {
                    publishWorkspace(checkNotNull(session))
                    autoLoggingIn = false
                }
            }
            ConnectionState.AUTH_FAILED -> {
                val retirementCause = authControllerRetirementCause(authenticationFailure)
                val failureMessage = if (
                    retirementCause == AuthControllerRetirementCause.LOCAL_CREDENTIAL_COMMIT_FAILURE
                ) {
                    "本地登录状态保存失败，当前继续使用离线数据"
                } else {
                    userSession.authFailureReason ?: "认证失败"
                }
                val localOwner = session
                val mayContinueOffline = retirementCause.mayContinueOffline(
                    hasLocalSessionOwner = localOwner != null,
                    persistedIdentityOwned = localOwner != null &&
                        ownerClaimLease.isCurrent() &&
                        credentialOwner.ownsPersistedIdentity(localOwner.ownerUid),
                )
                if (mayContinueOffline) {
                    // AUTH_FAILED 对这条 socket 是终止性的，但对持久账号未必。
                    // 保持 cache/repository 图挂载，只移除传输权威；
                    // 之后的进程/retry 可以为同一个已存储的 owner 认证。
                    publishWorkspace(checkNotNull(localOwner))
                    autoLoggingIn = false
                    authError = failureMessage
                    // 维护/连接压力会保留 refresh AUTH 载荷和传输的有界重连 owner。
                    // 本地凭据提交缺陷对该传输是终止性的，必须仍然断开连接，
                    // 但不能卸载恰好被拥有的离线缓存图。
                    if (retirementCause.disconnectAfterOfflineContinuation()) {
                        imClient.disconnect()
                    }
                } else {
                    retireAuthFailureAndDisconnect(
                        endSession = {
                            endAuthenticatedSession(
                                message = failureMessage,
                                cause = retirementCause,
                            )
                        },
                        disconnectTransport = imClient::disconnect,
                    )
                }
            }
            else -> {}
        }
    }

    /** 仅当期望会话仍是当前会话时执行动作并返回 true（会话卫兵）。 */
    fun onCurrentSession(expectedSession: ClientSession, action: () -> Boolean): Boolean =
        if (session === expectedSession) { action(); true } else false

    fun logout() {
        val closingSession = session
        fun retireUserSession() {
            val retirementGeneration = authGeneration + 1
            authGeneration = retirementGeneration
            val retirementJob = userLogoutRetirement.retire(
                sessionOwner = closingSession?.let(::ClientSessionUserLogoutOwner),
                isGenerationCurrent = { authGeneration == retirementGeneration },
                retireLocalSessionState = {
                    publishedWorkspace = null
                    autoLoggingIn = false
                    session = null
                    sessionInitialization.forgetAuthenticatedOwner()
                    authError = null
                    authenticationPresentation.showLogin()
                },
                markRemoteRetirement = { retiringSession = closingSession },
                clearRemoteRetirement = {
                    if (retiringSession === closingSession) retiringSession = null
                },
            )
            // 没有活动 session 的第二次点击绝不能丢弃更早的、仍在进行中的退役句柄；
            // 销毁时仍然需要取消那个 owner。
            if (retirementJob != null) logoutJob = retirementJob
        }
        if (closingSession == null) {
            retireUserSession()
        } else {
            retireWithPlatformBoundary(
                closingSession,
                SessionEndReason.USER_LOGOUT,
                ::retireUserSession,
            )
        }
    }

    fun expireAuthentication() {
        endAuthenticatedSession(
            message = "认证失效，请重新登录",
            cause = AuthControllerRetirementCause.SERVER_AUTHENTICATION_REVOKED,
        )
    }

    val authSubmission = AuthSubmissionCoordinator(
        imClient = imClient,
        credentialOwner = credentialOwner,
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = deviceFlag,
    )

    val authSubmissionActions = AuthSubmissionActions(
        coordinator = authSubmission,
        presentationSubmission = authenticationPresentation.captureSubmission(),
        canSubmit = { !requiresProtocolUpgrade },
        beginReplacement = ::beginAuthAttempt,
        onReplacementAccepted = { cleanupFailure ->
            displayedAuthenticationAttemptFailureReason = null
            authError = cleanupFailure?.let { "旧登录态清理不完整，将在认证成功后覆盖" }
        },
        publishError = { authError = it },
    )
    val presentedSession = requirePublishedWorkspace(
        resourceSession = session,
        publishedWorkspace = publishedWorkspace,
        isActive = ClientSession::isBusinessActive,
    )

    return AuthState(
        autoLoggingIn = autoLoggingIn,
        authError = authError,
        requiresProtocolUpgrade = requiresProtocolUpgrade || observedProtocolUpgrade ||
            requiresForcedProtocolUpgrade(authenticationFailure),
        session = presentedSession,
        connectionState = imClient.state,
        protocolCompatibility = protocolCompatibility,
        onLogin = authSubmissionActions::login,
        onRegister = authSubmissionActions::register,
        onLogout = ::logout,
        onAuthExpired = ::expireAuthentication,
        onLogoutForSession = { expectedSession ->
            onCurrentSession(expectedSession) { logout(); true }
        },
        onAuthExpiredForSession = { expectedSession ->
            onCurrentSession(expectedSession) { expireAuthentication(); true }
        },
        onHttpAuthExpiredForSession = { expectedSession, rejectedAccessToken ->
            credentialOwner.retireForHttpUnauthorized(
                rejectedAccessToken = rejectedAccessToken,
                ownerStillCurrent = { ownerClaimLease.isCurrent() && session === expectedSession },
                retirement = {
                    endAuthenticatedSession(
                        message = "认证失效，请重新登录",
                        cause = AuthControllerRetirementCause.SERVER_AUTHENTICATION_REVOKED,
                        authResultsAlreadyRetired = true,
                    )
                },
            )
        },
        clearError = {
            displayedAuthenticationAttemptFailureReason = null
            authError = null
        },
    )
}
