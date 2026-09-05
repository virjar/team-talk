package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.netty.PacketCodec
import com.virjar.tk.protocol.netty.PacketInboundRole
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.ScheduledFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Netty 资源与 TCP 尝试生命周期的唯一 owner。
 *
 * 连接/通道/重试/代际状态只在 [eventLoop] 上变更。[destroy] 是终态的；应用拆除后的新登录必须创建
 * 新的 [ImClient]，而普通登出使用 owner 资格的拆除。被拒绝的任务绝不在任意调用方线程上内联运行。
 * 上层只有在代际与通道身份都被校验之后才收到包。
 */
internal class TransportConnectionOwner(
    initialHost: String,
    initialPort: Int,
    private val beginProtocolNegotiation: (connectionGeneration: Long) -> Boolean,
    private val currentAuthenticationAttempt: () -> AuthenticationAttemptLease?,
    private val onAuthenticationTransportAttemptEnded: (AuthenticationAttemptLease?) -> Boolean,
    private val onAuthenticationTransportRetired: (AuthenticationAttemptLease?) -> Unit,
    private val authenticationTerminal: () -> Boolean,
    private val routePacket: (connectionGeneration: Long, IProto) -> Unit,
    private val onTransportDisconnected: () -> Unit,
    private val transportTls: ClientTransportTls = ClientTransportTls(),
    private val openConnection: (Bootstrap, String, Int) -> ChannelFuture =
        { bootstrap, host, port -> bootstrap.connect(host, port) },
) : MessageSendTransport {
    private val logger = PlatformOnlyTkLogger("TransportConnectionOwner")

    private val workerGroup = createClientTransportEventLoopGroup()
    private val eventLoop: EventLoop = workerGroup.next()
    private val terminallyDestroyed = AtomicBoolean(false)

    // EventLoop 拥有的尝试状态。
    private var channel: Channel? = null
    private var connectingChannel: Channel? = null
    private val connectionGeneration = ConnectionGeneration()
    private var retryCount = 0
    private var reconnectJitterSeed = 0u
    private var destroyed = false
    private var reconnectFuture: ScheduledFuture<*>? = null
    /** 被进程本地网络丢失测试接缝暂停的精确逻辑 owner。 */
    private var pausedReconnectOwnerForTest: Long? = null
    /** 该逻辑 transport 跨自动重连拥有的精确 AUTH 能力。 */
    private var logicalAuthenticationAttempt: AuthenticationAttemptLease? = null
    /** 一个全新逻辑 owner，在其调用方发布本地状态之前刻意不能触及 DNS/TCP。 */
    private var preparedInitialOwnerGeneration: Long? = null

    @Volatile
    private var ownerGeneration = 0L

    private val _ownerGeneration = MutableStateFlow(0L)
    val ownerGenerationState: StateFlow<Long> = _ownerGeneration.asStateFlow()

    @Volatile
    private var activeScope: CoroutineScope? = null

    @Volatile
    private var targetHost: String = initialHost

    @Volatile
    private var targetPort: Int = initialPort

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    override val currentOwnerGeneration: Long get() = ownerGeneration
    override val currentConnectionGeneration: Long get() = connectionGeneration.current
    override val coroutineScope: CoroutineScope? get() = activeScope
    val connectHost: String get() = targetHost
    val connectPort: Int get() = targetPort

    /**
     * 启动新的逻辑 transport 租约。[admitAndStart] 在选定 EventLoop 上运行，并且可以在调用提供的
     * 启动边时持有调用方侧能力。把准入保持到 owner/代际推进完成，防止 disconnect 在 A 的 transport
     * owner 仍然当前时观察到已准备的 B。false 让延迟任务成为完全 no-op。
     */
    fun connect(
        host: String,
        port: Int,
        jitterSeed: UInt? = null,
        admitAndStart: ((start: () -> Unit) -> Boolean)? = null,
    ) {
        if (terminallyDestroyed.get()) {
            logger.trace("connect ignored: transport owner is permanently destroyed")
            return
        }
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            var startInvoked = false
            val start = {
                startInvoked = true
                // 过期安装者是真正的 no-op：它绝不能清除之后 disconnect 的 destroyed 标记，
                // 也不能取消该 owner 的重连。所有生命周期变更留在被准入的启动边内部。
                reconnectFuture?.cancel(false)
                reconnectFuture = null
                pausedReconnectOwnerForTest = null
                destroyed = false
                retryCount = 0
                jitterSeed?.let { reconnectJitterSeed = it }
                advanceOwnerGeneration()
                targetHost = host
                targetPort = port
                logicalAuthenticationAttempt = currentAuthenticationAttempt()
                preparedInitialOwnerGeneration = null
                createAndConnect()
            }
            try {
                if (admitAndStart != null && !admitAndStart(start)) {
                    logger.trace("connect ignored: authentication attempt was retired before installation")
                    return@executeOn
                }
                if (admitAndStart == null) start()
            } catch (failure: Throwable) {
                if (startInvoked) {
                    logger.fault("Logical authentication transport failed during installation", failure)
                    // 不从部分进入的安装者内联拆除。以其精确 owner 排队清理，因此并发准入的 B
                    // 要么跟随它，要么使 cleanup(A) 失效。
                    scheduleDisconnectIfOwned(ownerGeneration)
                }
                throw failure
            }
        }
    }

    /**
     * 安装第一个逻辑 transport owner，而不解析或连接其端点。
     *
     * 持久账号启动使用这条狭窄两阶段边：owner 代际对本地 [ClientSession] 可用，而对应的 AUTH 载荷
     * 保持密封在 EventLoop 上，直到 [startPreparedInitialConnect] 被准入。该操作刻意仅限全新 owner；
     * 替代登录继续使用 [connect]，因此绝不在延迟替代者背后留下存活旧通道。
     */
    fun prepareInitialConnect(
        host: String,
        port: Int,
        jitterSeed: UInt = 0u,
        admitAndPrepare: (prepare: () -> Unit) -> Boolean,
    ) {
        if (terminallyDestroyed.get()) {
            logger.trace("prepareInitialConnect ignored: transport owner is permanently destroyed")
            return
        }
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            var prepareInvoked = false
            val prepare = {
                prepareInvoked = true
                check(
                    ownerGeneration == 0L &&
                        channel == null &&
                        connectingChannel == null &&
                        activeScope == null &&
                        reconnectFuture == null &&
                        logicalAuthenticationAttempt == null,
                ) { "Deferred authentication requires a fresh transport owner" }
                destroyed = false
                retryCount = 0
                reconnectJitterSeed = jitterSeed
                advanceOwnerGeneration()
                targetHost = host
                targetPort = port
                logicalAuthenticationAttempt = currentAuthenticationAttempt()
                preparedInitialOwnerGeneration = ownerGeneration
            }
            try {
                if (!admitAndPrepare(prepare)) {
                    logger.trace("prepareInitialConnect ignored: authentication attempt was retired")
                }
            } catch (failure: Throwable) {
                if (prepareInvoked) {
                    logger.fault("Logical authentication transport failed during deferred preparation", failure)
                    scheduleDisconnectIfOwned(ownerGeneration)
                }
                throw failure
            }
        }
    }

    /** 恰好启动一次 [prepareInitialConnect] 安装的全新逻辑 owner。 */
    fun startPreparedInitialConnect(
        admitAndStart: (start: () -> Unit) -> Boolean,
    ) {
        if (terminallyDestroyed.get()) return
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            val preparedGeneration = preparedInitialOwnerGeneration ?: return@executeOn
            var startInvoked = false
            val start = {
                startInvoked = true
                preparedInitialOwnerGeneration = null
                createAndConnect()
            }
            try {
                if (!admitAndStart(start)) {
                    logger.trace("startPreparedInitialConnect ignored: authentication attempt was retired")
                    preparedInitialOwnerGeneration = null
                }
            } catch (failure: Throwable) {
                if (startInvoked) {
                    logger.fault("Prepared authentication transport failed during start", failure)
                    scheduleDisconnectIfOwned(preparedGeneration)
                }
                throw failure
            }
        }
    }

    /** 调度连接拥有的工作。destroy 之后的拒绝是安全 no-op，绝不内联。 */
    fun execute(task: () -> Unit): Boolean =
        !terminallyDestroyed.get() && executeOn(eventLoop, task)

    fun send(proto: IProto) {
        execute { sendNow(proto) }
    }

    /**
     * 调度一次会话拥有的写入，并在 EventLoop 执行点校验其完整租约。仅在调用方线程捕获代际是不够的：
     * 一个退役中的 RPC 可以在登记后被抢占，然后在另一个账号已在该可复用 transport 上认证之后恢复。
     * 载荷绝不能落到该替代通道。
     *
     * [sendAdmission] 由请求/会话拥有，并在取消或会话 stop 时永久变 false。[onResult] 在 EventLoop
     * 上运行并报告载荷是否交给通道；任务拒绝改为通过 Boolean 返回值报告 false。
     */
    fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        proto: IProto,
        onResult: (Boolean) -> Unit,
    ): Boolean = execute {
        onResult(
            sendNowIfOwned(
                expectedOwnerGeneration,
                expectedConnectionGeneration,
                sendAdmission,
                proto,
            ),
        )
    }

    /** 原子 ACK-登记 + 消息发送路径使用的、仅 EventLoop 的租约写入。 */
    override fun sendNowIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        proto: IProto,
    ): Boolean {
        requireEventLoop()
        return sendAdmission.use {
            !terminallyDestroyed.get() &&
                ownerGeneration == expectedOwnerGeneration &&
                connectionGeneration.matches(expectedConnectionGeneration) &&
                sendNow(proto)
        }
    }

    /** AUTH/SYNC/PING 控制路径使用的、仅 EventLoop 的协议写入。 */
    fun writeProtocolNow(proto: IProto): Boolean {
        requireEventLoop()
        val active = channel
        if (active == null || !active.isActive) {
            logger.trace("write ignored: channel is absent/inactive, type=${proto::class.simpleName}")
            return false
        }
        active.writeAndFlush(proto)
        return true
    }

    /** 仅 EventLoop 的公开发送门禁。 */
    fun sendNow(proto: IProto): Boolean {
        requireEventLoop()
        val currentState = _state.value
        if (
            currentState != ConnectionState.AUTHENTICATED &&
            proto !is AuthRequestPayload &&
            !(currentState == ConnectionState.SYNCHRONIZING && proto is SyncRequestPayload) &&
            !(
                currentState == ConnectionState.SYNCHRONIZING &&
                    proto is InvokePayload &&
                    proto.serviceId == SyncRpcContract.SERVICE
            ) &&
            proto !is PingSignal &&
            proto !is PongSignal
        ) {
            logger.trace(
                "send blocked: not authenticated, state=$currentState, " +
                    "type=${proto::class.simpleName}",
            )
            return false
        }
        return writeProtocolNow(proto)
    }

    fun transitionTo(state: ConnectionState) {
        requireEventLoop()
        _state.value = state
    }

    fun onAuthenticationAccepted() {
        requireEventLoop()
        retryCount = 0
    }

    fun closeForRecoveryNow(reason: String, cause: Throwable? = null) {
        requireEventLoop()
        if (cause == null) logger.fault(reason) else logger.fault(reason, cause)
        (channel ?: connectingChannel)?.close()
    }

    /** 预期服务器背压：进入正常重连退避，而不产生 fault 级噪音。 */
    fun retryAuthenticationNow(reason: String) {
        requireEventLoop()
        logger.trace(reason)
        (channel ?: connectingChannel)?.close()
    }

    /** 过期 AUTH 响应只能退役投递它的那个连接代际。 */
    fun retryAuthenticationIfCurrent(expectedConnectionGeneration: Long, reason: String) {
        requireEventLoop()
        if (!connectionGeneration.matches(expectedConnectionGeneration)) return
        retryAuthenticationNow(reason)
    }

    fun closeForRecoveryIfCurrent(
        expectedConnectionGeneration: Long,
        reason: String,
        cause: Throwable? = null,
    ) {
        requireEventLoop()
        if (!connectionGeneration.matches(expectedConnectionGeneration)) return
        closeForRecoveryNow(reason, cause)
    }

    fun disconnectIfOwned(expectedOwnerGeneration: Long) {
        execute { disconnectIfOwnedNow(expectedOwnerGeneration) }
    }

    /**
     * 总是入队，包括从 EventLoop 本身。因此公开登出可以在 B 推进其 owner 时被重入调用，
     * 而不会拆掉 B 安装的一半。
     */
    fun scheduleDisconnectIfOwned(expectedOwnerGeneration: Long) {
        if (terminallyDestroyed.get()) return
        val scheduled = enqueueOn(eventLoop) { disconnectIfOwnedNow(expectedOwnerGeneration) }
        check(scheduled || terminallyDestroyed.get()) {
            "Reusable transport EventLoop rejected owner-qualified disconnect"
        }
    }

    /** 幂等地拆除连接，然后释放 EventLoopGroup。 */
    fun destroy() {
        if (!terminallyDestroyed.compareAndSet(false, true)) return
        if (!executeOn(eventLoop) {
            disconnectCurrentTransport()
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        }) {
            // 拒绝意味着 owner 循环已经在停止；关闭是幂等的，且不在调用方线程上运行任何
            // 连接状态变更。
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        }
    }

    /** 测试钩子：网络丢失保留逻辑 owner，并走重连路径。 */
    fun simulateNetworkDrop() {
        execute { (channel ?: connectingChannel)?.close() }
    }

    /**
     * 用于确定性单客户端离线窗口的测试接缝。
     *
     * 只有该 transport owner 受影响：当前通道被正常关闭，但其自动重连保持到
     * [resumeReconnectAfterSimulatedDrop]。主机网络与每个其他 client 保持不动。
     */
    fun simulateNetworkDropAndPauseReconnect() {
        execute {
            val active = channel ?: connectingChannel ?: return@execute
            pausedReconnectOwnerForTest = ownerGeneration
            active.close()
        }
    }

    /** 恢复被 [simulateNetworkDropAndPauseReconnect] 暂停的精确逻辑 owner。 */
    fun resumeReconnectAfterSimulatedDrop() {
        execute {
            if (pausedReconnectOwnerForTest != ownerGeneration) return@execute
            pausedReconnectOwnerForTest = null
            if (
                _state.value == ConnectionState.DISCONNECTED &&
                !destroyed &&
                !terminallyDestroyed.get() &&
                !authenticationTerminal() &&
                reconnectFuture == null
            ) {
                scheduleReconnect()
            }
        }
    }

    private fun disconnectCurrentTransport() {
        requireEventLoop()
        if (
            destroyed &&
            channel == null &&
            connectingChannel == null &&
            activeScope == null &&
            reconnectFuture == null &&
            logicalAuthenticationAttempt == null
        ) {
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        destroyed = true
        pausedReconnectOwnerForTest = null
        preparedInitialOwnerGeneration = null
        advanceOwnerGeneration()
        // 在 close() 可以把其 channelInactive 回调入队之前使 handler 失效。
        connectionGeneration.invalidate()
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val active = channel
        val connecting = connectingChannel
        val authenticationAttempt = logicalAuthenticationAttempt
        channel = null
        connectingChannel = null
        logicalAuthenticationAttempt = null
        _state.value = ConnectionState.DISCONNECTED
        onTransportDisconnected()
        onAuthenticationTransportRetired(authenticationAttempt)
        activeScope?.cancel()
        activeScope = null
        active?.close()
        if (connecting !== active) connecting?.close()
    }

    private fun disconnectIfOwnedNow(expectedOwnerGeneration: Long) {
        requireEventLoop()
        if (ownerGeneration != expectedOwnerGeneration) {
            logger.trace(
                "Ignoring disconnect from retired transport owner=$expectedOwnerGeneration, " +
                    "current=$ownerGeneration",
            )
            return
        }
        disconnectCurrentTransport()
    }

    private fun createAndConnect() {
        requireEventLoop()
        preparedInitialOwnerGeneration = null
        // 先递增：来自每个被取代尝试的回调在观察上变得失效。
        val generation = connectionGeneration.next()
        val previousActive = channel
        val previousConnecting = connectingChannel
        channel = null
        connectingChannel = null
        _state.value = ConnectionState.CONNECTING
        onTransportDisconnected()
        activeScope?.cancel()
        activeScope = null
        previousActive?.close()
        if (previousConnecting !== previousActive) previousConnecting?.close()

        val attemptHost = targetHost
        val attemptPort = targetPort
        logger.trace("Connecting to $attemptHost:$attemptPort (generation=$generation)")

        val bootstrap = Bootstrap()
        bootstrap.group(eventLoop)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val pipeline = ch.pipeline()
                    transportTls.newHandler(ch, attemptHost, attemptPort)?.let { sslHandler ->
                        // 入站 TLS 记录必须在分帧之前解密；出站协议帧必须在 SslHandler 加密之前编码。
                        pipeline.addLast(TLS_HANDLER_NAME, sslHandler)
                    }
                    pipeline
                        .addLast(
                            IdleStateHandler(
                                PacketCodec.READ_IDLE_TIMEOUT_SECONDS,
                                PacketCodec.PING_INTERVAL_SECONDS,
                                0,
                                TimeUnit.SECONDS,
                            ),
                        )
                        .addLast(PacketCodec(inboundRole = PacketInboundRole.CLIENT))
                        .addLast(PacketHandler(generation))
                }
            })

        val connectFuture = openConnection(bootstrap, attemptHost, attemptPort)
        connectingChannel = connectFuture.channel()
        connectFuture.addListener { future ->
            val completed = future as ChannelFuture
            // 完成的 future 可能从 bootstrap.connect() 内联通知其监听器。逻辑 owner 那时仍在
            // 其 AUTH 租约下被安装，因此内联失败不能退役同一租约。总是跨越一次 EventLoop 队列边。
            val scheduled = enqueueOn(eventLoop) {
                handleConnectCompletion(
                    future = completed,
                    generation = generation,
                    peerHost = attemptHost,
                    peerPort = attemptPort,
                )
            }
            if (!scheduled) {
                completed.channel().close()
            }
        }
    }

    private fun handleConnectCompletion(
        future: ChannelFuture,
        generation: Long,
        peerHost: String,
        peerPort: Int,
    ) {
        requireEventLoop()
        val connectedChannel = future.channel()
        if (!connectionGeneration.matches(generation) || destroyed) {
            logger.trace(
                "Ignoring stale connect completion " +
                    "(generation=$generation, current=${connectionGeneration.current})",
            )
            connectedChannel.close()
            return
        }
        if (!future.isSuccess) {
            if (connectingChannel === connectedChannel) connectingChannel = null
            logger.trace("Connect failed: ${future.cause()?.message}")
            _state.value = ConnectionState.DISCONNECTED
            val shouldReconnect = onAuthenticationTransportAttemptEnded(
                logicalAuthenticationAttempt,
            )
            if (!shouldReconnect) logicalAuthenticationAttempt = null
            if (!destroyed && shouldReconnect) scheduleReconnect()
            return
        }

        val sslHandler = connectedChannel.pipeline().get(SslHandler::class.java)
        if (sslHandler == null) {
            onTransportReady(connectedChannel, generation)
        } else {
            awaitTlsHandshake(
                connectedChannel = connectedChannel,
                sslHandler = sslHandler,
                generation = generation,
                peerHost = peerHost,
                peerPort = peerPort,
            )
        }
    }

    private fun awaitTlsHandshake(
        connectedChannel: Channel,
        sslHandler: SslHandler,
        generation: Long,
        peerHost: String,
        peerPort: Int,
    ) {
        requireEventLoop()
        sslHandler.handshakeFuture().addListener { handshake ->
            if (!connectionGeneration.matches(generation) || destroyed) {
                logger.trace("Ignoring stale TLS handshake completion generation=$generation")
                connectedChannel.close()
                return@addListener
            }
            if (!handshake.isSuccess) {
                // 把凭据保留在 AuthSyncCoordinator 内。关闭通道复用现有 channelInactive 分类
                // 与有界重连生命周期。
                logger.trace(
                    "TLS handshake failed for $peerHost:$peerPort: " +
                        "${handshake.cause()?.javaClass?.simpleName}",
                )
                connectedChannel.close()
                return@addListener
            }
            onTransportReady(connectedChannel, generation)
        }
    }

    private fun onTransportReady(connectedChannel: Channel, generation: Long) {
        requireEventLoop()
        if (!connectionGeneration.matches(generation) || destroyed || !connectedChannel.isActive) {
            connectedChannel.close()
            return
        }
        if (connectingChannel === connectedChannel) connectingChannel = null
        channel = connectedChannel
        activeScope = CoroutineScope(
            eventLoop.asCoroutineDispatcher() +
                SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    logger.fault("ImClient connection scope unhandled exception", throwable)
                },
        )
        _state.value = ConnectionState.CONNECTED

        beginProtocolNegotiation(generation)
    }

    private fun scheduleReconnect() {
        requireEventLoop()
        if (
            destroyed ||
            terminallyDestroyed.get() ||
            authenticationTerminal() ||
            reconnectFuture != null ||
            pausedReconnectOwnerForTest == ownerGeneration
        ) return
        val delay = reconnectRetryDelayMillis(retryCount, reconnectJitterSeed)
        retryCount += 1
        val disconnectedGeneration = connectionGeneration.current
        logger.trace("Schedule reconnect in ${delay}ms (retry=$retryCount)")
        reconnectFuture = eventLoop.schedule({
            reconnectFuture = null
            if (
                !destroyed &&
                !terminallyDestroyed.get() &&
                !authenticationTerminal() &&
                connectionGeneration.matches(disconnectedGeneration)
            ) {
                // 自动重连保持同一 ClientSession transport 租约。
                createAndConnect()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun advanceOwnerGeneration() {
        check(ownerGeneration < Long.MAX_VALUE) { "Transport owner generation exhausted" }
        ownerGeneration += 1
        _ownerGeneration.value = ownerGeneration
    }

    private fun executeOn(loop: EventLoop, task: () -> Unit): Boolean {
        if (loop.inEventLoop()) {
            task()
            return true
        }
        return enqueueOn(loop, task)
    }

    private fun enqueueOn(loop: EventLoop, task: () -> Unit): Boolean {
        return try {
            loop.execute(task)
            true
        } catch (rejected: RejectedExecutionException) {
            logger.trace(
                "EventLoop rejected task after shutdown: ${rejected::class.simpleName}",
            )
            false
        }
    }

    private fun requireEventLoop() {
        check(eventLoop.inEventLoop()) {
            "Transport connection state must be mutated on its EventLoop"
        }
    }

    /** Netty handler 只拥有 transport 有效性/空闲/失活；协议含义留在 router。 */
    private inner class PacketHandler(
        private val generation: Long,
    ) : ChannelInboundHandlerAdapter() {
        private fun isCurrent(ctx: ChannelHandlerContext): Boolean =
            connectionGeneration.matches(generation) &&
                (channel === ctx.channel() || connectingChannel === ctx.channel())

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (!isCurrent(ctx)) {
                logger.trace("Ignoring packet from stale channel generation=$generation")
                return
            }
            if (msg is IProto) routePacket(generation, msg)
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (!isCurrent(ctx)) {
                ctx.close()
                return
            }
            if (evt !is IdleStateEvent) {
                super.userEventTriggered(ctx, evt)
                return
            }
            when (evt.state()) {
                IdleState.WRITER_IDLE -> {
                    logger.trace("Writer idle, sending PING")
                    sendNow(PingSignal)
                }
                IdleState.READER_IDLE -> {
                    logger.trace(
                        "No data received for ${PacketCodec.READ_IDLE_TIMEOUT_SECONDS}s, closing connection",
                    )
                    ctx.close()
                }
                else -> Unit
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            if (destroyed || !isCurrent(ctx)) {
                logger.trace("Ignoring channelInactive from stale generation=$generation")
                return
            }
            val terminalAuthentication = authenticationTerminal()
            _state.value = if (terminalAuthentication) {
                ConnectionState.AUTH_FAILED
            } else {
                ConnectionState.DISCONNECTED
            }
            onTransportDisconnected()
            activeScope?.cancel()
            activeScope = null
            if (channel === ctx.channel()) channel = null
            if (connectingChannel === ctx.channel()) connectingChannel = null
            val shouldReconnect = onAuthenticationTransportAttemptEnded(
                logicalAuthenticationAttempt,
            )
            if (!shouldReconnect) logicalAuthenticationAttempt = null
            if (terminalAuthentication || !shouldReconnect) {
                if (pausedReconnectOwnerForTest == ownerGeneration) {
                    pausedReconnectOwnerForTest = null
                }
                return
            }
            scheduleReconnect()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.fault("Connection error", cause)
            ctx.close()
        }
    }
}

private const val TLS_HANDLER_NAME = "tls"
