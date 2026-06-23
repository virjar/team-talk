package com.virjar.tk.protocol.codec

import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.protocol.executor.IOExecutor
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.protocol.dispatcher.FatalCodecException
import com.virjar.tk.protocol.trace.Recorder
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import java.util.concurrent.atomic.AtomicReference

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
    private val messageStore: MessageStore,
    private val syncEventService: SyncEventService,
    private val presenceService: PresenceService,
    private val ioExecutor: IOExecutor,
) : ChannelInboundHandlerAdapter() {
    enum class State { CONNECTED, AUTHENTICATED, DISCONNECTED }

    private val _state = AtomicReference(State.CONNECTED)
    val state: State get() = _state.get()
    @Volatile
    var uid: String = ""; internal set
    @Volatile
    var deviceId: String = ""; internal set

    /** 连接是否活跃 */
    val isActive: Boolean get() = state != State.DISCONNECTED && channel.isActive

    /** 短 channel ID，用于日志 */
    val channelId: String = channel.id().asShortText()

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

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (state == State.AUTHENTICATED && uid.isNotEmpty()) {
            clientRegistry.unregister(this)
            // 广播下线
            ioExecutor.launchWithAgent(this) { facade ->
                presenceService.broadcastOffline(facade.uid)
            }
        }
        _state.set(State.DISCONNECTED)
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
        ioExecutor.launchWithAgent(this) { facade ->
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
    }

    private fun handleAuth(payload: AuthRequestPayload) {
        recorder.record { "[AUTH] type=${payload.authType} device=${payload.deviceId}" }
        ioExecutor.launchWithAgent(this) { facade ->
            val response = try {
                authService.handleAuth(payload)
            } catch (e: Exception) {
                facade.recorder.record({ "[AUTH] error" }, e)
                AuthResponsePayload(code = AuthService.CODE_AUTH_FAILED, reason = "Internal error")
            }

            if (response.code == AuthService.CODE_OK) {
                uid = response.uid!!
                deviceId = payload.deviceId
                _state.set(State.AUTHENTICATED)
                recorder.upgrade(uid, deviceId)
                clientRegistry.register(this@ImAgent)
                recorder.record { "[AUTH] success uid=$uid device=$deviceId" }

                presenceService.broadcastOnline(uid)

                if (payload.lastEventId > 0) {
                    val missedEvents = syncEventService.getEventsAfter(uid, payload.lastEventId)
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
    }

    private fun handleInvoke(payload: InvokePayload) {
        if (state != State.AUTHENTICATED) {
            write(ResponsePayload(payload.requestId, 401, null))
            return
        }
        ioExecutor.launchWithAgent(this) { facade ->
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
        ioExecutor.launchWithAgent(this) { facade ->
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
    }

    private fun handleTyping(msg: Message) {
        recorder.record { "[TYPING] chatId=${msg.chatId}" }
        ioExecutor.launchWithAgent(this) { facade ->
            val memberUids = chatStore.getMemberUids(msg.chatId)
            syncEventService.emitEvents(
                memberUids.filter { it != facade.uid },
                NotifyType.TYPING,
                msg,
            )
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
