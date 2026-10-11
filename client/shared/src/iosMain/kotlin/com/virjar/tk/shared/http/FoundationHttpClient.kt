@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.virjar.tk.shared.http

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.SessionBoundaryReentrantCloseException
import com.virjar.tk.shared.platform.PlatformLock
import com.virjar.tk.shared.platform.platformCurrentThreadId
import com.virjar.tk.shared.platform.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal const val IOS_HTTP_CHUNK_BYTES = 64 * 1024

internal data class FoundationHttpResponse(val status: Int, val contentLength: Long)

/** 每个 repository 拥有自己的 NSURLSession。不带 cookie、磁盘缓存、重定向或全局凭据存储。 */
internal class FoundationHttpClient : AutoCloseable {
    private val lock = PlatformLock()
    private var closed = false
    private val operations = mutableSetOf<FoundationHttpCall>()
    private val tasks = mutableMapOf<ULong, FoundationHttpCall>()
    private val activeThreads = mutableMapOf<Long, Int>()
    private val invalidated = CompletableDeferred<Unit>()
    private val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Dispatchers.Default.dispatch(context, Runnable {
                val thread = platformCurrentThreadId()
                synchronized(lock) { activeThreads[thread] = (activeThreads[thread] ?: 0) + 1 }
                try { block.run() } finally {
                    synchronized(lock) {
                        val depth = activeThreads.getValue(thread) - 1
                        if (depth == 0) activeThreads.remove(thread) else activeThreads[thread] = depth
                    }
                }
            })
        }
    }
    private val delegate = HttpDelegate(this)
    private val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1; name = "teamtalk.http.delegate" }
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            setHTTPCookieStorage(null)
            setURLCredentialStorage(null)
            setURLCache(null)
            HTTPShouldSetCookies = false
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            timeoutIntervalForRequest = 30.0
            timeoutIntervalForResource = 600.0
        }, delegate, queue,
    )

    /** 子 job 覆盖请求体准备与网络 IO。close 会一并取消两者。 */
    suspend fun <T> operation(block: suspend FoundationHttpCall.() -> T): T =
        withContext(dispatcher + FoundationIoContext(dispatcher)) {
            coroutineScope {
                val job = currentCoroutineContext().job
                val call = FoundationHttpCall(this@FoundationHttpClient, job)
                synchronized(lock) {
                    check(!closed) { "HTTP transport is closed" }
                    operations += call
                }
                // 在该作用域的子协程与清理退出之前，对每个 closer 保持可见。
                // 若取消先于注册发生，invokeOnCompletion 也会立即执行。
                job.invokeOnCompletion {
                    synchronized(lock) {
                        operations -= call
                        call.task?.let { tasks.remove(it.taskIdentifier) }
                    }
                }
                try { call.block() } finally { call.dispose() }
            }
        }

    internal fun start(call: FoundationHttpCall, request: NSURLRequest, uploadFile: NSURL?): NSURLSessionTask = synchronized(lock) {
        check(!closed && call in operations) { "HTTP transport is closed" }
        call.ensureActive()
        val task = if (uploadFile == null) session.dataTaskWithRequest(request)
            else session.uploadTaskWithRequest(request, fromFile = uploadFile)
        call.task = task
        tasks[task.taskIdentifier] = call
        task
    }

    private fun find(task: NSURLSessionTask): FoundationHttpCall? = synchronized(lock) { tasks[task.taskIdentifier] }

    override fun close() {
        val (first, pending, reentrant) = synchronized(lock) {
            val first = !closed
            closed = true
            Triple(first, operations.toList(), platformCurrentThreadId() in activeThreads)
        }
        // 取消可能触发回调或解除生产者阻塞，期间不要持有 owner 锁。
        pending.forEach { it.cancel() }
        if (first) session.invalidateAndCancel()
        if (reentrant) throw SessionBoundaryReentrantCloseException("HTTP transport cannot close from its own callback")
        // 该 client 拥有的全部准备/消费都执行在 Default 上；本 drain 与原生
        // 回调都不会要求主线程收尾。自定义 sink 必须保持同样的契约。
        runBlocking {
            pending.forEach { it.awaitExit() }
            invalidated.await()
        }
    }

    private class HttpDelegate(private val owner: FoundationHttpClient) : NSObject(), NSURLSessionDataDelegateProtocol {
        override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
            owner.invalidated.complete(Unit)
        }
        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
            owner.find(dataTask)?.receive(dataTask, didReceiveData)
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            owner.find(task)?.complete(task, didCompleteWithError)
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) {
            // 即使同源重定向也会被拒绝。调用方收到的是原始 HTTP 状态码。
            completionHandler(null)
        }
    }
}

/** 串行 delegate 队列只在容量为 1 的 channel 上阻塞，绝不阻塞主线程或 sink。 */
internal class FoundationHttpCall(private val owner: FoundationHttpClient, private val job: Job) {
    internal var task: NSURLSessionTask? = null
    private val response = CompletableDeferred<FoundationHttpResponse>()
    private val chunks = Channel<ByteArray>(1)

    fun ensureActive() = job.ensureActive()
    internal suspend fun awaitExit() = job.join()

    suspend fun stream(
        request: NSURLRequest,
        uploadFile: NSURL? = null,
        checkResponse: (FoundationHttpResponse) -> Unit = {},
        consume: suspend (ByteArray) -> Unit,
    ): FoundationHttpResponse {
        check(task == null) { "HTTP operation already started" }
        val current = owner.start(this, request, uploadFile)
        current.resume()
        val headers = response.await()
        ensureActive()
        checkResponse(headers)
        for (chunk in chunks) {
            ensureActive()
            consume(chunk)
        }
        ensureActive()
        return headers
    }

    suspend fun readBounded(
        request: NSURLRequest,
        limit: Int,
        uploadFile: NSURL? = null,
        checkResponse: (FoundationHttpResponse) -> Unit = {},
    ): Pair<FoundationHttpResponse, String> {
        require(limit >= 0)
        val bytes = ByteArray(limit)
        var length = 0
        val headers = stream(request, uploadFile, { value ->
            checkResponse(value)
            if (value.contentLength > limit) throw AppError.Business(413, "HTTP 响应过大")
        }) { chunk ->
            if (chunk.size > limit - length) throw AppError.Business(413, "HTTP 响应过大")
            chunk.copyInto(bytes, length)
            length += chunk.size
        }
        return headers to bytes.decodeToString(0, length)
    }

    internal fun receive(task: NSURLSessionTask, data: NSData) {
        try {
            publishResponse(task)
            var offset = 0uL
            // 只保留当前 Foundation 回调与有界的 Kotlin 分块。慢速消费者会向
            // 该 delegate 队列施加背压；取消则关闭 channel。
            while (offset < data.length) {
                ensureActive()
                val count = minOf(IOS_HTTP_CHUNK_BYTES.toULong(), data.length - offset).toInt()
                val chunk = ByteArray(count)
                chunk.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes!!.reinterpret<ByteVar>() + offset.toLong(), count.toULong())
                }
                runBlocking { chunks.send(chunk) }
                offset += count.toULong()
            }
        } catch (_: CancellationException) {
            task.cancel()
        } catch (_: Exception) {
            fail(AppError.Network)
            task.cancel()
        }
    }

    internal fun complete(task: NSURLSessionTask, error: NSError?) {
        if (error != null) {
            fail(AppError.Network)
        } else {
            try { publishResponse(task); chunks.close() }
            catch (_: Exception) { fail(AppError.Network) }
        }
    }

    private fun publishResponse(task: NSURLSessionTask) {
        val value = task.response as? NSHTTPURLResponse ?: throw AppError.Network
        response.complete(FoundationHttpResponse(value.statusCode.toInt(), value.expectedContentLength))
    }

    private fun fail(failure: Throwable) {
        response.completeExceptionally(failure)
        chunks.close(failure)
    }

    internal fun cancel() {
        job.cancel(CancellationException("HTTP transport is closed"))
        dispose()
    }

    internal fun dispose() {
        // 关闭已满的 channel，可在 session 失效之前释放 delegate 回调。
        chunks.cancel()
        response.cancel()
        task?.cancel()
    }
}

private class FoundationIoContext(val dispatcher: CoroutineDispatcher) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FoundationIoContext>
}

/** 文件源保留 owner 的 dispatcher，使同步回调能检测到重入 close。 */
internal suspend fun <T> foundationHttpIo(block: suspend CoroutineScope.() -> T): T =
    withContext(currentCoroutineContext()[FoundationIoContext]?.dispatcher ?: Dispatchers.Default, block)

internal fun foundationHttpRequest(
    url: String,
    method: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    timeoutSeconds: Double = 30.0,
): NSMutableURLRequest {
    val target = requireNotNull(NSURL.URLWithString(url)) { "Invalid HTTP URL" }
    require(target.scheme?.lowercase() in setOf("http", "https") && !target.host.isNullOrBlank() &&
        target.user == null && target.password == null && target.fragment == null) { "Invalid HTTP URL" }
    require(method in setOf("GET", "POST", "DELETE")) { "Unsupported HTTP method" }
    return NSMutableURLRequest.requestWithURL(target).apply {
        setHTTPMethod(method)
        setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        setTimeoutInterval(timeoutSeconds)
        headers.forEach { (name, value) ->
            require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' }) { "Invalid HTTP header" }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid HTTP header value" }
            setValue(value, name)
        }
        if (body != null) setHTTPBody(if (body.isEmpty()) NSData() else body.usePinned {
            NSData.create(bytes = it.addressOf(0), length = body.size.toULong())
        })
    }
}
