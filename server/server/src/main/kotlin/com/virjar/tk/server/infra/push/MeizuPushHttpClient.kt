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
 * 魅族服务端：每次请求按参数字典序拼接 appSecret 取 MD5 签名；
 * pushByPushId 通知栏消息的 value 仅返回推送非法的 pushId。
 */
internal object MeizuPush {
    val sendEndpoint: URI = URI("https://server-api-push.meizu.com/garcia/api/server/push/varnished/pushByPushId")
}

internal suspend fun sendMeizuPush(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
): OemPushDeliveryResult = sendMeizuPushRequest(configuration, notification, MeizuPush.sendEndpoint)

internal suspend fun sendMeizuPushRequest(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
    endpoint: URI,
): OemPushDeliveryResult {
    val messageJson = buildString {
        append("{\"noticeBarInfo\":{\"noticeMsgType\":1,")
        append("\"title\":${JsonPrimitive(configuration.title)},\"content\":${JsonPrimitive(OEM_PUSH_CONTENT)}},")
        // clickType=3 在 Android 12 之后不再可靠，固定用 0（打开应用）由本地通知导航定位会话。
        append("\"clickTypeInfo\":{\"clickType\":0},")
        append("\"pushTimeInfo\":{\"offLine\":1,\"validTime\":1},")
        append("\"advanceInfo\":{\"notifyKey\":${JsonPrimitive(notification.jobKey.take(8))}}}")
    }
    val fields = sortedMapOf(
        "appId" to configuration.appId,
        "pushIds" to notification.registrationId,
        "messageJson" to messageJson,
    )
    // 官方规范：非 urlencode 的原始串按 key=value 顺序拼接后追加 appSecret，取 32 位小写 MD5。
    val sign = md5Hex(fields.entries.joinToString("") { "${it.key}=${it.value}" } + configuration.appSecret)
    val request = HttpRequest.newBuilder(endpoint)
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(
            fields.entries.joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" } + "&sign=$sign",
        ))
        .build()
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> pushRejected(exchange.reason)
        is OemPushHttpExchange.Responded -> meizuResult(
            exchange.status, exchange.body.toString(Charsets.UTF_8), notification.registrationId,
        )
    }
}

private fun meizuResult(status: Int, body: String, pushId: String): OemPushDeliveryResult {
    if (status != 200) return pushRejected(oemPushHttpRejection(status))
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return pushRejected("INVALID_RESPONSE")
    val code = (root["code"] as? JsonPrimitive)?.let { primitive ->
        primitive.contentOrNull ?: primitive.takeUnless { it.isString }?.intOrNull?.toString()
    } ?: return pushRejected("INVALID_RESPONSE")
    if (code != "200") return when (code) {
        "1003", "1001", "500" -> pushRejected("PROVIDER_UNAVAILABLE")
        "110010" -> pushRejected("PROVIDER_RATE_LIMITED")
        "110019", "110051" -> pushRejected("PROVIDER_QUOTA_EXCEEDED")
        "1006", "110000", "110001", "110031", "110033", "1005", "110004" -> pushRejected("PROVIDER_CONFIGURATION_REJECTED")
        else -> pushRejected("PROVIDER_REJECTED")
    }
    // value 只携带非法 pushId 的 code 映射；空表示全部接受。
    val failed = (root["value"] as? JsonObject)?.get(pushId)?.let { it as? JsonPrimitive }?.contentOrNull
    return when (failed) {
        null -> OemPushDeliveryResult(true)
        "110002", "110003", "110010" -> OemPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
        "520" -> OemPushDeliveryResult(true)
        "110011" -> pushRejected("PROVIDER_QUOTA_EXCEEDED")
        "519", "513", "501", "201" -> pushRejected("PROVIDER_UNAVAILABLE")
        else -> pushRejected("PROVIDER_REJECTED")
    }
}
