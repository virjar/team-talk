package com.virjar.tk.server.protocol.executor

import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 有界单线程事件循环。
 *
 * 普通工作有硬队列上限，饱和时被显式拒绝。一个物理
 * 队列槽为 [signalCriticalSweep] 保留。重复的关键信号围绕
 * 固定的状态扫描回调合并，因此断开风暴既不会按信号保留一个 Runnable，
 * 也不会保留一个连接引用。第一个信号相对普通工作保持 FIFO 位置；
 * 清扫运行期间到达的信号会导致再一次尾部排队的清扫，且不会丢失。
 *
 * [stop] 是同步拥有权边界：停止准入之前接受的工作被排空，
 * 可选终结器在 looper 线程上运行，调用方等待该线程完成终结。
 */
class Looper(
    val name: String,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val criticalSweep: (() -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(Looper::class.java)

    init {
        require(queueCapacity > 0) { "Looper queue capacity must be positive" }
    }

    private enum class State { NEW, RUNNING, STOPPING, TERMINATED }

    private enum class Rejection { NOT_RUNNING, CAPACITY }

    private val lifecycleLock = Any()
    private val queue = ArrayBlockingQueue<Runnable>(
        queueCapacity + STOP_QUEUE_RESERVE + if (criticalSweep == null) 0 else CRITICAL_QUEUE_RESERVE,
        true,
    )
    private val queuedRegularTasks = AtomicInteger(0)
    private val terminated = CountDownLatch(1)
    private val terminationFailure = AtomicReference<Throwable?>(null)

    @Volatile
    private var state = State.NEW
    private var stopFinalizer: (() -> Unit)? = null
    private var criticalRevision = 0L
    private var criticalScheduled = false

    private val thread = Thread(::loop, name).apply { isDaemon = true }

    private val criticalCommand = Runnable { runCriticalSweep() }
    private val stopCommand = Runnable { }

    fun start() {
        synchronized(lifecycleLock) {
            when (state) {
                State.NEW -> {
                    state = State.RUNNING
                    thread.start()
                    logger.info("Looper '{}' started with queueCapacity={}", name, queueCapacity)
                }

                State.RUNNING -> return
                State.STOPPING, State.TERMINATED ->
                    throw IllegalStateException("Looper '$name' is already stopped")
            }
        }
    }

    /**
     * 停止接受工作，排空已接受的工作，在此 looper 上运行 [finalizer]，并等待终结。
     * 重复调用只等待第一次 stop 完成；looper 任务不能停止并等待自己的线程。
     */
    fun stop(finalizer: (() -> Unit)? = null) {
        check(!isCurrentThread()) { "Looper '$name' cannot join itself" }

        var shouldStartForStop = false
        synchronized(lifecycleLock) {
            when (state) {
                State.NEW -> {
                    stopFinalizer = finalizer
                    state = State.STOPPING
                    check(queue.offer(stopCommand)) { "Looper '$name' stop reserve is unavailable" }
                    shouldStartForStop = true
                }

                State.RUNNING -> {
                    stopFinalizer = finalizer
                    state = State.STOPPING
                    check(queue.offer(stopCommand)) { "Looper '$name' stop reserve is unavailable" }
                }

                State.STOPPING, State.TERMINATED -> Unit
            }
        }

        if (shouldStartForStop) thread.start()
        awaitTermination()
        terminationFailure.get()?.let { throw IllegalStateException("Looper '$name' failed while stopping", it) }
    }

    /** 非阻塞普通准入。`false` 表示已停止/未启动或饱和。 */
    fun post(block: () -> Unit): Boolean = enqueue(RegularTask(block)) == null

    /**
     * 只为执行而挂起，绝不为队列容量挂起。饱和以
     * [RejectedExecutionException] 恢复续延；停止以 [IllegalStateException] 恢复。若
     * 调用方在出队之前被取消，其排队任务被移除且其容量被释放。
     */
    suspend fun <T> suspendAwait(block: () -> T): T {
        if (isCurrentThread()) return block()

        return suspendCancellableCoroutine { continuation ->
            lateinit var task: RegularTask
            task = RegularTask {
                if (!continuation.isActive) return@RegularTask
                continuation.resumeWith(runCatching(block))
            }

            val rejection = enqueue(task)
            if (rejection == null) {
                continuation.invokeOnCancellation {
                    if (queue.remove(task)) task.releaseQueueCapacity()
                }
            } else {
                val error = when (rejection) {
                    Rejection.NOT_RUNNING -> IllegalStateException("Looper '$name' is not running")
                    Rejection.CAPACITY -> RejectedExecutionException(
                        "Looper '$name' queue capacity $queueCapacity is exhausted",
                    )
                }
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

    /**
     * 触发固定的关键清扫，而不保留调用方状态。普通饱和无法
     * 拒绝它。重复信号可能合并，因此回调必须扫描单调的终结状态，
     * 而不是代表单条命令。
     */
    fun signalCriticalSweep(): Boolean = synchronized(lifecycleLock) {
        if (state != State.RUNNING || criticalSweep == null) return@synchronized false
        criticalRevision += 1L
        if (!criticalScheduled) {
            criticalScheduled = true
            check(queue.offer(criticalCommand)) {
                "Looper '$name' critical reserve was consumed by regular work"
            }
        }
        true
    }

    fun isCurrentThread(): Boolean = Thread.currentThread() == thread

    fun checkLooper() {
        check(isCurrentThread()) {
            "Expected looper thread '$name' but was '${Thread.currentThread().name}'"
        }
    }

    private fun enqueue(task: RegularTask): Rejection? = synchronized(lifecycleLock) {
        if (state != State.RUNNING) return@synchronized Rejection.NOT_RUNNING
        if (queuedRegularTasks.get() >= queueCapacity) return@synchronized Rejection.CAPACITY

        task.acquireQueueCapacity()
        check(queue.offer(task)) {
            task.releaseQueueCapacity()
            "Looper '$name' physical queue violated its reserved-capacity invariant"
        }
        null
    }

    private fun runCriticalSweep() {
        val observedRevision = synchronized(lifecycleLock) { criticalRevision }
        try {
            checkNotNull(criticalSweep).invoke()
        } finally {
            synchronized(lifecycleLock) {
                if (state == State.RUNNING && criticalRevision != observedRevision) {
                    check(queue.offer(criticalCommand)) {
                        "Looper '$name' could not retain a repeated critical signal"
                    }
                } else {
                    criticalScheduled = false
                }
            }
        }
    }

    private fun loop() {
        try {
            while (true) {
                val task = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    continue
                }
                if (task === stopCommand) break

                try {
                    task.run()
                } catch (error: Exception) {
                    logger.error("Looper '{}' task failed", name, error)
                }
            }

            stopFinalizer?.invoke()
        } catch (error: Throwable) {
            terminationFailure.compareAndSet(null, error)
            logger.error("Looper '{}' terminated with failure", name, error)
        } finally {
            synchronized(lifecycleLock) {
                state = State.TERMINATED
                criticalScheduled = false
                stopFinalizer = null
            }
            terminated.countDown()
            logger.info("Looper '{}' exited", name)
        }
    }

    private fun awaitTermination() {
        var interrupted = false
        while (true) {
            try {
                terminated.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private inner class RegularTask(private val block: () -> Unit) : Runnable {
        private val queueCapacityHeld = AtomicBoolean(false)

        fun acquireQueueCapacity() {
            check(queueCapacityHeld.compareAndSet(false, true)) { "Regular task was admitted twice" }
            queuedRegularTasks.incrementAndGet()
        }

        override fun run() {
            releaseQueueCapacity()
            block()
        }

        fun releaseQueueCapacity() {
            if (queueCapacityHeld.compareAndSet(true, false)) queuedRegularTasks.decrementAndGet()
        }
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1_024
        const val CRITICAL_QUEUE_RESERVE = 1
        const val STOP_QUEUE_RESERVE = 1
    }
}
