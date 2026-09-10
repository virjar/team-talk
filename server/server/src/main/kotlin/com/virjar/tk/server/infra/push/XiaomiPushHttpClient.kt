package com.virjar.tk.server.infra.push

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

private val xiaomiPushEndpoint = URI("https://api.xmpush.xiaomi.com/v3/message/regid")

internal suspend fun sendXiaomiPush(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
): OemPushDeliveryResult = sendXiaomiPushRequest(configuration, notification, xiaomiPushEndpoint)

/** The endpoint is fixed in production; the argument permits a real local HTTP fixture. */
internal suspend fun sendXiaomiPushRequest(
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
    endpoint: URI,
): OemPushDeliveryResult {
    val fields = linkedMapOf(
        "registration_id" to notification.registrationId,
        "restricted_package_name" to configuration.packageName,
        "title" to configuration.title,
        "description" to OEM_PUSH_CONTENT,
        "payload" to "unread",
        "time_to_live" to "3600000",
        "notify_id" to "0",
        "extra.channel_id" to configuration.channelId,
        "extra.template_id" to configuration.templateId,
        "extra.template_param" to "{}",
        "extra.notify_foreground" to "0",
        "extra.notify_effect" to "2",
        "extra.intent_uri" to oemPushIntentUri(configuration.packageName, notification),
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
    return when (val exchange = oemPushExchange(request)) {
        is OemPushHttpExchange.Failed -> pushRejected(exchange.reason)
        is OemPushHttpExchange.Responded -> {
            if (exchange.status != 200) return pushRejected(oemPushHttpRejection(exchange.status))
            val result = try {
                Json.parseToJsonElement(exchange.body.toString(Charsets.UTF_8)) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return pushRejected("INVALID_RESPONSE")
            val code = (result["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return pushRejected("INVALID_RESPONSE")
            if (code == 0) {
                return if ((result["result"] as? JsonPrimitive)?.content == "ok") OemPushDeliveryResult(true)
                else pushRejected("INVALID_RESPONSE")
            }
            when (code) {
                66007 -> OemPushDeliveryResult(false, "INVALID_REGISTRATION", invalidRegistration = true)
                1, 10001, 10002, 10003, 20607, 26003, 65029 -> pushRejected("PROVIDER_UNAVAILABLE")
                10027, 200002 -> pushRejected("PROVIDER_RATE_LIMITED")
                10034, 200001 -> pushRejected("PROVIDER_QUOTA_EXCEEDED")
                21301, 21302, 22000, 22006, 22007, 22022 -> pushRejected("PROVIDER_CONFIGURATION_REJECTED")
                10041, 27001 -> pushRejected("PROVIDER_TEMPLATE_REJECTED")
                else -> pushRejected("PROVIDER_REJECTED")
            }
        }
    }
}
