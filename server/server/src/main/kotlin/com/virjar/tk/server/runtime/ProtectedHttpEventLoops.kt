package com.virjar.tk.server.runtime

import com.virjar.tk.server.protocol.executor.guardedNioEventLoopGroup
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler

/**
 * 给 Ktor 提供固定的、受保护的网络 EventLoop。应用调用立即穿过 HTTP
 * 阻塞边界，因此为 Ktor 的调用组共享受保护的 worker 组是安全的。
 * Ktor 仍是拥有者，并随其引擎生命周期关闭两个组。
 */
internal fun NettyApplicationEngine.Configuration.configureProtectedHttpEventLoops(
    connectionIdleTimeoutSeconds: Int = HTTP_CONNECTION_IDLE_TIMEOUT_SECONDS,
) {
    require(connectionIdleTimeoutSeconds > 0)
    shareWorkGroup = true
    // Ktor 的 read timeout 监控整个连接：GET 读完后，即使响应持续发送也会超时。
    // 改为双向空闲约束，既回收停止上传的连接，也允许持续推进的大文件下载。
    requestReadTimeoutSeconds = 0
    channelPipelineConfig = {
        // 放在 Ktor 的 codec/业务 handler 之前，避免它们消费事件后看不到实际读写。
        addFirst("teamtalkHttpIdleTimeout", object : IdleStateHandler(0, 0, connectionIdleTimeoutSeconds) {
            override fun channelIdle(context: ChannelHandlerContext, event: IdleStateEvent) {
                context.close()
            }
        })
    }
    val connectionGroup = protectedHttpEventLoopGroup(HTTP_CONNECTION_EVENT_LOOP_THREADS, "connection")
    val workerGroup = protectedHttpEventLoopGroup(HTTP_WORKER_EVENT_LOOP_THREADS, "worker")
    configureBootstrap = {
        group(connectionGroup, workerGroup)
        channel(NioServerSocketChannel::class.java)
    }
}

private fun protectedHttpEventLoopGroup(threadCount: Int, role: String): EventLoopGroup =
    guardedNioEventLoopGroup(threadCount, "teamtalk-http-$role")

private const val HTTP_CONNECTION_EVENT_LOOP_THREADS = 1
private const val HTTP_WORKER_EVENT_LOOP_THREADS = 2
private const val HTTP_CONNECTION_IDLE_TIMEOUT_SECONDS = 30
