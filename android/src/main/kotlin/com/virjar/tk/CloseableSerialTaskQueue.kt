package com.virjar.tk

import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Process-owned, single-writer queue for small private-storage mutations.
 *
 * [barrier] is deliberately non-blocking: lifecycle callbacks can enqueue a durability marker and
 * return immediately, while tests or an IO caller may wait for that marker. [closeAsync] rejects new
 * work, drains everything accepted before close, and is idempotent.
 */
internal class CloseableSerialTaskQueue(threadName: String) : AutoCloseable {
    private val stateLock = Any()
    private val executor = ThreadPoolExecutor(
        0,
        1,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { task -> Thread(task, threadName).apply { isDaemon = true } },
    )
    private var closeCompletion: CompletableFuture<Boolean>? = null

    /** Returns false after close has begun; accepted tasks always run before the close marker. */
    fun execute(task: () -> Unit): Boolean = synchronized(stateLock) {
        if (closeCompletion != null) return@synchronized false
        executor.execute(task)
        true
    }

    /** Completes after every task accepted before this call has left the serial executor. */
    fun barrier(): CompletableFuture<Boolean> = synchronized(stateLock) {
        closeCompletion?.let { return@synchronized it }
        CompletableFuture<Boolean>().also { completion ->
            executor.execute { completion.complete(true) }
        }
    }

    /**
     * Starts a graceful close without blocking the caller. Repeated calls share one completion.
     * The returned future completes only after the final previously accepted task has run.
     */
    fun closeAsync(): CompletableFuture<Boolean> = synchronized(stateLock) {
        closeCompletion?.let { return@synchronized it }
        CompletableFuture<Boolean>().also { completion ->
            closeCompletion = completion
            executor.execute { completion.complete(true) }
            executor.shutdown()
        }
    }

    override fun close() {
        closeAsync()
    }

    private companion object {
        const val THREAD_KEEP_ALIVE_SECONDS = 5L
    }
}
