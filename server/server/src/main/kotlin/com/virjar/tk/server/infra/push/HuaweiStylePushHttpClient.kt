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
 * 华为与荣耀使用同构的 OAuth2 + v2 下行消息接口，仅域名与字段命名不同：
 * 华为 snake_case，荣耀 camelCase。端点按厂商构造，测试注入本地 fixture。
 */
internal object HuaweiStylePush {
    fun tokenEndpoint(vendor: String): URI = when (vendor) {
        OemPushVendors.HONOR -> URI("https://oauth-login.cloud.honor.com/oauth2/v3/token")
        else -> URI("https://oauth-login.cloud.huawei.com/oauth2/v3/token")
    }

    fun sendEndpoint(vendor: String, appId: String): URI = when (vendor) {
        OemPushVendors.HONOR -> URI("https://push-api.cloud.honor.com/v2/$appId/messages:send")
        else -> URI("https://push-api.cloud.huawei.com/v1/$appId/messages:send")
    }
}

internal suspend fun sendHuaweiStylePush(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
): OemPushDeliveryResult {
    val result = sendHuaweiStylePushRequest(
        configuration, notification,
        HuaweiStylePush.tokenEndpoint(configuration.vendor),
        HuaweiStylePush.sendEndpoint(configuration.vendor, configuration.appId),
    ) { cachedOemPushToken(configuration.vendor) { fetchHuaweiStyleAccessToken(configuration, HuaweiStylePush.tokenEndpoint(configuration.vendor)) } }
    if (result.refreshToken) invalidateOemPushToken(configuration.vendor)
    return result
}

internal suspend fun sendHuaweiStylePushRequest(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
    tokenEndpoint: URI,
    sendEndpoint: URI,
    accessToken: suspend () -> String?,
): OemPushDeliveryResult {
    val token = accessToken() ?: return pushRejected("PROVIDER_AUTH_TOKEN_UNAVAILABLE")
    // Huawei uses the snake_case field set; Honor mirrors the structure with camelCase names.
    val honor = configuration.vendor == OemPushVendors.HONOR
    val body = buildString {
        append("{\"message\":{")
        append("\"notification\":{\"title\":${JsonPrimitive(configuration.title)},\"body\":${JsonPrimitive(OEM_PUSH_CONTENT)}}")
        append(",\"android\":{\"notification\":{")
        append("\"title\":${JsonPrimitive(configuration.title)},\"body\":${JsonPrimitive(OEM_PUSH_CONTENT)},")
        append("\"${if (honor) "clickAction" else "click_action"}\":{\"type\":1,\"intent\":${JsonPrimitive(oemPushIntentUri(configuration.packageName, notification))}},")
        append("\"${if (honor) "channelId" else "channel_id"}\":${JsonPrimitive(configuration.channelId)},")
        append("\"${if (honor) "notifyId" else "notify_id"}\":0,")
        append("\"${if (honor) "foregroundShow" else "foreground_show"}\":false}")
        append("},\"ttl\":\"3600s\"")
        append(",\"token\":[${JsonPrimitive(notification.registrationId)}]}}")
    }
    val request = HttpRequest.newBuilder(sendEndpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> pushRejected(exchange.reason)
        is OemPushHttpExchange.Responded -> huaweiStyleResult(
            exchange.status, exchange.body.toString(Charsets.UTF_8), notification.registrationId,
        )
    }
}

internal suspend fun fetchHuaweiStyleAccessToken(
    configuration: OemPushVendorConfiguration,
    tokenEndpoint: URI,
): Pair<String, Long>? {
    val request = HttpRequest.newBuilder(tokenEndpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(
            "grant_type=client_credentials&client_id=${formEncode(configuration.appId)}" +
                "&client_secret=${formEncode(configuration.appSecret)}",
        ))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> null
        is OemPushHttpExchange.Responded -> {
            if (exchange.status != 200) return null
            val root = runCatching {
                Json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)).jsonObject
            }.getOrNull() ?: return null
            val token = (root["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: return null
            // expire_time 可选（毫秒）；默认按 3600 秒取整并留出刷新余量。
            val expiresAt = (root["expires_in"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?.let { System.currentTimeMillis() + it * 1000L }
                ?: (root["expire_time"] as? JsonPrimitive)?.takeUnless { it.isString }?.contentOrNull?.toLongOrNull()
                ?: (System.currentTimeMillis() + 3_600_000L)
            token to expiresAt
        }
    }
}

private fun huaweiStyleResult(status: Int, body: String, registrationId: String): OemPushDeliveryResult {
    if (status != 200) return pushRejected(oemPushHttpRejection(status))
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return pushRejected("INVALID_RESPONSE")
    // 华为/荣耀的 code 以字符串下发，个别网关返回数字。
    val code = (root["code"] as? JsonPrimitive)?.let { primitive ->
        primitive.contentOrNull ?: primitive.takeUnless { it.isString }?.intOrNull?.toString()
    } ?: return pushRejected("INVALID_RESPONSE")
    return when (code) {
        "80000000" -> OemPushDeliveryResult(true)
        "80300007" -> OemPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        "80100000" -> {
            // 单 token 下发时部分失败即本 token 非法；illegal_tokens 缺失按未知失败退避。
            val illegal = (root["illegal_tokens"] as? JsonPrimitive)?.contentOrNull
            if (illegal == null || illegal == registrationId || illegal.split(',').contains(registrationId)) {
                OemPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
            } else {
                pushRejected("PROVIDER_REJECTED")
            }
        }
        "80200003" -> OemPushDeliveryResult(false, "PROVIDER_AUTH_TOKEN_EXPIRED", refreshToken = true)
        "80100003", "80300008", "80300010", "10001", "10205", "10207" -> pushRejected("PROVIDER_CONFIGURATION_REJECTED")
        "80300006" -> pushRejected("PROVIDER_RATE_LIMITED")
        in "80300000".."80309999" -> pushRejected("PROVIDER_UNAVAILABLE")
        else -> pushRejected("PROVIDER_REJECTED")
    }
}
