package com.virjar.tk.client

import com.virjar.tk.auth.AuthRules
import com.virjar.tk.util.PlatformOnlyTkLogger
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * SDK TCP facade。传输、认证同步、入站路由和 ACK 等待分别由单一 owner 管理。
 *
 * 线程模型：
 * - 单线程 EventLoop（NioEventLoopGroup(1)），所有状态操作串行
 * - Pipeline 事件驱动：channelRead / channelInactive / userEventTriggered
 * - scope dispatcher = eventLoop，协程也在同一线程执行
 *
 * 心跳：IdleStateHandler(writerIdle=30s, readerIdle=90s)
 * - 写空闲 30s → 自动发 PingSignal
 * - 读空闲 90s → 关闭连接 → channelInactive → 自动重连
 *
 * 重连：非主动 disconnect 时自动重连，指数退避 1s→2s→4s→8s→30s。
 * 重连后自动重发上次认证包（保存在 pendingAuth）。
 *
 * @param onAuthResult 认证结果回调（success, uid, username, name, refreshToken, failureReason）。
 *        ImClient 不持有用户身份（三级状态隔离），认证结果通过此回调传给 UserSession。
 */
class ImClient(
    private val host: String = "",
    private val port: Int = 0,
    private val onAuthResult: ((success: Boolean, uid: String?, username: String?, name: String?, refreshToken: String?, accessToken: String?, failureReason: String?) -> Unit)? = null,
) {
    private val standaloneAckOwner = Any()
    private val logger = PlatformOnlyTkLogger("ImClient")
    private val transport: TransportConnectionOwner
    private val router: PacketRouter
    private val authSync = AuthSyncCoordinator(
        connectionState = { transport.state.value },
        transitionTo = { next -> transport.transitionTo(next) },
        connectionScope = { transport.coroutineScope },
        writeProtocol = { proto -> transport.writeProtocolNow(proto) },
        closeTransport = { reason, cause -> transport.closeForRecoveryNow(reason, cause) },
        onAuthenticationAccepted = { transport.onAuthenticationAccepted() },
        publishAuthResponse = { response ->
            router.publishAuthResponse(transport.currentConnectionGeneration, response)
        },
        onAuthResult = onAuthResult,
    )

    init {
        router = PacketRouter(
            connectionState = { transport.state.value },
            connectionScope = { transport.coroutineScope },
            handleAuthResponse = authSync::handleAuthResponse,
            handleSyncBatch = authSync::handleSyncBatch,
            handleSyncEvent = authSync::handleSyncEvent,
            handleSyncReady = authSync::handleSyncReady,
            handleSyncReset = authSync::handleSyncReset,
            writeControl = { proto -> transport.sendNow(proto) },
            closeTransport = { reason -> transport.closeForRecoveryNow(reason) },
        )
        transport = TransportConnectionOwner(
            initialHost = host,
            initialPort = port,
            authenticationPayload = authSync::authenticationPayload,
            authenticationTerminal = authSync::isAuthenticationTerminal,
            routePacket = router::route,
            onTransportDisconnected = {
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

    internal fun installEventSync(
        owner: Any,
        expectedUid: String?,
        wireAdmission: WireSendAdmission,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        reset: suspend () -> Long,
    ) {
        transport.execute {
            authSync.installEventSync(owner, expectedUid, wireAdmission, cursor, processBatch, reset)
        }
    }

    internal fun removeEventSync(owner: Any) {
        transport.execute { authSync.removeEventSync(owner) }
    }

    /** A broken projection must reconnect from its last durable cursor, never skip the event. */
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
    ) {
        require(auth.authType != 2 || !expectedUid.isNullOrBlank()) {
            "Refresh authentication must bind its expected uid"
        }
        logger.trace("connectAndAuth: host=$host, port=$port, authType=${auth.authType}")
        transport.connect(host, port) {
            authSync.prepareAuthentication(auth, expectedUid)
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
        val auth = AuthRequestPayload(authType = 0, username = username, password = password,
            deviceId = deviceId, deviceName = deviceName, deviceModel = deviceModel, deviceFlag = deviceFlag)
        logger.trace("login requested: username=$username")
        // pendingAuth + connect 原子化，消除协程/EventLoop 竞态
        connectAndAuth(auth, host, port)
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
        AuthRules.validateRegister(username, password)
        val auth = AuthRequestPayload(authType = 1, username = username, password = password,
            name = name, deviceId = deviceId, deviceName = deviceName,
            deviceModel = deviceModel, deviceFlag = deviceFlag)
        logger.trace("register requested: username=$username")
        connectAndAuth(auth, host, port)
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
        val auth = AuthRequestPayload(authType = 2, refreshToken = token,
            deviceId = deviceId, deviceName = deviceName, deviceModel = deviceModel, deviceFlag = deviceFlag)
        logger.trace("authenticate requested: uid=$uid")
        connectAndAuth(auth, host, port, expectedUid = uid)
    }

    fun send(proto: IProto) {
        // SDK 出站防线：先归一化附件，再执行所有消息体/type/正文预算规则。
        // FileStore 存在性仍由服务端做权威校验。
        val outbound = if (proto is com.virjar.tk.model.Message) {
            canonicalizeOutboundMessage(proto)
        } else proto
        transport.send(outbound)
    }

    /** Non-suspending session send whose lease is rechecked on the EventLoop write point. */
    internal fun sendSessionOwned(
        expectedOwnerGeneration: Long,
        sessionLease: SessionOutboundLease,
        proto: IProto,
    ): Boolean {
        val outbound = if (proto is com.virjar.tk.model.Message) {
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
     * RPC-only leased send. Both transport generations and the request/session lifetime are checked
     * on the EventLoop immediately before the write, so an old ClientSession cannot target a later
     * account merely because the same ImClient instance was reused.
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
    suspend fun sendAndWaitAck(message: com.virjar.tk.model.Message, timeoutMs: Long = 10_000L): MessageAckPayload {
        val expectedOwnerGeneration = transport.currentOwnerGeneration
        val expectedConnectionGeneration = transport.currentConnectionGeneration
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
        message: com.virjar.tk.model.Message,
        expectedOwnerGeneration: Long,
        sessionLease: SessionOutboundLease,
        timeoutMs: Long = 10_000L,
    ): MessageAckPayload = sendAndWaitAckOwned(
        message = message,
        timeoutMs = timeoutMs,
        expectedOwnerGeneration = expectedOwnerGeneration,
        expectedConnectionGeneration = transport.currentConnectionGeneration,
        sessionOwner = sessionLease.ackOwner,
        sessionLease = sessionLease,
        sendAdmission = sessionLease,
    )

    private suspend fun sendAndWaitAckOwned(
        message: com.virjar.tk.model.Message,
        timeoutMs: Long,
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sessionOwner: Any,
        sessionLease: SessionOutboundLease?,
        sendAdmission: WireSendAdmission,
    ): MessageAckPayload {
        // Validate before registration, then repeat the complete lease at the EventLoop write.
        val outbound = canonicalizeOutboundMessage(message)
        check(expectedOwnerGeneration > 0L && expectedConnectionGeneration > 0L) {
            "Session transport is not available"
        }
        val s = transport.coroutineScope ?: throw IllegalStateException("Not connected")
        return withContext(s.coroutineContext.minusKey(Job)) {
            router.sendAndAwaitAck(
                clientMsgId = outbound.clientMsgId,
                timeoutMs = timeoutMs,
                sessionOwner = sessionOwner,
                sessionLease = sessionLease,
            ) {
                check(
                    transport.sendNowIfOwned(
                        expectedOwnerGeneration,
                        expectedConnectionGeneration,
                        sendAdmission,
                        outbound,
                    ),
                ) { "Session changed before message send" }
            }
        }
    }

    /** Cancel only this retired session's ACK waiters; replacement-account waiters are untouched. */
    internal fun retireSessionOutbound(sessionOwner: Any) {
        transport.execute { router.retirePendingAcks(sessionOwner) }
    }

    /**
     * 软断开：关闭当前连接 + 取消 scope + 清理 pending，但**保留 EventLoop**，
     * 允许后续 [connect] 复用同一 [ImClient] 实例（UI 登出→重新登录场景）。
     *
     * 与 [destroy] 的区别：[disconnect] 仅切断"这次连接"，[destroy] 才永久销毁线程资源。
     * 登出、认证失效应调用 [disconnect]；进程退出或彻底放弃实例才调用 [destroy]。
     */
    fun disconnect() = transport.disconnect()

    /** Disconnect only while [expectedOwnerGeneration] still owns this logical transport. */
    internal fun disconnectIfOwned(expectedOwnerGeneration: Long) =
        transport.disconnectIfOwned(expectedOwnerGeneration)

    /**
     * 永久销毁：软断开 + 关闭 Netty EventLoop 线程。之后此实例不可再用。
     * 仅在进程退出或实例彻底废弃时调用；UI 登出请用 [disconnect]。
     */
    fun destroy() = transport.destroy()

    /**
     * 模拟网络断开（测试钩子，SDK 集成测试用）：关闭底层 channel 但不置 destroyed，
     * 触发 channelInactive → 自动重连路径（区别于主动 disconnect）。
     */
    fun simulateNetworkDrop() = transport.simulateNetworkDrop()
}

private object AlwaysWireSendAdmission : WireSendAdmission {
    override fun isActive(): Boolean = true
    override fun use(block: () -> Boolean): Boolean = block()
}

/** SDK 所有消息发送入口共用的确定性出站防线。 */
internal fun canonicalizeOutboundMessage(message: com.virjar.tk.model.Message): com.virjar.tk.model.Message =
    com.virjar.tk.body.MessageBodyPolicy.canonicalize(
        com.virjar.tk.body.AttachmentPolicy.canonicalize(message),
    )

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCHRONIZING,
    AUTHENTICATED,
    AUTH_FAILED,
}
