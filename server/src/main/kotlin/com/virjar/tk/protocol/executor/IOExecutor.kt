package com.virjar.tk.protocol.executor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * IO 线程池，用于将重量级操作（DB/消息存储）从 Netty EventLoop 上卸载。
 *
 * EventLoop 适合处理轻量网络操作（PING/PONG/DISCONNECT/SUBSCRIBE），
 * 重量操作（消息存储、好友查询、频道成员查询）应 dispatch 到此线程池。
 *
 * Channel.writeAndFlush() 是线程安全的（Netty 内部会提交到对应 EventLoop），
 * IO 线程可以直接调用。
 */
class IOExecutor(
    private val workerCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(4, MAX_WORKERS),
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val logger = LoggerFactory.getLogger(IOExecutor::class.java)

    init {
        require(workerCount > 0) { "IO worker 数量必须为正数" }
        require(queueCapacity > 0) { "IO 队列容量必须为正数" }
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
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 执行中最多 workerCount 个，另有 queueCapacity 个等待；两者都持有完整解码对象。 */
    private val outstandingTasks = AtomicInteger(0)
    private val maximumOutstandingTasks = workerCount + queueCapacity

    private data class QueuedWork(
        val releaseSlotOnCompletion: Boolean,
        val block: suspend CoroutineScope.() -> Unit,
    )

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
                    if (work.releaseSlotOnCompletion) releaseWorkSlot()
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
        agent: com.virjar.tk.protocol.codec.ImAgent,
        block: suspend CoroutineScope.(ImAgentFacade) -> Unit,
    ): Boolean {
        val facade = ImAgentFacade(agent)
        return trySubmit {
            runAgentTask(facade, block)
        }
    }

    /** 测试和同包基础设施共用的非阻塞准入入口。 */
    internal fun tryLaunchTask(block: suspend CoroutineScope.() -> Unit): Boolean = trySubmit(block = block)

    internal val outstandingTaskCount: Int get() = outstandingTasks.get()

    fun launchSerialWithAgent(
        key: String,
        agent: com.virjar.tk.protocol.codec.ImAgent,
        block: suspend CoroutineScope.(ImAgentFacade) -> Unit,
    ): Boolean {
        val task = SerialAgentTask(ImAgentFacade(agent), block)
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
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean {
        if (acquireSlot && !tryAcquireWorkSlot()) return false
        val result = workQueue.trySend(QueuedWork(acquireSlot, block))
        if (result.isFailure && acquireSlot) releaseWorkSlot()
        return result.isSuccess
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
            block(facade)
        } catch (e: AgentDisposedException) {
            logger.debug("Coroutine cancelled, agent gone: uid=${facade.uid}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("IOExecutor coroutine task failed, uid=${facade.uid}", e)
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
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

        while (true) {
            val abandoned = workQueue.tryReceive().getOrNull() ?: break
            if (abandoned.releaseSlotOnCompletion) releaseWorkSlot()
        }
        scope.cancel()
        pool.shutdown()
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val MAX_WORKERS = 32
        const val DEFAULT_QUEUE_CAPACITY = 64
        const val MAX_SERIAL_QUEUE_DEPTH = 256
    }
}
