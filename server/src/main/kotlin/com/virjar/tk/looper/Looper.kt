package com.virjar.tk.looper

import com.virjar.tk.env.ThreadIOGuard
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/**
 * 单线程事件循环，类似 Android Looper。
 *
 * 所有投递到 Looper 的任务在同一个 daemon 线程中串行执行，
 * 从而消除对 ConcurrentHashMap 的需求——普通 HashMap 受 Looper 线程保护即可。
 */
class Looper(val name: String) {
    private val logger = LoggerFactory.getLogger(Looper::class.java)

    private val queue = LinkedBlockingDeque<Runnable>()
    private val thread: Thread
    private val scheduler = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "$name-scheduler").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    companion object {
        private val currentLooper = ThreadLocal<Looper?>()

        /** 返回当前线程关联的 Looper，若不在 Looper 线程则返回 null */
        fun myLooper(): Looper? = currentLooper.get()
    }

    init {
        thread = Thread({ loop() }, name).apply { isDaemon = true }
    }

    /** 启动 Looper 线程 */
    fun start() {
        if (started.compareAndSet(false, true)) {
            thread.start()
            logger.info("Looper '{}' started", name)
        }
    }

    /** 停止 Looper 线程和调度器 */
    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            // 投递一个哨兵任务来唤醒线程，让其检查停止标志
            queue.offer(Runnable { /* sentinel */ })
            scheduler.shutdown()
            logger.info("Looper '{}' stopping", name)
        }
    }

    /** 异步投递一个任务（fire-and-forget） */
    fun post(block: () -> Unit) {
        queue.offer(Runnable { block() })
    }

    /** 同步等待任务执行完成（阻塞调用线程，慎用） */
    fun <T> await(block: () -> T): T {
        if (Thread.currentThread() == thread) {
            return block()
        }
        val future = LinkedBlockingDeque<T>()
        val errorQueue = LinkedBlockingDeque<Throwable>()
        queue.offer(Runnable {
            try {
                future.offer(block())
            } catch (e: Throwable) {
                errorQueue.offer(e)
            }
        })
        errorQueue.poll()?.let { throw it }
        return future.take()
    }

    /** 协程挂起等待任务执行完成 */
    suspend fun <T> suspendAwait(block: () -> T): T {
        if (Thread.currentThread() == thread) {
            return block()
        }
        return suspendCancellableCoroutine { cont ->
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

    /** 延迟投递任务 */
    fun postDelay(block: () -> Unit, delayMs: Long): Future<*> {
        return scheduler.schedule({
            post(block)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** 判断当前是否在 Looper 线程 */
    fun isCurrentThread(): Boolean = Thread.currentThread() == thread

    /** 断言当前在 Looper 线程，否则抛异常 */
    fun checkLooper() {
        check(Thread.currentThread() == thread) {
            "Expected looper thread '$name' but was '${Thread.currentThread().name}'"
        }
    }

    fun unlockIoProtect() {
        post {
            ThreadIOGuard.unmarkProtected()
        }
    }

    private fun loop() {
        currentLooper.set(this)
        ThreadIOGuard.markProtected()
        try {
            while (!stopped.get()) {
                try {
                    val task = queue.poll(1, TimeUnit.SECONDS)
                    task?.run()
                } catch (e: InterruptedException) {
                    // 正常退出
                } catch (e: Exception) {
                    logger.error("Looper '{}' task failed", name, e)
                }
            }
        } finally {
            ThreadIOGuard.unmarkProtected()
            currentLooper.remove()
            logger.info("Looper '{}' exited", name)
        }
    }
}
