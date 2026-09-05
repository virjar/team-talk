package com.virjar.tk.server.env

/**
 * Fail-fast guard for threads that must never perform blocking IO.
 *
 * Protection is opt-in and thread-confined. A nesting depth supports independently owned wrappers;
 * the final matching [unprotectCurrentThread] removes the ThreadLocal entry so pooled threads do
 * not retain guard state after their owner exits. Ordinary IO workers, registry loopers, trace
 * workers and other explicitly blocking owners remain allowed unless their lifecycle protects them.
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
     * Called at the lowest common boundary of a blocking resource. An accidental call on a
     * protected transport EventLoop fails before borrowing a connection or starting the IO.
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
