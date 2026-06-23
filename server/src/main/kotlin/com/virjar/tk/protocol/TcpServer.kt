package com.virjar.tk.protocol

import com.virjar.tk.protocol.codec.HandshakeHandler
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.executor.IOExecutor
import com.virjar.tk.protocol.trace.Recorder
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class TcpServer(
    private val port: Int = 5100,
    private val ioExecutor: IOExecutor = IOExecutor(),
) {
    private val logger = LoggerFactory.getLogger("TcpServer")
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    fun start(agentFactory: (Channel, Recorder, IOExecutor) -> ImAgent) {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()

        val bootstrap = ServerBootstrap()
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val recorder = Recorder.touch(ch)
                    val agent = agentFactory(ch, recorder, ioExecutor)
                    ch.pipeline()
                        // readerIdle=3倍心跳间隔(45s)，与客户端一致。
                        // 僵死连接最多45s被发现（此前60s太久）。
                        .addLast(IdleStateHandler(
                            com.virjar.tk.protocol.Frame.READ_IDLE_TIMEOUT_SECONDS,
                            0, 0, TimeUnit.SECONDS))
                        .addLast(HandshakeHandler())
                        .addLast(PacketCodec())
                        .addLast(agent)
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)

        channel = bootstrap.bind(port).sync().channel()
        logger.info("TCP server started on port $port")
    }

    fun stop() {
        channel?.close()?.sync()
        ioExecutor.shutdown()
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        logger.info("TCP server stopped")
    }

    /** 获取实际监听端口（支持 port=0 随机端口） */
    val actualPort: Int
        get() {
            val ch = channel ?: throw IllegalStateException("Server not started")
            return (ch.localAddress() as InetSocketAddress).port
        }
}
