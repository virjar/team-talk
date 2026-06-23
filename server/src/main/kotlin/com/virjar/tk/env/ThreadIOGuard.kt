package com.virjar.tk.env

import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EventLoop 线程保护。防止在 Netty EventLoop 线程上执行阻塞 IO。
 *
 * 注册受保护线程后，调用 [check] 检测当前线程是否安全。
 * 典型用于 Repository/Store 的 DB 操作入口。
 */
object ThreadIOGuard {
    private val logger = LoggerFactory.getLogger("ThreadIOGuard")
    private val protectedThreads = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())
    private val enabled = AtomicBoolean(true)

    fun protectThread(threadId: Long) {
        protectedThreads.add(threadId)
    }

    fun unprotectThread(threadId: Long) {
        protectedThreads.remove(threadId)
    }

    fun protectCurrentThread() = protectThread(Thread.currentThread().id)

    fun check() {
        if (!enabled.get()) return
        if (Thread.currentThread().id in protectedThreads) {
            val name = Thread.currentThread().name
            logger.error("BLOCKING IO on protected thread: {} — this will cause performance degradation", name)
            if (Environment.isDevelopment) {
                throw IllegalStateException("Blocking IO on protected thread: $name. Use IOExecutor.launchWithAgent() instead.")
            }
        }
    }

    fun enable() { enabled.set(true) }
    fun disable() { enabled.set(false) }
}
