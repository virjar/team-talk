package com.virjar.tk.server.infra.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/** 全部厂商推送共用一条出站连接池；不跟随重定向，响应体先按上限截断。 */
internal val oemPushHttpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

internal const val OEM_PUSH_MAX_RESPONSE_BYTES = 16 * 1024
internal const val OEM_PUSH_CONTENT = "你有新的未读消息，点击查看"

internal sealed interface OemPushHttpExchange {
    data class Responded(val status: Int, val body: ByteArray) : OemPushHttpExchange
    data class Failed(val reason: String) : OemPushHttpExchange
}

/** 与调用方协程取消保持一致：取消向上传播，不沉淀为厂商失败结果。 */
internal suspend fun oemPushExchange(request: HttpRequest, timeoutMillis: Long = 15_000): OemPushHttpExchange {
    val exchange: OemPushHttpExchange = try {
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            val future = oemPushHttpClient.sendAsync(request) { BoundedPushResponse() }
            suspendCancellableCoroutine<HttpResponse<ByteArray>> { continuation ->
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error == null) continuation.resume(value)
                    else continuation.resumeWithException(error)
                }
            }
        }?.let { OemPushHttpExchange.Responded(it.statusCode(), it.body()) }
            ?: OemPushHttpExchange.Failed("HTTP_TIMEOUT")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Coroutine stack-trace recovery can copy CompletionException around the HTTP future's wrapper.
        val cause = generateSequence<Throwable>(error) { (it as? CompletionException)?.cause }
            .take(8)
            .last()
        if (cause is Error) throw cause
        if (cause is CancellationException) throw cause
        OemPushHttpExchange.Failed(if (cause is PushResponseTooLarge) "RESPONSE_TOO_LARGE" else "HTTP_UNAVAILABLE")
    }
    return exchange
}

internal fun oemPushHttpRejection(status: Int): String = when (status) {
    401, 403 -> "HTTP_AUTHENTICATION_FAILED"
    429 -> "HTTP_RATE_LIMITED"
    in 500..599 -> "HTTP_UNAVAILABLE"
    else -> "HTTP_REJECTED"
}

/** 通知点击后由厂商通道拉起应用并定位会话；与本地 TCP 通知共用同一打开协议。 */
internal fun oemPushIntentUri(packageName: String, notification: OemPushNotification): String {
    val identityPath = listOf(
        notification.deploymentFingerprint, notification.datasetId, notification.uid, notification.chatId,
    ).joinToString("/") { formEncode(it).replace("+", "%20") }
    return "intent://message/$identityPath#Intent;scheme=teamtalk-local;" +
        "action=${packageName}.OPEN_MESSAGE;" +
        "component=${packageName}/com.virjar.tk.android.MainActivity;" +
        "launchFlags=0x24000000;end"
}

internal fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

internal fun pushRejected(reason: String) = OemPushDeliveryResult(false, reason)

internal class PushResponseTooLarge : IOException("Push response exceeds limit")

/** Reject before buffering an oversized response, including chunked bodies without Content-Length. */
private class BoundedPushResponse : HttpResponse.BodySubscriber<ByteArray> {
    private val completion = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = completion

    override fun onSubscribe(subscription: Flow.Subscription) {
        if (this.subscription != null) {
            subscription.cancel()
            return
        }
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(items: List<ByteBuffer>) {
        if (completion.isDone) return
        if (items.sumOf { it.remaining().toLong() } > OEM_PUSH_MAX_RESPONSE_BYTES - bytes.size()) {
            // JDK cancellation can synchronously report a generic IOException via onError.
            // Publish the precise local failure before terminating the subscription.
            completion.completeExceptionally(PushResponseTooLarge())
            subscription?.cancel()
            return
        }
        items.forEach { item ->
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            bytes.write(chunk)
        }
    }

    override fun onError(error: Throwable) { completion.completeExceptionally(error) }
    override fun onComplete() { completion.complete(bytes.toByteArray()) }
}
