package com.virjar.tk.shared.client

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal actual fun createPlatformTelemetryHttpIoWorker(): PlatformTelemetryHttpIoWorker =
    JvmAndAndroidTelemetryHttpIoWorker()

/** 固定单线程 owner；物理线程与保留的上传请求都不能自由增长。 */
private class JvmAndAndroidTelemetryHttpIoWorker : PlatformTelemetryHttpIoWorker {
    private val lifecycleLock = Any()
    private val workerThread = AtomicReference<Thread?>()
    private var accepting = true
    private var terminalTaskFailure: Throwable? = null
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_UPLOADS),
        ThreadFactory { task ->
            Thread(task, "teamtalk-telemetry-http-io-${threadSequence.incrementAndGet()}").also { thread ->
                thread.isDaemon = true
                workerThread.set(thread)
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun execute(task: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (!accepting) return@synchronized false
        try {
            executor.execute {
                try {
                    task()
                } catch (failure: Throwable) {
                    // 调用方通常自己完成其普通任务失败。任何逃逸出该边界的东西仍归这里所有：
                    // 保留它并停止准入，而不是让 ThreadPoolExecutor 在会话背后替换物理 worker。
                    // 已被准入的有界工作在同一线程上排空。
                    retainTaskFailure(failure)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun closeAndDrain() {
        check(Thread.currentThread() !== workerThread.get()) {
            "Log HTTP IO worker cannot join its own retirement"
        }
        synchronized(lifecycleLock) { accepting = false }
        executor.shutdown()
        var interrupted: InterruptedException? = null
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(1L, TimeUnit.SECONDS)
            } catch (failure: InterruptedException) {
                if (interrupted == null) interrupted = failure else interrupted.addSuppressed(failure)
            }
        }
        // ThreadPoolExecutor 在 worker 的最终记账中、恰在 Thread.run 包装本身返回之前发布
        // TERMINATED。最后 join 该进程拥有的线程，这样调用方不会在一个调度窗口内观察到名义上
        // 已排空的 uploader 其 daemon 仍存活。逃逸的任务失败保留在 executor 任务内，因此不能
        // 替换这个固定物理 worker。
        workerThread.get()?.let { thread ->
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (failure: InterruptedException) {
                    if (interrupted == null) interrupted = failure else interrupted.addSuppressed(failure)
                }
            }
        }
        val taskFailure = synchronized(lifecycleLock) { terminalTaskFailure }
        interrupted?.let {
            Thread.currentThread().interrupt()
            if (taskFailure != null) {
                throw mergeSessionLifecycleFailures(taskFailure, it)
            }
            throw it
        }
        if (taskFailure != null) throw taskFailure
    }

    /** 在停止准入之前冻结逃逸的任务失败；已接受的工作保持有界。 */
    private fun retainTaskFailure(failure: Throwable) {
        synchronized(lifecycleLock) {
            terminalTaskFailure = mergeSessionLifecycleFailures(terminalTaskFailure, failure)
            accepting = false
        }
        // 该任务已经在拥有的线程上。有序关闭只让失败之前准入的有限集合排空，
        // 然后终止 owner 而不替换其线程。
        executor.shutdown()
    }

    private companion object {
        const val MAX_QUEUED_UPLOADS = 8
        val threadSequence = AtomicLong()
    }
}
