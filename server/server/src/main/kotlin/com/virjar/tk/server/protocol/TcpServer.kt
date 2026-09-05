package com.virjar.tk.server.protocol

import com.virjar.tk.protocol.netty.PacketCodec
import com.virjar.tk.protocol.netty.PacketInboundRole
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventSink
import com.virjar.tk.server.protocol.executor.IOExecutor
import com.virjar.tk.server.protocol.executor.guardedNioEventLoopGroup
import com.virjar.tk.server.protocol.trace.Recorder
import com.virjar.tk.server.protocol.trace.TraceRuntime
import com.virjar.tk.server.runtime.RuntimeFailureCollector
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.util.concurrent.GlobalEventExecutor
import java.net.InetSocketAddress
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TcpServer internal constructor(
    private val configuration: TcpServerConfiguration,
    private val ioExecutor: IOExecutor = IOExecutor(),
    traceRuntime: TraceRuntime? = null,
    private val connections: TcpConnectionAdmission = TcpConnectionAdmission(),
    private val unauthenticatedConnections: UnauthenticatedConnectionAdmission =
        UnauthenticatedConnectionAdmission(),
    traceEventSink: ConnectionTraceEventSink = ConnectionTraceEventSink { true },
) {
    private val traceRuntime = traceRuntime ?: TraceRuntime(eventSink = traceEventSink)
    private val logger = LoggerFactory.getLogger("TcpServer")
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null
    // stayClosed 会关闭在 stop() 已关闭组之后才完成注册的子连接。
    private val childChannels = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
    private val stopped = AtomicBoolean(false)
    /** 受此 TcpServer 的监视器守卫；@Synchronized 让并发调用方等待拥有者。 */
    private var stopFailure: Throwable? = null

    @Synchronized
    internal fun start(
        agentFactory: (Channel, Recorder, IOExecutor) -> ChannelInboundHandler,
    ) {
        check(!stopped.get()) { "TCP server is already stopped" }
        check(channel == null) { "TCP server is already started" }
        var newBossGroup: EventLoopGroup? = null
        var newWorkerGroup: EventLoopGroup? = null
        var bindingChannel: Channel? = null
        try {
            val createdBossGroup = guardedEventLoopGroup(1, "teamtalk-tcp-boss")
            newBossGroup = createdBossGroup
            val createdWorkerGroup = guardedEventLoopGroup(0, "teamtalk-tcp-worker")
            newWorkerGroup = createdWorkerGroup
            val bootstrap = ServerBootstrap()
            bootstrap.group(createdBossGroup, createdWorkerGroup)
                .channel(NioServerSocketChannel::class.java)
                // ServerBootstrap 在其私有接受器之前立即安装此父处理器。
                // 因此它在 boss EventLoop 上、于 worker 注册之前运行。
                .handler(AcceptedChannelAdmissionHandler(connections, unauthenticatedConnections))
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        childChannels.add(ch)
                        if (stopped.get()) {
                            ch.close()
                            return
                        }
                        when (val security = configuration.security) {
                            is TcpTransportSecurity.Tls -> {
                                val sslHandler = security.context.newHandler(ch.alloc()).apply {
                                    setHandshakeTimeoutMillis(TLS_HANDSHAKE_TIMEOUT_MILLIS)
                                }
                                ch.pipeline()
                                    .addLast(TLS_HANDLER_NAME, sslHandler)
                                    .addLast(
                                        TLS_HANDSHAKE_GATE_NAME,
                                        TlsHandshakeGateHandler {
                                            installProtocolPipeline(ch, agentFactory)
                                        },
                                    )
                            }
                            TcpTransportSecurity.Plaintext ->
                                installProtocolPipeline(ch, agentFactory)
                        }
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

            val bindFuture = bootstrap.bind(configuration.bindHost, configuration.port)
            bindingChannel = bindFuture.channel()
            bindFuture.sync()
            val boundChannel = checkNotNull(bindingChannel)
            bossGroup = createdBossGroup
            workerGroup = createdWorkerGroup
            channel = boundChannel
        } catch (error: Throwable) {
            stopped.set(true)
            val cleanupFailures = RuntimeFailureCollector()
            cleanupFailures.capture { bindingChannel?.close()?.syncUninterruptibly() }
            cleanupFailures.capture { childChannels.close().awaitUninterruptibly() }
            cleanupFailures.capture { traceRuntime.close() }
            cleanupFailures.capture { ioExecutor.shutdown() }
            cleanupFailures.capture { shutdownEventLoop(newBossGroup) }
            cleanupFailures.capture { shutdownEventLoop(newWorkerGroup) }
            // 在 boss 上被接受的子连接可能仍在等待 worker 注册。
            // 在断言租约计数之前，worker 终止必须使该 future 得以确定。
            cleanupFailures.capture { requireConnectionLeasesReleased() }
            throw cleanupFailures.failureOrNull()
                ?.let { mergeRuntimeFailure(error, it) }
                ?: error
        }
        logger.info(
            "TCP server started on {}:{} ({})",
            configuration.bindHost,
            actualPort,
            if (configuration.security is TcpTransportSecurity.Tls) "TLS" else "plaintext",
        )
    }

    @Synchronized
    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            stopFailure?.let { throw it }
            return
        }
        val listeningChannel = channel
        val openedBossGroup = bossGroup
        val openedWorkerGroup = workerGroup
        channel = null
        bossGroup = null
        workerGroup = null

        // 先停止接受，然后关闭每个已接受的连接，并等到
        // channelInactive 运行之后，拥有者运行时才会停止 ClientRegistry。
        val failures = RuntimeFailureCollector()
        failures.capture { listeningChannel?.close()?.syncUninterruptibly() }
        failures.capture { childChannels.close().awaitUninterruptibly() }
        failures.capture { traceRuntime.close() }
        failures.capture { ioExecutor.shutdown() }
        failures.capture { shutdownEventLoop(openedBossGroup) }
        failures.capture { shutdownEventLoop(openedWorkerGroup) }
        failures.capture { requireConnectionLeasesReleased() }
        failures.failureOrNull()?.let {
            stopFailure = it
            throw it
        }
        logger.info("TCP server stopped")
    }

    private fun shutdownEventLoop(group: EventLoopGroup?) {
        if (group == null) return
        group.shutdownGracefully(0, EVENT_LOOP_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .syncUninterruptibly()
    }

    private fun requireConnectionLeasesReleased() {
        val total = connections.activeCount
        val unauthenticated = unauthenticatedConnections.activeCount
        check(total == 0 && unauthenticated == 0) {
            "TCP channels closed with $total total and $unauthenticated unauthenticated connection lease(s)"
        }
    }

    private fun guardedEventLoopGroup(threadCount: Int, threadName: String): EventLoopGroup =
        guardedNioEventLoopGroup(threadCount, threadName)

    /** 在 TLS 建立对端传输边界之前，不安装任何协议对象。 */
    private fun installProtocolPipeline(
        channel: Channel,
        agentFactory: (Channel, Recorder, IOExecutor) -> ChannelInboundHandler,
    ) {
        check(channel.eventLoop().inEventLoop()) { "TCP protocol pipeline must be installed on its EventLoop" }
        val recorder = Recorder.touch(channel, traceRuntime)
        val agent = agentFactory(channel, recorder, ioExecutor)
        channel.pipeline()
            // readerIdle=3倍心跳间隔(45s)，与客户端一致。
            .addLast(
                IDLE_HANDLER_NAME,
                IdleStateHandler(
                    PacketCodec.READ_IDLE_TIMEOUT_SECONDS,
                    0,
                    0,
                    TimeUnit.SECONDS,
                ),
            )
            // 服务端只接受请求方向的包；响应/推送类型在 payload 累积和解码前拒绝。
            .addLast(PACKET_CODEC_NAME, PacketCodec(inboundRole = PacketInboundRole.SERVER))
            .addLast(IM_AGENT_NAME, agent)
    }

    /** 获取实际监听端口（支持 port=0 随机端口） */
    val actualPort: Int
        get() {
            val ch = channel ?: throw IllegalStateException("Server not started")
            return (ch.localAddress() as InetSocketAddress).port
        }

    private companion object {
        const val EVENT_LOOP_SHUTDOWN_TIMEOUT_SECONDS = 10L
        const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
    }
}

private class TlsHandshakeGateHandler(
    private val activateProtocol: () -> Unit,
) : ChannelInboundHandlerAdapter() {
    private var activated = false

    override fun userEventTriggered(context: ChannelHandlerContext, event: Any) {
        if (event !is SslHandshakeCompletionEvent) {
            context.fireUserEventTriggered(event)
            return
        }
        if (!event.isSuccess) {
            context.close()
            return
        }
        if (activated) {
            context.close()
            return
        }
        activated = true
        try {
            activateProtocol()
            context.pipeline().remove(this)
            context.fireUserEventTriggered(event)
        } catch (failure: Throwable) {
            context.fireExceptionCaught(failure)
            context.close()
        }
    }

    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        // SslHandler 必须已发布握手完成，之后才有解密的应用字节。在此保留一个
        // fail-closed 断言，使将来的流水线重排无法在握手前暴露 AUTH。
        io.netty.util.ReferenceCountUtil.release(message)
        context.close()
    }

    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
        context.close()
    }
}

internal const val TLS_HANDLER_NAME = "teamtalk-tls"
internal const val TLS_HANDSHAKE_GATE_NAME = "teamtalk-tls-handshake-gate"
internal const val IDLE_HANDLER_NAME = "teamtalk-idle"
internal const val PACKET_CODEC_NAME = "teamtalk-packet-codec"
internal const val IM_AGENT_NAME = "teamtalk-im-agent"
