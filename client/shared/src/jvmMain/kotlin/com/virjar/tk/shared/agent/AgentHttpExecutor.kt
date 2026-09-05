package com.virjar.tk.shared.agent

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 无头 agent 嵌入式 HTTP API 的有界请求执行。
 *
 * receive-wait 请求可以合理地占用一个 worker 长达一分钟。缓存池会把
 * 并发的长轮询变成无界的原生线程，而中止策略则可能让一个已被接受的
 * exchange 得不到响应。Caller-runs 使内存与线程增长都保持有界，并且
 * 在 worker 队列饱和时把背压施加到 HttpServer 的 dispatcher 上。
 */
internal fun createAgentHttpExecutor(
    workerCount: Int = DEFAULT_AGENT_HTTP_WORKERS,
    queueCapacity: Int = DEFAULT_AGENT_HTTP_QUEUE_CAPACITY,
): ExecutorService {
    require(workerCount > 0) { "Agent HTTP worker count must be positive" }
    require(queueCapacity > 0) { "Agent HTTP queue capacity must be positive" }
    return ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        AgentHttpThreadFactory,
        ThreadPoolExecutor.CallerRunsPolicy(),
    )
}

private object AgentHttpThreadFactory : ThreadFactory {
    private val nextId = AtomicInteger(0)

    override fun newThread(task: Runnable): Thread =
        Thread(task, "tt-agent-http-${nextId.incrementAndGet()}").apply { isDaemon = true }
}

private const val DEFAULT_AGENT_HTTP_WORKERS = 16
private const val DEFAULT_AGENT_HTTP_QUEUE_CAPACITY = 128
