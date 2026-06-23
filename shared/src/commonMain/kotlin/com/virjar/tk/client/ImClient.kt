package com.virjar.tk.client

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.ScheduledFuture
import io.netty.channel.EventLoop
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.virjar.tk.log.TkLoggerFactory
import java.util.concurrent.TimeUnit

/**
 * TCP 客户端。完全事件驱动，无阻塞等待。
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
    private val onAuthResult: ((success: Boolean, uid: String?, username: String?, name: String?, refreshToken: String?, failureReason: String?) -> Unit)? = null,
) {
    private val logger = TkLoggerFactory.get("ImClient")

    // 单线程 EventLoop，所有状态串行。
    @Volatile
    private var workerGroup: NioEventLoopGroup = NioEventLoopGroup(1)
    @Volatile
    private var eventLoop: EventLoop = workerGroup.next()

    // 连接级状态（EventLoop 独占）
    private var channel: Channel? = null
    private var scope: CoroutineScope? = null
    private val pendingAcks = mutableMapOf<String, CompletableDeferred<MessageAckPayload>>()

    // 重连
    private var retryCount = 0
    private var destroyed = false
    private var reconnectFuture: ScheduledFuture<*>? = null

    // 连接目标（connect 时设置，重连时复用）
    private var connectHost = host
    private var connectPort = port

    // 认证参数（重连时自动重发）
    private var pendingAuth: AuthRequestPayload? = null

    // 线程安全的观察状态
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val incomingPackets = MutableSharedFlow<IProto>(extraBufferCapacity = 64)
    val packets: SharedFlow<IProto> = incomingPackets.asSharedFlow()

    /** 暴露 scope 供 RpcClient / EventProcessor 复用。 */
    val coroutineScope: CoroutineScope? get() = scope

    // ── 公共 API ──

    /**
     * 原子化认证连接：设置 pendingAuth + connect 在同一个 EventLoop 任务内完成。
     *
     * 消除协程线程与 EventLoop 的竞态：调用方在协程线程上构造 AuthRequestPayload（CPU 工作），
     * 然后调此方法——payload 已构造完毕，此方法内不做 CPU 工作，直接投递一个 EventLoop 任务，
     * 在任务内先设 pendingAuth 再 createAndConnect。TCP 回调一定排在 pendingAuth 设置之后。
     */
    fun connectAndAuth(auth: AuthRequestPayload, host: String, port: Int) {
        logger.trace("connectAndAuth: host=$host, port=$port, authType=${auth.authType}")
        if (destroyed && !ensureEventLoop()) {
            logger.trace("connectAndAuth ignored: ImClient destroyed (EventLoop shut down)")
            return
        }
        doOnEventLoop {
            // 取消待执行的重连定时器（channelInactive 排的），避免与新连接竞争
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            destroyed = false
            pendingAuth = auth           // 先设 pendingAuth
            connectHost = host
            connectPort = port
            createAndConnect()            // 再启动连接（TCP 回调排在 pendingAuth 设置之后）
        }
    }

    fun connect(host: String = this.connectHost, port: Int = this.connectPort) {
        logger.trace("connect: host=$host, port=$port")
        if (destroyed && !ensureEventLoop()) {
            logger.trace("connect() ignored: ImClient destroyed (EventLoop shut down)")
            return
        }
        doOnEventLoop {
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            destroyed = false
            connectHost = host
            connectPort = port
            createAndConnect()
        }
    }

    fun login(username: String, password: String, deviceId: String, deviceName: String, host: String = connectHost, port: Int = connectPort) {
        AuthRules.validateLogin(username, password)
        val auth = AuthRequestPayload(authType = 0, username = username, password = password,
            deviceId = deviceId, deviceName = deviceName)
        logger.trace("login requested: username=$username")
        // pendingAuth + connect 原子化，消除协程/EventLoop 竞态
        connectAndAuth(auth, host, port)
    }

    fun register(username: String, password: String, name: String, deviceId: String, deviceName: String, host: String = connectHost, port: Int = connectPort) {
        AuthRules.validateRegister(username, password)
        val auth = AuthRequestPayload(authType = 1, username = username, password = password,
            name = name, deviceId = deviceId, deviceName = deviceName)
        logger.trace("register requested: username=$username")
        connectAndAuth(auth, host, port)
    }

    fun authenticate(uid: String, token: String, deviceId: String, deviceName: String, host: String = connectHost, port: Int = connectPort) {
        val auth = AuthRequestPayload(authType = 2, refreshToken = token,
            deviceId = deviceId, deviceName = deviceName)
        logger.trace("authenticate requested: uid=$uid")
        connectAndAuth(auth, host, port)
    }

    fun send(proto: IProto) {
        val ch = channel
        if (ch == null) {
            logger.trace("send() called but channel is null, type=${proto::class.simpleName}")
        } else if (_state.value != ConnectionState.AUTHENTICATED
            && proto !is AuthRequestPayload
            && proto !is PingSignal
            && proto !is PongSignal
        ) {
            // 非认证/心跳包：必须在 AUTHENTICATED 状态才发送，防止重连期间业务消息抢先到达服务端
            logger.trace("send() blocked: not authenticated, state=${_state.value}, type=${proto::class.simpleName}")
        } else {
            ch.writeAndFlush(proto)
        }
    }

    /**
     * 发送消息并等待服务端 ACK。
     * withContext(scope) 确保 pendingAcks 操作在 EventLoop 上。
     */
    suspend fun sendAndWaitAck(message: com.virjar.tk.model.Message, timeoutMs: Long = 10_000L): MessageAckPayload {
        val s = scope ?: throw IllegalStateException("Not connected")
        return withContext(s.coroutineContext) {
            val deferred = CompletableDeferred<MessageAckPayload>()
            pendingAcks[message.clientMsgId] = deferred
            send(message)
            try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                pendingAcks.remove(message.clientMsgId)
                MessageAckPayload(message.clientMsgId, 0, -1, "ACK timeout")
            }
        }
    }

    /**
     * 软断开：关闭当前连接 + 取消 scope + 清理 pending，但**保留 EventLoop**，
     * 允许后续 [connect] 复用同一 [ImClient] 实例（UI 登出→重新登录场景）。
     *
     * 与 [destroy] 的区别：[disconnect] 仅切断"这次连接"，[destroy] 才永久销毁线程资源。
     * 登出、认证失效应调用 [disconnect]；进程退出或彻底放弃实例才调用 [destroy]。
     */
    fun disconnect() {
        doOnEventLoop {
            destroyed = true
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            channel?.close()
            scope?.cancel()
            scope = null
            channel = null
            cleanupOnDisconnect()
            // 注意：不 shutdown workerGroup——保留 EventLoop 供 connect() 复用。
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * 永久销毁：软断开 + 关闭 Netty EventLoop 线程。之后此实例不可再用。
     * 仅在进程退出或实例彻底废弃时调用；UI 登出请用 [disconnect]。
     */
    fun destroy() {
        doOnEventLoop {
            destroyed = true
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            channel?.close()
            scope?.cancel()
            scope = null
            channel = null
            cleanupOnDisconnect()
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * 确保 EventLoop 存活。若已被 [destroy] 关闭则重建。
     * @return true 若 EventLoop 当前可用（原有或新建）；false 若重建失败。
     */
    private fun ensureEventLoop(): Boolean {
        if (!eventLoop.isShuttingDown && !eventLoop.isShutdown) return true
        return try {
            workerGroup = NioEventLoopGroup(1)
            eventLoop = workerGroup.next()
            logger.trace("EventLoop rebuilt after shutdown")
            true
        } catch (e: Exception) {
            logger.fault("Failed to rebuild EventLoop", e)
            false
        }
    }

    // ── 连接管理（EventLoop 上执行） ──

    private fun createAndConnect() {
        _state.value = ConnectionState.CONNECTING
        logger.trace("Connecting to $connectHost:$connectPort")

        val bootstrap = Bootstrap()
        bootstrap.group(eventLoop)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(IdleStateHandler(
                            Frame.READ_IDLE_TIMEOUT_SECONDS,
                            Frame.PING_INTERVAL_SECONDS,
                            0, TimeUnit.SECONDS))
                        .addLast(HandshakeHandler())
                }
            })

        bootstrap.connect(connectHost, connectPort).addListener { future ->
            if (!future.isSuccess) {
                logger.trace("Connect failed: ${future.cause()?.message}")
                _state.value = ConnectionState.DISCONNECTED
                if (!destroyed) scheduleReconnect()
            } else {
                logger.trace("TCP connected, waiting for handshake")
            }
        }
    }

    private fun scheduleReconnect() {
        val delay = nextRetryDelay(retryCount)
        retryCount++
        logger.trace("Schedule reconnect in ${delay}ms (retry=$retryCount)")
        reconnectFuture = eventLoop.schedule({ createAndConnect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun nextRetryDelay(count: Int): Long {
        return minOf(30_000L, 1000L * (1L shl minOf(count, 4)))
    }

    // ── 内部方法（EventLoop 上调用） ──

    private fun handleAck(ack: MessageAckPayload) {
        pendingAcks.remove(ack.clientMsgId)?.complete(ack)
            ?: logger.trace("Received ACK for unknown clientMsgId: ${ack.clientMsgId}")
    }

    private fun handleAuthResponse(response: AuthResponsePayload) {
        if (response.code == 0) {
            retryCount = 0
            _state.value = ConnectionState.AUTHENTICATED
            // 认证成功 → 更新 pendingAuth 中的 refreshToken（服务端令牌一次一换，
            // 旧的已被消费，不更新会导致下次重连时 AUTH_FAILED → 掉登录）
            response.refreshToken?.let { newToken ->
                pendingAuth = pendingAuth?.copy(refreshToken = newToken)
            }
            // 认证结果通过回调传给 UserSession（三级状态隔离：ImClient 不持有用户身份）
            onAuthResult?.invoke(true, response.uid, response.username, response.name, response.refreshToken, null)
            logger.trace("Authenticated: uid=${response.uid}, username=${response.username}")
        } else {
            val reason = response.reason ?: "认证失败(code=${response.code})"
            _state.value = ConnectionState.AUTH_FAILED
            onAuthResult?.invoke(false, null, null, null, null, reason)
            logger.trace("Auth failed: code=${response.code}, reason=${response.reason}")
        }
        scope?.launch { incomingPackets.emit(response) }
    }

    /**
     * 连接级清理：只清连接层状态（pendingAcks）。
     *
     * uid/myUsername/myName/refreshToken 是用户层状态（三级状态设计），
     * 用户层状态（uid/refreshToken 等）在 UserSession 中，不受 TCP 断开影响。
     */
    private fun cleanupOnDisconnect() {
        pendingAcks.forEach { (_, deferred) ->
            deferred.completeExceptionally(CancellationException("Connection closed"))
        }
        pendingAcks.clear()
    }

    private fun doOnEventLoop(task: () -> Unit) {
        if (eventLoop.inEventLoop()) {
            task()
        } else {
            // EventLoop 可能已被 destroy() 关闭，execute 会抛 RejectedExecutionException。
            // 退化为同步执行并记日志，避免上层协程崩溃（登录页等场景不希望崩进程）。
            try {
                eventLoop.execute(task)
            } catch (e: RejectedExecutionException) {
                logger.fault("doOnEventLoop rejected (executor ${if (eventLoop.isShutdown) "shut down" else "shutting down"}); running inline", e)
                task()
            }
        }
    }

    // ── Netty Handlers ──

    /**
     * 握手 Handler：等待服务端 MAGIC(2B)+VERSION(1B)，回复后升级 pipeline。
     */
    private inner class HandshakeHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is io.netty.buffer.ByteBuf && msg.readableBytes() >= 3) {
                val magicHigh = msg.readByte()
                val magicLow = msg.readByte()
                val version = msg.readByte()
                if (magicHigh == Frame.MAGIC_HIGH && magicLow == Frame.MAGIC_LOW) {
                    logger.trace("Handshake received: version=$version")

                    val reply = Unpooled.buffer(3)
                    reply.writeByte(Frame.MAGIC_HIGH.toInt())
                    reply.writeByte(Frame.MAGIC_LOW.toInt())
                    reply.writeByte(Frame.PROTOCOL_VERSION.toInt())
                    ctx.writeAndFlush(reply)
                    msg.release()

                    // 设置连接级状态（在 pipeline 升级前设置 channel 引用）
                    channel = ctx.channel()
                    scope = CoroutineScope(eventLoop.asCoroutineDispatcher() + SupervisorJob())
                    _state.value = ConnectionState.CONNECTED

                    // 升级 pipeline：移除握手 handler，添加 PacketCodec + PacketHandler
                    ctx.pipeline().remove(this)
                    ctx.pipeline().addLast(PacketCodec())
                    ctx.pipeline().addLast(PacketHandler())

                    logger.trace("Pipeline upgraded, state=CONNECTED, hasPendingAuth=${pendingAuth != null}")

                    // 通过 channel 发送认证包（不能用 ctx，因为 HandshakeHandler 已从 pipeline 移除）
                    // channel.writeAndFlush 从 pipeline 尾部开始出站，会经过 PacketCodec 编码
                    pendingAuth?.let {
                        logger.trace("Sending auth: type=${it.authType} username=${it.username}")
                        channel?.writeAndFlush(it)
                    }
                    return
                }
            }
            ctx.fireChannelRead(msg)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.fault("Handshake error", cause)
            ctx.close()
        }
    }

    /**
     * 数据阶段 Handler：处理业务包、心跳、断连。
     */
    private inner class PacketHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is IProto) {
                logger.trace("Packet received: type=${msg::class.simpleName}")
                when (msg) {
                    is AuthResponsePayload -> handleAuthResponse(msg)
                    is MessageAckPayload -> handleAck(msg)
                    is PingSignal -> send(PongSignal)
                    else -> {
                        if (!incomingPackets.tryEmit(msg)) {
                            logger.trace("Packet dropped: buffer full, type=${msg::class.simpleName}")
                        }
                    }
                }
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                when (evt.state()) {
                    IdleState.WRITER_IDLE -> {
                        logger.trace("Writer idle, sending PING")
                        send(PingSignal)
                    }
                    IdleState.READER_IDLE -> {
                        logger.trace("No data received for ${Frame.READ_IDLE_TIMEOUT_SECONDS}s, closing connection")
                        ctx.close()
                    }
                    else -> {}
                }
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            if (destroyed) return  // disconnect 已清理
            cleanupOnDisconnect()
            scope?.cancel()
            scope = null
            channel = null
            _state.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.fault("Connection error", cause)
            ctx.close()
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    AUTH_FAILED,
}
