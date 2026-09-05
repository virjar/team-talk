package com.virjar.tk.server.protocol.executor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * IO 线程池，用于将重量级操作（DB/消息存储）从 Netty EventLoop 上卸载。
 *
 * EventLoop 适合处理轻量网络操作（PING/PONG/DISCONNECT），
 * 重量操作（消息存储、好友查询、频道成员查询）应 dispatch 到此线程池。
 *
 * Channel.writeAndFlush() 是线程安全的（Netty 内部会提交到对应 EventLoop），
 * IO 线程可以直接调用。
 */
class IOExecutor(
    private val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, MAX_WORKERS),
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val shutdownTimeoutMillis: Long = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS,
) {
    private val logger = LoggerFactory.getLogger(IOExecutor::class.java)

    init {
        require(workerCount > 0) { "IO worker 数量必须为正数" }
        require(queueCapacity > 0) { "IO 队列容量必须为正数" }
        require(shutdownTimeoutMillis > 0L) { "IO shutdown timeout must be positive" }
    }

    // 固定 worker coroutine 是唯一会进入底层 executor 的任务；物理队列同样有界，
    // 避免未来代码绕过上层 Channel 时重新引入 LinkedBlockingQueue 的无限积压。
    private val pool: ExecutorService = ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(workerCount),
        object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread {
                return Thread(r, "tk-io-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + pool.asCoroutineDispatcher())
    private val closed = AtomicBoolean(false)
    private val shutdownFinished = CountDownLatch(1)
    private val shutdownFailure = AtomicReference<Throwable?>(null)

    /** 执行中最多 workerCount 个，另有 queueCapacity 个等待；两者都持有完整解码对象。 */
    private val outstandingTasks = AtomicInteger(0)
    private val maximumOutstandingTasks = workerCount + queueCapacity

    /**
     * 鉴权可能在保留一个逻辑队列 worker 的同时等待 BCrypt 或存储。
     * 把生产执行器至少一半的 worker 留给已鉴权流量。
     */
    internal val authenticationConcurrencyCeiling: Int = (workerCount / 2).coerceAtLeast(1)

    private data class QueuedWork(
        val releaseSlotOnCompletion: Boolean,
        val completion: CompletionAction?,
        val block: suspend CoroutineScope.() -> Unit,
    )

    /** 提交、worker、断开与关闭路径可能竞态；清理仍恰好运行一次。 */
    private class CompletionAction(private val action: () -> Unit) {
        private val completed = AtomicBoolean(false)

        fun complete() {
            if (completed.compareAndSet(false, true)) action()
        }
    }

    private val workQueue = Channel<QueuedWork>(queueCapacity)
    private val workers = List(workerCount) {
        scope.launch {
            for (work in workQueue) {
                try {
                    work.block(this)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("IOExecutor worker task failed", e)
                } finally {
                    try {
                        completeWorkerOwned(work.completion)
                    } finally {
                        if (work.releaseSlotOnCompletion) releaseWorkSlot()
                    }
                }
            }
        }
    }
    private data class SerialAgentTask(
        val facade: ImAgentFacade,
        val block: suspend CoroutineScope.(ImAgentFacade) -> Unit,
    )

    /**
     * 需要跨连接保持到达顺序的少量状态写入使用该队列。队列在 Netty handler 调用时同步入队，
     * 不会因为线程池调度、旧连接超时或响应协程取消而让旧请求晚于新请求执行。
     */
    private data class SerialAgentQueue(
        val tasks: ArrayDeque<SerialAgentTask> = ArrayDeque(),
        var running: Boolean = false,
    )

    private val serialQueueLock = Any()
    private val serialQueues = mutableMapOf<String, SerialAgentQueue>()

    /**
     * 以协程方式执行 IO 操作，通过 [ImAgentFacade] 安全访问 agent。
     *
     * agent 断开后协程自动取消（[AgentDisposedException]），不会泄漏 GC root。
     */
    fun launchWithAgent(
        agent: com.virjar.tk.server.protocol.connection.ImAgent,
        onCompletion: (() -> Unit)? = null,
        block: suspend CoroutineScope.(ImAgentFacade) -> Unit,
    ): Boolean {
        val facade = ImAgentFacade(agent)
        val completion = onCompletion?.let(::CompletionAction)
        if (!facade.isActive) {
            completeForCaller(completion)
            return false
        }
        return trySubmit(completion = completion) {
            runAgentTask(facade, block)
        }
    }

    /** 测试和同包基础设施共用的非阻塞准入入口。 */
    internal fun tryLaunchTask(block: suspend CoroutineScope.() -> Unit): Boolean = trySubmit(block = block)

    /** 生产 facade 使用的同一连接生命周期边界的确定性缝隙。 */
    internal fun tryLaunchTask(
        lease: AgentTaskLease,
        onCompletion: (() -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean {
        val completion = onCompletion?.let(::CompletionAction)
        if (!lease.isActive) {
            completeForCaller(completion)
            return false
        }
        return trySubmit(completion = completion) {
            try {
                runLeasedTask(lease, block)
            } catch (cancelled: CancellationException) {
                // 协程机制可能包装租约的稳定 AgentDisposedException。
                // 权威的区分是租约状态，而不是包装实现。
                if (lease.isActive) throw cancelled
            }
        }
    }

    internal val outstandingTaskCount: Int get() = outstandingTasks.get()

    fun launchSerialWithAgent(
        key: String,
        agent: com.virjar.tk.server.protocol.connection.ImAgent,
        block: suspend CoroutineScope.(ImAgentFacade) -> Unit,
    ): Boolean {
        val task = SerialAgentTask(ImAgentFacade(agent), block)
        if (!task.facade.isActive) return false
        synchronized(serialQueueLock) {
            val queue = serialQueues.getOrPut(key, ::SerialAgentQueue)
            if (queue.tasks.size >= MAX_SERIAL_QUEUE_DEPTH) return false
            if (!tryAcquireWorkSlot()) {
                if (!queue.running && queue.tasks.isEmpty()) serialQueues.remove(key)
                return false
            }
            queue.tasks.addLast(task)
            if (!queue.running) {
                queue.running = true
                // 在同一把锁内提交，避免失败回滚前同 key 的第二个请求插入并形成僵尸队列。
                // 首个串行任务已经占用全局 slot；drain 本身不能再重复计数。
                if (!trySubmit(acquireSlot = false) { drainSerialQueue(key, queue) }) {
                    queue.tasks.removeLast()
                    queue.running = false
                    if (queue.tasks.isEmpty()) serialQueues.remove(key)
                    releaseWorkSlot()
                    return false
                }
            }
        }
        return true
    }

    private suspend fun CoroutineScope.drainSerialQueue(key: String, queue: SerialAgentQueue) {
        while (true) {
            val task = synchronized(serialQueueLock) {
                queue.tasks.removeFirstOrNull().also { next ->
                    if (next == null) {
                        queue.running = false
                        if (serialQueues[key] === queue) serialQueues.remove(key)
                    }
                }
            } ?: return
            try {
                runAgentTask(task.facade, task.block)
            } finally {
                releaseWorkSlot()
            }
        }
    }

    private fun trySubmit(
        acquireSlot: Boolean = true,
        completion: CompletionAction? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean {
        if (acquireSlot && !tryAcquireWorkSlot()) {
            completeForCaller(completion)
            return false
        }
        val result = workQueue.trySend(QueuedWork(acquireSlot, completion, block))
        if (result.isFailure) {
            try {
                completeForCaller(completion)
            } finally {
                if (acquireSlot) releaseWorkSlot()
            }
        }
        return result.isSuccess
    }

    /** 同步拒绝仍把精确的拥有者取消返回给其调用方。 */
    private fun completeForCaller(completion: CompletionAction?) {
        try {
            completion?.complete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.error("IOExecutor task completion failed", failure)
        }
    }

    /** 完成回调属于一个任务，绝不能回收其长寿命 worker。 */
    private fun completeWorkerOwned(completion: CompletionAction?) {
        try {
            completeForCaller(completion)
        } catch (cancelled: CancellationException) {
            logger.error("IOExecutor task completion was cancelled", cancelled)
        }
    }

    private fun tryAcquireWorkSlot(): Boolean {
        if (closed.get()) return false
        while (true) {
            val current = outstandingTasks.get()
            if (current >= maximumOutstandingTasks || closed.get()) return false
            if (outstandingTasks.compareAndSet(current, current + 1)) {
                if (!closed.get()) return true
                releaseWorkSlot()
                return false
            }
        }
    }

    private fun releaseWorkSlot() {
        outstandingTasks.decrementAndGet()
    }

    private suspend fun CoroutineScope.runAgentTask(
        facade: ImAgentFacade,
        block: suspend CoroutineScope.(ImAgentFacade) -> Unit,
    ) {
        try {
            facade.ensureTaskActive()
            runLeasedTask(facade.taskLease) {
                facade.ensureTaskActive()
                block(facade)
            }
        } catch (e: AgentDisposedException) {
            logger.debug("Coroutine cancelled, agent gone: uid=${facade.uid}")
        } catch (e: CancellationException) {
            if (facade.taskLease.isActive) throw e
            logger.debug("Coroutine cancelled, connection lease ended: uid=${facade.uid}")
        } catch (e: Exception) {
            logger.error("IOExecutor coroutine task failed, uid=${facade.uid}", e)
        }
    }

    /**
     * 在被监督的子协程中运行一个请求。生命周期桥只取消该子协程：它
     * 绝不能取消排空 [workQueue] 的固定 worker 协程。执行器关闭
     * 仍会取消外围 worker，因此通过此作用域传播。
     */
    private suspend fun CoroutineScope.runLeasedTask(
        lease: AgentTaskLease,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        lease.ensureActive()
        supervisorScope {
            lease.ensureActive()
            val requestJob = coroutineContext.job
            // 桥只执行 Job.cancel。Unconfined 在完成
            // 租约信号的线程上恢复它，因此连接取消不能为此执行器的
            // 刻意 worker 大小物理队列再按 worker 添加第二个续延。
            val lifecycleBridge = launch(
                context = Dispatchers.Unconfined,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                requestJob.cancel(lease.awaitCancellation())
            }
            try {
                lease.ensureActive()
                block()
            } finally {
                lifecycleBridge.cancel()
            }
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) {
            awaitShutdownOwner()
            shutdownFailure.get()?.let { throw it }
            return
        }

        var failure: Throwable? = null
        try {
            shutdownOwnedResources()
        } catch (error: Throwable) {
            failure = error
            shutdownFailure.set(error)
        } finally {
            shutdownFinished.countDown()
        }
        failure?.let { throw it }
    }

    private fun shutdownOwnedResources() {
        workQueue.close()

        val abandonedSerialTasks = synchronized(serialQueueLock) {
            serialQueues.values.sumOf { queue ->
                queue.tasks.size.also {
                    queue.tasks.clear()
                    queue.running = false
                }
            }.also { serialQueues.clear() }
        }
        repeat(abandonedSerialTasks) { releaseWorkSlot() }

        var abandonedCompletionFailure: Throwable? = null
        while (true) {
            val abandoned = workQueue.tryReceive().getOrNull() ?: break
            try {
                completeForCaller(abandoned.completion)
            } catch (failure: Throwable) {
                abandonedCompletionFailure = appendFailure(abandonedCompletionFailure, failure)
            } finally {
                if (abandoned.releaseSlotOnCompletion) releaseWorkSlot()
            }
        }
        scope.cancel()
        pool.shutdown()
        var interrupted = false
        var terminated = awaitPoolTermination(shutdownTimeoutMillis) {
            interrupted = true
        }
        if (!terminated) {
            pool.shutdownNow()
            terminated = awaitPoolTermination(shutdownTimeoutMillis) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        check(terminated) {
            "IOExecutor did not terminate after two $shutdownTimeoutMillis ms shutdown phases"
        }
        abandonedCompletionFailure?.let { throw it }
    }

    private fun appendFailure(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        if (current !== next) current.addSuppressed(next)
        return current
    }

    private fun awaitShutdownOwner() {
        var interrupted = false
        while (true) {
            try {
                shutdownFinished.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun awaitPoolTermination(
        timeoutMillis: Long,
        onInterrupted: () -> Unit,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return pool.isTerminated
            try {
                return pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                onInterrupted()
            }
        }
    }

    private companion object {
        const val MAX_WORKERS = 32
        const val DEFAULT_QUEUE_CAPACITY = 64
        const val MAX_SERIAL_QUEUE_DEPTH = 256
        const val DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
    }
}
