package com.virjar.tk.tcp

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.tcp.agent.AuthProcessor
import com.virjar.tk.tcp.agent.HistoryDispatcher
import com.virjar.tk.tcp.agent.MessageDispatcher
import com.virjar.tk.tcp.agent.SubscribeDispatcher
import com.virjar.tk.tcp.agent.TypingDispatcher
import com.virjar.tk.tcp.trace.Recorder
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.timeout.IdleStateEvent

class ImAgent(
    val recorder: Recorder,
    val channel: Channel
) : SimpleChannelInboundHandler<IProto>() {
    lateinit var authRequest: AuthRequestPayload
    private var hasAuth = false

    val uid: String
        get() = authRequest.uid
    val deviceId: String
        get() = authRequest.deviceId
    val isActive: Boolean
        get() = channel.isActive

    override fun channelRead0(ctx: ChannelHandlerContext, msg: IProto) {
        when (msg) {
            is AuthRequestPayload -> {
                val success = AuthProcessor.processAuth(this, msg)
                if (!success) {
                    recorder.record { "auth failed" }
                    channel.close()
                }
            }

            is DisconnectSignal -> {
                recorder.record { "close connection because of receive client disconnect signal" }
                ctx.close()
            }

            is PingSignal -> {
                channel.writeAndFlush(PongSignal)
            }

            is PongSignal -> Unit

            is RecvAckPayload -> Unit

            else -> dispatchAuthedPacket(msg)
        }
    }


    private fun dispatchAuthedPacket(msg: IProto) {
        if (!hasAuth) {
            recorder.record { "this connect is unauthed" }
            return
        }
        when (msg) {
            is Message -> {
                if (msg.body is TypingBody) {
                    TypingDispatcher.handleTyping(this, msg)
                } else if (msg.body is EditBody) {
                    MessageDispatcher.handleEdit(this, msg)
                } else {
                    MessageDispatcher.handleMessage(this, msg)
                }
            }

            is SubscribePayload -> IOExecutor.execute { SubscribeDispatcher.handleSubscribe(this, msg) }
            is UnsubscribePayload -> Unit
            is HistoryLoadPayload -> IOExecutor.execute { HistoryDispatcher.handleHistoryLoad(this, msg) }
            else -> recorder.record { "unknown packet: ${msg.packetType}" }
        }
    }


    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            recorder.record { "idle timeout, closing connection" }
            ctx.close()
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        recorder.record({ "agent exception, close tcp" }, cause)
        ctx.close()
    }

    /**
     * 由 AuthProcessor 在认证成功后调用。
     */
    fun markAuthed(authRequest: AuthRequestPayload) {
        if (hasAuth) {
            recorder.record { "duplicate send auth request" }
            return
        }
        hasAuth = true
        this.authRequest = authRequest
        recorder.record("register connection:$channel")
        ClientRegistry.register(this)
    }

    fun send(proto: IProto) {
        channel.writeAndFlush(proto)
    }

    fun write(proto: IProto) {
        channel.write(proto)
    }

    fun flush() {
        channel.flush()
    }

    fun kick() {
        recorder.record { "kick device" }
        channel.writeAndFlush(DisconnectSignal)
            .addListener {
                channel.close()
            }
    }

    fun sendAuthResp(status: Byte, reason: String) {
        channel.writeAndFlush(AuthResponsePayload(status, reason)).addListener {
            if (!it.isSuccess) {
                recorder.record("send authResp failed", it.cause())
            }
        }
    }
}
