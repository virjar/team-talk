package com.virjar.tk.server.infra.push

/** Single optional OEM integration. Secrets never appear in diagnostics or data-class toString. */
internal class XiaomiPushConfiguration(
    val enabled: Boolean = false,
    val appSecret: String = "",
    val packageName: String = "",
    val channelId: String = "",
    val templateId: String = "",
    val title: String = "",
) {
    init {
        if (enabled) {
            require(appSecret.isNotBlank() && appSecret.length <= 512 && appSecret.none(Char::isISOControl)) {
                "XIAOMI_PUSH_APP_SECRET is invalid"
            }
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && packageName.length <= 255) {
                "XIAOMI_PUSH_PACKAGE_NAME is invalid"
            }
            require(channelId.isNotBlank() && channelId.length <= 128 && channelId.none(Char::isISOControl)) {
                "XIAOMI_PUSH_CHANNEL_ID is invalid"
            }
            require(templateId.isNotBlank() && templateId.length <= 128 && templateId.none(Char::isISOControl)) {
                "XIAOMI_PUSH_TEMPLATE_ID is invalid"
            }
            require(title.isNotBlank() && title.length < 50 && title.none(Char::isISOControl)) {
                "XIAOMI_PUSH_TITLE is invalid"
            }
        }
    }

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): XiaomiPushConfiguration {
            val enabled = when (environment("XIAOMI_PUSH_ENABLED")) {
                null, "false" -> false
                "true" -> true
                else -> error("XIAOMI_PUSH_ENABLED must be true or false")
            }
            return XiaomiPushConfiguration(
                enabled = enabled,
                appSecret = environment("XIAOMI_PUSH_APP_SECRET").orEmpty(),
                packageName = environment("XIAOMI_PUSH_PACKAGE_NAME").orEmpty(),
                channelId = environment("XIAOMI_PUSH_CHANNEL_ID").orEmpty(),
                templateId = environment("XIAOMI_PUSH_TEMPLATE_ID").orEmpty(),
                title = environment("XIAOMI_PUSH_TITLE").orEmpty(),
            )
        }
    }
}

internal class XiaomiPushNotification(
    val registrationId: String,
    val deploymentFingerprint: String,
    val datasetId: String,
    val uid: String,
    val chatId: String,
    val jobKey: String,
)

internal data class XiaomiPushDeliveryResult(
    val accepted: Boolean,
    val reason: String? = null,
    val invalidRegistration: Boolean = false,
)
