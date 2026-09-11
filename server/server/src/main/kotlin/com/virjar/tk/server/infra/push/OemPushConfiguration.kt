package com.virjar.tk.server.infra.push

/** 厂商通道标识与部署侧公共约束；客户端按设备制造商选择其中之一注册。 */
internal object OemPushVendors {
    const val XIAOMI = "xiaomi"
    const val HUAWEI = "huawei"
    const val HONOR = "honor"
    const val OPPO = "oppo"
    const val VIVO = "vivo"
    const val MEIZU = "meizu"
    val ALL = setOf(XIAOMI, HUAWEI, HONOR, OPPO, VIVO, MEIZU)

}

/** 每个厂商单独开通；密钥不进入诊断输出或 data-class toString。 */
internal class OemPushVendorConfiguration(
    val vendor: String,
    val appSecret: String,
    val packageName: String,
    val title: String,
    val appId: String = "",
    val appKey: String = "",
    val channelId: String = "",
    val templateId: String = "",
    val category: String = "",
) {
    init {
        require(vendor in OemPushVendors.ALL) { "Unknown OEM push vendor" }
        require(appSecret.isNotBlank() && appSecret.length <= 512 && appSecret.none(Char::isISOControl)) {
            "${envPrefix(vendor)}_APP_SECRET is invalid"
        }
        require(
            packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && packageName.length <= 255
        ) { "${envPrefix(vendor)}_PACKAGE_NAME is invalid" }
        when (vendor) {
            OemPushVendors.XIAOMI -> {
                requirePrintable(channelId, 128, "${envPrefix(vendor)}_CHANNEL_ID")
                requirePrintable(templateId, 128, "${envPrefix(vendor)}_TEMPLATE_ID")
                requireTitle(50)
            }
            OemPushVendors.HUAWEI, OemPushVendors.HONOR -> {
                requireNumeric(appId, "${envPrefix(vendor)}_APP_ID")
                requirePrintable(channelId, 128, "${envPrefix(vendor)}_CHANNEL_ID")
                requireTitle(50)
            }
            OemPushVendors.OPPO -> {
                requirePrintable(appKey, 128, "${envPrefix(vendor)}_APP_KEY")
                requirePrintable(channelId, 128, "${envPrefix(vendor)}_CHANNEL_ID")
                requireTitle(50)
            }
            OemPushVendors.VIVO -> {
                // vivo 鉴权与发送接口要求 appId 以 JSON 整型下发。
                require(appId.toIntOrNull() != null) { "${envPrefix(vendor)}_APP_ID is invalid" }
                requirePrintable(appKey, 64, "${envPrefix(vendor)}_APP_KEY")
                requirePrintable(category, 64, "${envPrefix(vendor)}_CATEGORY")
                // vivo 通知标题上限为 20 个汉字。
                require(title.isNotBlank() && title.length <= 20 && title.none(Char::isISOControl)) {
                    "${envPrefix(vendor)}_TITLE is invalid"
                }
            }
            OemPushVendors.MEIZU -> {
                requireNumeric(appId, "${envPrefix(vendor)}_APP_ID")
                requirePrintable(appKey, 64, "${envPrefix(vendor)}_APP_KEY")
                requireTitle(50)
            }
        }
    }

    private fun requirePrintable(value: String, maxLength: Int, name: String) {
        require(value.isNotEmpty() && value.length <= maxLength && value.all { it.code in 33..126 }) { "$name is invalid" }
    }

    private fun requireNumeric(value: String, name: String) {
        require(value.matches(Regex("[0-9]{1,32}"))) { "$name is invalid" }
    }

    private fun requireTitle(maxLength: Int) {
        require(title.isNotBlank() && title.length < maxLength && title.none(Char::isISOControl)) {
            "${envPrefix(vendor)}_TITLE is invalid"
        }
    }

    companion object {
        fun envPrefix(vendor: String): String = "${vendor.uppercase()}_PUSH"
    }
}

/** 未配置的厂商不出现在映射中；每个安装只会注册自己制造商对应的通道。 */
internal class OemPushConfiguration(val vendors: Map<String, OemPushVendorConfiguration> = emptyMap()) {
    operator fun get(vendor: String): OemPushVendorConfiguration? = vendors[vendor]

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): OemPushConfiguration {
            val vendors = linkedMapOf<String, OemPushVendorConfiguration>()
            for (vendor in OemPushVendors.ALL) {
                val prefix = OemPushVendorConfiguration.envPrefix(vendor)
                when (environment("${prefix}_ENABLED")) {
                    null, "false" -> {}
                    "true" -> vendors[vendor] = OemPushVendorConfiguration(
                        vendor = vendor,
                        appSecret = environment("${prefix}_APP_SECRET").orEmpty(),
                        packageName = environment("${prefix}_PACKAGE_NAME").orEmpty(),
                        title = environment("${prefix}_TITLE").orEmpty(),
                        appId = environment("${prefix}_APP_ID").orEmpty(),
                        appKey = environment("${prefix}_APP_KEY").orEmpty(),
                        channelId = environment("${prefix}_CHANNEL_ID").orEmpty(),
                        templateId = environment("${prefix}_TEMPLATE_ID").orEmpty(),
                        category = environment("${prefix}_CATEGORY").orEmpty(),
                    )
                    else -> error("${prefix}_ENABLED must be true or false")
                }
            }
            return OemPushConfiguration(vendors)
        }
    }
}

internal class OemPushNotification(
    val vendor: String,
    val registrationId: String,
    val deploymentFingerprint: String,
    val datasetId: String,
    val uid: String,
    val chatId: String,
    val jobKey: String,
)

internal data class OemPushDeliveryResult(
    val accepted: Boolean,
    val reason: String? = null,
    val invalidRegistration: Boolean = false,
    /** 提示发送方丢弃缓存的厂商级令牌（OAuth/authToken），下次重取。 */
    val refreshToken: Boolean = false,
)
