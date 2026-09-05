package com.virjar.tk.shared.client

import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.util.concurrent.DefaultThreadFactory

/** Netty 4.2 NIO 组，保留客户端历史上的线程数、优先级与名称。 */
internal fun createClientTransportEventLoopGroup(): EventLoopGroup =
    MultiThreadIoEventLoopGroup(
        1,
        DefaultThreadFactory(CLIENT_EVENT_LOOP_THREAD_POOL_NAME, Thread.MAX_PRIORITY),
        NioIoHandler.newFactory(),
    )

private const val CLIENT_EVENT_LOOP_THREAD_POOL_NAME = "nioEventLoopGroup"

/** EventLoop 持有的单调门禁；过期尝试回调只能比较，绝不能变更。 */
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
