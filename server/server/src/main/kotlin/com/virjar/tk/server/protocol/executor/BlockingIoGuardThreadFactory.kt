package com.virjar.tk.server.protocol.executor

import com.virjar.tk.server.env.ThreadIOGuard
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.util.concurrent.DefaultThreadFactory
import java.util.concurrent.ThreadFactory

/**
 * 给传输 worker 线程的完整生命周期添加阻塞 IO 禁令。
 *
 * 委托方仍拥有线程类型、命名、优先级与守护策略（因此 Netty 保持
 * 其 FastThreadLocal 清理语义）。保护始终在 `finally` 中移除，
 * 包括异常退出的 EventLoop。
 */
internal class BlockingIoGuardThreadFactory(
    private val delegate: ThreadFactory,
) : ThreadFactory {
    override fun newThread(command: Runnable): Thread = delegate.newThread {
        ThreadIOGuard.protectCurrentThread()
        try {
            command.run()
        } finally {
            ThreadIOGuard.unprotectCurrentThread()
        }
    }
}

/** 创建服务器自有网络线程唯一被允许的 NIO EventLoopGroup 形态。 */
internal fun guardedNioEventLoopGroup(threadCount: Int, threadName: String): EventLoopGroup =
    MultiThreadIoEventLoopGroup(threadCount, BlockingIoGuardThreadFactory(DefaultThreadFactory(threadName)),
        NioIoHandler.newFactory(),
    )
