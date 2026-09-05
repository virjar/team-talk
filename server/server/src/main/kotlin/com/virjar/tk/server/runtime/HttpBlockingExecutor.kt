package com.virjar.tk.server.runtime

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 一次非阻塞准入尝试进入 [HttpBlockingExecutor] 的结果。 */
internal sealed interface HttpBlockingExecution<out T> {
    data class Completed<T>(val value: T) : HttpBlockingExecution<T>
    data object Rejected : HttpBlockingExecution<Nothing>
}

/**
 * 可能阻塞在 PostgreSQL、本地存储或文件上的 HTTP 工作的应用自有边界。
 *
 * 准入是非阻塞的，并界定执行中加等待调用的完整集合。每个
 * 已准入的协程在普通顺序路由执行期间最多有一个续延等待此私有分发器，
 * 因此物理执行器队列具有相同的显式上界。关闭
 * 先停止准入，排空已准入的调用，然后 join 自有 worker 线程。
 */
internal class HttpBlockingExecutor(
    workerCount: Int = DEFAULT_WORKER_COUNT,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
) : AutoCloseable {
    init {
        require(workerCount > 0) { "HTTP blocking worker count must be positive" }
        require(queueCapacity > 0) { "HTTP blocking queue capacity must be positive" }
        require(shutdownTimeoutMillis > 0L) { "HTTP blocking shutdown timeout must be positive" }
    }

    private val lifecycleLock = ReentrantLock()
    private val drained = lifecycleLock.newCondition()
    private val closeStarted = AtomicBoolean(false)
    private val closeFinished = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>(null)
    private var accepting = true
    private var outstandingTasks = 0
    private val admission = Semaphore(workerCount + queueCapacity)
    private val pool = ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        HttpBlockingThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val dispatcher: ExecutorCoroutineDispatcher = pool.asCoroutineDispatcher()

    /**
     * 在不挂起调用方（通常是 Netty EventLoop）的情况下，尝试占有一个有界调用槽。
     * 被拒绝的块绝不保留，也绝不评估。
     */
    suspend fun <T> tryExecute(block: suspend () -> T): HttpBlockingExecution<T> {
        if (!tryAcquireSlot()) return HttpBlockingExecution.Rejected
        return try {
            HttpBlockingExecution.Completed(withContext(dispatcher) { block() })
        } finally {
            releaseSlot()
        }
    }

    internal val outstandingTaskCount: Int
        get() = lifecycleLock.withLock { outstandingTasks }

    internal val acceptsNewTasks: Boolean
        get() = lifecycleLock.withLock { accepting }

    /** 只在每个自有 worker 实际停止且没有已准入调用剩余之后才为 true。 */
    internal val workersTerminated: Boolean
        get() = pool.isTerminated && outstandingTaskCount == 0

    private fun tryAcquireSlot(): Boolean = lifecycleLock.withLock {
        if (!accepting || !admission.tryAcquire()) return@withLock false
        outstandingTasks += 1
        true
    }

    private fun releaseSlot() {
        lifecycleLock.withLock {
            check(outstandingTasks > 0) { "HTTP blocking admission accounting underflow" }
            outstandingTasks -= 1
            admission.release()
            if (!accepting && outstandingTasks == 0) drained.signalAll()
        }
    }

    /**
     * ServerResourceOwner 只在 Ktor 已停止接受并排空 HTTP 调用之后才调用此方法。
     * 有界等待对部分启动与异常引擎关闭路径是防御性的。
     */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) {
            awaitCloseOwner()
            closeFailure.get()?.let { throw it }
            return
        }

        var failure: Throwable? = null
        try {
            closeOwnedResources()
        } catch (error: Throwable) {
            failure = error
            closeFailure.set(error)
        } finally {
            closeFinished.countDown()
        }
        failure?.let { throw it }
    }

    private fun closeOwnedResources() {
        lifecycleLock.withLock { accepting = false }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMillis)
        var interrupted: InterruptedException? = null
        lifecycleLock.withLock {
            while (outstandingTasks > 0) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                try {
                    drained.awaitNanos(remainingNanos)
                } catch (error: InterruptedException) {
                    interrupted = error
                    break
                }
            }
        }
        val drainedCleanly = outstandingTaskCount == 0

        dispatcher.close()
        var terminated = awaitTermination(deadlineNanos)
        if (!terminated) {
            pool.shutdownNow()
            terminated = awaitTermination(
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FORCED_SHUTDOWN_JOIN_MILLIS),
            )
        }
        if (interrupted != null) Thread.currentThread().interrupt()

        val remaining = outstandingTaskCount
        if (interrupted != null || !drainedCleanly || !terminated || remaining > 0) {
            throw HttpBlockingExecutorShutdownException(
                remainingTasks = remaining,
                drainedCleanly = drainedCleanly,
                workersTerminated = terminated,
                cause = interrupted,
            )
        }
    }

    private fun awaitCloseOwner() {
        var interrupted = false
        while (true) {
            try {
                closeFinished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun awaitTermination(deadlineNanos: Long): Boolean {
        val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
        return try {
            pool.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private class HttpBlockingThreadFactory : ThreadFactory {
        private val nextId = AtomicInteger(0)

        override fun newThread(command: Runnable): Thread = Thread(
            command,
            "teamtalk-http-blocking-${nextId.incrementAndGet()}",
        ).apply {
            isDaemon = true
        }
    }

    private companion object {
        const val DEFAULT_WORKER_COUNT = 8
        const val DEFAULT_QUEUE_CAPACITY = 256
        const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
        const val FORCED_SHUTDOWN_JOIN_MILLIS = 250L
    }
}

internal class HttpBlockingExecutorShutdownException(
    remainingTasks: Int,
    drainedCleanly: Boolean,
    workersTerminated: Boolean,
    cause: Throwable?,
) : IllegalStateException(
    "HTTP blocking executor did not stop cleanly: remainingTasks=$remainingTasks, " +
        "drainedCleanly=$drainedCleanly, workersTerminated=$workersTerminated",
    cause,
)
