package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 窄的出站消息 transport 接缝；生产实现由 [TransportConnectionOwner] 拥有。 */
internal interface MessageSendTransport {
    val currentOwnerGeneration: Long
    val currentConnectionGeneration: Long
    val coroutineScope: CoroutineScope?

    fun sendNowIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        proto: IProto,
    ): Boolean
}

/**
 * 逻辑 owner 已安装、但端点尚未解析或连接的 refresh 认证的一次性能力。
 *
 * 因此调用方可以在 [start] 让任何远程结果可观察之前构造并发布其持久化的本地工作区。放弃该能力
 * 是安全的：普通的 ImClient disconnect/destroy 退役会释放已准备的 AUTH 尝试。
 */
class PreparedAuthentication internal constructor(
    private val startPrepared: () -> Unit,
) {
    private val lock = Any()
    private var started = false

    /** 只对消费该能力的那次调用返回 true。 */
    fun start(): Boolean = synchronized(lock) {
        if (started) return@synchronized false
        started = true
        startPrepared()
        true
    }
}

/**
 * SDK TCP facade。传输、认证同步、入站路由和 ACK 等待分别由单一 owner 管理。
 *
 * 线程模型：
 * - 单线程 Netty 4.2 EventLoop（MultiThreadIoEventLoopGroup + NioIoHandler），所有状态操作串行
 * - Pipeline 事件驱动：channelRead / channelInactive / userEventTriggered
 * - scope dispatcher = eventLoop，协程也在同一线程执行
 *
 * 心跳：IdleStateHandler(writerIdle=30s, readerIdle=90s)
 * - 写空闲 30s → 自动发 PingSignal
 * - 读空闲 90s → 关闭连接 → channelInactive → 自动重连
 *
 * 重连：普通连接与 durable refresh 自动重连，使用 1s→2s→4s→8s→16s→30s 上限内的指数抖动退避。
 * 登录/注册口令只允许一个传输尝试；连接失败或响应前断线后释放口令并由用户重试。
 *
 * @param onAuthResult 认证结果回调（success, uid, username, name, refreshToken, accessToken,
 * datasetId, failureReason）。
 *        ImClient 不持有用户身份（三级状态隔离），认证结果通过此回调传给 UserSession。
 */
class ImClient(
    private val host: String = "",
    private val port: Int = 0,
    private val onAuthResult: ((success: Boolean, uid: String?, username: String?, name: String?, refreshToken: String?, accessToken: String?, datasetId: String?, failureReason: String?) -> Unit)? = null,
    private val onAuthenticationFailureObserved: ((AuthenticationFailure) -> Unit)? = null,
    private val authenticationAttempts: AuthenticationAttemptAdmission =
        AuthenticationAttemptAdmission(),
    supportedProtocol: ProtocolRange = ProtocolVersions.SUPPORTED,
) {
    internal constructor(messageTransport: MessageSendTransport) : this() {
        messageTransportOverride = messageTransport
    }

    private val standaloneAckOwner = Any()
    private val logger = PlatformOnlyTkLogger("ImClient")
    private val transport: TransportConnectionOwner
    private var messageTransportOverride: MessageSendTransport? = null
    private val messageTransport: MessageSendTransport
        get() = messageTransportOverride ?: transport
    private val router: PacketRouter
    private val connectionTraceContextOwner = ClientConnectionTraceContext()
    private val authSync = AuthSyncCoordinator(
        connectionState = { transport.state.value },
        isConnectionGenerationCurrent = { generation ->
            transport.currentConnectionGeneration == generation
        },
        transitionTo = { next -> transport.transitionTo(next) },
        connectionScope = { transport.coroutineScope },
        writeProtocol = { proto -> transport.writeProtocolNow(proto) },
        closeTransport = { reason, cause -> transport.closeForRecoveryNow(reason, cause) },
        retryTransport = { generation, reason ->
            transport.retryAuthenticationIfCurrent(generation, reason)
        },
        onAuthenticationFailureObserved = onAuthenticationFailureObserved,
        onAuthenticationAccepted = { transport.onAuthenticationAccepted() },
        onAuthResult = onAuthResult,
        authenticationAttempts = authenticationAttempts,
        onAuthenticationSending = connectionTraceContextOwner::onAuthenticationSending,
        onAuthenticationContext = connectionTraceContextOwner::acceptAuthenticationContext,
        supportedProtocol = supportedProtocol,
    )

    init {
        router = PacketRouter(
            connectionState = { transport.state.value },
            handleAuthResponse = authSync::handleAuthResponse,
            handleProtocolNegotiationResponse = authSync::handleProtocolNegotiationResponse,
            handleSyncBatch = authSync::handleSyncBatch,
            handleSyncEvent = authSync::handleSyncEvent,
            handleSyncReady = authSync::handleSyncReady,
            handleSyncReset = authSync::handleSyncReset,
            writeControl = { proto -> transport.sendNow(proto) },
            closeTransport = { reason -> transport.closeForRecoveryNow(reason) },
            handleConnectionTraceContext = connectionTraceContextOwner::acceptUpdate,
        )
        transport = TransportConnectionOwner(
            initialHost = host,
            initialPort = port,
            beginProtocolNegotiation = authSync::beginProtocolNegotiation,
            currentAuthenticationAttempt = authSync::currentAuthenticationAttempt,
            onAuthenticationTransportAttemptEnded =
                authSync::onAuthenticationTransportAttemptEnded,
            onAuthenticationTransportRetired = authSync::onAuthenticationTransportRetired,
            authenticationTerminal = authSync::isAuthenticationTerminal,
            routePacket = router::route,
            onTransportDisconnected = {
                connectionTraceContextOwner.clear()
                authSync.onTransportDisconnected()
                router.onTransportDisconnected()
            },
        )
    }

    internal val currentTransportOwnerGeneration: Long
        get() = transport.currentOwnerGeneration

    internal val currentConnectionGeneration: Long
        get() = transport.currentConnectionGeneration

    val state: StateFlow<ConnectionState>
        get() = transport.state

    val authenticationFailure: StateFlow<AuthenticationFailure?>
        get() = authSync.authenticationFailure

    val protocolCompatibility: StateFlow<ProtocolCompatibility?>
        get() = authSync.protocolCompatibility

    val authenticationAttemptFailure: StateFlow<AuthenticationAttemptFailure?>
        get() = authSync.authenticationAttemptFailure

    /** 服务器为当前物理连接签发的 trace 身份；日志中的标识符会被脱敏。 */
    val connectionTraceContext: StateFlow<ConnectionTraceContext?>
        get() = connectionTraceContextOwner.state

    /** 断开或过期之后返回 null，而不暴露任何可变的 trace owner 状态。 */
    fun connectionTraceContextSnapshot(
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ConnectionTraceContext? = connectionTraceContextOwner.snapshot(nowEpochMs)

    val packets: Flow<IProto>
        get() = router.packets

    internal val routedPackets: Flow<RoutedPacket>
        get() = router.routedPackets

    internal val transportDisconnectEpoch: StateFlow<Long>
        get() = router.transportDisconnectEpoch

    /** 暴露连接级 scope 供 RpcClient / EventProcessor 复用。 */
    val coroutineScope: CoroutineScope?
        get() = transport.coroutineScope

    val eventSyncCursor: StateFlow<Long>
        get() = authSync.eventSyncCursor

    /** 进程内检查点页脉冲；与 [eventSyncCursor] 不同，这不是持久权威。 */
    val eventSyncProgress: StateFlow<Long>
        get() = authSync.eventSyncProgress

    /**
     * 只等待逻辑 transport 租约被安装，而不等待 DNS、TCP 或认证。客户端组合根使用此边界把离线
     * LocalCache 会话绑定到将执行后台 refresh 认证的那个精确 owner 代际。
     */
    suspend fun awaitTransportOwnerStart() {
        transport.ownerGenerationState.first { it > 0L }
    }

    internal fun installEventSync(
        owner: Any,
        expectedUid: String?,
        wireAdmission: WireSendAdmission,
        datasetId: () -> String,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        applyCheckpoint: suspend (String, reportProgress: () -> Unit) -> Long,
    ) {
        transport.execute {
            authSync.installEventSync(
                owner,
                expectedUid,
                wireAdmission,
                datasetId,
                cursor,
                processBatch,
                applyCheckpoint,
            )
        }
    }

    internal fun removeEventSync(owner: Any) {
        transport.execute { authSync.removeEventSync(owner) }
    }

    /** 损坏的投影必须从其最后一个持久游标重连，绝不能跳过该事件。 */
    internal fun closeForEventResync(
        owner: Any,
        connectionGeneration: Long,
        reason: String,
        cause: Throwable? = null,
    ) {
        transport.execute {
            if (authSync.isEventSyncOwner(owner)) {
                transport.closeForRecoveryIfCurrent(connectionGeneration, reason, cause)
            }
        }
    }

    // ── 公共 API ──

    /**
     * 原子化认证连接：设置 pendingAuth + connect 在同一个 EventLoop 任务内完成。
     *
     * 消除协程线程与 EventLoop 的竞态：调用方在协程线程上构造 AuthRequestPayload（CPU 工作），
     * 然后调此方法——payload 已构造完毕，此方法内不做 CPU 工作，直接投递一个 EventLoop 任务，
     * 在任务内先设 pendingAuth 再 createAndConnect。TCP 回调一定排在 pendingAuth 设置之后。
     */
    fun connectAndAuth(
        auth: AuthRequestPayload,
        host: String,
        port: Int,
        expectedUid: String? = null,
    ) = connectAndAuthCredentials(
        credentials = PendingAuthenticationCredentials.from(auth),
        host = host,
        port = port,
        expectedUid = expectedUid,
    )

    private fun connectAndAuthCredentials(
        credentials: PendingAuthenticationCredentials,
        host: String,
        port: Int,
        expectedUid: String? = null,
    ) {
        require(credentials.authType != 2 || !expectedUid.isNullOrBlank()) {
            "Refresh authentication must bind its expected uid"
        }
        // 预留是同步的：在 B 的安装任务入队之前，已在 EventLoop 排队的 A 响应就已失效。
        // 该任务携带 B 的精确一次性能力。
        val attempt = authenticationAttempts.reserve()
        logger.trace("connectAndAuth: host=$host, port=$port, authType=${credentials.authType}")
        transport.connect(
            host = host,
            port = port,
            jitterSeed = stableReconnectJitterSeed(credentials.deviceId),
        ) { startTransport ->
            authSync.prepareAuthentication(credentials, expectedUid, attempt, startTransport)
        }
    }

    fun connect(host: String = transport.connectHost, port: Int = transport.connectPort) {
        logger.trace("connect: host=$host, port=$port")
        transport.connect(host, port)
    }

    fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        host: String = transport.connectHost,
        port: Int = transport.connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ) {
        AuthRules.validateLogin(username, password)
        AuthRules.validateDevice(deviceId, deviceName, deviceModel, deviceFlag)
        val auth = PendingAuthenticationCredentials(
            authType = 0,
            username = username,
            password = password,
            name = null,
            refreshToken = null,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            deviceFlag = deviceFlag,
        )
        logger.trace("login requested: username=$username")
        // pendingAuth + connect 原子化，消除协程/EventLoop 竞态
        connectAndAuthCredentials(auth, host, port)
    }

    fun register(
        username: String,
        password: String,
        name: String,
        deviceId: String,
        deviceName: String,
        host: String = transport.connectHost,
        port: Int = transport.connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ) {
        AuthRules.validateRegister(username, password, name)
        AuthRules.validateDevice(deviceId, deviceName, deviceModel, deviceFlag)
        val auth = PendingAuthenticationCredentials(
            authType = 1,
            username = username,
            password = password,
            name = name,
            refreshToken = null,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            deviceFlag = deviceFlag,
        )
        logger.trace("register requested: username=$username")
        connectAndAuthCredentials(auth, host, port)
    }

    fun authenticate(
        uid: String,
        token: String,
        deviceId: String,
        deviceName: String,
        host: String = transport.connectHost,
        port: Int = transport.connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ) {
        AuthRules.validateDevice(deviceId, deviceName, deviceModel, deviceFlag)
        val auth = PendingAuthenticationCredentials(
            authType = 2,
            username = null,
            password = null,
            name = null,
            refreshToken = token,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            deviceFlag = deviceFlag,
        )
        logger.trace("authenticate requested: uid=$uid")
        connectAndAuthCredentials(auth, host, port, expectedUid = uid)
    }

    /**
     * 为持久化的本地账号准备 refresh 认证，而不触及 DNS 或 TCP。
     *
     * 这是一个全新 ImClient 的引导边界。此调用之后可以用 [awaitTransportOwnerStart] 把
     * ClientSession 绑定到已准备的逻辑 owner；只有 [PreparedAuthentication.start] 被允许启动网络尝试。
     */
    fun prepareAuthentication(
        uid: String,
        token: String,
        deviceId: String,
        deviceName: String,
        host: String = transport.connectHost,
        port: Int = transport.connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ): PreparedAuthentication {
        AuthRules.validateDevice(deviceId, deviceName, deviceModel, deviceFlag)
        require(uid.isNotBlank()) { "Expected auth uid must not be blank" }
        require(token.isNotBlank()) { "Refresh token must not be blank" }
        check(transport.currentOwnerGeneration == 0L) {
            "Deferred refresh authentication requires a fresh ImClient"
        }
        val auth = PendingAuthenticationCredentials(
            authType = 2,
            username = null,
            password = null,
            name = null,
            refreshToken = token,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            deviceFlag = deviceFlag,
        )
        val attempt = authenticationAttempts.reserve()
        logger.trace("prepareAuthentication requested: uid=$uid")
        transport.prepareInitialConnect(
            host = host,
            port = port,
            jitterSeed = stableReconnectJitterSeed(auth.deviceId),
        ) { prepareTransportOwner ->
            authSync.prepareAuthentication(
                credentials = auth,
                expectedUid = uid,
                attempt = attempt,
                startTransport = prepareTransportOwner,
            )
        }
        return PreparedAuthentication {
            transport.startPreparedInitialConnect { startTransport ->
                var started = false
                val admitted = attempt.runIfActive {
                    if (authSync.currentAuthenticationAttempt() === attempt) {
                        startTransport()
                        started = true
                    }
                }
                admitted && started
            }
        }
    }

    fun send(proto: IProto) {
        // SDK 出站防线：先归一化附件，再执行所有消息体/type/正文预算规则。
        // FileStore 存在性仍由服务端做权威校验。
        val outbound = if (proto is com.virjar.tk.protocol.model.Message) {
            canonicalizeOutboundMessage(proto)
        } else proto
        transport.send(outbound)
    }

    /** 非挂起的会话发送，其租约在 EventLoop 写入点被重新检查。 */
    internal fun sendSessionOwned(
        expectedOwnerGeneration: Long,
        sessionLease: SessionOutboundLease,
        proto: IProto,
    ): Boolean {
        val outbound = if (proto is com.virjar.tk.protocol.model.Message) {
            canonicalizeOutboundMessage(proto)
        } else {
            proto
        }
        val expectedConnectionGeneration = transport.currentConnectionGeneration
        if (expectedConnectionGeneration <= 0L) return false
        return transport.sendIfOwned(
            expectedOwnerGeneration = expectedOwnerGeneration,
            expectedConnectionGeneration = expectedConnectionGeneration,
            sendAdmission = sessionLease,
            proto = outbound,
            onResult = {},
        )
    }

    /**
     * 仅限 RPC 的租约发送。两个 transport 代际与请求/会话生命周期都在写入之前的 EventLoop 上
     * 立即检查，因此旧的 ClientSession 不能仅仅因为复用了同一 ImClient 实例就针对之后的账号。
     */
    internal suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        proto: IProto,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        val scheduled = transport.sendIfOwned(
            expectedOwnerGeneration = expectedOwnerGeneration,
            expectedConnectionGeneration = expectedConnectionGeneration,
            sendAdmission = sendAdmission,
            proto = proto,
            onResult = { accepted -> result.complete(accepted) },
        )
        if (!scheduled) return false
        return result.await()
    }

    /**
     * 发送消息并等待服务端 ACK。切入连接 scope 后，登记、发送与清理都在 EventLoop 上。
     */
    suspend fun sendAndWaitAck(message: com.virjar.tk.protocol.model.Message, timeoutMs: Long = 10_000L): MessageAckPayload {
        val expectedOwnerGeneration = messageTransport.currentOwnerGeneration
        val expectedConnectionGeneration = messageTransport.currentConnectionGeneration
        return sendAndWaitAckOwned(
            message = message,
            timeoutMs = timeoutMs,
            expectedOwnerGeneration = expectedOwnerGeneration,
            expectedConnectionGeneration = expectedConnectionGeneration,
            sessionOwner = standaloneAckOwner,
            sessionLease = null,
            sendAdmission = AlwaysWireSendAdmission,
        )
    }

    internal suspend fun sendAndWaitAckIfOwned(
        message: com.virjar.tk.protocol.model.Message,
        expectedOwnerGeneration: Long,
        sessionLease: SessionOutboundLease,
        timeoutMs: Long = 10_000L,
    ): MessageAckPayload = sendAndWaitAckOwned(
        message = message,
        timeoutMs = timeoutMs,
        expectedOwnerGeneration = expectedOwnerGeneration,
        expectedConnectionGeneration = messageTransport.currentConnectionGeneration,
        sessionOwner = sessionLease.ackOwner,
        sessionLease = sessionLease,
        sendAdmission = sessionLease,
    )

    private suspend fun sendAndWaitAckOwned(
        message: com.virjar.tk.protocol.model.Message,
        timeoutMs: Long,
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sessionOwner: Any,
        sessionLease: SessionOutboundLease?,
        sendAdmission: WireSendAdmission,
    ): MessageAckPayload {
        // 在登记之前校验，然后在 EventLoop 写入处重复完整租约。
        if (sessionLease?.isActive() == false) {
            throw CancellationException("Outbound session retired before message send")
        }
        val outbound = canonicalizeOutboundMessage(message)
        if (expectedOwnerGeneration <= 0L || expectedConnectionGeneration <= 0L) {
            if (sessionLease?.isActive() == false) {
                throw CancellationException("Outbound session retired before message send")
            }
            throw TransportUnavailableException("Message transport generation is unavailable")
        }
        val activeTransport = messageTransport
        val s = activeTransport.coroutineScope ?: run {
            if (sessionLease?.isActive() == false) {
                throw CancellationException("Outbound session retired before message send")
            }
            throw TransportUnavailableException("Message transport connection is unavailable")
        }
        return withContext(s.coroutineContext.minusKey(Job)) {
            router.sendAndAwaitAck(
                chatId = outbound.chatId,
                clientMsgId = outbound.clientMsgId,
                timeoutMs = timeoutMs,
                sessionOwner = sessionOwner,
                sessionLease = sessionLease,
            ) {
                val sent = activeTransport.sendNowIfOwned(
                    expectedOwnerGeneration,
                    expectedConnectionGeneration,
                    sendAdmission,
                    outbound,
                )
                if (!sent) {
                    if (sessionLease?.isActive() == false) {
                        throw CancellationException("Outbound session retired before message send")
                    }
                    throw TransportUnavailableException("Connection closed before message send")
                }
            }
        }
    }

    /** 只取消该已退役会话的 ACK 等待者；替代账号的等待者不受影响。 */
    internal fun retireSessionOutbound(sessionOwner: Any) {
        transport.execute { router.retirePendingAcks(sessionOwner) }
    }

    /**
     * 软断开：同步退休 AUTH 准入，再由 EventLoop 关闭连接、取消 scope 并释放 pending payload；
     * **保留 EventLoop**，
     * 允许后续 [connect] 复用同一 [ImClient] 实例（UI 登出→重新登录场景）。
     *
     * 与 [destroy] 的区别：[disconnect] 仅切断"这次连接"，[destroy] 才永久销毁线程资源。
     * 登出、认证失效应调用 [disconnect]；进程退出或彻底放弃实例才调用 [destroy]。
     */
    fun disconnect() {
        // 在同一准入 monitor 下捕获 A、退役它并让 teardown(A) 入队。B 既不能在调度空档中预留，
        // 也不会被 A 的延迟拆除关闭。
        authenticationAttempts.retireAndSchedule(
            captureTeardownOwner = { transport.currentOwnerGeneration },
            scheduleTeardown = { owner -> transport.scheduleDisconnectIfOwned(owner) },
        )
    }

    /** 只有当 [expectedOwnerGeneration] 仍然拥有该逻辑 transport 时才断开。 */
    internal fun disconnectIfOwned(expectedOwnerGeneration: Long) =
        transport.disconnectIfOwned(expectedOwnerGeneration)

    /**
     * 永久销毁：软断开 + 关闭 Netty EventLoop 线程。之后此实例不可再用。
     * 仅在进程退出或实例彻底废弃时调用；UI 登出请用 [disconnect]。
     */
    fun destroy() {
        // 终态 owner 同步释放其响应能力。即使回调尝试了被禁止的重入 destroy 且退役报告了它，
        // transport 拆除仍会运行。
        try {
            authenticationAttempts.retire()
        } finally {
            transport.destroy()
        }
    }

    /**
     * 模拟网络断开（测试钩子，SDK 集成测试用）：关闭底层 channel 但不置 destroyed，
     * 触发 channelInactive；durable refresh/普通连接进入自动重连，一次性登录/注册终结。
     */
    fun simulateNetworkDrop() = transport.simulateNetworkDrop()

    /**
     * 测试钩子：只关闭本 client 的通道，并将其自动重连保持到
     * [resumeReconnectAfterSimulatedDrop]。这绝不改变 host 或进程范围的网络。
     */
    fun simulateNetworkDropAndPauseReconnect() = transport.simulateNetworkDropAndPauseReconnect()

    /** 恢复被 [simulateNetworkDropAndPauseReconnect] 保持的重连。 */
    fun resumeReconnectAfterSimulatedDrop() = transport.resumeReconnectAfterSimulatedDrop()
}

private object AlwaysWireSendAdmission : WireSendAdmission {
    override fun isActive(): Boolean = true
    override fun use(block: () -> Boolean): Boolean = block()
}

/**
 * SDK 所有消息发送入口共用的确定性出站防线。
 * 外部 [LocalCache] / outbox 实现也必须在持久化前使用同一规则。
 */
fun canonicalizeOutboundMessage(message: com.virjar.tk.protocol.model.Message): com.virjar.tk.protocol.model.Message =
    com.virjar.tk.protocol.body.MessageBodyPolicy.canonicalize(
        com.virjar.tk.protocol.body.AttachmentPolicy.canonicalize(message),
    )

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCHRONIZING,
    AUTHENTICATED,
    AUTH_FAILED,
}
