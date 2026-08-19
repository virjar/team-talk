package com.virjar.tk.protocol.codec

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.event.SyncEventReader
import com.virjar.tk.domain.message.MessageRepository
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.protocol.executor.IOExecutor
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.protocol.dispatcher.FatalCodecException
import com.virjar.tk.protocol.trace.Recorder
import com.virjar.tk.rpc.gen.ConversationRpcContract
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import java.util.concurrent.atomic.AtomicBoolean
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

    fun markAuthenticated(): Boolean =
        state.compareAndSet(ImAgent.State.AUTHENTICATING, ImAgent.State.AUTHENTICATED)

    fun disconnect(): ImAgent.State = state.getAndSet(ImAgent.State.DISCONNECTED)
}

/**
 * 连接级处理器。管理认证状态和包分发。
 *
 * 线程安全模型：
 * - **EventLoop**（当前线程）：只做轻量操作（PING/PONG/DISCONNECT/SUBSCRIBE、数据提取、协程启动）
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
    private val messageStore: MessageRepository,
    private val syncEvents: SyncEventReader,
    private val events: EventPublisher,
    private val presenceService: PresenceService,
    private val ioExecutor: IOExecutor,
) : ChannelInboundHandlerAdapter() {
    enum class State { CONNECTED, AUTHENTICATING, AUTHENTICATED, DISCONNECTED }

    companion object {
        /** 未认证连接全局上限：端口扫描/慢速攻击的资源围栏（超限新连接即拒） */
        private val unauthedCount = java.util.concurrent.atomic.AtomicInteger(0)
        private const val MAX_UNAUTHED_CONNECTIONS = 1024
        /** 认证超时：未认证连接最长存活（慢滴保活攻击窗口） */
        private const val AUTH_TIMEOUT_SECONDS = 10L
    }

    private val authState = ImAgentAuthState()
    private val authenticationLifecycleLock = Any()
    private val unauthedSlotHeld = AtomicBoolean(false)
    val state: State get() = authState.current
    @Volatile
    var uid: String = ""; internal set
    @Volatile
    var deviceId: String = ""; internal set

    /** 连接是否活跃 */
    val isActive: Boolean get() = state != State.DISCONNECTED && channel.isActive

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
        ctx.channel().eventLoop().schedule({
            if (state == State.CONNECTED || state == State.AUTHENTICATING) {
                recorder.record { "[AUTH_TIMEOUT] closing after ${AUTH_TIMEOUT_SECONDS}s" }
                ctx.close()
            }
        }, AUTH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            // ── 轻量操作：EventLoop 直接处理 ──
            is PingSignal -> write(PongSignal)
            is DisconnectSignal -> ctx.close()
            is UnsubscribePayload -> handleUnsubscribe(msg)

            // ── 重量操作：dispatch 到 IOExecutor ──
            is AuthRequestPayload -> handleAuth(msg)
            is InvokePayload -> handleInvoke(msg)
            is Message -> handleMessage(msg)
            is SubscribePayload -> handleSubscribe(msg)

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
            if (previousState == State.AUTHENTICATED && uid.isNotEmpty()) {
                // 与认证成功注册共用生命周期锁，保证 registry 队列中的 register 一定先于 unregister。
                // 下线广播由 ClientRegistry.onLastDeviceOffline 钩子触发（仅最后一台设备）。
                clientRegistry.unregister(this)
            }
        }
        recorder.record { "[CLOSE] uid=$uid" }
    }

    // ── 轻量操作（EventLoop 直接处理） ──

    private fun handleUnsubscribe(payload: UnsubscribePayload) {
        if (state != State.AUTHENTICATED) return
        recorder.record { "[UNSUBSCRIBE] chatId=${payload.chatId}" }
    }

    // ── 重量操作（IOExecutor 协程处理） ──

    private fun handleSubscribe(payload: SubscribePayload) {
        if (state != State.AUTHENTICATED) return
        recorder.record { "[SUBSCRIBE] chatId=${payload.chatId} lastSeq=${payload.lastSeq}" }
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            // 校验成员关系
            if (!chatStore.isMember(payload.chatId, facade.uid)) {
                facade.recorder.record { "[SUBSCRIBE] denied: not member of ${payload.chatId}" }
                return@launchWithAgent
            }

            // 获取离线消息
            val messages = if (payload.lastSeq > 0) {
                messageStore.getHistory(payload.chatId, payload.lastSeq + 1, 100, forward = true)
            } else {
                messageStore.getHistory(payload.chatId, 0, 100, forward = false).reversed()
            }

            for (msg in messages) {
                facade.send(NotifyPayload(0, NotifyType.MESSAGE_RECV.code, ProtoCodec.encode(msg)))
            }
            facade.recorder.record { "[SUBSCRIBE] chatId=${payload.chatId}: sent ${messages.size} history messages" }
        }
        if (!accepted) {
            recorder.record { "[OVERLOAD] subscribe rejected; closing for resumable reconnect" }
            channel.close()
        }
    }

    private fun handleAuth(payload: AuthRequestPayload) {
        // 状态切换必须在 EventLoop 收包路径同步完成。若等 IO worker 执行才切换，攻击者可在
        // 首次认证排队期间继续塞入多份 AUTH；认证成功后的重复 AUTH 同样属于协议违规。
        if (authState.admitAuthentication() == ImAgentAuthAdmission.REJECT_AND_CLOSE) {
            recorder.record { "[AUTH REJECTED] duplicate auth in state=$state; closing" }
            channel.close()
            return
        }
        recorder.record { "[AUTH] type=${payload.authType} device=${payload.deviceId}" }
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            val response = try {
                authService.handleAuth(payload)
            } catch (e: Exception) {
                facade.recorder.record({ "[AUTH] error" }, e)
                AuthResponsePayload(code = AuthService.CODE_AUTH_FAILED, reason = "Internal error")
            }

            if (response.code == AuthService.CODE_OK) {
                val completed = synchronized(authenticationLifecycleLock) {
                    // channelInactive 可能在认证 IO 期间先行发生；断线状态不能被旧任务复活。
                    uid = response.uid!!
                    deviceId = payload.deviceId
                    if (!authState.markAuthenticated()) {
                        false
                    } else {
                        releaseUnauthedSlot() // 已认证：退出未认证围栏
                        // 认证后放开帧限（未认证期间 4KB——慢速攻击的最小权限防御）
                        channel.pipeline().get(PacketCodec::class.java)
                            ?.maxPayloadLimit = PacketCodec.AUTHED_LIMIT
                        recorder.upgrade(uid, deviceId)
                        // 与 channelInactive 共用锁，确保 registry 的异步操作顺序为先注册、后注销。
                        clientRegistry.register(this@ImAgent)
                        true
                    }
                }
                if (!completed) return@launchWithAgent
                recorder.record { "[AUTH] success uid=$uid device=$deviceId" }

                presenceService.broadcastOnline(uid)

                if (payload.lastEventId > 0) {
                    val missedEvents = syncEvents.getEventsAfter(uid, payload.lastEventId)
                    for (event in missedEvents) {
                        facade.send(event)
                    }
                    if (missedEvents.isNotEmpty()) {
                        facade.recorder.record { "[SYNC_REPLAY] replayed ${missedEvents.size} missed events" }
                    }
                }
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

    private fun handleInvoke(payload: InvokePayload) {
        if (state != State.AUTHENTICATED) {
            write(ResponsePayload(payload.requestId, 401, null))
            return
        }
        val dispatch: (
            suspend kotlinx.coroutines.CoroutineScope.(com.virjar.tk.protocol.executor.ImAgentFacade) -> Unit
        ) = { facade ->
            try {
                val response = rpcDispatcher.dispatch(facade.uid, payload)
                facade.recorder.record { "[RPC] service=${payload.serviceId} method=${payload.methodId} status=${response.status}" }
                facade.send(response)
            } catch (e: FatalCodecException) {
                // 协议紊乱：连接已不可靠，直接断连 + FATAL 日志，不尝试返回错误响应
                recorder.record({ "[FATAL CODEC] service=${e.service} method=${e.method} uid=${e.uid}: 断开不可靠连接" }, e)
                channel.close()
            }
        }
        // 草稿是同一用户跨设备共享的“最后写入”状态。按服务端观察到的请求顺序串行执行，
        // 防止旧连接已超时的任务在线程池里晚于新连接的清空请求落库，复活已发送正文。
        if (
            payload.serviceId == ConversationRpcContract.SERVICE &&
            payload.methodId == ConversationRpcContract.M_SET_DRAFT
        ) {
            if (!ioExecutor.launchSerialWithAgent("conversation-draft:$uid", this, dispatch)) {
                write(ResponsePayload(payload.requestId, 429, "草稿请求过于频繁".encodeToByteArray()))
            }
        } else {
            if (!ioExecutor.launchWithAgent(this, dispatch)) {
                write(ResponsePayload(payload.requestId, 503, "服务器繁忙，请稍后重试".encodeToByteArray()))
            }
        }
    }

    private fun handleMessage(msg: Message) {
        if (state != State.AUTHENTICATED) {
            write(MessageAckPayload(msg.clientMsgId, 0, 401, "Not authenticated"))
            return
        }

        if (msg.messageType == MessageType.TYPING.code) {
            handleTyping(msg)
            return
        }

        recorder.record { "[SEND] chatId=${msg.chatId} clientMsgId=${msg.clientMsgId} type=${msg.messageType}" }
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            try {
                val serverSeq = messageService.sendMessage(facade.uid, msg)
                facade.send(MessageAckPayload(msg.clientMsgId, serverSeq, 0, null))
                facade.recorder.record { "[SENDACK] clientMsgId=${msg.clientMsgId} serverSeq=$serverSeq" }
            } catch (e: IllegalArgumentException) {
                facade.send(MessageAckPayload(msg.clientMsgId, 0, 400, e.message))
            } catch (e: IndexOutOfBoundsException) {
                // 消息体编解码紊乱：连接不可靠，断连 + FATAL 日志
                recorder.record({ "[FATAL CODEC] 消息体解析越界 clientMsgId=${msg.clientMsgId}: 断开不可靠连接" }, e)
                channel.close()
            } catch (e: Exception) {
                recorder.record({ "[FATAL] 消息处理内部错误 clientMsgId=${msg.clientMsgId}" }, e)
                facade.send(MessageAckPayload(msg.clientMsgId, 0, 500, "服务器内部错误"))
            }
        }
        if (!accepted) {
            write(MessageAckPayload(msg.clientMsgId, 0, 503, "服务器繁忙，请稍后重试"))
        }
    }

    private fun handleTyping(msg: Message) {
        val accepted = ioExecutor.launchWithAgent(this) { facade ->
            try {
                val declared = com.virjar.tk.body.MessageBodyPolicy.canonicalize(msg)
                if (!chatStore.isMember(declared.chatId, facade.uid)) {
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
                val memberUids = chatStore.getMemberUids(trusted.chatId)
                for (memberUid in memberUids) {
                    if (memberUid != facade.uid) {
                        events.emitTransient(memberUid, NotifyType.TYPING, trusted)
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

    /** 踢下线 */
    fun kick() {
        recorder.record { "[KICK] uid=$uid" }
        channel.close()
    }

}
