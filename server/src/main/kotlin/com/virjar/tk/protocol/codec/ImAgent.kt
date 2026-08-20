package com.virjar.tk.protocol.codec

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.AuthenticationResult
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.event.SyncBatchResult
import com.virjar.tk.domain.event.SyncEventReader
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.protocol.executor.IOExecutor
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.protocol.dispatcher.FatalCodecException
import com.virjar.tk.protocol.trace.Recorder
import com.virjar.tk.rpc.gen.AuthRpcContract
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelFutureListener
import io.netty.handler.timeout.IdleStateEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 单连接认证状态机；CAS 保证同一 TCP 连接至多受理一个认证请求。 */
internal enum class ImAgentAuthAdmission { ACCEPT, REJECT_AND_CLOSE }

internal class ImAgentAuthState {
    private val state = AtomicReference(ImAgent.State.CONNECTED)

    val current: ImAgent.State get() = state.get()

    fun admitAuthentication(): ImAgentAuthAdmission =
        if (state.compareAndSet(ImAgent.State.CONNECTED, ImAgent.State.AUTHENTICATING)) {
            ImAgentAuthAdmission.ACCEPT
        } else {
            ImAgentAuthAdmission.REJECT_AND_CLOSE
        }

    fun markSynchronizing(): Boolean =
        state.compareAndSet(ImAgent.State.AUTHENTICATING, ImAgent.State.SYNCHRONIZING)

    fun markReady(): Boolean =
        state.compareAndSet(ImAgent.State.SYNCHRONIZING, ImAgent.State.AUTHENTICATED)

    fun disconnect(): ImAgent.State = state.getAndSet(ImAgent.State.DISCONNECTED)
}

/**
 * 同一连接的同步请求游标准入。正常分页必须严格递增；当服务端确认游标不属于当前用户时，
 * RESET 把门槛恢复到 -1，使客户端可以在不重新认证的情况下从 0 重新开始。
 *
 * 无效游标的判定运行在 IO worker，而下一条请求在 Netty EventLoop，因此这里必须是原子状态。
 */
internal class ImAgentSyncCursor {
    private val admitted = AtomicLong(-1L)

    val current: Long get() = admitted.get()

    fun admit(cursor: Long): Boolean {
        require(cursor >= 0L) { "sync cursor must be non-negative" }
        while (true) {
            val previous = admitted.get()
            if (cursor <= previous) return false
            if (admitted.compareAndSet(previous, cursor)) return true
        }
    }

    fun reset() {
        admitted.set(-1L)
    }
}

/**
 * 连接级处理器。管理认证状态和包分发。
 *
 * 线程安全模型：
 * - **EventLoop**（当前线程）：只做轻量操作（PING/PONG/DISCONNECT、数据提取、协程启动）
 * - **IOExecutor**：重量 IO 操作（auth/RPC/message）通过 launchWithAgent 调度
 * - **ImAgentFacade**：WeakReference 门面，协程挂起期间 agent 可被 GC 回收
 *
 * 日志：连接级日志使用 recorder（采样 + 懒加载），非连接级日志使用 slf4j。
 */
class ImAgent(
    val channel: io.netty.channel.Channel,
    val recorder: Recorder,
    private val authService: AuthService,
    private val clientRegistry: ClientRegistry,
    private val rpcDispatcher: RpcDispatcher,
    private val messageService: MessageService,
    private val chatStore: ChatStore,
    private val syncEvents: SyncEventReader,
    private val events: EventPublisher,
    private val ioExecutor: IOExecutor,
) : ChannelInboundHandlerAdapter() {
    enum class State { CONNECTED, AUTHENTICATING, SYNCHRONIZING, AUTHENTICATED, DISCONNECTED }

    companion object {
        /** 未认证连接全局上限：端口扫描/慢速攻击的资源围栏（超限新连接即拒） */
        private val unauthedCount = java.util.concurrent.atomic.AtomicInteger(0)
        private const val MAX_UNAUTHED_CONNECTIONS = 1024
        /** 认证超时：未认证连接最长存活（慢滴保活攻击窗口） */
        private const val AUTH_TIMEOUT_SECONDS = 10L
        /** 身份通过后必须在有界时间内完成持久事件同步，不能占用半开连接。 */
        private const val SYNC_STALL_TIMEOUT_SECONDS = 60L
    }

    private val authState = ImAgentAuthState()
    private val authenticationLifecycleLock = Any()
    private val unauthedSlotHeld = AtomicBoolean(false)
    private val syncRequestInFlight = AtomicBoolean(false)
    private val syncTimeoutGeneration = AtomicLong(0L)
    private val credentialTerminal = AtomicBoolean(false)
    /** 同一连接的分页 cursor 必须严格前进；InvalidCursor 后显式恢复为可接收 0。 */
    private val admittedSyncCursor = ImAgentSyncCursor()
    val state: State get() = authState.current
    @Volatile
    var uid: String = ""; internal set
    @Volatile
    var deviceId: String = ""; internal set
    @Volatile
    internal var userCredentialEpoch: Long = 0L
    @Volatile
    internal var deviceCredentialEpoch: Long = 0L

    /** Full Netty channel id is used for security identity; [channelId] remains log-only. */
    val sessionId: String = channel.id().asLongText()

    /** 连接是否活跃 */
    val isActive: Boolean get() = state != State.DISCONNECTED && channel.isActive
    internal val isCredentialTerminal: Boolean get() = credentialTerminal.get()

    internal fun markCredentialTerminal() {
        credentialTerminal.set(true)
    }

    /** Called only by ClientRegistry's serial looper immediately before publishing this session. */
    internal fun markReadyForLiveActivation(): Boolean = synchronized(authenticationLifecycleLock) {
        channel.isActive && authState.markReady()
    }

    /**
     * Completes the identity phase without exposing mutable connection state to an IO closure.
     * The caller reaches this method through [com.virjar.tk.protocol.executor.ImAgentFacade], so
     * a queued authentication task never owns this handler or its channel strongly.
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
            // channelInactive may win while authentication IO is running. A stale worker must not
            // resurrect that connection or publish partially initialized identity state.
            if (!authState.markSynchronizing()) {
                false
            } else {
                uid = authenticatedUid
                deviceId = authenticatedDeviceId
                userCredentialEpoch = authenticatedUserCredentialEpoch
                deviceCredentialEpoch = authenticatedDeviceCredentialEpoch
                releaseUnauthedSlot()
                channel.pipeline().get(PacketCodec::class.java)
                    ?.maxPayloadLimit = PacketCodec.AUTHED_LIMIT
                recorder.upgrade(authenticatedUid, authenticatedDeviceId)
                true
            }
        }

    internal fun resetSyncAdmission() {
        admittedSyncCursor.reset()
    }

    /** 短 channel ID，用于日志 */
    val channelId: String = channel.id().asShortText()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        // 未认证资源围栏：超限直接拒（不给扫描流量任何握手机会）
        if (unauthedCount.incrementAndGet() > MAX_UNAUTHED_CONNECTIONS) {
            unauthedCount.decrementAndGet()
            recorder.record { "[REJECT] unauthed limit reached" }
            ctx.close()
            return
        }
        unauthedSlotHeld.set(true)
        // 认证超时：慢滴保活绕不过（与读空闲独立——每44s滴1字节可无限续命 readerIdle）
        val timeoutFacade = com.virjar.tk.protocol.executor.ImAgentFacade(this)
        ctx.channel().eventLoop().schedule({
            timeoutFacade.closeIfAuthenticationStalled(AUTH_TIMEOUT_SECONDS)
        }, AUTH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun closeIfAuthenticationStalled(timeoutSeconds: Long) {
        if (state == State.CONNECTED || state == State.AUTHENTICATING) {
            recorder.record { "[AUTH_TIMEOUT] closing after ${timeoutSeconds}s" }
            channel.close()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            // ── 轻量操作：EventLoop 直接处理 ──
            is PingSignal -> write(PongSignal)
            is DisconnectSignal -> ctx.close()

            // ── 重量操作：dispatch 到 IOExecutor ──
            is AuthRequestPayload -> handleAuth(msg)
            is SyncRequestPayload -> handleSyncRequest(msg)
            is InvokePayload -> handleInvoke(msg)
            is Message -> handleMessage(msg)

            else -> recorder.record { "[UNKNOWN] type=${msg::class.simpleName}" }
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            recorder.record { "[IDLE] timeout, closing connection" }
            ctx.close()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        protocolVersionFailureResponse(cause)?.let { response ->
            // A valid TeamTalk preamble with a different version is the one codec failure the
            // peer can act on. Return the stable AUTH_RESP rejection before closing so clients
            // can distinguish an upgrade requirement from packet loss or an unreachable server.
            recorder.record { "[AUTH VERSION] ${response.reason}" }
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
            return
        }
        // Netty 坑：异常传播到 pipeline tail 默认只打日志不关连接——
        // CorruptedFrameException（垃圾流量/错位）抛了也白抛，连接继续挂着。
        // 此处统一兜底断连（编解码错误的既定策略：连接不可信即断）。
        recorder.record({ "[FATAL] pipeline exception, closing" }, cause)
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        synchronized(authenticationLifecycleLock) {
            val previousState = authState.disconnect()
            releaseUnauthedSlot()
            if (uid.isNotEmpty()) {
                // SYNCHRONIZING 连接也已经受凭据 fence 管理，必须对称注销。
                // 下线广播由 ClientRegistry.onLastDeviceOffline 钩子触发（仅最后一台设备）。
                clientRegistry.unregister(this)
            }
        }
        recorder.record { "[CLOSE] uid=$uid" }
    }

    // ── 重量操作（IOExecutor 协程处理） ──

    private fun handleAuth(payload: AuthRequestPayload) {
        // 状态切换必须在 EventLoop 收包路径同步完成。若等 IO worker 执行才切换，攻击者可在
        // 首次认证排队期间继续塞入多份 AUTH；认证成功后的重复 AUTH 同样属于协议违规。
        if (authState.admitAuthentication() == ImAgentAuthAdmission.REJECT_AND_CLOSE) {
            recorder.record { "[AUTH REJECTED] duplicate auth in state=$state; closing" }
            channel.close()
            return
        }
        recorder.record { "[AUTH] type=${payload.authType} device=${payload.deviceId}" }
        val authentication = authService
        val registry = clientRegistry
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            val result = try {
                authentication.authenticate(payload)
            } catch (e: Exception) {
                facade.recorder.record({ "[AUTH] error" }, e)
                AuthenticationResult(
                    response = AuthResponsePayload(code = AuthService.CODE_AUTH_FAILED, reason = "Internal error"),
                    principal = null,
                )
            }
            val response = result.response

            if (response.code == AuthService.CODE_OK) {
                val principal = checkNotNull(result.principal) { "Successful auth result has no principal" }
                check(response.uid == principal.uid) { "Authentication response/principal mismatch" }
                check(payload.deviceId == principal.deviceId) { "Authentication device/principal mismatch" }
                val authenticatedUid = principal.uid
                val completed = facade.completeAuthentication(
                    uid = authenticatedUid,
                    deviceId = principal.deviceId,
                    userCredentialEpoch = principal.userCredentialEpoch,
                    deviceCredentialEpoch = principal.deviceCredentialEpoch,
                )
                if (!completed) return@launchWithAgent
                if (!facade.admitAuthenticated { registry.admitAuthenticated(it) }) {
                    return@launchWithAgent
                }
                facade.recorder.record {
                    "[AUTH] identity accepted uid=$authenticatedUid device=${payload.deviceId}; awaiting sync"
                }
                facade.refreshSyncStallTimeout()
            }

            facade.send(response)
        }
        if (!accepted) {
            // 未收到认证终态的客户端会按连接断开策略退避重连；返回认证失败反而会停止重试。
            recorder.record { "[OVERLOAD] auth rejected; closing for retry" }
            channel.close()
        }
    }

    private fun releaseUnauthedSlot() {
        if (unauthedSlotHeld.compareAndSet(true, false)) unauthedCount.decrementAndGet()
    }

    private fun handleSyncRequest(payload: SyncRequestPayload) {
        if (state != State.SYNCHRONIZING) {
            recorder.record { "[SYNC REJECTED] state=$state" }
            channel.close()
            return
        }
        if (!admittedSyncCursor.admit(payload.lastEventId)) {
            recorder.record {
                "[SYNC REJECTED] cursor did not advance: ${payload.lastEventId} <= ${admittedSyncCursor.current}"
            }
            channel.close()
            return
        }
        if (!syncRequestInFlight.compareAndSet(false, true)) {
            recorder.record { "[SYNC REJECTED] overlapping request" }
            channel.close()
            return
        }
        // This cursor was accepted as strictly newer than the previous request. A backlog may
        // legitimately take many pages, so the timeout measures lack of progress, not total sync
        // duration.
        refreshSyncStallTimeout()
        val requestGate = syncRequestInFlight
        val eventReader = syncEvents
        val registry = clientRegistry
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            try {
                when (val result = eventReader.nextBatchOrActivate(
                    uid = facade.uid,
                    afterEventId = payload.lastEventId,
                    limit = SyncBatchPayload.MAX_EVENTS,
                    activate = { facade.activateLive { registry.activate(it) } },
                )) {
                    is SyncBatchResult.Events -> {
                        val bounded = SyncBatchPayload.boundedPrefix(result.events)
                        if (bounded.isEmpty()) {
                            // The event itself fits a NOTIFY frame, but adding the batch count
                            // would cross the authenticated payload ceiling.
                            facade.send(result.events.first())
                        } else {
                            facade.send(SyncBatchPayload(bounded))
                        }
                        facade.recorder.record {
                            "[SYNC_BATCH] after=${payload.lastEventId} count=${if (bounded.isEmpty()) 1 else bounded.size}"
                        }
                    }
                    SyncBatchResult.Activated ->
                        facade.recorder.record { "[SYNC_READY] cursor=${payload.lastEventId}" }
                    SyncBatchResult.ConnectionClosed -> Unit
                    SyncBatchResult.InvalidCursor -> {
                        facade.recorder.record {
                            "[SYNC_RESET] cursor is not owned by uid: ${payload.lastEventId}"
                        }
                        // Ownership validation runs on the IO worker. Reset the atomic admission
                        // fence before sending RESET so the same authenticated connection can
                        // subsequently admit SYNC_REQUEST(0). It must not enter live delivery.
                        facade.resetSyncAdmission()
                        facade.send(SyncResetPayload)
                    }
                }
            } finally {
                requestGate.set(false)
            }
        }
        if (!accepted) {
            requestGate.set(false)
            recorder.record { "[OVERLOAD] sync request rejected; closing for resumable reconnect" }
            channel.close()
        }
    }

    internal fun refreshSyncStallTimeout() {
        val generation = syncTimeoutGeneration.incrementAndGet()
        val timeoutFacade = com.virjar.tk.protocol.executor.ImAgentFacade(this)
        channel.eventLoop().schedule({
            timeoutFacade.closeIfSyncStalled(generation, SYNC_STALL_TIMEOUT_SECONDS)
        }, SYNC_STALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    internal fun closeIfSyncStalled(generation: Long, timeoutSeconds: Long) {
        if (
            state == State.SYNCHRONIZING &&
            syncTimeoutGeneration.get() == generation
        ) {
            recorder.record { "[SYNC_TIMEOUT] no cursor progress for ${timeoutSeconds}s" }
            channel.close()
        }
    }

    private fun handleInvoke(payload: InvokePayload) {
        if (state != State.AUTHENTICATED || isCredentialTerminal) {
            write(ResponsePayload(payload.requestId, 401, null))
            if (isCredentialTerminal) channel.close()
            return
        }
        val dispatcher = rpcDispatcher
        val dispatch: (
            suspend kotlinx.coroutines.CoroutineScope.(com.virjar.tk.protocol.executor.ImAgentFacade) -> Unit
        ) = dispatch@ { facade ->
            if (facade.isCredentialTerminal) {
                facade.send(ResponsePayload(payload.requestId, 401, null))
                facade.closeConnection()
                return@dispatch
            }
            try {
                val response = dispatcher.dispatch(facade.uid, facade.deviceId, facade.sessionId, payload)
                facade.recorder.record { "[RPC] service=${payload.serviceId} method=${payload.methodId} status=${response.status}" }
                if (
                    response.status == 0 && payload.serviceId == AuthRpcContract.SERVICE &&
                    (payload.methodId == AuthRpcContract.M_UPDATE_PASSWORD ||
                        payload.methodId == AuthRpcContract.M_LOGOUT)
                ) {
                    // The credential mutation has already advanced its fence while preserving
                    // this exact terminal session solely to flush the success response.
                    facade.sendAndClose(response)
                } else {
                    facade.send(response)
                }
            } catch (e: FatalCodecException) {
                // 协议紊乱：连接已不可靠，直接断连 + FATAL 日志，不尝试返回错误响应
                facade.recorder.record(
                    { "[FATAL CODEC] service=${e.service} method=${e.method} uid=${e.uid}: 断开不可靠连接" },
                    e,
                )
                facade.closeConnection()
            }
        }
        // Per-user command order is the security boundary for credential rotation and also keeps
        // cross-device draft writes deterministic. Reads currently share the same bounded queue;
        // optimize with an explicit read/write command model only when profiling justifies it.
        if (!ioExecutor.launchSerialWithAgent("user-command:$uid", this, dispatch)) {
            write(ResponsePayload(payload.requestId, 429, "用户请求过于频繁".encodeToByteArray()))
        }
    }

    private fun handleMessage(msg: Message) {
        if (state != State.AUTHENTICATED || isCredentialTerminal) {
            write(MessageAckPayload(msg.clientMsgId, 0, 401, "Not authenticated"))
            if (isCredentialTerminal) channel.close()
            return
        }

        if (msg.messageType == MessageType.TYPING.code) {
            handleTyping(msg)
            return
        }

        recorder.record { "[SEND] chatId=${msg.chatId} clientMsgId=${msg.clientMsgId} type=${msg.messageType}" }
        val messages = messageService
        val accepted = ioExecutor.launchSerialWithAgent("user-command:$uid", this) { facade ->
            if (facade.isCredentialTerminal) {
                facade.send(MessageAckPayload(msg.clientMsgId, 0, 401, "Credentials rotated"))
                facade.closeConnection()
                return@launchSerialWithAgent
            }
            try {
                val serverSeq = messages.sendMessage(facade.uid, msg)
                facade.send(MessageAckPayload(msg.clientMsgId, serverSeq, 0, null))
                facade.recorder.record { "[SENDACK] clientMsgId=${msg.clientMsgId} serverSeq=$serverSeq" }
            } catch (e: IllegalArgumentException) {
                facade.send(MessageAckPayload(msg.clientMsgId, 0, 400, e.message))
            } catch (e: IndexOutOfBoundsException) {
                // 消息体编解码紊乱：连接不可靠，断连 + FATAL 日志
                facade.recorder.record(
                    { "[FATAL CODEC] 消息体解析越界 clientMsgId=${msg.clientMsgId}: 断开不可靠连接" },
                    e,
                )
                facade.closeConnection()
            } catch (e: Exception) {
                facade.recorder.record({ "[FATAL] 消息处理内部错误 clientMsgId=${msg.clientMsgId}" }, e)
                facade.send(MessageAckPayload(msg.clientMsgId, 0, 500, "服务器内部错误"))
            }
        }
        if (!accepted) {
            write(MessageAckPayload(msg.clientMsgId, 0, 503, "服务器繁忙，请稍后重试"))
        }
    }

    private fun handleTyping(msg: Message) {
        val membership = chatStore
        val publisher = events
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            if (facade.isCredentialTerminal) {
                facade.closeConnection()
                return@launchWithAgent
            }
            try {
                val declared = com.virjar.tk.body.MessageBodyPolicy.canonicalize(msg)
                if (!membership.isMember(declared.chatId, facade.uid)) {
                    facade.recorder.record { "[TYPING REJECTED] uid=${facade.uid} chatId=${declared.chatId}" }
                    return@launchWithAgent
                }
                // TYPING 不进入 MessageService，但仍必须由认证会话重建身份信封。
                val trusted = declared.copy(
                    senderUid = facade.uid,
                    serverSeq = 0,
                    timestamp = System.currentTimeMillis(),
                    flags = 0,
                    sendStatus = Message.SEND_STATUS_SENT,
                    uploadProgress = 0f,
                )
                facade.recorder.record { "[TYPING] chatId=${trusted.chatId}" }
                val memberUids = membership.getMemberUids(trusted.chatId)
                for (memberUid in memberUids) {
                    if (memberUid != facade.uid) {
                        publisher.emitTransient(memberUid, NotifyType.TYPING, trusted)
                    }
                }
            } catch (e: IllegalArgumentException) {
                facade.recorder.record { "[TYPING REJECTED] uid=${facade.uid}: ${e.message}" }
            }
        }
        if (!accepted) {
            // 输入状态是瞬态提示；过载时直接丢弃，不能挤占持久消息/RPC 的恢复窗口。
            recorder.record { "[OVERLOAD] typing dropped uid=$uid chatId=${msg.chatId}" }
        }
    }

    // ── 连接操作 ──

    fun write(msg: IProto) {
        if (channel.isActive) {
            channel.writeAndFlush(msg)
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
        recorder.record { "[KICK] uid=$uid" }
        channel.close()
    }

}

/** Returns an upgrade response only for the dedicated, structurally verified preamble error. */
internal fun protocolVersionFailureResponse(cause: Throwable): AuthResponsePayload? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is ProtocolVersionMismatchException) {
            return AuthResponsePayload(
                code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED,
                reason = "Client protocol ${current.receivedVersion} is unsupported; " +
                    "server requires ${current.supportedVersion}",
            )
        }
        current = current.cause
    }
    return null
}
