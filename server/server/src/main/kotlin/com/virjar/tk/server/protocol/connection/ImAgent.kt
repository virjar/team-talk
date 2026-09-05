package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.netty.PacketCodec
import com.virjar.tk.server.protocol.UnauthenticatedConnectionAdmission
import com.virjar.tk.server.protocol.ServerProtocolConfiguration
import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.event.TransientEventPublisher
import com.virjar.tk.server.domain.event.SyncBatchResult
import com.virjar.tk.server.domain.event.SyncEventReader
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.infra.sync.ClientRegistry
import com.virjar.tk.server.infra.sync.AuthenticatedAgentAdmission
import com.virjar.tk.server.infra.sync.AuthenticatedAgentAdmissionPlan
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.*
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.server.protocol.executor.IOExecutor
import com.virjar.tk.server.protocol.executor.AgentTaskLease
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.server.protocol.dispatcher.FatalCodecException
import com.virjar.tk.server.protocol.trace.Recorder
import com.virjar.tk.server.protocol.trace.RecorderPolicyUpdate
import com.virjar.tk.protocol.rpc.gen.AuthRpcContract
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelFutureListener
import io.netty.handler.timeout.IdleStateEvent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接级处理器。管理认证状态和包分发。
 *
 * 线程安全模型：
 * - **EventLoop**（当前线程）：只做轻量操作（PING/PONG/DISCONNECT、数据提取、协程启动）
 * - **IOExecutor**：重量 IO 操作（auth/RPC/message）通过 launchWithAgent 调度
 * - **ImAgentFacade**：WeakReference 门面，协程挂起期间 agent 可被 GC 回收
 *
 * 日志：连接级日志只在 DIAGNOSTIC 策略命中后由 recorder 有界、懒加载记录；非连接级日志使用 slf4j。
 */
class ImAgent internal constructor(
    val channel: io.netty.channel.Channel,
    val recorder: Recorder,
    private val authService: AuthService,
    private val clientRegistry: ClientRegistry,
    private val rpcDispatcher: RpcDispatcher,
    private val messageService: MessageService,
    private val chatAccess: ChatAccess,
    private val syncEvents: SyncEventReader,
    private val events: TransientEventPublisher,
    private val ioExecutor: IOExecutor,
    private val authenticationAttempts: AuthenticationAttemptGuard,
    private val protocolConfiguration: ServerProtocolConfiguration = ServerProtocolConfiguration(),
) : ChannelInboundHandlerAdapter() {
    enum class State { CONNECTED, AUTHENTICATING, SYNCHRONIZING, AUTHENTICATED, DISCONNECTED }

    companion object {
        /** 认证超时：未认证连接最长存活（慢滴保活攻击窗口） */
        private const val AUTH_TIMEOUT_SECONDS = 10L
        /** 身份通过后必须在有界时间内完成持久事件同步，不能占用半开连接。 */
        private const val SYNC_STALL_TIMEOUT_SECONDS = 60L
    }

    private val authState = ImAgentAuthState()
    private val authenticationLifecycleLock = Any()
    private val syncOperationAdmission = ImAgentSyncOperationAdmission()
    private val syncTimeoutGeneration = AtomicLong(0L)
    private val credentialTerminal = AtomicBoolean(false)
    private val connectionTracePolicy = ImAgentConnectionTracePolicy(recorder)
    private val registryUnregisterRequested = AtomicBoolean(false)
    /** 同一连接的分页 cursor 必须严格前进；InvalidCursor 后显式恢复为可接收 0。 */
    private val admittedSyncCursor = ImAgentSyncCursor()
    /** Chosen once before authentication; business tasks copy this immutable connection fact. */
    @Volatile
    var negotiatedProtocolVersion: ProtocolVersion? = null
        private set
    val state: State get() = authState.current
    @Volatile
    var uid: String = ""; internal set
    @Volatile
    var deviceId: String = ""; internal set
    @Volatile
    internal var userCredentialEpoch: Long = 0L
    @Volatile
    internal var deviceCredentialEpoch: Long = 0L

    /** 完整 Netty channel id 用于安全身份；[channelId] 仅用于日志。 */
    val sessionId: String = channel.id().asLongText()

    /** 复制到 IO 任务中，而不保留此处理器或其 Netty 通道。 */
    internal val taskLease = AgentTaskLease(sessionId)

    /** 连接是否活跃 */
    val isActive: Boolean get() = state != State.DISCONNECTED && channel.isActive
    internal val isCredentialTerminal: Boolean get() = credentialTerminal.get()
    internal val isRegistryUnregisterRequested: Boolean get() = registryUnregisterRequested.get()
    internal val connectionTraceIdentity get() = connectionTracePolicy.identity

    internal fun markCredentialTerminal() {
        credentialTerminal.set(true)
    }

    /**
     * 由 ClientRegistry 合并的关键清扫消费的终结标记。把标记放在
     * 已拥有的连接上，可避免在 registry looper 饱和时
     * 再用一条额外的断开队列为每个通道保留一个 ImAgent。
     */
    internal fun requestRegistryUnregister() {
        registryUnregisterRequested.set(true)
    }

    /** 只由 ClientRegistry 的串行 looper 在发布此会话之前立即调用。 */
    internal fun markReadyForLiveActivation(): Boolean = synchronized(authenticationLifecycleLock) {
        channel.isActive && authState.markReady()
    }

    /**
     * 完成身份阶段，而不把可变的连接状态暴露给 IO 闭包。
     * 调用方通过 [com.virjar.tk.server.protocol.executor.ImAgentFacade] 到达此方法，因此
     * 排队的鉴权任务从不强引用此处理器或其通道。
     */
    internal fun completeAuthentication(
        authenticatedUid: String,
        authenticatedDeviceId: String,
        authenticatedUserCredentialEpoch: Long,
        authenticatedDeviceCredentialEpoch: Long,
    ): Boolean =
        synchronized(authenticationLifecycleLock) {
            require(authenticatedUserCredentialEpoch > 0L && authenticatedDeviceCredentialEpoch > 0L) {
                "Credential epochs must be positive"
            }
            // 鉴权 IO 运行期间 channelInactive 可能获胜。过期的 worker 绝不能
            // 复活该连接，或发布部分初始化的身份状态。
            if (!authState.markSynchronizing()) {
                false
            } else {
                uid = authenticatedUid
                deviceId = authenticatedDeviceId
                userCredentialEpoch = authenticatedUserCredentialEpoch
                deviceCredentialEpoch = authenticatedDeviceCredentialEpoch
                releaseUnauthenticatedLease()
                channel.pipeline().get(PacketCodec::class.java)
                    ?.maxPayloadLimit = PacketCodec.AUTHED_LIMIT
                true
            }
        }

    /** 常量时间的本地应用；策略读取始终先在 IO worker 上发生。 */
    internal fun applyConnectionTracePolicy(policy: ClientTelemetryPolicy): RecorderPolicyUpdate? =
        connectionTracePolicy.apply(uid, deviceId, policy)

    /** 控制面读取失败会围住此物理连接，直到它重连。 */
    internal fun terminalDisableConnectionTracePolicy(): RecorderPolicyUpdate? =
        connectionTracePolicy.terminalDisable()

    /** 最后的单调记录器决策，在此精确连接可接收实时帧时使用。 */
    internal fun currentConnectionTracePolicyDecision(): RecorderPolicyUpdate? =
        connectionTracePolicy.currentDecision()

    internal fun sendConnectionTracePolicyUpdate(update: RecorderPolicyUpdate) {
        val identity = connectionTraceIdentity ?: return
        if (
            identity.correlationId != update.correlationId ||
            identity.connectionGeneration != update.connectionGeneration ||
            state != State.AUTHENTICATED ||
            !isActive
        ) return
        write(
            ConnectionTraceContextPayload(
                correlationId = update.correlationId,
                connectionGeneration = update.connectionGeneration,
                policyRevision = update.policyRevision,
                context = update.context,
            ),
        )
    }

    internal fun resetSyncAdmission() {
        admittedSyncCursor.reset()
    }

    /** 短 channel ID，用于日志 */
    val channelId: String = channel.id().asShortText()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        // boss EventLoop 在把此通道提交给 worker 注册之前，已经获取了生命周期连接租约。
        // ImAgent 拥有认证截止时间，而不是租约。
        // 认证超时：慢滴保活绕不过（与读空闲独立——每44s滴1字节可无限续命 readerIdle）
        val timeoutFacade = com.virjar.tk.server.protocol.executor.ImAgentFacade(this)
        ctx.channel().eventLoop().schedule({
            timeoutFacade.closeIfAuthenticationStalled(AUTH_TIMEOUT_SECONDS)
        }, AUTH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun closeIfAuthenticationStalled(timeoutSeconds: Long) {
        if (state == State.CONNECTED || state == State.AUTHENTICATING) {
            recorder.trace(ConnectionTracePhase.AUTHENTICATION, ConnectionTraceOutcome.FAILED) {
                "event=timeout timeoutSeconds=$timeoutSeconds"
            }
            channel.close()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // PacketCodec 只转发完整解码的协议对象。此刻意粗糙的
        // 事件证明帧接收/解码，而不保留或渲染载荷本身。
        val decodedType = msg::class.simpleName ?: "Unknown"
        recorder.trace(ConnectionTracePhase.CONNECTION, ConnectionTraceOutcome.SUCCEEDED) {
            "event=frameDecoded type=$decodedType"
        }
        when (msg) {
            // ── 轻量操作：EventLoop 直接处理 ──
            is PingSignal -> write(PongSignal)
            is DisconnectSignal -> ctx.close()
            is ProtocolNegotiateRequestPayload -> handleProtocolNegotiation(msg)

            // ── 重量操作：dispatch 到 IOExecutor ──
            is AuthRequestPayload -> handleAuth(msg)
            is SyncRequestPayload -> handleSyncRequest(msg)
            is InvokePayload -> handleInvoke(msg)
            is Message -> handleMessage(msg)

            else -> recorder.trace(ConnectionTracePhase.CONNECTION, ConnectionTraceOutcome.REJECTED) {
                "event=unknownFrame type=$decodedType"
            }
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            recorder.trace(ConnectionTracePhase.HEARTBEAT, ConnectionTraceOutcome.CLOSED) {
                "event=idleTimeout"
            }
            ctx.close()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Netty 坑：异常传播到 pipeline tail 默认只打日志不关连接——
        // CorruptedFrameException（垃圾流量/错位）抛了也白抛，连接继续挂着。
        // 此处统一兜底断连（编解码错误的既定策略：连接不可信即断）。
        recorder.trace(
            ConnectionTracePhase.CONNECTION,
            ConnectionTraceOutcome.FAILED,
            throwable = cause,
        ) { "event=decodeAlarm" }
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        // 在发布断开状态之前，先取消已排队/运行中的连接工作。
        // 租约没有指向此处理器的引用，因此排队的任务保持 GC 安全。
        taskLease.cancel()
        val disconnectedUid = synchronized(authenticationLifecycleLock) {
            val authenticatedUid = uid
            authState.disconnect()
            releaseUnauthenticatedLease()
            if (authenticatedUid.isNotEmpty()) {
                // SYNCHRONIZING 连接也已经受凭据 fence 管理，必须对称注销。
                // 下线广播由 ClientRegistry 的 PresenceTransitionSource 触发（仅最后一台设备）。
                clientRegistry.unregister(this)
            }
            authenticatedUid
        }
        syncEvents.releaseSession(disconnectedUid, sessionId)
        recorder.trace(ConnectionTracePhase.SHUTDOWN, ConnectionTraceOutcome.CLOSED) {
            "event=channelInactive"
        }
    }

    private fun releaseUnauthenticatedLease() {
        UnauthenticatedConnectionAdmission.release(channel)
    }

    // ── 重量操作（IOExecutor 协程处理） ──

    private fun handleProtocolNegotiation(payload: ProtocolNegotiateRequestPayload) {
        if (state != State.CONNECTED || negotiatedProtocolVersion != null) {
            channel.close()
            return
        }
        val response = ProtocolNegotiation.negotiate(
            payload.supported,
            protocolConfiguration.supported,
            protocolConfiguration.serverReleaseVersion,
        )
        if (response.code != ProtocolNegotiateResponsePayload.CODE_OK) {
            writeAndClose(response)
            return
        }
        negotiatedProtocolVersion = checkNotNull(response.negotiated)
        write(response)
    }

    private fun handleAuth(payload: AuthRequestPayload) {
        if (negotiatedProtocolVersion == null) {
            // The last preview client can still decode AUTH_RESP, even though it cannot negotiate.
            writeAndClose(
                AuthResponsePayload(
                    code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED,
                    reason = "Protocol negotiation is required; upgrade the TeamTalk client",
                ),
            )
            return
        }
        val authType = payload.authType
        // 状态切换必须在 EventLoop 收包路径同步完成。若等 IO worker 执行才切换，攻击者可在
        // 首次认证排队期间继续塞入多份 AUTH；认证成功后的重复 AUTH 同样属于协议违规。
        if (authState.admitAuthentication() == ImAgentAuthAdmission.REJECT_AND_CLOSE) {
            val rejectedState = state
            recorder.trace(ConnectionTracePhase.AUTHENTICATION, ConnectionTraceOutcome.REJECTED) {
                "event=duplicate state=$rejectedState"
            }
            channel.close()
            return
        }
        if (!recorder.bindAuthentication(payload.correlationId, payload.connectionGeneration)) {
            recorder.discardPreAuthentication()
            channel.close()
            return
        }
        val authenticationAdmission = authenticationAttempts.tryAcquire(
            attempt = authenticationAttempt(channel.remoteAddress(), payload),
            callerConcurrencyCeiling = ioExecutor.authenticationConcurrencyCeiling,
        )
        if (authenticationAdmission == null) {
            // 准入在有界密码 worker 之前运行在 EventLoop 上。公共响应
            // 不揭示是全局、来源、账户、操作还是键容量拒绝的。
            recorder.trace(ConnectionTracePhase.AUTHENTICATION, ConnectionTraceOutcome.REJECTED) {
                "event=throttled authType=$authType"
            }
            recorder.discardPreAuthentication()
            writeAndClose(authenticationGuardDenialResponse())
            return
        }
        recorder.trace(ConnectionTracePhase.AUTHENTICATION, ConnectionTraceOutcome.STARTED) {
            "event=request authType=$authType"
        }
        val authentication = authService
        val registry = clientRegistry
        val authoritativeDatasetId = syncEvents.datasetId
        val accepted = try {
            ioExecutor.launchWithAgent(
                agent = this,
                onCompletion = authenticationAdmission::close,
            ) { facade ->
                val result = executeAuthenticationBoundary(
                    authenticate = { authentication.authenticate(payload) },
                    reportInternalFailure = { failure ->
                        facade.recorder.trace(
                            ConnectionTracePhase.AUTHENTICATION,
                            ConnectionTraceOutcome.FAILED,
                            throwable = failure,
                        ) { "event=service" }
                    },
                )
                var response = if (result.response.code == AuthService.CODE_OK) {
                    result.response.copy(datasetId = authoritativeDatasetId)
                } else {
                    result.response
                }

                if (response.code == AuthService.CODE_OK) {
                    val principal = checkNotNull(result.principal) { "Successful auth result has no principal" }
                    check(response.uid == principal.uid) { "Authentication response/principal mismatch" }
                    check(payload.deviceId == principal.deviceId) { "Authentication device/principal mismatch" }
                    response = establishAuthenticatedSession(facade, registry, principal, response)
                        ?: return@launchWithAgent
                } else {
                    facade.recorder.discardPreAuthentication()
                }

                facade.send(response)
            }
        } catch (failure: Throwable) {
            authenticationAdmission.close()
            throw failure
        }
        if (!accepted) {
            authenticationAdmission.close()
            // 未收到认证终态的客户端会按连接断开策略退避重连；返回认证失败反而会停止重试。
            recorder.trace(ConnectionTracePhase.AUTHENTICATION, ConnectionTraceOutcome.REJECTED) {
                "event=executorOverload"
            }
            recorder.discardPreAuthentication()
            channel.close()
        }
    }

    private fun handleSyncRequest(payload: SyncRequestPayload) {
        val requestedCursor = payload.lastEventId
        if (state != State.SYNCHRONIZING) {
            val rejectedState = state
            recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                "event=wrongState state=$rejectedState"
            }
            channel.close()
            return
        }
        if (!admittedSyncCursor.admit(requestedCursor)) {
            recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                "event=cursorRegression cursor=$requestedCursor"
            }
            channel.close()
            return
        }
        val operationLease = syncOperationAdmission.tryAcquire()
        if (operationLease == null) {
            recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                "event=overlap"
            }
            channel.close()
            return
        }
        // 此游标已被接受为严格新于上一个请求。积压可能
        // 合法地占用许多页，因此超时衡量的是缺乏进展，而不是总同步
        // 时长。
        refreshSyncStallTimeout()
        val eventReader = syncEvents
        val registry = clientRegistry
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            try {
                when (val result = eventReader.nextBatchOrActivate(
                    uid = facade.uid,
                    sessionId = facade.sessionId,
                    claimedDatasetId = payload.datasetId,
                    afterEventId = requestedCursor,
                    limit = SyncBatchPayload.MAX_EVENTS,
                    activate = {
                        try {
                            facade.activateLive { registry.activate(it) }
                        } catch (_: RejectedExecutionException) {
                            facade.recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                                "event=activationOverload"
                            }
                            facade.closeConnection()
                            false
                        }
                    },
                )) {
                    is SyncBatchResult.Events -> {
                        val bounded = SyncBatchPayload.boundedPrefix(result.events)
                        operationLease.close()
                        if (bounded.isEmpty()) {
                            // 事件本身能装入 NOTIFY 帧，但加上批次计数
                            // 会越过已鉴权载荷上限。
                            facade.send(result.events.first())
                        } else {
                            facade.send(SyncBatchPayload(bounded))
                        }
                        val batchCount = if (bounded.isEmpty()) 1 else bounded.size
                        facade.recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.SUCCEEDED) {
                            "event=batch cursor=$requestedCursor count=$batchCount"
                        }
                    }
                    SyncBatchResult.Activated ->
                        facade.recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.SUCCEEDED) {
                            "event=ready cursor=$requestedCursor"
                        }
                    SyncBatchResult.ConnectionClosed -> Unit
                    is SyncBatchResult.DatasetMismatch -> {
                        facade.recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                            "event=datasetReset"
                        }
                        facade.resetSyncAdmission()
                        // 只在此被拒绝的重放请求释放其连接门之后
                        // 发布 RESET。客户端可能收到后立即开始 SyncRpc。
                        operationLease.close()
                        facade.send(SyncResetPayload(result.datasetId))
                    }
                    SyncBatchResult.InvalidCursor -> {
                        facade.recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                            "event=cursorReset cursor=$requestedCursor"
                        }
                        // 所有权校验在 IO worker 上运行。在发送 RESET 之前重置原子准入
                        // fence，使同一已鉴权连接可以
                        // 随后开始 checkpoint RPC。它绝不能进入实时投递。
                        facade.resetSyncAdmission()
                        operationLease.close()
                        facade.send(SyncResetPayload(eventReader.datasetId))
                    }
                }
            } finally {
                operationLease.close()
                if (!facade.isActive) eventReader.releaseSession(facade.uid, facade.sessionId)
            }
        }
        if (!accepted) {
            operationLease.close()
            recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                "event=executorOverload"
            }
            channel.close()
        }
    }

    internal fun refreshSyncStallTimeout() {
        val generation = syncTimeoutGeneration.incrementAndGet()
        val timeoutFacade = com.virjar.tk.server.protocol.executor.ImAgentFacade(this)
        channel.eventLoop().schedule({
            timeoutFacade.closeIfSyncStalled(generation, SYNC_STALL_TIMEOUT_SECONDS)
        }, SYNC_STALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun closeIfSyncStalled(generation: Long, timeoutSeconds: Long) {
        if (
            state == State.SYNCHRONIZING &&
            syncTimeoutGeneration.get() == generation
        ) {
            recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.FAILED) {
                "event=timeout timeoutSeconds=$timeoutSeconds"
            }
            channel.close()
        }
    }

    private fun handleInvoke(payload: InvokePayload) {
        val serviceId = payload.serviceId
        val methodId = payload.methodId
        val checkpointInvoke =
            state == State.SYNCHRONIZING && serviceId == SyncRpcContract.SERVICE
        val businessInvoke =
            state == State.AUTHENTICATED && serviceId != SyncRpcContract.SERVICE
        if ((!checkpointInvoke && !businessInvoke) || isCredentialTerminal) {
            write(ResponsePayload(payload.requestId, 401, null))
            if (isCredentialTerminal) channel.close()
            return
        }
        val operationLease = if (checkpointInvoke) syncOperationAdmission.tryAcquire() else null
        if (checkpointInvoke && operationLease == null) {
            recorder.trace(ConnectionTracePhase.SYNC, ConnectionTraceOutcome.REJECTED) {
                "event=checkpointRpcOverlap method=$methodId"
            }
            channel.close()
            return
        }
        if (checkpointInvoke) refreshSyncStallTimeout()
        val dispatcher = rpcDispatcher
        val eventReader = syncEvents
        val dispatch: (
            suspend kotlinx.coroutines.CoroutineScope.(com.virjar.tk.server.protocol.executor.ImAgentFacade) -> Unit
        ) = dispatch@ { facade ->
            if (facade.isCredentialTerminal) {
                facade.send(ResponsePayload(payload.requestId, 401, null))
                facade.closeConnection()
                return@dispatch
            }
            try {
                val response = dispatcher.dispatch(
                    uid = facade.uid,
                    deviceId = facade.deviceId,
                    deviceCredentialEpoch = facade.deviceCredentialEpoch,
                    sessionId = facade.sessionId,
                    invoke = payload,
                    protocolVersion = checkNotNull(facade.negotiatedProtocolVersion),
                )
                val responseStatus = response.status
                facade.recorder.trace(ConnectionTracePhase.RPC, ConnectionTraceOutcome.SUCCEEDED) {
                    "event=response service=$serviceId method=$methodId status=$responseStatus"
                }
                if (
                    response.status == 0 && payload.serviceId == AuthRpcContract.SERVICE &&
                    (payload.methodId == AuthRpcContract.M_UPDATE_PASSWORD ||
                        payload.methodId == AuthRpcContract.M_LOGOUT)
                ) {
                    // 凭据变更已经推进其 fence，同时保留
                    // 此精确终结会话，只为刷出成功响应。
                    facade.sendAndClose(response)
                } else {
                    operationLease?.close()
                    facade.send(response)
                }
            } catch (e: FatalCodecException) {
                // 协议紊乱：连接已不可靠，直接断连 + FATAL 日志，不尝试返回错误响应
                val failedService = e.service
                val failedMethod = e.method
                facade.recorder.trace(
                    ConnectionTracePhase.RPC,
                    ConnectionTraceOutcome.FAILED,
                    throwable = e,
                ) { "event=codec service=$failedService method=$failedMethod" }
                facade.closeConnection()
            } finally {
                operationLease?.close()
                if (checkpointInvoke && !facade.isActive) {
                    eventReader.releaseSession(facade.uid, facade.sessionId)
                }
            }
        }
        // 每用户命令顺序是凭据变更的安全边界，也使
        // 跨设备草稿写入保持确定性。读取目前共享同一条有界队列；
        // 只有在剖析证明有必要时才优化为显式的读/写命令模型。
        if (!ioExecutor.launchSerialWithAgent("user-command:$uid", this, dispatch)) {
            operationLease?.close()
            write(ResponsePayload(payload.requestId, 429, "用户请求过于频繁".encodeToByteArray()))
        }
    }

    private fun handleMessage(msg: Message) {
        val messageType = msg.messageType
        if (state != State.AUTHENTICATED || isCredentialTerminal) {
            write(MessageAckPayload(msg.chatId, msg.clientMsgId, 0, 401, "Not authenticated"))
            if (isCredentialTerminal) channel.close()
            return
        }
        if (!ProtocolWireRegistry.supportsMessageType(messageType, checkNotNull(negotiatedProtocolVersion))) {
            write(MessageAckPayload(msg.chatId, msg.clientMsgId, 0, 426, "Message type is unavailable at the negotiated protocol version"))
            return
        }

        if (messageType == MessageType.TYPING.code) {
            handleTyping(msg)
            return
        }

        recorder.trace(ConnectionTracePhase.MESSAGE, ConnectionTraceOutcome.STARTED) {
            "event=send type=$messageType"
        }
        val messages = messageService
        val accepted = ioExecutor.launchSerialWithAgent("user-command:$uid", this) { facade ->
            if (facade.isCredentialTerminal) {
                facade.send(MessageAckPayload(msg.chatId, msg.clientMsgId, 0, 401, "Credentials rotated"))
                facade.closeConnection()
                return@launchSerialWithAgent
            }
            try {
                val serverSeq = messages.sendMessage(facade.uid, msg)
                facade.send(MessageAckPayload(msg.chatId, msg.clientMsgId, serverSeq, 0, null))
                facade.recorder.trace(ConnectionTracePhase.MESSAGE, ConnectionTraceOutcome.SUCCEEDED) {
                    "event=ack serverSeq=$serverSeq"
                }
            } catch (e: IllegalArgumentException) {
                facade.send(MessageAckPayload(msg.chatId, msg.clientMsgId, 0, 400, e.message))
            } catch (e: IndexOutOfBoundsException) {
                // 消息体编解码紊乱：连接不可靠，断连 + FATAL 日志
                facade.recorder.trace(
                    ConnectionTracePhase.MESSAGE,
                    ConnectionTraceOutcome.FAILED,
                    throwable = e,
                ) { "event=codec" }
                facade.closeConnection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                facade.recorder.trace(
                    ConnectionTracePhase.MESSAGE,
                    ConnectionTraceOutcome.FAILED,
                    throwable = e,
                ) { "event=service" }
                facade.send(MessageAckPayload(msg.chatId, msg.clientMsgId, 0, 500, "服务器内部错误"))
            }
        }
        if (!accepted) {
            write(MessageAckPayload(msg.chatId, msg.clientMsgId, 0, 503, "服务器繁忙，请稍后重试"))
        }
    }

    private fun handleTyping(msg: Message) {
        val access = chatAccess
        val publisher = events
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            if (facade.isCredentialTerminal) {
                facade.closeConnection()
                return@launchWithAgent
            }
            try {
                // 此调用运行在 IOExecutor 上。其回调只构建不可变数据；所有
                // 瞬时网络发射都发生在权威 PG 快照关闭之后。
                val delivery = authorizeTypingDelivery(access, facade.uid, msg)
                facade.recorder.trace(ConnectionTracePhase.MESSAGE, ConnectionTraceOutcome.SUCCEEDED) {
                    "event=typing"
                }
                for (memberUid in delivery.recipientUids) {
                    publisher.emitTransient(memberUid, NotifyType.TYPING, delivery.message)
                }
            } catch (e: IllegalArgumentException) {
                facade.recorder.trace(
                    ConnectionTracePhase.MESSAGE,
                    ConnectionTraceOutcome.REJECTED,
                    throwable = e,
                ) { "event=typing" }
            }
        }
        if (!accepted) {
            // 输入状态是瞬态提示；过载时直接丢弃，不能挤占持久消息/RPC 的恢复窗口。
            recorder.trace(ConnectionTracePhase.MESSAGE, ConnectionTraceOutcome.DROPPED) {
                "event=typing"
            }
        }
    }

    // ── 连接操作 ──

    fun write(msg: IProto) {
        if (channel.isActive) {
            val version = negotiatedProtocolVersion
            val compatible = if (version == null) msg else eventFrameForProtocol(msg, version) ?: return
            channel.writeAndFlush(compatible)
            val notifyType = (compatible as? NotifyPayload)?.notifyType
            if (notifyType != null) recorder.traceNotifyDelivery(notifyType)
        }
    }

    internal fun closeConnection() {
        channel.close()
    }

    internal fun writeAndClose(msg: IProto) {
        if (channel.isActive) channel.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE)
    }

    /** 踢下线 */
    fun kick() {
        recorder.trace(ConnectionTracePhase.SHUTDOWN, ConnectionTraceOutcome.CLOSED) {
            "event=kick"
        }
        channel.close()
    }

}
