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
 * OPPO 服务端：/server/v1/auth 以 sha256(appKey+timestamp+appSecret) 换取 auth_token（24 小时），
 * 单推接口 target_type=2 按 registration_id 下发；通知支持 click_action_type=5 的 intent scheme。
 */
internal object OppoPush {
    val authEndpoint: URI = URI("https://api.push.oppomobile.com/server/v1/auth")
    val sendEndpoint: URI = URI("https://api.push.oppomobile.com/server/v1/message/notification/unicast")
}

internal suspend fun sendOppoPush(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
): OemPushDeliveryResult {
    val result = sendOppoPushRequest(configuration, notification, OppoPush.authEndpoint, OppoPush.sendEndpoint) {
        cachedOemPushToken(OemPushVendors.OPPO) { fetchOppoAuthToken(configuration, OppoPush.authEndpoint) }
    }
    if (result.refreshToken) invalidateOemPushToken(OemPushVendors.OPPO)
    return result
}

internal suspend fun sendOppoPushRequest(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
    authEndpoint: URI,
    sendEndpoint: URI,
    authToken: suspend () -> String?,
): OemPushDeliveryResult {
    val token = authToken() ?: return pushRejected("PROVIDER_AUTH_TOKEN_UNAVAILABLE")
    val message = buildString {
        append('{')
        append("\"title\":${JsonPrimitive(configuration.title)},")
        append("\"content\":${JsonPrimitive(OEM_PUSH_CONTENT)},")
        append("\"channel_id\":${JsonPrimitive(configuration.channelId)},")
        append("\"click_action_type\":5,")
        append("\"click_action_url\":${JsonPrimitive(oemPushIntentUri(configuration.packageName, notification))},")
        append("\"app_message_id\":${JsonPrimitive(notification.jobKey)},")
        append("\"off_line\":true,\"off_line_ttl\":3600")
        append('}')
    }
    val request = HttpRequest.newBuilder(sendEndpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(
            "auth_token=${formEncode(token)}&target_type=2" +
                "&target_value=${formEncode(notification.registrationId)}" +
                "&message=${formEncode(message)}",
        ))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> pushRejected(exchange.reason)
        is OemPushHttpExchange.Responded -> oppoResult(exchange.status, exchange.body.toString(Charsets.UTF_8))
    }
}

internal suspend fun fetchOppoAuthToken(
    configuration: OemPushVendorConfiguration,
    authEndpoint: URI,
): Pair<String, Long>? {
    val timestamp = System.currentTimeMillis().toString()
    val request = HttpRequest.newBuilder(authEndpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(
            "app_key=${formEncode(configuration.appKey)}&timestamp=$timestamp" +
                "&sign=${sha256Hex(configuration.appKey + timestamp + configuration.appSecret)}",
        ))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> null
        is OemPushHttpExchange.Responded -> {
            if (exchange.status != 200) return null
            val root = runCatching {
                Json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)).jsonObject
            }.getOrNull() ?: return null
            if ((root["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 0) return null
            val token = (root["data"] as? JsonObject)?.get("auth_token")?.let { it as? JsonPrimitive }
                ?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            // 官方有效期 24 小时，按 1 小时刷新余量缓存。
            token to System.currentTimeMillis() + 3_600_000L
        }
    }
}

private fun oppoResult(status: Int, body: String): OemPushDeliveryResult {
    if (status != 200) return pushRejected(oemPushHttpRejection(status))
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return pushRejected("INVALID_RESPONSE")
    val code = (root["code"] as? JsonPrimitive)?.let { primitive ->
        primitive.takeUnless { it.isString }?.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    } ?: return pushRejected("INVALID_RESPONSE")
    return when (code) {
        0 -> OemPushDeliveryResult(true)
        10000 -> OemPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        13 -> pushRejected("PROVIDER_RATE_LIMITED")
        11, 14, 15, 16, 17, 18, 19, 40, 41 -> pushRejected("PROVIDER_CONFIGURATION_REJECTED")
        else -> pushRejected("PROVIDER_REJECTED")
    }
}
