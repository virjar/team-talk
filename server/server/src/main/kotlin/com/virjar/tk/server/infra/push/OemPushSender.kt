package com.virjar.tk.server.infra.push

import com.virjar.tk.server.runtime.BoundedCloseGate
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** One Application owns its provider credentials, token caches and outbound connection pool. */
internal class OemPushSender(
    configuration: OemPushConfiguration,
    shutdownTimeoutMillis: Long = 5_000L,
) : AutoCloseable {
    private val configurations = configuration.vendors.toMap()
    private val tokenCaches = configurations.mapValues { OemPushTokenCache() }
    private val closing = AtomicBoolean(false)
    private val closeGate = BoundedCloseGate("OEM push sender", shutdownTimeoutMillis, onTerminal = {})
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun send(
        configuration: OemPushVendorConfiguration,
        notification: OemPushNotification,
    ): OemPushDeliveryResult {
        requireConfiguration(configuration)
        return when (configuration.vendor) {
            OemPushVendors.XIAOMI -> sendXiaomiPush(configuration, notification)
            OemPushVendors.HUAWEI, OemPushVendors.HONOR -> sendHuaweiStylePush(configuration, notification)
            OemPushVendors.OPPO -> sendOppoPush(configuration, notification)
            OemPushVendors.VIVO -> sendVivoPush(configuration, notification)
            OemPushVendors.MEIZU -> sendMeizuPush(configuration, notification)
            else -> pushRejected("UNKNOWN_VENDOR")
        }
    }

    internal fun tokenCache(configuration: OemPushVendorConfiguration): OemPushTokenCache {
        requireConfiguration(configuration)
        return checkNotNull(tokenCaches[configuration.vendor])
    }

    private fun requireConfiguration(configuration: OemPushVendorConfiguration) {
        check(!closing.get()) { "OEM push sender is closed" }
        require(configurations[configuration.vendor] === configuration) {
            "OEM push configuration belongs to another sender"
        }
    }

    internal fun startRequest(request: HttpRequest): CompletableFuture<HttpResponse<ByteArray>> {
        check(!closing.get()) { "OEM push sender is closed" }
        // shutdownNow also rejects a request racing the close check.
        return httpClient.sendAsync(request) { BoundedPushResponse() }
    }

    override fun close() {
        val failure = when (val attempt = closeGate.begin()) {
            is BoundedCloseGate.Attempt.Owner -> closeOwned(attempt)
            is BoundedCloseGate.Attempt.Follower -> closeGate.awaitFollowerBlocking(attempt)
            is BoundedCloseGate.Attempt.Terminal -> attempt.failure
        }
        failure?.let { throw it }
    }

    private fun closeOwned(attempt: BoundedCloseGate.Attempt.Owner): Throwable? {
        closing.set(true)
        var interrupted = false
        try {
            try {
                httpClient.shutdownNow()
            } catch (failure: Throwable) {
                closeGate.recordFailure(failure)
                return closeGate.complete(attempt)
            }
            while (!httpClient.isTerminated) {
                val remaining = attempt.deadline.remainingNanos()
                if (remaining <= 0L) return closeGate.expire(attempt.deadline)
                try {
                    httpClient.awaitTermination(Duration.ofNanos(remaining))
                } catch (failure: InterruptedException) {
                    interrupted = true
                    closeGate.recordFailure(failure)
                } catch (failure: Throwable) {
                    closeGate.recordFailure(failure)
                    return closeGate.complete(attempt)
                }
            }
            return closeGate.complete(attempt)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
