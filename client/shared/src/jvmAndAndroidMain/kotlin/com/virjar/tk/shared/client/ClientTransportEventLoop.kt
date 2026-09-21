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
