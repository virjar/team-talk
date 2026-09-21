@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import platform.darwin.*

/** One serial GCD queue owns state; accepted work is drained before closing storage. */
internal class IosSerialExecutor(name: String, private val capacity: Int = Int.MAX_VALUE) : CoroutineDispatcher() {
    private val logger = PlatformOnlyTkLogger("IosSerialExecutor")
    val queue = dispatch_queue_create(name, null)
    private val lock = PlatformLock()
    private var closed = false
    private var queued = 0
    private var runningThread = 0L
    fun inExecutor(): Boolean = synchronized(lock) { runningThread != 0L && runningThread == platformCurrentThreadId() }
    fun execute(task: () -> Unit): Boolean = synchronized(lock) {
        if (closed || queued >= capacity) return@synchronized false
        queued++
        dispatch_async(queue) {
            synchronized(lock) { runningThread = platformCurrentThreadId() }
            try {
                task()
            } catch (failure: Throwable) {
                // As on Netty/executor workers, one rejected task must not cross the native block boundary.
                logger.fault("Serial worker task failed", failure)
            } finally {
                synchronized(lock) { runningThread = 0; queued-- }
            }
        }
        true
    }
    fun executeNowOrEnqueue(task: () -> Unit): Boolean = if (inExecutor()) { task(); true } else execute(task)
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !inExecutor()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!execute { block.run() }) {
            // Coroutine cleanup still needs a dispatcher after the owned queue rejects new work.
            context[Job]?.cancel(CancellationException("iOS serial executor is closed"))
            Dispatchers.Default.dispatch(context, block)
        }
    }
    fun closeAndDrain() {
        check(!inExecutor()) { "Serial worker cannot wait for its own retirement" }
        synchronized(lock) { closed = true }
        dispatch_sync(queue) { }
    }
    fun closeAfterQueuedWork() { synchronized(lock) { closed = true } }
}
internal actual fun createSessionLocalMutationExecutor(): SessionLocalMutationExecutor = object : SessionLocalMutationExecutor {
    private val worker = IosSerialExecutor("teamtalk.local-writer", 8)
    override fun execute(task: () -> Unit): Boolean = worker.execute(task)
    override fun closeAndDrain() = worker.closeAndDrain()
}
