package com.virjar.tk.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.xiaomi.mipush.sdk.MiPushClient
import com.xiaomi.mipush.sdk.MiPushCommandMessage
import com.xiaomi.mipush.sdk.PushMessageReceiver
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在已授权的小米设备主进程调用。通知展示交给小米系统服务。 */
internal object XiaomiPushChannel : OemPushChannel {
    override val vendor = "xiaomi"
    override val displayName = "小米"
    override val available = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    override val registrationId = MutableStateFlow("")
    override val registrationFailureCode = MutableStateFlow<Long?>(null)
    override val privacyUrl = "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1534"

    override fun initialize(context: Context) {
        if (!available) return
        registrationFailureCode.value = null
        registrationId.value = MiPushClient.getRegId(context).orEmpty()
        MiPushClient.registerPush(context, BuildConfig.XIAOMI_PUSH_APP_ID, BuildConfig.XIAOMI_PUSH_APP_KEY)
    }

    override fun unregister(context: Context) {
        if (!available) return
        MiPushClient.unregisterPush(context)
        registrationId.value = ""
        registrationFailureCode.value = null
        clearNotifications(context)
    }

    override fun clearNotifications(context: Context) {
        if (available) MiPushClient.clearNotification(context)
    }
}

class XiaomiPushReceiver : PushMessageReceiver() {
    override fun onReceiveRegisterResult(context: Context, message: MiPushCommandMessage) {
        if (message.command != MiPushClient.COMMAND_REGISTER) return
        if (message.resultCode == 0L) {
            XiaomiPushChannel.registrationFailureCode.value = null
            XiaomiPushChannel.registrationId.value = message.commandArguments?.firstOrNull().orEmpty()
        } else {
            XiaomiPushChannel.registrationFailureCode.value = message.resultCode
            // 注册错误只记录错误码，regId、App Key 与供应商原始响应不进入日志。
            Log.w("XiaomiPush", "Registration failed code=${message.resultCode}")
        }
    }
}
