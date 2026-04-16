package com.virjar.tk.client

import com.virjar.tk.protocol.Handshake
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.DisconnectSignal
import com.virjar.tk.protocol.payload.PingSignal
import com.virjar.tk.util.AppLog
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit

/**
 * 连接层回调。所有回调在 EventLoop 线程上执行。
 */
interface ConnectionListener {
    fun onConnected(conn: TcpConnection)
    fun onDisconnected(conn: TcpConnection)
    fun onAuthFailed(conn: TcpConnection, code: Byte, reason: String)
    fun onProtoReceived(conn: TcpConnection, proto: IProto)
}

/**
 * 单次 TCP 连接。连接 + 认证 + 心跳 + 数据 I/O。
 *
 * 新协议流程：
 * 1. TCP 连接建立
 * 2. 服务端发送 MAGIC_WITH_VERSION(8 bytes)
 * 3. 客户端发送 AUTH 包（PacketCodec 编码的 AuthRequestPayload）
 * 4. 服务端回复 AUTH_RESP
 * 5. 认证成功后进入数据阶段
 *
 * Pipeline 结构：
 * ```
 * 握手前: idle → ClientHandshakeHandler（处理原始 ByteBuf）
 * 握手后: idle → PacketCodec → ClientHandler（处理 IProto 对象）
 * ```
 */
class TcpConnection(
    private val host: String,
    private val port: Int,
    private val eventLoop: EventLoop,
    private val listener: ConnectionListener,
) {
    enum class State { CONNECTING, CONNECTED, CLOSED }

    private var channel: Channel? = null
    var state: State = State.CONNECTING
        private set

    // 认证参数，在 connect 时保存，收到 MAGIC 后发送
    private var pendingAuth: AuthRequestPayload? = null

    private var connectedInvoked = false
    private var disconnectedInvoked = false

    fun isActive(): Boolean = state == State.CONNECTED && channel?.isActive == true

    fun connect(uid: String, token: String, deviceId: String, timeoutMs: Long = 10_000) {
        try {
            pendingAuth = AuthRequestPayload(
                token = token,
                uid = uid,
                deviceId = deviceId,
                deviceName = "",
                deviceModel = "",
                clientVersion = "1.0.0",
                flags = 2
            )

            val bootstrap = Bootstrap()
            bootstrap.group(eventLoop)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs.toInt())
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().apply {
                            addLast("idle", IdleStateHandler(0, Handshake.CLIENT_PING_INTERVAL_SECONDS.toLong(), 0, TimeUnit.SECONDS))
                            addLast("handshake", ClientHandshakeHandler(this@TcpConnection, listener))
                        }
                    }
                })

            val future = bootstrap.connect(host, port)
            future.addListener { connectFuture ->
                if (!connectFuture.isSuccess) {
                    invokeOnDisconnected()
                    return@addListener
                }
                channel = future.channel()
                channel!!.closeFuture().addListener { invokeOnDisconnected() }
            }
        } catch (e: Exception) {
            AppLog.e("TcpConnection", "connect failed", e)
            invokeOnDisconnected()
        }
    }

    /**
     * 由 ClientHandshakeHandler 在收到 MAGIC_WITH_VERSION 后调用。
     * 先发送 MAGIC + VERSION，再发送 AUTH 包。
     */
    internal fun onMagicReceived() {
        val auth = pendingAuth ?: return
        val ch = channel ?: return

        // 先发送 MAGIC + VERSION（服务端 HandshakeHandler 需要验证）
        val magicBuf = Unpooled.wrappedBuffer(IProto.MAGIC_WITH_VERSION)
        ch.write(magicBuf)

        // 再发送 AUTH 包：PacketType(1) + Length(4) + Payload
        val payloadBuf = Unpooled.buffer()
        auth.writeTo(payloadBuf)
        val packetBuf = Unpooled.buffer(1 + 4 + payloadBuf.readableBytes())
        packetBuf.writeByte(PacketType.AUTH.code.toInt())
        packetBuf.writeInt(payloadBuf.readableBytes())
        packetBuf.writeBytes(payloadBuf)
        payloadBuf.release()
        ch.writeAndFlush(packetBuf)
    }

    /**
     * 创建 ClientHandler 实例，供 ClientHandshakeHandler 在握手成功后添加到 pipeline。
     */
    internal fun createClientHandler(): ChannelInboundHandlerAdapter = ClientHandler()

    /**
     * 发送 IProto 对象。PacketCodec 的 encode() 会自动处理出站编码。
     */
    fun sendProto(proto: IProto) {
        val ch = channel ?: return
        if (state != State.CONNECTED) return
        ch.writeAndFlush(proto)
    }

    fun close() {
        try {
            val ch = channel ?: return
            sendProto(DisconnectSignal)
            ch.close()?.await(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        state = State.CLOSED
    }

    // ── 供 ClientHandshakeHandler 回调的方法 ──

    internal fun onHandshakeSuccess() {
        invokeOnConnected()
    }

    internal fun onAuthFailed() {
        disconnectedInvoked = true
        state = State.CLOSED
    }

    private fun invokeOnConnected() {
        if (connectedInvoked) return
        connectedInvoked = true
        doOnMainThread {
            state = State.CONNECTED
            listener.onConnected(this@TcpConnection)
        }
    }

    private fun invokeOnDisconnected() {
        if (disconnectedInvoked) return
        disconnectedInvoked = true
        doOnMainThread {
            state = State.CLOSED
            listener.onDisconnected(this@TcpConnection)
        }
    }

    private fun doOnMainThread(task: () -> Unit) {
        if (eventLoop.inEventLoop()) {
            task()
        } else {
            eventLoop.execute(task)
        }
    }

    // ── 内部 Handler ──

    /**
     * 数据阶段 Handler：接收已解码的 IProto 对象，处理心跳和生命周期。
     */
    private inner class ClientHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg !is IProto) {
                ctx.fireChannelRead(msg)
                return
            }
            doOnMainThread {
                listener.onProtoReceived(this@TcpConnection, msg)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                sendProto(PingSignal)
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            invokeOnDisconnected()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            AppLog.e("TcpConnection", "exceptionCaught", cause)
            ctx.close()
        }
    }
}
