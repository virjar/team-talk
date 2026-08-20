package com.virjar.tk.protocol

import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.executor.IOExecutor
import com.virjar.tk.protocol.trace.Recorder
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import java.net.InetSocketAddress
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TcpServer(
    private val port: Int = 5100,
    private val ioExecutor: IOExecutor = IOExecutor(),
) {
    private val logger = LoggerFactory.getLogger("TcpServer")
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null
    private val childChannels = DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    private val stopped = AtomicBoolean(false)

    @Synchronized
    fun start(agentFactory: (Channel, Recorder, IOExecutor) -> ImAgent) {
        check(!stopped.get()) { "TCP server is already stopped" }
        check(channel == null) { "TCP server is already started" }
        var newBossGroup: EventLoopGroup? = null
        var newWorkerGroup: EventLoopGroup? = null
        var bindingChannel: Channel? = null
        try {
            val createdBossGroup = NioEventLoopGroup(1)
            newBossGroup = createdBossGroup
            val createdWorkerGroup = NioEventLoopGroup()
            newWorkerGroup = createdWorkerGroup
            val bootstrap = ServerBootstrap()
            bootstrap.group(createdBossGroup, createdWorkerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        childChannels.add(ch)
                        val recorder = Recorder.touch(ch)
                        val agent = agentFactory(ch, recorder, ioExecutor)
                        ch.pipeline()
                            // readerIdle=3倍心跳间隔(45s)，与客户端一致。
                            // 僵死连接最多45s被发现（此前60s太久）。
                            .addLast(IdleStateHandler(
                                com.virjar.tk.protocol.PacketCodec.READ_IDLE_TIMEOUT_SECONDS,
                                0, 0, TimeUnit.SECONDS))
                            // 当前协议无独立握手层：客户端首帧 AUTH 即连接序言
                            //（帧头 MAGIC+VERSION 由 PacketCodec 首帧校验，误连/版本不符即断）
                            // 服务端只接受请求方向的包；响应/推送类型在 payload 累积和解码前拒绝。
                            .addLast(PacketCodec(inboundRole = PacketInboundRole.SERVER))
                            .addLast(agent)
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

            val bindFuture = bootstrap.bind(port)
            bindingChannel = bindFuture.channel()
            bindFuture.sync()
            val boundChannel = checkNotNull(bindingChannel)
            bossGroup = createdBossGroup
            workerGroup = createdWorkerGroup
            channel = boundChannel
        } catch (error: Throwable) {
            stopped.set(true)
            runCatching { bindingChannel?.close()?.syncUninterruptibly() }.onFailure(error::addSuppressed)
            runCatching { childChannels.close().awaitUninterruptibly() }.onFailure(error::addSuppressed)
            runCatching { ioExecutor.shutdown() }.onFailure(error::addSuppressed)
            runCatching { shutdownEventLoop(newBossGroup) }.onFailure(error::addSuppressed)
            runCatching { shutdownEventLoop(newWorkerGroup) }.onFailure(error::addSuppressed)
            throw error
        }
        logger.info("TCP server started on port $port")
    }

    @Synchronized
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val listeningChannel = channel
        val openedBossGroup = bossGroup
        val openedWorkerGroup = workerGroup
        channel = null
        bossGroup = null
        workerGroup = null

        // Stop accepting first, then close every accepted connection and wait until
        // channelInactive has run before the owning runtime stops ClientRegistry.
        var failure: Throwable? = null
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        closePart { listeningChannel?.close()?.syncUninterruptibly() }
        closePart { childChannels.close().awaitUninterruptibly() }
        closePart { ioExecutor.shutdown() }
        closePart { shutdownEventLoop(openedBossGroup) }
        closePart { shutdownEventLoop(openedWorkerGroup) }
        logger.info("TCP server stopped")
        failure?.let { throw it }
    }

    private fun shutdownEventLoop(group: EventLoopGroup?) {
        if (group == null) return
        group.shutdownGracefully(0, EVENT_LOOP_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .syncUninterruptibly()
    }

    /** 获取实际监听端口（支持 port=0 随机端口） */
    val actualPort: Int
        get() {
            val ch = channel ?: throw IllegalStateException("Server not started")
            return (ch.localAddress() as InetSocketAddress).port
        }

    private companion object {
        const val EVENT_LOOP_SHUTDOWN_TIMEOUT_SECONDS = 10L
    }
}
