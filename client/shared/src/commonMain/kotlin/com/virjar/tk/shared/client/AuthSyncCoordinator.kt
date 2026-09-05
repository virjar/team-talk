package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.ProtocolNegotiation
import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateRequestPayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 拥有认证职责，并协调 AUTH 之后的持久事件同步器。
 *
 * 该协调器与 Netty 无关。生产环境从活跃的 transport EventLoop 调用所有可变方法；因此确定性测试
 * 可以用单线程测试作用域驱动同一状态机。认证材料与终态存放在这里；投影 ownership 与游标状态
 * 存放在 [EventSyncCoordinator]。transport 与包路由保留单一门面，只接收只读回调。
 */
internal class AuthSyncCoordinator(
    private val connectionState: () -> ConnectionState,
    private val isConnectionGenerationCurrent: (Long) -> Boolean,
    private val transitionTo: (ConnectionState) -> Unit,
    private val connectionScope: () -> CoroutineScope?,
    private val writeProtocol: (IProto) -> Boolean,
    private val closeTransport: (reason: String, cause: Throwable?) -> Unit,
    private val retryTransport: (connectionGeneration: Long, reason: String) -> Unit,
    private val onAuthenticationFailureObserved: ((AuthenticationFailure) -> Unit)?,
    private val onAuthenticationAccepted: () -> Unit,
    private val onAuthResult: ((success: Boolean, uid: String?, username: String?, name: String?, refreshToken: String?, accessToken: String?, datasetId: String?, failureReason: String?) -> Unit)?,
    private val authenticationAttempts: AuthenticationAttemptAdmission =
        AuthenticationAttemptAdmission(),
    private val onAuthenticationSending: (connectionGeneration: Long, correlationId: String) -> Unit = { _, _ -> },
    private val onAuthenticationContext: (connectionGeneration: Long, context: ConnectionTraceContext) -> Boolean =
        { _, _ -> false },
    private val newCorrelationId: () -> String = { UUID.randomUUID().toString() },
    private val supportedProtocol: ProtocolRange = ProtocolVersions.SUPPORTED,
) {
    private val logger = PlatformOnlyTkLogger("AuthSyncCoordinator")
    private val eventSync = EventSyncCoordinator(
        connectionState = connectionState,
        transitionTo = transitionTo,
        connectionScope = connectionScope,
        writeProtocol = writeProtocol,
        closeTransport = closeTransport,
        onSynchronizationReady = onAuthenticationAccepted,
    )

    /**
     * 一次逻辑认证的完整 owner。凭据、期望 uid 和精确租约一起安装/释放，不能各自成为独立状态。
     * 普通断线保留 refresh owner；显式替代、一次性登录失败或权威拒绝会释放整个对象。
     */
    private class PendingAuthentication(
        val credentials: PendingAuthenticationCredentials,
        val expectedUid: String?,
        val attempt: AuthenticationAttemptLease,
    ) {
        val canRetryServerFailure: Boolean
            get() = credentials.authType == 2 && !expectedUid.isNullOrBlank()

        fun authenticated(uid: String, refreshToken: String): PendingAuthentication =
            PendingAuthentication(
                credentials = credentials.copy(
                    authType = 2,
                    refreshToken = refreshToken,
                    username = null,
                    password = null,
                    name = null,
                ),
                expectedUid = uid,
                attempt = attempt,
            )
    }

    private var pendingAuthentication: PendingAuthentication? = null

    /** 终态的 AUTH/一次性 transport 边界会一直持续，直到出现一次显式的新认证尝试。 */
    private var authenticationTerminal = false

    private var negotiatingConnectionGeneration: Long? = null
    private var negotiatedConnectionGeneration: Long? = null
    private val _protocolCompatibility = MutableStateFlow<ProtocolCompatibility?>(null)
    val protocolCompatibility: StateFlow<ProtocolCompatibility?> = _protocolCompatibility.asStateFlow()

    private val _authenticationFailure = MutableStateFlow<AuthenticationFailure?>(null)
    val authenticationFailure: StateFlow<AuthenticationFailure?> =
        _authenticationFailure.asStateFlow()

    private val _authenticationAttemptFailure = MutableStateFlow<AuthenticationAttemptFailure?>(null)
    val authenticationAttemptFailure: StateFlow<AuthenticationAttemptFailure?> =
        _authenticationAttemptFailure.asStateFlow()

    /** 持久投影进度；当本 transport 处于同步之外时为 -1。 */
    val eventSyncCursor: StateFlow<Long> = eventSync.cursor

    /** 进程内单调的检查点页进度；绝不是持久化的线格式权威。 */
    val eventSyncProgress: StateFlow<Long> = eventSync.progress

    fun prepareAuthentication(auth: AuthRequestPayload, expectedUid: String? = null) {
        val attempt = authenticationAttempts.reserve()
        check(prepareAuthentication(auth, expectedUid, attempt)) {
            "New authentication attempt was retired before installation"
        }
    }

    /** EventLoop 安装入口，用于 [ImClient.connectAndAuth] 同步预留的租约。 */
    fun prepareAuthentication(
        auth: AuthRequestPayload,
        expectedUid: String?,
        attempt: AuthenticationAttemptLease,
        startTransport: () -> Unit = {},
    ): Boolean = prepareAuthentication(
        credentials = PendingAuthenticationCredentials.from(auth),
        expectedUid = expectedUid,
        attempt = attempt,
        startTransport = startTransport,
    )

    internal fun prepareAuthentication(
        credentials: PendingAuthenticationCredentials,
        expectedUid: String?,
        attempt: AuthenticationAttemptLease,
        startTransport: () -> Unit = {},
    ): Boolean {
        var transportStartFailure: Throwable? = null
        val admitted = attempt.runIfActive {
            require(expectedUid == null || expectedUid.isNotBlank()) {
                "Expected auth uid must not be blank"
            }
            pendingAuthentication = PendingAuthentication(credentials, expectedUid, attempt)
            authenticationTerminal = false
            _authenticationFailure.value = null
            _authenticationAttemptFailure.value = null
            _protocolCompatibility.value = null
            try {
                // owner 与连接代际在此精确尝试租约释放之前已可见。因此断开连接要么捕获 A 的全部，
                // 要么捕获 B 的全部。
                startTransport()
            } catch (failure: Throwable) {
                clearAuthenticationIfExact(attempt)
                authenticationTerminal = true
                _authenticationAttemptFailure.value = AuthenticationAttemptFailure(
                    kind = AuthenticationAttemptFailureKind.TRANSPORT_UNAVAILABLE,
                    reason = EXPLICIT_AUTH_TRANSPORT_FAILURE_REASON,
                )
                transportStartFailure = failure
            }
        }
        val failure = transportStartFailure ?: return admitted
        // runIfActive 已释放其操作深度，因此精确退役不会触发重入关闭守卫。如果 B 已经预留，
        // 则 B 获胜，这里仍然只是 cleanup(A)。
        attempt.retireIfActive {}
        throw failure
    }

    /** 逻辑 transport owner 与其 AUTH 尝试之间的、由 EventLoop 持有的不透明绑定。 */
    fun currentAuthenticationAttempt(): AuthenticationAttemptLease? =
        pendingAuthentication?.attempt

    /** 每条 TCP 连接先协商版本；这里不读取、更不会写出密码或 refresh token。 */
    fun beginProtocolNegotiation(connectionGeneration: Long): Boolean {
        negotiatingConnectionGeneration = connectionGeneration
        negotiatedConnectionGeneration = null
        return writeProtocol(
            ProtocolNegotiateRequestPayload(supportedProtocol, com.virjar.tk.shared.TeamTalkBuild.RELEASE_VERSION),
        )
    }

    fun handleProtocolNegotiationResponse(
        connectionGeneration: Long,
        response: ProtocolNegotiateResponsePayload,
    ) {
        if (!isConnectionGenerationCurrent(connectionGeneration)) return
        if (connectionState() != ConnectionState.CONNECTED ||
            negotiatingConnectionGeneration != connectionGeneration
        ) {
            closeTransport("Unexpected protocol negotiation response", null)
            return
        }
        try {
            ProtocolNegotiation.requireValidResponse(supportedProtocol, response)
        } catch (failure: IllegalArgumentException) {
            // 结构错误或矛盾的响应不是权威版本拒绝，不得写入持久升级围栏。
            closeTransport("Invalid protocol negotiation response", failure)
            return
        }
        negotiatingConnectionGeneration = null
        val compatibility = ProtocolCompatibility(
            supportedProtocol, response.server, response.negotiated, response.code,
        )
        _protocolCompatibility.value = compatibility
        if (!compatibility.requiresUpgrade) {
            negotiatedConnectionGeneration = connectionGeneration
            sendAuthenticationIfActive(connectionGeneration, writeProtocol)
            return
        }

        val pending = pendingAuthentication
        val reason = when (response.code) {
            ProtocolNegotiateResponsePayload.CODE_CLIENT_TOO_OLD ->
                "当前客户端版本过低，请升级客户端后重试"
            ProtocolNegotiateResponsePayload.CODE_SERVER_TOO_OLD ->
                "服务器版本过低，请联系管理员升级服务器或使用兼容的客户端"
            else -> "客户端与服务器主版本不兼容，请使用兼容的客户端后重试"
        }
        val failure = AuthenticationFailure(
            AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED,
            reason,
            requiresClientUpgrade =
                response.code == ProtocolNegotiateResponsePayload.CODE_CLIENT_TOO_OLD ||
                    (response.code == ProtocolNegotiateResponsePayload.CODE_MAJOR_UNSUPPORTED &&
                        supportedProtocol.major < response.server.major),
        )
        // 即使登录尝试刚被替代，也先通知部署观察者。只有服务器淘汰旧客户端的事实可持久化；
        // 服务器自身落后只终止本次连接，让服务器升级后的新尝试能够重新协商。
        observeAuthenticationFailure(failure)
        if (!isConnectionGenerationCurrent(connectionGeneration) || pendingAuthentication !== pending) return
        fun rejectCurrent() {
            endAuthentication()
            _authenticationFailure.value = failure
            transitionTo(ConnectionState.AUTH_FAILED)
            onAuthResult?.invoke(false, null, null, null, null, null, null, reason)
            closeTransport("Protocol negotiation rejected", null)
        }
        if (pending == null) rejectCurrent() else pending.attempt.runIfActive { rejectCurrent() }
    }

    /**
     * 在其精确尝试租约下借用并写出当前的 AUTH 载荷。因此退役与把密码/refresh token 交给 socket
     * 是一条可线性化的边：一旦退役返回，任何延迟的 TCP-ready 回调都不可能再发送该尝试的凭据。
     */
    fun sendAuthenticationIfActive(
        connectionGeneration: Long,
        write: (AuthRequestPayload) -> Boolean,
    ): Boolean {
        ConnectionTraceContextPolicy.requirePositive(
            connectionGeneration,
            "auth.connectionGeneration",
        )
        val pending = pendingAuthentication ?: return false
        var sent = false
        pending.attempt.runIfActive {
            val correlationId = newCorrelationId()
            ConnectionTraceContextPolicy.requireToken(correlationId, "auth.correlationId")
            val auth = pending.credentials.toWirePayload(correlationId, connectionGeneration)
            onAuthenticationSending(connectionGeneration, correlationId)
            sent = write(auth)
        }
        return sent
    }

    fun isAuthenticationTerminal(): Boolean = authenticationTerminal

    /**
     * 处理 AUTH 尚未产生响应前失败的 TCP 尝试或失活通道。
     *
     * 密码与注册材料是一次性的：消费精确租约、释放每一个秘密引用、发布可重试的 UI 结果，并停止
     * 这个逻辑 transport owner。持久刷新凭据（authType=2）保留现有的重连 owner。普通的未认证
     * 连接没有尝试绑定，保留其历史上的重连行为。
     */
    fun onAuthenticationTransportAttemptEnded(
        attempt: AuthenticationAttemptLease?,
    ): Boolean {
        if (attempt == null) return true
        val pending = pendingAuthentication?.takeIf { it.attempt === attempt } ?: return false

        if (pending.credentials.authType == 2) {
            if (attempt.isActive()) return true
            clearAuthenticationIfExact(attempt)
            return false
        }

        val consumed = attempt.retireIfActive {
            if (pendingAuthentication?.attempt !== attempt) return@retireIfActive
            clearAuthenticationIfExact(attempt)
            authenticationTerminal = true
            _authenticationAttemptFailure.value = AuthenticationAttemptFailure(
                kind = AuthenticationAttemptFailureKind.TRANSPORT_UNAVAILABLE,
                reason = EXPLICIT_AUTH_TRANSPORT_FAILURE_REASON,
            )
        }
        if (!consumed) {
            // 在 A 的 transport 回调排队期间，B 同步预留了准入。只丢弃 A 持有的 EventLoop 引用；
            // B 的延迟安装者依然完全权威。
            clearAuthenticationIfExact(attempt)
        }
        return false
    }

    /** 主动断开/销毁会释放精确 transport 的凭据，而不产生 UI 失败。 */
    fun onAuthenticationTransportRetired(attempt: AuthenticationAttemptLease?) {
        if (attempt == null || pendingAuthentication?.attempt !== attempt) return
        val consumed = attempt.retireIfActive {
            if (clearAuthenticationIfExact(attempt)) {
                _authenticationAttemptFailure.value = null
            }
        }
        if (!consumed) {
            // 后继者已拥有准入；在释放已退役 transport 的过期载荷引用时，绝不清除后继者可见的
            // 失败/终态状态。
            clearAuthenticationIfExact(attempt)
        }
    }

    fun installEventSync(
        owner: Any,
        expectedUid: String?,
        wireAdmission: WireSendAdmission,
        datasetId: () -> String,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        applyCheckpoint: suspend (String, reportProgress: () -> Unit) -> Long,
    ) = eventSync.install(
        owner = owner,
        expectedUid = expectedUid,
        wireAdmission = wireAdmission,
        datasetId = datasetId,
        cursor = cursor,
        processBatch = processBatch,
        applyCheckpoint = applyCheckpoint,
    )

    fun removeEventSync(owner: Any) {
        eventSync.remove(owner)
    }

    fun isEventSyncOwner(owner: Any): Boolean = eventSync.isOwner(owner)

    fun closeForEventResync(reason: String, cause: Throwable? = null) {
        closeTransport(reason, cause)
    }

    fun handleAuthResponse(connectionGeneration: Long, response: AuthResponsePayload) {
        if (!isConnectionGenerationCurrent(connectionGeneration)) return
        if (negotiatedConnectionGeneration != connectionGeneration) {
            closeTransport("AUTH response arrived before successful protocol negotiation", null)
            return
        }
        // 在调用任意部署观察者之前冻结该包的精确尝试。观察者可能在同一 EventLoop 上同步安装 B。
        val pending = pendingAuthentication
        val deploymentFailure = response.toAuthenticationFailure()
        if (
            connectionState() == ConnectionState.CONNECTED &&
            deploymentFailure?.kind == AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED
        ) {
            // 协议兼容性属于二进制+部署，而不是账号尝试。该包已通过 transport 代际/通道校验，
            // 因此即使在 A 退役前刚排队的响应也必须隔断 B 及后继 UI owner。
            observeAuthenticationFailure(deploymentFailure)
        }
        var handled = false
        val admitted = pending?.attempt?.runIfActive {
            if (
                pendingAuthentication === pending &&
                isConnectionGenerationCurrent(connectionGeneration)
            ) {
                handled = true
                handleAdmittedAuthResponse(pending, connectionGeneration, response)
            }
        } == true && handled
        if (!admitted) {
            // 整个响应——不只是凭据持久化——都已过期。不要发布一条 AUTH_FAILED 边，那可能会在
            // 替代尝试排队的安装之前就把它退役。
            retryTransport(
                connectionGeneration,
                "Discarding response from a retired authentication attempt",
            )
        }
    }

    private fun handleAdmittedAuthResponse(
        pending: PendingAuthentication,
        connectionGeneration: Long,
        response: AuthResponsePayload,
    ) {
        if (connectionState() != ConnectionState.CONNECTED) {
            closeForEventResync("Unexpected AUTH response in state=${connectionState()}")
            return
        }
        if (response.code == AuthResponsePayload.CODE_OK) {
            acceptAuthentication(pending, connectionGeneration, response)
        } else {
            rejectAuthentication(pending, connectionGeneration, response)
        }
    }

    /** 校验完整身份 → 提交调用方凭据 → 启动同步；任何一步失败都不能越过下一步。 */
    private fun acceptAuthentication(
        pending: PendingAuthentication,
        connectionGeneration: Long,
        response: AuthResponsePayload,
    ) {
        val uid = response.uid?.takeIf(String::isNotBlank)
        val username = response.username?.takeIf(String::isNotBlank)
        val name = response.name?.takeIf(String::isNotBlank)
        val refreshToken = response.refreshToken?.takeIf(String::isNotBlank)
        val accessToken = response.accessToken?.takeIf(String::isNotBlank)
        val datasetId = response.datasetId?.takeIf(String::isNotBlank)
        if (
            uid == null || username == null || name == null || refreshToken == null ||
            accessToken == null || datasetId == null
        ) {
            rejectInvalidIdentity("服务器认证成功响应缺少必需身份或令牌字段")
            return
        }
        val expectedAuthUid = pending.expectedUid
        if (expectedAuthUid != null && expectedAuthUid != uid) {
            rejectInvalidIdentity("认证响应 uid 与 refresh credential owner 不一致")
            closeTransport("Authentication uid rejected by credential owner", null)
            return
        }
        val expectedProjectionUid = eventSync.expectedUid
        if (expectedProjectionUid != null && expectedProjectionUid != uid) {
            rejectInvalidIdentity("认证身份与已安装的事件投影 owner 不一致")
            closeTransport("Authentication uid rejected by event projection owner", null)
            return
        }
        _authenticationFailure.value = null
        // 登录/注册凭据是一次性的。成功之后每次网络重连都使用服务器确认的稳定设备 refresh
        // bearer，而不再保留密码。
        pendingAuthentication = pending.authenticated(uid, refreshToken)
        try {
            onAuthResult?.invoke(
                true,
                uid,
                username,
                name,
                refreshToken,
                accessToken,
                datasetId,
                null,
            )
        } catch (failure: Exception) {
            // 凭据准入是 AUTH 接受的一部分。无法提交已轮换持久凭据的客户端绝不能带着
            // 不可用的 token 进入 sync/ready。
            endAuthentication()
            transitionTo(ConnectionState.AUTH_FAILED)
            closeTransport("Authentication credential admission failed", failure)
            return
        }
        response.connectionTraceContext?.let { context ->
            if (!onAuthenticationContext(connectionGeneration, context)) {
                logger.trace(
                    "Discarding AUTH trace context outside the current connection identity",
                )
            }
        }
        eventSync.beginAuthenticatedAttempt(datasetId)
        logger.trace(
            "Identity authenticated; synchronizing uid=${response.uid}, " +
                "username=${response.username}",
        )
    }

    /** 服务器拒绝只有对已绑定账号的 refresh owner 才能触发自动重连。 */
    private fun rejectAuthentication(
        pending: PendingAuthentication,
        connectionGeneration: Long,
        response: AuthResponsePayload,
    ) {
        val failure = checkNotNull(response.toAuthenticationFailure())
        // 只有持久刷新凭据才能搭上受限重连 owner。在服务器背压响应之后重放密码/注册载荷会
        // 无限期保留一次性秘密，并把一次显式用户尝试变成后台登录循环。
        val retryable = failure.kind.isRetryableServerState && pending.canRetryServerFailure
        if (!retryable) {
            endAuthentication()
        }
        authenticationTerminal = !retryable
        if (failure.kind != AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED) {
            // 账号级服务器失败停留在精确尝试租约内部。普通观察者/存储失败会在不隐藏权威
            // 响应的情况下被诊断出来。
            observeAuthenticationFailure(failure)
        }
        _authenticationFailure.value = failure
        transitionTo(ConnectionState.AUTH_FAILED)
        onAuthResult?.invoke(false, null, null, null, null, null, null, failure.reason)
        if (retryable) {
            // 保留刷新载荷与逻辑 transport owner。关闭此尝试会进入 TransportConnectionOwner
            // 已有的受限重连调度；显式 logout/销毁仍然会取消该 owner 及所有排队的重试。
            retryTransport(
                connectionGeneration,
                "Retryable authentication failure: ${failure.kind}",
            )
        }
        // 服务器原因刻意不记入日志：认证诊断绝不能回显攻击者可控的、或意外带有凭据的文本。
        logger.trace("Auth failed (retryable=$retryable): code=${response.code}")
    }

    private fun rejectInvalidIdentity(reason: String) {
        endAuthentication()
        _authenticationFailure.value = AuthenticationFailure(AuthenticationFailureKind.REJECTED, reason)
        transitionTo(ConnectionState.AUTH_FAILED)
        onAuthResult?.invoke(false, null, null, null, null, null, null, reason)
    }

    private fun endAuthentication() {
        pendingAuthentication = null
        authenticationTerminal = true
    }

    private fun observeAuthenticationFailure(failure: AuthenticationFailure) {
        try {
            onAuthenticationFailureObserved?.invoke(failure)
        } catch (observerFailure: Exception) {
            logger.fault("Authentication failure observer failed", observerFailure)
        }
    }

    fun handleSyncBatch(batch: SyncBatchPayload) {
        eventSync.handleBatch(batch)
    }

    /** 最大尺寸的持久事件在重放期间可能作为独立的 NOTIFY 发送。 */
    fun handleSyncEvent(event: NotifyPayload) {
        eventSync.handleEvent(event)
    }

    fun handleSyncReady() {
        eventSync.handleReady()
    }

    fun handleSyncReset(payload: SyncResetPayload) {
        eventSync.handleReset(payload)
    }

    /** 每当一次尝试被取代或断开时，由 transport owner 调用一次。 */
    fun onTransportDisconnected() {
        negotiatingConnectionGeneration = null
        negotiatedConnectionGeneration = null
        eventSync.onTransportDisconnected()
    }

    private fun clearAuthenticationIfExact(attempt: AuthenticationAttemptLease): Boolean {
        if (pendingAuthentication?.attempt !== attempt) return false
        pendingAuthentication = null
        return true
    }

}

private const val EXPLICIT_AUTH_TRANSPORT_FAILURE_REASON = "网络连接失败，请重试登录"
