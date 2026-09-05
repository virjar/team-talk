package com.virjar.tk.server.protocol.trace

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 只在专用 trace worker 上解析的延迟工作。 */
internal fun interface PendingTraceWork {
    fun resolve(): TraceWork?
}

/** 专用于策略启用的连接 trace 的一个有界、非阻塞投递边界。 */
internal class TraceDispatchQueue(
    threadName: String,
    private val capacity: Int,
    private val workerJoinTimeoutMillis: Long = DEFAULT_WORKER_JOIN_TIMEOUT_MILLIS,
    private val sink: (TraceWork) -> Unit,
) : AutoCloseable {
    private val validatedWorkerJoinTimeoutMillis = workerJoinTimeoutMillis.also {
        require(it > 0L) { "trace worker join timeout must be positive" }
    }
    private val queue = ArrayBlockingQueue<PendingTraceWork>(capacity.also {
        require(it > 0) { "trace queue capacity must be positive" }
    })
    private val closed = AtomicBoolean(false)
    private val closeFinished = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>(null)
    private val accepted = AtomicLong(0)
    private val delivered = AtomicLong(0)
    private val droppedQueueFull = AtomicLong(0)
    private val droppedDispatcherClosed = AtomicLong(0)
    private val deliveryFailures = AtomicLong(0)
    private val worker = Thread(::runLoop, threadName).apply {
        isDaemon = true
        start()
    }

    /**
     * 绝不阻塞协议调用方。队列满时丢弃最新的 trace，因此
     * 已接受的工作保持 FIFO 顺序，且内存不随网络流量增长。
     */
    fun offer(work: PendingTraceWork): Boolean {
        val result = when {
            closed.get() -> OfferResult.CLOSED
            !queue.offer(work) -> OfferResult.FULL
            // close() 是测试/进程生命周期清理，而不是普通服务器流程。此
            // 第二次检查防止与清理竞态的提交被永远搁浅。
            closed.get() && queue.remove(work) -> OfferResult.CLOSED
            else -> OfferResult.ACCEPTED
        }
        return when (result) {
            OfferResult.ACCEPTED -> {
                accepted.incrementAndGet()
                true
            }
            OfferResult.FULL -> {
                droppedQueueFull.incrementAndGet()
                false
            }
            OfferResult.CLOSED -> {
                droppedDispatcherClosed.incrementAndGet()
                false
            }
        }
    }

    fun snapshot(): TraceDispatchSnapshot = TraceDispatchSnapshot(
        capacity = capacity,
        queued = queue.size,
        accepted = accepted.get(),
        delivered = delivered.get(),
        droppedQueueFull = droppedQueueFull.get(),
        droppedDispatcherClosed = droppedDispatcherClosed.get(),
        deliveryFailures = deliveryFailures.get(),
    )

    override fun close() {
        check(Thread.currentThread() !== worker) {
            "Trace dispatcher cannot synchronously close itself from its sink"
        }
        if (!closed.compareAndSet(false, true)) {
            awaitCloseOwner()
            closeFailure.get()?.let { throw it }
            return
        }
        var failure: Throwable? = null
        try {
            val discardedWork = arrayListOf<PendingTraceWork>()
            queue.drainTo(discardedWork)
            val discarded = discardedWork.size
            if (discarded > 0) droppedDispatcherClosed.addAndGet(discarded.toLong())
            worker.interrupt()
            try {
                worker.join(validatedWorkerJoinTimeoutMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while stopping trace dispatcher", interrupted)
            }
            check(!worker.isAlive) {
                "Trace dispatcher did not terminate within $validatedWorkerJoinTimeoutMillis ms"
            }
        } catch (error: Throwable) {
            failure = error
            closeFailure.set(error)
        } finally {
            closeFinished.countDown()
        }
        failure?.let { throw it }
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

    private fun runLoop() {
        while (!closed.get()) {
            val work = try {
                queue.poll(REPORT_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (closed.get()) return else continue
            }
            if (work == null) continue
            if (closed.get()) {
                droppedDispatcherClosed.incrementAndGet()
                continue
            }
            try {
                work.resolve()?.let {
                    sink(it)
                    delivered.incrementAndGet()
                }
            } catch (_: Throwable) {
                // trace 投递刻意与 IM 隔离。不要记录异常：
                // sink 或延迟调用方可能把敏感文本附加到其消息上。
                deliveryFailures.incrementAndGet()
            }
        }
    }

    private enum class OfferResult { ACCEPTED, FULL, CLOSED }

    private companion object {
        const val REPORT_POLL_MILLIS = 1_000L
        const val DEFAULT_WORKER_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}

internal data class TraceWork(
    val uid: String,
    val deviceId: String,
    val context: com.virjar.tk.protocol.telemetry.ConnectionTraceContext,
    val occurredAt: Long,
    val phase: com.virjar.tk.server.domain.telemetry.ConnectionTracePhase,
    val outcome: com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome,
    val detail: String?,
)

internal data class TraceDispatchSnapshot(
    val capacity: Int,
    val queued: Int,
    val accepted: Long,
    val delivered: Long,
    val droppedQueueFull: Long,
    val droppedDispatcherClosed: Long,
    val deliveryFailures: Long,
)
