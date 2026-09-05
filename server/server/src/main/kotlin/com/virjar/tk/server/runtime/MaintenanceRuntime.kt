package com.virjar.tk.server.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/** 为完整服务器运行时拥有的一个固定维护 worker。 */
internal class MaintenanceWorker(
    val name: String,
    val run: suspend CoroutineScope.() -> Unit,
)

/**
 * 拥有服务器的固定周期性 worker 集合及其关闭终结。
 *
 * Worker 只能安装一次，固定的上界防止此运行时变成
 * 无界的任务注册表。关闭吊销整个作用域，等待一个单调截止时间，并
 * 发布一个由并发与重复调用方共享的结果。[workersTerminated] 刻意
 * 报告根 Job 的实际完成，而不是有界关闭尝试的完成：
 * 超时是进程关闭失败，不是关闭可能仍被忽略取消的
 * worker 正在使用的 JDBC 或原生依赖的许可。
 */
internal class MaintenanceRuntime(
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
) : AutoCloseable {
    private enum class Phase {
        NEW,
        RUNNING,
        FAILED,
        STOPPING,
    }

    private val logger = LoggerFactory.getLogger(MaintenanceRuntime::class.java)
    private val lifecycle = SupervisorJob()
    private val shutdownCancellation = CancellationException("MaintenanceRuntime is closing")
    private val workerFailureCancellation = CancellationException("MaintenanceRuntime worker failed")
    private val exceptionHandler = CoroutineExceptionHandler { context, failure ->
        logger.error(
            "Server maintenance worker {} terminated",
            context[CoroutineName]?.name ?: "unnamed",
            failure,
        )
    }
    private val scope = CoroutineScope(lifecycle + workerDispatcher + exceptionHandler)
    private val lifecycleLock = Any()
    private val lifecycleFinished = CountDownLatch(1)
    private var phase = Phase.NEW
    private val closeGate = BoundedCloseGate(
        ownerName = "MaintenanceRuntime",
        timeoutMillis = shutdownTimeoutMillis,
        onTerminal = {},
    )

    /** 只在每个自有 worker 实际离开其协程体之后才为 true。 */
    val workersTerminated: Boolean
        get() = lifecycleFinished.count == 0L

    init {
        lifecycle.invokeOnCompletion { lifecycleFinished.countDown() }
    }

    fun start(workers: List<MaintenanceWorker>) {
        validateWorkers(workers)
        val jobs = synchronized(lifecycleLock) {
            check(phase == Phase.NEW) { "MaintenanceRuntime can only be started once" }
            phase = Phase.RUNNING
            workers.map { worker ->
                scope.launch(
                    context = CoroutineName(worker.name),
                    start = CoroutineStart.LAZY,
                ) {
                    worker.run(this)
                }.also { job ->
                    job.invokeOnCompletion { cause -> retainWorkerTerminal(worker.name, cause) }
                }
            }
        }
        // 惰性安装使 worker 集合原子化。并发关闭可能在此循环之前
        // 取消每个 Job；start() 随后成为无害的空操作，而不是逃逸所有权。
        jobs.forEach { it.start() }
    }

    override fun close() {
        val attempt = synchronized(lifecycleLock) {
            closeGate.begin().also { selected ->
                if (selected is BoundedCloseGate.Attempt.Owner) {
                    phase = Phase.STOPPING
                    lifecycle.cancel(shutdownCancellation)
                }
            }
        }
        val failure = when (attempt) {
            is BoundedCloseGate.Attempt.Owner -> {
                val completed = attempt.deadline.awaitBlocking(lifecycleFinished) { interrupted ->
                    closeGate.recordFailure(interrupted)
                }
                if (completed) closeGate.complete(attempt) else closeGate.expire(attempt.deadline)
            }

            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollowerBlocking(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }
        failure?.let { throw it }
    }

    private fun retainWorkerTerminal(name: String, cause: Throwable?) {
        val runtimeWasActive = synchronized(lifecycleLock) {
            phase == Phase.RUNNING || phase == Phase.FAILED
        }
        val failure = when {
            cause != null && !isOwnedCancellation(cause) -> cause
            cause == null && runtimeWasActive ->
                IllegalStateException("Maintenance worker $name stopped while runtime was active")
            else -> null
        } ?: return

        closeGate.recordFailure(failure)
        synchronized(lifecycleLock) {
            if (phase == Phase.RUNNING) phase = Phase.FAILED
        }
        // 部分运行的维护集合健康状况不明确。保留原始 worker
        // 失败作为终结，并通过一个可单独识别的原因取消其兄弟。
        lifecycle.cancel(workerFailureCancellation)
    }

    private fun isOwnedCancellation(failure: Throwable): Boolean {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            if (current === shutdownCancellation || current === workerFailureCancellation) return true
            current = current?.cause ?: return false
        }
        return false
    }

    private fun validateWorkers(workers: List<MaintenanceWorker>) {
        require(workers.isNotEmpty()) { "MaintenanceRuntime requires at least one worker" }
        require(workers.size <= MAX_WORKER_COUNT) {
            "MaintenanceRuntime supports at most $MAX_WORKER_COUNT workers"
        }
        workers.forEach { worker ->
            require(worker.name.isNotBlank()) { "maintenance worker name must not be blank" }
        }
        require(workers.map(MaintenanceWorker::name).distinct().size == workers.size) {
            "maintenance worker names must be unique"
        }
    }

    private companion object {
        const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 5_000L
        const val MAX_WORKER_COUNT = 16
        const val MAX_CAUSE_DEPTH = 16
    }
}
