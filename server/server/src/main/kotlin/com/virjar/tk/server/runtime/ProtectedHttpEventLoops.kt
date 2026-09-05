package com.virjar.tk.server.runtime

import com.virjar.tk.server.protocol.executor.guardedNioEventLoopGroup
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel

/**
 * 给 Ktor 提供固定的、受保护的网络 EventLoop。应用调用立即穿过 HTTP
 * 阻塞边界，因此为 Ktor 的调用组共享受保护的 worker 组是安全的。
 * Ktor 仍是拥有者，并随其引擎生命周期关闭两个组。
 */
internal fun NettyApplicationEngine.Configuration.configureProtectedHttpEventLoops() {
    shareWorkGroup = true
    // Ktor 默认请求读取为无限超时。停止发送请求体的客户端
    // 否则会永久占用一个全局有界的 HTTP 调用槽。这是
    // 非活跃超时，不是总上传时长，因此持续推进的大上传不受影响。
    requestReadTimeoutSeconds = HTTP_REQUEST_READ_TIMEOUT_SECONDS
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
private const val HTTP_REQUEST_READ_TIMEOUT_SECONDS = 30
