package com.virjar.tk.client

import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.PacketInboundRole
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
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
 * Sole owner of Netty resources and TCP-attempt lifecycle.
 *
 * Connection/channel/retry/generation state is mutated only on [eventLoop]. [destroy] is terminal;
 * a new login after application teardown must create a new [ImClient], while ordinary logout uses
 * [disconnect]. Rejected tasks are never run inline on an arbitrary caller thread. Higher layers
 * receive packets only after both generation and channel identity have been validated.
 */
internal class TransportConnectionOwner(
    initialHost: String,
    initialPort: Int,
    private val authenticationPayload: () -> AuthRequestPayload?,
    private val authenticationTerminal: () -> Boolean,
    private val routePacket: (connectionGeneration: Long, IProto) -> Unit,
    private val onTransportDisconnected: () -> Unit,
) {
    private val logger = TkLoggerFactory.get("TransportConnectionOwner")

    private val workerGroup = NioEventLoopGroup(1)
    private val eventLoop: EventLoop = workerGroup.next()
    private val terminallyDestroyed = AtomicBoolean(false)

    // EventLoop-owned attempt state.
    private var channel: Channel? = null
    private var connectingChannel: Channel? = null
    private val connectionGeneration = ConnectionGeneration()
    private var retryCount = 0
    private var destroyed = false
    private var reconnectFuture: ScheduledFuture<*>? = null

    @Volatile
    private var ownerGeneration = 0L

    @Volatile
    private var activeScope: CoroutineScope? = null

    @Volatile
    private var targetHost: String = initialHost

    @Volatile
    private var targetPort: Int = initialPort

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    val currentOwnerGeneration: Long get() = ownerGeneration
    val currentConnectionGeneration: Long get() = connectionGeneration.current
    val coroutineScope: CoroutineScope? get() = activeScope
    val connectHost: String get() = targetHost
    val connectPort: Int get() = targetPort

    /**
     * Starts a new logical transport lease. [beforeConnect] runs on the selected EventLoop before
     * any old attempt is retired or TCP callbacks can run, which keeps AUTH setup atomic.
     */
    fun connect(
        host: String,
        port: Int,
        beforeConnect: () -> Unit = {},
    ) {
        if (terminallyDestroyed.get()) {
            logger.trace("connect ignored: transport owner is permanently destroyed")
            return
        }
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            destroyed = false
            retryCount = 0
            advanceOwnerGeneration()
            beforeConnect()
            targetHost = host
            targetPort = port
            createAndConnect()
        }
    }

    /** Schedule connection-owned work. Rejection after destroy is a safe no-op, never inline. */
    fun execute(task: () -> Unit): Boolean =
        !terminallyDestroyed.get() && executeOn(eventLoop, task)

    fun send(proto: IProto) {
        execute { sendNow(proto) }
    }

    /** EventLoop-only protocol write used by AUTH/SYNC/PING control paths. */
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

    /** EventLoop-only public-send gate. */
    fun sendNow(proto: IProto): Boolean {
        requireEventLoop()
        val currentState = _state.value
        if (
            currentState != ConnectionState.AUTHENTICATED &&
            proto !is AuthRequestPayload &&
            !(currentState == ConnectionState.SYNCHRONIZING && proto is SyncRequestPayload) &&
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

    fun closeForRecoveryIfCurrent(
        expectedConnectionGeneration: Long,
        reason: String,
        cause: Throwable? = null,
    ) {
        requireEventLoop()
        if (!connectionGeneration.matches(expectedConnectionGeneration)) return
        closeForRecoveryNow(reason, cause)
    }

    fun disconnect() {
        execute(::disconnectCurrentTransport)
    }

    fun disconnectIfOwned(expectedOwnerGeneration: Long) {
        execute {
            if (ownerGeneration != expectedOwnerGeneration) {
                logger.trace(
                    "Ignoring disconnect from retired transport owner=$expectedOwnerGeneration, " +
                        "current=$ownerGeneration",
                )
                return@execute
            }
            disconnectCurrentTransport()
        }
    }

    /** Idempotently tears down the connection and then releases the EventLoopGroup. */
    fun destroy() {
        if (!terminallyDestroyed.compareAndSet(false, true)) return
        if (!executeOn(eventLoop) {
            disconnectCurrentTransport()
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        }) {
            // Rejection means the owner loop is already stopping; shutdown is idempotent and does
            // not run any connection state mutation on the caller thread.
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        }
    }

    /** Test hook: network loss retains the logical owner and follows the reconnect path. */
    fun simulateNetworkDrop() {
        execute { (channel ?: connectingChannel)?.close() }
    }

    private fun disconnectCurrentTransport() {
        requireEventLoop()
        if (
            destroyed &&
            channel == null &&
            connectingChannel == null &&
            activeScope == null &&
            reconnectFuture == null
        ) {
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        destroyed = true
        advanceOwnerGeneration()
        // Invalidate handlers before close() can enqueue their channelInactive callbacks.
        connectionGeneration.invalidate()
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val active = channel
        val connecting = connectingChannel
        channel = null
        connectingChannel = null
        _state.value = ConnectionState.DISCONNECTED
        onTransportDisconnected()
        activeScope?.cancel()
        activeScope = null
        active?.close()
        if (connecting !== active) connecting?.close()
    }

    private fun createAndConnect() {
        requireEventLoop()
        // Increment first: callbacks from every superseded attempt become observationally inert.
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

        logger.trace("Connecting to $targetHost:$targetPort (generation=$generation)")

        val bootstrap = Bootstrap()
        bootstrap.group(eventLoop)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
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

        val connectFuture = bootstrap.connect(targetHost, targetPort)
        connectingChannel = connectFuture.channel()
        connectFuture.addListener { future ->
            val connectedChannel = (future as io.netty.channel.ChannelFuture).channel()
            if (!connectionGeneration.matches(generation) || destroyed) {
                logger.trace(
                    "Ignoring stale connect completion " +
                        "(generation=$generation, current=${connectionGeneration.current})",
                )
                connectedChannel.close()
                return@addListener
            }
            if (connectingChannel === connectedChannel) connectingChannel = null
            if (!future.isSuccess) {
                logger.trace("Connect failed: ${future.cause()?.message}")
                _state.value = ConnectionState.DISCONNECTED
                if (!destroyed) scheduleReconnect()
            } else {
                onTcpReady(connectedChannel, generation)
            }
        }
    }

    private fun onTcpReady(connectedChannel: Channel, generation: Long) {
        requireEventLoop()
        if (!connectionGeneration.matches(generation) || destroyed) {
            connectedChannel.close()
            return
        }
        channel = connectedChannel
        activeScope = CoroutineScope(
            eventLoop.asCoroutineDispatcher() +
                SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    logger.fault("ImClient connection scope unhandled exception", throwable)
                },
        )
        _state.value = ConnectionState.CONNECTED

        authenticationPayload()?.let { auth ->
            logger.trace("Sending auth: type=${auth.authType}")
            writeProtocolNow(auth)
        }
    }

    private fun scheduleReconnect() {
        requireEventLoop()
        if (destroyed || terminallyDestroyed.get() || authenticationTerminal() || reconnectFuture != null) return
        val delay = nextRetryDelay(retryCount)
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
                // Automatic reconnect keeps the same ClientSession transport lease.
                createAndConnect()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun nextRetryDelay(count: Int): Long =
        minOf(30_000L, 1000L * (1L shl minOf(count, 5)))

    private fun advanceOwnerGeneration() {
        check(ownerGeneration < Long.MAX_VALUE) { "Transport owner generation exhausted" }
        ownerGeneration += 1
    }

    private fun executeOn(loop: EventLoop, task: () -> Unit): Boolean {
        if (loop.inEventLoop()) {
            task()
            return true
        }
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

    /** Netty handler owns only transport validity/idle/inactive; protocol meaning stays in router. */
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
            if (terminalAuthentication) return
            scheduleReconnect()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.fault("Connection error", cause)
            ctx.close()
        }
    }
}

/** EventLoop-owned monotonic gate; stale attempt callbacks can only compare, never mutate. */
internal class ConnectionGeneration {
    @Volatile
    var current: Long = 0L
        private set

    fun next(): Long {
        check(current < Long.MAX_VALUE) { "Connection generation exhausted" }
        current += 1
        return current
    }

    fun invalidate() {
        check(current < Long.MAX_VALUE) { "Connection generation exhausted" }
        current += 1
    }

    fun matches(candidate: Long): Boolean = candidate == current
}
