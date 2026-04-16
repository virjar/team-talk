package com.virjar.tk.tcp

import com.virjar.tk.env.ThreadIOGuard
import com.virjar.tk.protocol.HandshakeHandler
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketCodec
import com.virjar.tk.protocol.TkLogger
import com.virjar.tk.tcp.trace.Recorder
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TcpServer(
    private val port: Int,
) {
    private val logger = LoggerFactory.getLogger(TcpServer::class.java)
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null

    fun start() {
        bossGroup = NioEventLoopGroup(1, guardedThreadFactory("tk-netty-boss"))
        workerGroup = NioEventLoopGroup(0, guardedThreadFactory("tk-netty-worker"))

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val recorder = Recorder.touch(ch)
                    recorder.record("new connection:$ch")

                    ch.writeAndFlush(Unpooled.wrappedBuffer(IProto.MAGIC_WITH_VERSION))
                        .addListener {
                            val isSuccess = it.isSuccess
                            recorder.record { "magic response write status: $isSuccess" }
                        }

                    val idleStateHandler = IdleStateHandler(
                        IProto.IDLE_TIMEOUT_SECONDS.toLong(), 0, 0, TimeUnit.SECONDS
                    )
                    val handshakeHandler = HandshakeHandler(object : TkLogger {
                        override fun log(msgProvider: () -> String, throwable: Throwable?) {
                            recorder.record({ msgProvider() }, throwable)
                        }
                    }, ch)

                    val setup = Setup(recorder)

                    ch.pipeline().addLast(
                        idleStateHandler, handshakeHandler, setup
                    )
                }
            })
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)

        val future = bootstrap.bind(port).sync()
        logger.info("TCP server started on port {}", port)
        future.channel().closeFuture().addListener {
            stop()
        }
    }

    class Setup(val recorder: Recorder) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg !is ByteBuf) {
                super.channelRead(ctx, msg)
                return
            }
            // 此时握手已经通过，可以挂载编解码和业务handler了
            val imAgent = ImAgent(recorder, ctx.channel())
            ctx.pipeline().apply {
                addLast(PacketCodec(), imAgent)
                remove(this@Setup)
                fireChannelRead(msg)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
            // 这里的异常，只会有setup阶段的，握手阶段拦截了握手的所有异常消息
            recorder.record("proto setup error", cause)
            ctx.close()
        }
    }

    fun stop() {
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        logger.info("TCP server stopped")
    }

    /**
     * 创建自定义 ThreadFactory：包装 Runnable，在 EventLoop 线程执行前 [ThreadIOGuard.markProtected]，
     * finally 中 [ThreadIOGuard.unmarkProtected]，防止阻塞 IO 在 EventLoop 线程上执行。
     */
    private fun guardedThreadFactory(prefix: String): java.util.concurrent.ThreadFactory {
        val counter = AtomicInteger(0)
        return java.util.concurrent.ThreadFactory { r ->
            val wrapped = Runnable {
                ThreadIOGuard.markProtected()
                try {
                    r.run()
                } finally {
                    ThreadIOGuard.unmarkProtected()
                }
            }
            Thread(wrapped, "$prefix-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
}
