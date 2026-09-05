package com.virjar.tk.android

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 进程所有的、用于小型私有存储变更的单写入者队列。
 *
 * [barrier] 有意设计为非阻塞：生命周期回调可以入队一个持久化标记并立即返回，
 * 而测试或 IO 调用方可以等待该标记。[closeAsync] 会拒绝新工作、排空关闭之前
 * 已接受的全部任务，并且是幂等的。
 */
internal class CloseableSerialTaskQueue(
    threadName: String,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : AutoCloseable {
    private val stateLock = Any()
    private val executor = ThreadPoolExecutor(
        0,
        1,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(requirePositiveCapacity(queueCapacity)),
        ThreadFactory { task -> Thread(task, threadName).apply { isDaemon = true } },
    )
    private val pendingBarriers = mutableMapOf<Long, CompletableFuture<Boolean>>()
    private var nextTaskId = 0L
    private var completedTaskId = 0L
    private var firstTaskFailure: TaskFailure? = null
    private var closeCompletion: CompletableFuture<Boolean>? = null

    /** 在关闭/失败之后，或固定容量执行器无法接受工作时返回 false。 */
    fun execute(task: () -> Unit): Boolean = synchronized(stateLock) {
        if (closeCompletion != null || firstTaskFailure != null) return@synchronized false
        check(nextTaskId < Long.MAX_VALUE) { "Serial task id exhausted" }
        val taskId = nextTaskId + 1L
        try {
            executor.execute {
                var failure: Throwable? = null
                try {
                    task()
                } catch (thrown: Throwable) {
                    failure = thrown
                    // 普通任务失败由 barrier/close 暴露，不会杀死工作线程，
                    // 因此已按 FIFO 接受的后继任务仍能排空。VM 级失败在发布失败信息之后，
                    // 保留其正常的进程级语义。
                    if (thrown !is Exception) throw thrown
                } finally {
                    completeTask(taskId, failure)
                }
            }
            nextTaskId = taskId
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /**
     * 在此调用之前接受的所有任务都离开串行执行器之后完成。
     *
     * 屏障记录当前已接受的序列号，而不是再入队一个任务。因此重复的生命周期冲刷
     * 不会消耗执行器容量，也不会推迟它们所等待的写入。观察到同一序列号的调用
     * 共享同一个完成。不同的待处理目标数受运行中的任务加执行器容量的限制；
     * 任务失败会让匹配的以及所有更晚的屏障以异常方式完成。
     */
    fun barrier(): CompletableFuture<Boolean> = synchronized(stateLock) {
        closeCompletion?.let { return@synchronized it }
        barrierLocked(nextTaskId)
    }

    /**
     * 在不阻塞调用方的情况下启动优雅关闭。重复调用共享同一个完成。
     * 返回的 future 只有在最后一个先前接受的任务运行完毕之后才会完成。
     */
    fun closeAsync(): CompletableFuture<Boolean> = synchronized(stateLock) {
        closeCompletion?.let { return@synchronized it }
        barrierLocked(nextTaskId).also { completion ->
            closeCompletion = completion
            executor.shutdown()
        }
    }

    override fun close() {
        closeAsync()
    }

    private fun barrierLocked(targetTaskId: Long): CompletableFuture<Boolean> {
        if (completedTaskId >= targetTaskId) {
            return completedBarrier(targetTaskId)
        }
        return pendingBarriers.getOrPut(targetTaskId) { CompletableFuture() }
    }

    private fun completedBarrier(targetTaskId: Long): CompletableFuture<Boolean> {
        val failure = firstTaskFailure?.takeIf { it.taskId <= targetTaskId }?.cause
        if (failure == null) return CompletableFuture.completedFuture(true)
        return CompletableFuture<Boolean>().also { it.completeExceptionally(failure) }
    }

    private fun completeTask(taskId: Long, failure: Throwable?) {
        val completions = synchronized(stateLock) {
            // 执行器只有一个工作线程，因此已接受的 id 按 FIFO 顺序离开。使用 maxOf
            // 可以保证即使未来的执行器实现重复上报，关闭恢复也依然安全。
            completedTaskId = maxOf(completedTaskId, taskId)
            if (failure != null && firstTaskFailure == null) {
                firstTaskFailure = TaskFailure(taskId, failure)
            }
            pendingBarriers.entries
                .filter { (targetTaskId, _) -> targetTaskId <= completedTaskId }
                .map { (targetTaskId, completion) ->
                    BarrierCompletion(
                        future = completion,
                        failure = firstTaskFailure
                            ?.takeIf { it.taskId <= targetTaskId }
                            ?.cause,
                    )
                }
                .also {
                    pendingBarriers.entries.removeAll { (targetTaskId, _) ->
                        targetTaskId <= completedTaskId
                    }
                }
        }
        // CompletableFuture 回调可能重入此队列。绝不能在持有 stateLock 时调用它们。
        completions.forEach { completion ->
            val completionFailure = completion.failure
            if (completionFailure == null) {
                completion.future.complete(true)
            } else {
                completion.future.completeExceptionally(completionFailure)
            }
        }
    }

    private data class TaskFailure(val taskId: Long, val cause: Throwable)

    private data class BarrierCompletion(
        val future: CompletableFuture<Boolean>,
        val failure: Throwable?,
    )

    private companion object {
        const val THREAD_KEEP_ALIVE_SECONDS = 5L
        const val DEFAULT_QUEUE_CAPACITY = 64

        fun requirePositiveCapacity(queueCapacity: Int): Int {
            require(queueCapacity > 0) { "Serial task queue capacity must be positive" }
            return queueCapacity
        }
    }
}
