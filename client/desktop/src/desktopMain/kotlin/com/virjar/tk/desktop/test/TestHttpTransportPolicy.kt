package com.virjar.tk.desktop.test

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val MAX_TEST_HTTP_REQUEST_BODY_BYTES = 1_048_576

internal class TestHttpRequestTooLargeException(maxBytes: Int) :
    IllegalArgumentException("request body exceeds $maxBytes bytes")

/** 像真实网络监听器一样，为仅限开发的自动化端点设置边界。 */
internal fun createTestHttpExecutor(
    workerCount: Int = 4,
    queueCapacity: Int = 64,
): ExecutorService {
    require(workerCount > 0) { "Test HTTP worker count must be positive" }
    require(queueCapacity > 0) { "Test HTTP queue capacity must be positive" }
    return ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        TestHttpThreadFactory,
        // HttpServer 自身的 dispatcher 是一条安全的最终执行通道，在有界 worker 饱和时
        // 自然停止接收更多自动化命令。
        ThreadPoolExecutor.CallerRunsPolicy(),
    )
}

internal fun shutdownTestHttpExecutor(
    executor: ExecutorService,
    timeoutMillis: Long = TEST_HTTP_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS,
) {
    require(timeoutMillis > 0L) { "Test HTTP executor shutdown timeout must be positive" }
    executor.shutdownNow()
    try {
        check(executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
            "Test HTTP executor did not terminate within $timeoutMillis ms"
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while stopping test HTTP executor", interrupted)
    }
}

internal fun readBoundedUtf8(
    input: InputStream,
    declaredLength: Long?,
    maxBytes: Int = MAX_TEST_HTTP_REQUEST_BODY_BYTES,
): String {
    require(maxBytes > 0) { "Request body limit must be positive" }
    if (declaredLength != null && declaredLength > maxBytes.toLong()) {
        throw TestHttpRequestTooLargeException(maxBytes)
    }
    val initialCapacity = declaredLength
        ?.coerceAtLeast(0L)
        ?.coerceAtMost(maxBytes.toLong())
        ?.toInt()
        ?: 0
    val bytes = ByteArrayOutputStream(initialCapacity)
    val chunk = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw TestHttpRequestTooLargeException(maxBytes)
        bytes.write(chunk, 0, read)
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private object TestHttpThreadFactory : ThreadFactory {
    private val nextId = AtomicInteger(0)

    override fun newThread(task: Runnable): Thread =
        Thread(task, "teamtalk-test-http-${nextId.incrementAndGet()}").apply { isDaemon = true }
}

private const val TEST_HTTP_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS = 2_000L
