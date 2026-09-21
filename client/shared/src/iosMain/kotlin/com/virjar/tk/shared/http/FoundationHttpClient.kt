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

/** Each repository owns its NSURLSession. No cookies, disk cache, redirects or global credential store. */
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

    /** The child job spans request-body preparation as well as network IO. close cancels both. */
    suspend fun <T> operation(block: suspend FoundationHttpCall.() -> T): T =
        withContext(dispatcher + FoundationIoContext(dispatcher)) {
            coroutineScope {
                val job = currentCoroutineContext().job
                val call = FoundationHttpCall(this@FoundationHttpClient, job)
                synchronized(lock) {
                    check(!closed) { "HTTP transport is closed" }
                    operations += call
                }
                // Remain visible to every closer until the scope's children and cleanup have exited.
                // invokeOnCompletion also invokes immediately if cancellation won before registration.
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
        // Do not hold the owner lock while cancellation may invoke callbacks or unblock a producer.
        pending.forEach { it.cancel() }
        if (first) session.invalidateAndCancel()
        if (reentrant) throw SessionBoundaryReentrantCloseException("HTTP transport cannot close from its own callback")
        // All owned preparation/consumption executes on Default; neither this drain nor a native
        // callback asks the main thread to finish. A custom sink must preserve that same contract.
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
            // Even same-origin redirects are refused. The caller receives the original HTTP status.
            completionHandler(null)
        }
    }
}

/** The serial delegate queue blocks only on a capacity-one channel, never on the main thread or sink. */
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
            // Retain only the current Foundation callback and bounded Kotlin chunks. A slow
            // consumer applies backpressure to this delegate queue; cancellation closes the channel.
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
        // Closing a full channel releases the delegate callback before invalidating the session.
        chunks.cancel()
        response.cancel()
        task?.cancel()
    }
}

private class FoundationIoContext(val dispatcher: CoroutineDispatcher) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FoundationIoContext>
}

/** File sources retain the owner's dispatcher so synchronous callbacks detect reentrant close. */
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
