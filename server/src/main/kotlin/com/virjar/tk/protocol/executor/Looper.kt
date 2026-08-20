package com.virjar.tk.protocol.executor

import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import com.virjar.tk.env.ThreadIOGuard
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单线程事件循环，类似 Android Looper。
 *
 * 所有投递到 Looper 的任务在同一个 daemon 线程中串行执行，
 * 从而消除对 ConcurrentHashMap 的需求——普通 HashMap 受 Looper 线程保护即可。
 */
class Looper(val name: String) {
    private val logger = LoggerFactory.getLogger(Looper::class.java)

    private val queue = LinkedBlockingDeque<Runnable>()
    private val lifecycleLock = Any()
    private val stopSignal = Runnable { }
    private val thread: Thread
    private val scheduler = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "$name-scheduler").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    init {
        thread = Thread({ loop() }, name).apply { isDaemon = true }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            thread.start()
            logger.info("Looper '{}' started", name)
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (stopped.compareAndSet(false, true)) {
                // The signal is queued after every task accepted under the same lock. The loop
                // drains those tasks before exiting, while all later submissions fail fast.
                queue.offer(stopSignal)
                scheduler.shutdown()
                logger.info("Looper '{}' stopping", name)
            }
        }
    }

    fun post(block: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (stopped.get()) false else queue.offer(Runnable { block() })
    }

    suspend fun <T> suspendAwait(block: () -> T): T {
        if (Thread.currentThread() == thread) {
            return block()
        }
        return suspendCancellableCoroutine { cont ->
            val accepted = synchronized(lifecycleLock) {
                if (stopped.get()) {
                    false
                } else {
                    queue.offer(Runnable {
                        try {
                            val result = block()
                            cont.resumeWith(Result.success(result))
                        } catch (e: Throwable) {
                            cont.resumeWithException(e)
                        }
                    })
                }
            }
            if (!accepted) {
                cont.resumeWithException(IllegalStateException("Looper '$name' is stopped"))
            }
        }
    }

    fun postDelay(block: () -> Unit, delayMs: Long): Future<*> {
        return scheduler.schedule({
            post(block)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun isCurrentThread(): Boolean = Thread.currentThread() == thread

    fun checkLooper() {
        check(Thread.currentThread() == thread) {
            "Expected looper thread '$name' but was '${Thread.currentThread().name}'"
        }
    }

    /**
     * 解除当前 Looper 线程的 IO 保护。
     * 用于 trace 等需要执行文件 IO 的 Looper 线程。
     */
    fun unlockIoProtect() {
        post {
            ThreadIOGuard.unprotectThread(Thread.currentThread().id)
        }
    }

    private fun loop() {
        try {
            while (true) {
                try {
                    val task = queue.take()
                    if (task === stopSignal) break
                    task.run()
                } catch (_: InterruptedException) {
                    if (stopped.get()) break
                } catch (e: Exception) {
                    logger.error("Looper '{}' task failed", name, e)
                }
            }
        } finally {
            logger.info("Looper '{}' exited", name)
        }
    }
}
