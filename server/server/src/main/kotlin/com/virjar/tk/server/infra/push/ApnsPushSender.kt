package com.virjar.tk.server.infra.push

import kotlinx.serialization.json.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.security.Signature
import java.time.Duration
import java.util.Base64

/** ES256 uses the JOSE 64-byte R || S signature, not ASN.1 DER. One owner caches each key's JWT. */
internal class ApnsProviderToken(private val configuration: ApnsPushConfiguration) {
    private var cached: String? = null
    private var issuedAt: Long = 0

    @Synchronized
    fun get(nowSeconds: Long): String {
        cached?.takeIf { nowSeconds >= issuedAt && nowSeconds - issuedAt < 50 * 60 }?.let { return it }
        val header = encode(buildJsonObject { put("alg", "ES256"); put("kid", configuration.keyId) }.toString().toByteArray())
        val claims = encode(buildJsonObject { put("iss", configuration.teamId); put("iat", nowSeconds) }.toString().toByteArray())
        val signingInput = "$header.$claims"
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(configuration.privateKey)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        check(signature.size == 64)
        return "$signingInput.${encode(signature)}".also { cached = it; issuedAt = nowSeconds }
    }

    @Synchronized
    fun invalidate(token: String) {
        if (cached == token) cached = null
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** APNs shares the Application-owned HTTP/2 pool, bounded body subscriber, timeout and cancellation. */
internal suspend fun OemPushSender.sendApnsPush(
    notification: OemPushNotification,
    clock: () -> Long = System::currentTimeMillis,
    exchange: suspend (HttpRequest) -> OemPushHttpExchange = { oemPushExchange(it) },
): OemPushDeliveryResult {
    val configuration = checkNotNull(apnsConfiguration) { "APNs is not configured" }
    val environment = requireNotNull(ApnsEnvironment.fromChannel(notification.vendor)) { "Invalid APNs channel" }
    require(environment in configuration.environments) { "APNs environment is not enabled" }
    require(notification.registrationId.length % 2 == 0 &&
        notification.registrationId.matches(Regex("[0-9a-f]{2,512}"))) { "Invalid APNs device token" }
    val cache = apnsProviderToken()
    val nowSeconds = clock() / 1_000
    val token = cache.get(nowSeconds)
    val payload = buildJsonObject {
        putJsonObject("aps") {
            putJsonObject("alert") { put("title", configuration.title); put("body", OEM_PUSH_CONTENT) }
            put("sound", "default")
            put("thread-id", notification.chatId)
        }
        put("deploymentFingerprint", notification.deploymentFingerprint)
        put("datasetId", notification.datasetId)
        put("uid", notification.uid)
        put("chatId", notification.chatId)
    }.toString().toByteArray(Charsets.UTF_8)
    check(payload.size <= 4096) { "APNs notification exceeds payload limit" }
    val request = HttpRequest.newBuilder(environment.endpoint.resolve("/3/device/${notification.registrationId}"))
        .version(HttpClient.Version.HTTP_2)
        .timeout(Duration.ofSeconds(15))
        .header("authorization", "bearer $token")
        .header("apns-topic", configuration.bundleId)
        .header("apns-push-type", "alert")
        .header("apns-priority", "10")
        .header("apns-expiration", (nowSeconds + 86_400).toString())
        .header("apns-collapse-id", notification.jobKey)
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
        .build()
    return when (val response = exchange(request)) {
        is OemPushHttpExchange.Failed -> pushRejected(response.reason)
        is OemPushHttpExchange.Responded -> {
            if (response.version != HttpClient.Version.HTTP_2) return pushRejected("APNS_HTTP2_REQUIRED")
            if (response.status == 200) return OemPushDeliveryResult(true)
            val body = runCatching { Json.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
            val reason = (body?.get("reason") as? JsonPrimitive)?.contentOrNull
            if (reason == "ExpiredProviderToken") cache.invalidate(token)
            val invalid = when (reason) {
                "BadDeviceToken", "DeviceTokenNotForTopic" -> response.status == 400
                "Unregistered" -> response.status == 410 &&
                    ((body?.get("timestamp") as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE) >= notification.registeredAt
                else -> false
            }
            OemPushDeliveryResult(
                accepted = false,
                reason = when (reason) {
                    "BadDeviceToken" -> "APNS_BAD_DEVICE_TOKEN"
                    "DeviceTokenNotForTopic" -> "APNS_TOKEN_TOPIC_MISMATCH"
                    "Unregistered" -> "APNS_UNREGISTERED"
                    "ExpiredProviderToken" -> "APNS_EXPIRED_PROVIDER_TOKEN"
                    "InvalidProviderToken" -> "APNS_INVALID_PROVIDER_TOKEN"
                    else -> oemPushHttpRejection(response.status)
                },
                invalidRegistration = invalid,
            )
        }
    }
}
