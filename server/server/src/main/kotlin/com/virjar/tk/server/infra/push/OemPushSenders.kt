package com.virjar.tk.server.infra.push

/** 按注册的厂商通道分派到对应官方 REST 发送器；新增厂商在此登记。 */
internal suspend fun sendOemPush(
    vendor: String,
    configuration: OemPushVendorConfiguration,
    notification: OemPushNotification,
): OemPushDeliveryResult = when (vendor) {
    OemPushVendors.XIAOMI -> sendXiaomiPush(configuration, notification)
    OemPushVendors.HUAWEI -> sendHuaweiStylePush(configuration, notification)
    OemPushVendors.HONOR -> sendHuaweiStylePush(configuration, notification)
    OemPushVendors.OPPO -> sendOppoPush(configuration, notification)
    OemPushVendors.VIVO -> sendVivoPush(configuration, notification)
    OemPushVendors.MEIZU -> sendMeizuPush(configuration, notification)
    else -> pushRejected("UNKNOWN_VENDOR")
}
