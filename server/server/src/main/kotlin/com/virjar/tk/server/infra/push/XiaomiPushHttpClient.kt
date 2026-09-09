package com.virjar.tk.server.infra.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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

private val xiaomiPushEndpoint = URI("https://api.xmpush.xiaomi.com/v3/message/regid")
private val xiaomiPushHttpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}
private const val MAX_RESPONSE_BYTES = 16 * 1024

internal suspend fun sendXiaomiPush(
    configuration: XiaomiPushConfiguration,
    notification: XiaomiPushNotification,
): XiaomiPushDeliveryResult = sendXiaomiPushRequest(configuration, notification, xiaomiPushEndpoint)

/** The endpoint is fixed in production; the argument permits a real local HTTP fixture. */
internal suspend fun sendXiaomiPushRequest(
    configuration: XiaomiPushConfiguration,
    notification: XiaomiPushNotification,
    endpoint: URI,
): XiaomiPushDeliveryResult {
    if (!configuration.enabled) return rejected("DISABLED")
    val identityPath = listOf(
        notification.deploymentFingerprint, notification.datasetId, notification.uid, notification.chatId,
    ).joinToString("/") { formEncode(it).replace("+", "%20") }
    val intentUri = "intent://message/$identityPath#Intent;scheme=teamtalk-local;" +
        "action=${configuration.packageName}.OPEN_MESSAGE;" +
        "component=${configuration.packageName}/com.virjar.tk.android.MainActivity;" +
        "launchFlags=0x24000000;end"
    val fields = linkedMapOf(
        "registration_id" to notification.registrationId,
        "restricted_package_name" to configuration.packageName,
        "title" to configuration.title,
        "description" to "你有新的未读消息，点击查看",
        "payload" to "unread",
        "time_to_live" to "3600000",
        "notify_id" to "0",
        "extra.channel_id" to configuration.channelId,
        "extra.template_id" to configuration.templateId,
        "extra.template_param" to "{}",
        "extra.notify_foreground" to "0",
        "extra.notify_effect" to "2",
        "extra.intent_uri" to intentUri,
        "extra.jobkey" to notification.jobKey,
    )
    val request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "key=${configuration.appSecret}")
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(fields.entries.joinToString("&") {
            "${formEncode(it.key)}=${formEncode(it.value)}"
        }))
        .build()
    val response: HttpResponse<ByteArray> = try {
        withTimeoutOrNull(15_000.milliseconds) {
            val future = xiaomiPushHttpClient.sendAsync(request) { BoundedPushResponse() }
            suspendCancellableCoroutine<HttpResponse<ByteArray>> { continuation ->
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error == null) continuation.resume(value)
                    else continuation.resumeWithException(error)
                }
            }
        } ?: return rejected("HTTP_TIMEOUT")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Coroutine stack-trace recovery can copy CompletionException around the HTTP future's wrapper.
        val cause = generateSequence<Throwable>(error) { (it as? CompletionException)?.cause }
            .take(8)
            .last()
        if (cause is Error) throw cause
        if (cause is CancellationException) throw cause
        return rejected(if (cause is PushResponseTooLarge) "RESPONSE_TOO_LARGE" else "HTTP_UNAVAILABLE")
    }
    if (response.statusCode() != 200) return rejected(when (response.statusCode()) {
        401, 403 -> "HTTP_AUTHENTICATION_FAILED"
        429 -> "HTTP_RATE_LIMITED"
        in 500..599 -> "HTTP_UNAVAILABLE"
        else -> "HTTP_REJECTED"
    })
    val result = try {
        Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return rejected("INVALID_RESPONSE")
    val code = (result["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: return rejected("INVALID_RESPONSE")
    if (code == 0) {
        return if ((result["result"] as? JsonPrimitive)?.content == "ok") XiaomiPushDeliveryResult(true)
        else rejected("INVALID_RESPONSE")
    }
    return when (code) {
        66007 -> XiaomiPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        1, 10001, 10002, 10003, 20607, 26003, 65029 -> rejected("PROVIDER_UNAVAILABLE")
        10027, 200002 -> rejected("PROVIDER_RATE_LIMITED")
        10034, 200001 -> rejected("PROVIDER_QUOTA_EXCEEDED")
        21301, 21302, 22000, 22006, 22007, 22022 -> rejected("PROVIDER_CONFIGURATION_REJECTED")
        10041, 27001 -> rejected("PROVIDER_TEMPLATE_REJECTED")
        else -> rejected("PROVIDER_REJECTED")
    }
}

private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
private fun rejected(reason: String) = XiaomiPushDeliveryResult(false, reason)
private class PushResponseTooLarge : IOException("Push response exceeds limit")

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
        if (items.sumOf { it.remaining().toLong() } > MAX_RESPONSE_BYTES - bytes.size()) {
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
