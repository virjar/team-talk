package com.virjar.tk.server.env

/**
 * 面向绝不允许执行阻塞 IO 的线程的 fail-fast 守卫。
 *
 * 保护需显式开启且线程封闭。嵌套深度支持独立持有的包装者；
 * 最后一个匹配的 [unprotectCurrentThread] 移除 ThreadLocal 条目，使池化线程
 * 在其持有者退出后不残留守卫状态。普通 IO 工作线程、registry 循环线程、trace
 * 工作线程及其他显式阻塞的持有者仍然放行，除非其生命周期自行开启保护。
 */
object ThreadIOGuard {
    private val protectionDepth = ThreadLocal<Int>()

    fun protectCurrentThread() {
        val current = protectionDepth.get() ?: 0
        check(current < Int.MAX_VALUE) { "Blocking-IO guard nesting overflow" }
        protectionDepth.set(current + 1)
    }

    fun unprotectCurrentThread() {
        val current = protectionDepth.get()
            ?: throw IllegalStateException("Current thread is not protected from blocking IO")
        check(current > 0) { "Invalid blocking-IO guard nesting depth: $current" }
        if (current == 1) {
            protectionDepth.remove()
        } else {
            protectionDepth.set(current - 1)
        }
    }

    /**
     * 在阻塞资源的最低公共边界调用。受保护传输 EventLoop 上的误调用
     * 会在借用连接或开始 IO 之前就失败。
     */
    fun check(operation: String = "blocking IO") {
        if ((protectionDepth.get() ?: 0) > 0) {
            throw BlockingIoOnProtectedThreadException(operation, Thread.currentThread().name)
        }
    }
}

class BlockingIoOnProtectedThreadException(
    operation: String,
    threadName: String,
) : IllegalStateException("$operation is forbidden on protected thread '$threadName'")
