package com.virjar.tk.server.infra.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * vivo 服务端：/message/auth 以 md5(appId+appKey+timestamp+appSecret) 换取 authToken（24 小时），
 * /message/send 单推按 regId 下发；skipType=4 用 intent 打开应用内定位页。
 */
internal object VivoPush {
    val authEndpoint: URI = URI("https://api-push.vivo.com.cn/message/auth")
    val sendEndpoint: URI = URI("https://api-push.vivo.com.cn/message/send")
}

internal suspend fun sendVivoPush(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
): OemPushDeliveryResult {
    val result = sendVivoPushRequest(configuration, notification, VivoPush.authEndpoint, VivoPush.sendEndpoint) {
        cachedOemPushToken(OemPushVendors.VIVO) { fetchVivoAuthToken(configuration, VivoPush.authEndpoint) }
    }
    if (result.refreshToken) invalidateOemPushToken(OemPushVendors.VIVO)
    return result
}

internal suspend fun sendVivoPushRequest(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
    authEndpoint: URI,
    sendEndpoint: URI,
    authToken: suspend () -> String?,
): OemPushDeliveryResult {
    val token = authToken() ?: return pushRejected("PROVIDER_AUTH_TOKEN_UNAVAILABLE")
    val body = buildString {
        append('{')
        append("\"appId\":${JsonPrimitive(configuration.appId.toInt())},")
        append("\"regId\":${JsonPrimitive(notification.registrationId)},")
        append("\"notifyType\":1,")
        append("\"title\":${JsonPrimitive(configuration.title)},")
        append("\"content\":${JsonPrimitive(OEM_PUSH_CONTENT)},")
        append("\"timeToLive\":86400,")
        append("\"skipType\":4,\"skipContent\":${JsonPrimitive(oemPushIntentUri(configuration.packageName, notification))},")
        if (configuration.category.isNotEmpty()) append("\"category\":${JsonPrimitive(configuration.category)},")
        append("\"requestId\":${JsonPrimitive(notification.jobKey)},\"pushMode\":0")
        append('}')
    }
    val request = HttpRequest.newBuilder(sendEndpoint)
        .timeout(Duration.ofSeconds(15))
        .header("authToken", token)
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> pushRejected(exchange.reason)
        is OemPushHttpExchange.Responded -> vivoResult(exchange.status, exchange.body.toString(Charsets.UTF_8))
    }
}

internal suspend fun fetchVivoAuthToken(
    configuration: OemPushVendorConfiguration,
    authEndpoint: URI,
): Pair<String, Long>? {
    val timestamp = System.currentTimeMillis()
    val appId = configuration.appId.toIntOrNull() ?: return null
    val body = buildString {
        append('{')
        append("\"appId\":$appId,")
        append("\"appKey\":${JsonPrimitive(configuration.appKey)},")
        append("\"timestamp\":$timestamp,")
        append("\"sign\":${JsonPrimitive(md5Hex("${configuration.appId}${configuration.appKey}$timestamp${configuration.appSecret}"))}")
        append('}')
    }
    val request = HttpRequest.newBuilder(authEndpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> null
        is OemPushHttpExchange.Responded -> {
            if (exchange.status != 200) return null
            val root = runCatching {
                Json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)).jsonObject
            }.getOrNull() ?: return null
            if ((root["result"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 0) return null
            val token = (root["authToken"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: return null
            // 官方有效期 24 小时，按 1 小时刷新余量缓存。
            token to System.currentTimeMillis() + 3_600_000L
        }
    }
}

private fun vivoResult(status: Int, body: String): OemPushDeliveryResult {
    if (status != 200) return pushRejected(oemPushHttpRejection(status))
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return pushRejected("INVALID_RESPONSE")
    val result = (root["result"] as? JsonPrimitive)?.let { primitive ->
        primitive.takeUnless { it.isString }?.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    } ?: return pushRejected("INVALID_RESPONSE")
    return when (result) {
        0 -> OemPushDeliveryResult(true)
        10302 -> OemPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        10000 -> OemPushDeliveryResult(false, "PROVIDER_AUTH_TOKEN_EXPIRED", refreshToken = true)
        10200, 10201, 10202, 10203, 10204, 10205, 10206, 10207, 10094 -> pushRejected("PROVIDER_CONFIGURATION_REJECTED")
        10070, 10073 -> pushRejected("PROVIDER_QUOTA_EXCEEDED")
        10071, 10072, 10252 -> pushRejected("PROVIDER_RATE_LIMITED")
        10043, 10045 -> pushRejected("PROVIDER_CONFIGURATION_REJECTED")
        else -> pushRejected("PROVIDER_REJECTED")
    }
}
