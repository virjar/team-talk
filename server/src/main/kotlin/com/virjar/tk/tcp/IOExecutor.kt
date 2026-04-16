package com.virjar.tk.tcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * IO 线程池，用于将重量级操作（DB/消息存储）从 Netty EventLoop 上卸载。
 *
 * EventLoop 适合处理轻量网络操作（PING/PONG/DISCONNECT/RECVACK），
 * 重量操作（消息存储、好友查询、频道成员查询）应 dispatch 到此线程池。
 *
 * Channel.writeAndFlush() 是线程安全的（Netty 内部会提交到对应 EventLoop），
 * IO 线程可以直接调用。
 */
object IOExecutor {
    private val logger = LoggerFactory.getLogger(IOExecutor::class.java)

    private val pool: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(4),
        object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread {
                return Thread(r, "tk-io-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }
    )

    private val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())

    fun execute(block: () -> Unit) {
        pool.execute {
            try {
                block()
            } catch (e: Exception) {
                logger.error("IOExecutor task failed", e)
            }
        }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                logger.error("IOExecutor coroutine task failed", e)
            }
        }
    }

    fun shutdown() {
        pool.shutdown()
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            pool.shutdownNow()
        }
    }
}
