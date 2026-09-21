package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.netty.PacketCodec
import com.virjar.tk.protocol.netty.PacketInboundRole
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher

internal actual fun createClientTransportBackend(tcpTlsCertificatePem: String?): ClientTransportBackend =
    NettyClientTransportBackend(ClientTransportTls(tcpTlsCertificatePem))

/** Netty owns socket/TLS/idle handling; logical ownership and retries live in common code. */
internal class NettyClientTransportBackend(
    private val transportTls: ClientTransportTls = ClientTransportTls(),
) : ClientTransportBackend {
    private val logger = PlatformOnlyTkLogger("NettyTransport")
    private val workerGroup = createClientTransportEventLoopGroup()
    private val eventLoop = workerGroup.next()
    override val dispatcher = eventLoop.asCoroutineDispatcher()
    override fun inEventLoop(): Boolean = eventLoop.inEventLoop()
    override fun enqueue(task: () -> Unit): Boolean = try {
        eventLoop.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }
    override fun schedule(delayMillis: Long, task: () -> Unit): ClientTransportTimer {
        val future = eventLoop.schedule({ task() }, delayMillis, TimeUnit.MILLISECONDS)
        return ClientTransportTimer { future.cancel(false) }
    }
    override fun shutdown() { workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS) }
    override fun createChannel(host: String, port: Int, events: ClientTransportEvents): ClientTransportChannel =
        NettyConnection(host, port, events)

    private inner class NettyConnection(
        private val host: String,
        private val port: Int,
        private val events: ClientTransportEvents,
    ) : ClientTransportChannel {
        private var channel: Channel? = null
        private var closed = false
        private var closurePublished = false
        override val isActive: Boolean get() = !closed && channel?.isActive == true

        override fun start() {
            check(inEventLoop())
            check(channel == null && !closed) { "TCP attempt has already started" }
            try {
                val bootstrap = Bootstrap().group(eventLoop)
                    .channel(NioSocketChannel::class.java)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            channel = ch
                            val pipeline = ch.pipeline()
                            transportTls.newHandler(ch, host, port)?.let { pipeline.addLast("tls", it) }
                            pipeline.addLast(IdleStateHandler(
                                PacketCodec.READ_IDLE_TIMEOUT_SECONDS, PacketCodec.PING_INTERVAL_SECONDS,
                                0, TimeUnit.SECONDS,
                            ))
                            pipeline.addLast(PacketCodec(inboundRole = PacketInboundRole.CLIENT))
                            pipeline.addLast(PacketHandler())
                        }
                    })
                val future = bootstrap.connect(host, port)
                channel = future.channel()
                future.addListener { completed ->
                    val result = completed as ChannelFuture
                    // A failed bootstrap can complete inline while common code installs its AUTH lease.
                    // Neither ready nor failure may retire that lease before installation returns.
                    if (!enqueue { completeConnect(result) }) result.channel().close()
                }
            } catch (failure: Throwable) {
                publishClosed(failure)
                channel?.close()
            }
        }

        private fun completeConnect(future: ChannelFuture) {
            if (closed) { future.channel().close(); return }
            if (!future.isSuccess) {
                publishClosed(future.cause())
                future.channel().close()
                return
            }
            val ssl = future.channel().pipeline().get(SslHandler::class.java)
            if (ssl == null) publishReady() else ssl.handshakeFuture().addListener { handshake ->
                if (closed) { future.channel().close(); return@addListener }
                if (handshake.isSuccess) publishReady() else {
                    logger.trace("TLS handshake failed for $host:$port: ${handshake.cause()?.javaClass?.simpleName}")
                    publishClosed(handshake.cause())
                    future.channel().close()
                }
            }
        }

        private fun publishReady() {
            if (isActive) events.ready(this)
        }

        private fun publishClosed(failure: Throwable?) {
            closed = true
            if (closurePublished) return
            closurePublished = true
            enqueue { events.closed(this, failure) }
        }

        override fun writeAndFlush(proto: IProto) {
            check(isActive) { "TCP channel is closed" }
            checkNotNull(channel).writeAndFlush(proto)
        }
        override fun onAuthenticationAccepted() {
            // PacketCodec raises its bound while decoding AUTH_RESP, before a coalesced sync frame.
        }
        override fun close() {
            closed = true
            val active = channel
            if (active == null) publishClosed(null) else active.close().addListener { publishClosed(it.cause()) }
        }

        private inner class PacketHandler : ChannelInboundHandlerAdapter() {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                if (!closed && msg is IProto) events.packet(this@NettyConnection, msg)
            }
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                if (closed) { ctx.close(); return }
                if (evt !is IdleStateEvent) { super.userEventTriggered(ctx, evt); return }
                when (evt.state()) {
                    IdleState.WRITER_IDLE -> events.writeIdle(this@NettyConnection)
                    IdleState.READER_IDLE -> ctx.close()
                    else -> Unit
                }
            }
            override fun channelInactive(ctx: ChannelHandlerContext) { publishClosed(null) }
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                logger.fault("Connection error", cause)
                publishClosed(cause)
                ctx.close()
            }
        }
    }
}
