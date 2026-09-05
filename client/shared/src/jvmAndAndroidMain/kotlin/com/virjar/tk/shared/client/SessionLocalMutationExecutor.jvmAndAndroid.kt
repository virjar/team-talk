package com.virjar.tk.shared.client

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal actual fun createSessionLocalMutationExecutor(): SessionLocalMutationExecutor =
    JvmSessionLocalMutationExecutor()

private class JvmSessionLocalMutationExecutor : SessionLocalMutationExecutor {
    private val worker = AtomicReference<Thread?>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(EXECUTOR_QUEUE_CAPACITY),
        ThreadFactory { task ->
            Thread(task, "teamtalk-local-writer-${threadSequence.incrementAndGet()}").also { thread ->
                thread.isDaemon = true
                worker.set(thread)
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun execute(task: () -> Unit): Boolean = try {
        executor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun closeAndDrain() {
        check(Thread.currentThread() !== worker.get()) {
            "Local mutation worker cannot wait for its own retirement"
        }
        executor.shutdown()
        var interrupted: InterruptedException? = null
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(1L, TimeUnit.SECONDS)
            } catch (failure: InterruptedException) {
                if (interrupted == null) interrupted = failure else interrupted.addSuppressed(failure)
            }
        }
        interrupted?.let {
            Thread.currentThread().interrupt()
            throw it
        }
    }

    private companion object {
        /** 公共队列调度一个排空 runnable；空闲槽覆盖 close/屏障竞争。 */
        const val EXECUTOR_QUEUE_CAPACITY = 8
        val threadSequence = AtomicLong()
    }
}
