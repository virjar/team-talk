package com.virjar.tk.android

import android.content.Context
import android.os.Build
import com.meizu.cloud.pushsdk.MzPushMessageReceiver
import com.meizu.cloud.pushsdk.PushManager
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在已授权的魅族设备主进程调用。 */
internal object MeizuPushChannel : OemPushChannel {
    override val vendor = "meizu"
    override val displayName = "魅族"
    override val available = Build.MANUFACTURER.equals("Meizu", ignoreCase = true)
    override val registrationId = MutableStateFlow("")
    override val registrationFailureCode = MutableStateFlow<Long?>(null)
    override val privacyUrl = null

    override fun initialize(context: Context) {
        if (!available) return
        registrationFailureCode.value = null
        // pushId 经 REGISTER.FEEDBACK 广播回调；注册调用本身不返回结果。
        PushManager.register(context, BuildConfig.MEIZU_PUSH_APP_ID, BuildConfig.MEIZU_PUSH_APP_KEY)
    }

    override fun unregister(context: Context) {
        if (!available) return
        try {
            PushManager.unRegister(context)
        } catch (_: Exception) {
            // 注册身份由服务端注销兜底；失败不阻塞本地关闭流程。
        }
        registrationId.value = ""
        registrationFailureCode.value = null
        clearNotifications(context)
    }

    override fun clearNotifications(context: Context) {
        clearForeignAndroidNotifications(context)
    }
}

class MeizuPushReceiver : MzPushMessageReceiver() {
    override fun onRegister(context: Context, pushId: String) {
        if (pushId.isEmpty()) return
        MeizuPushChannel.registrationFailureCode.value = null
        MeizuPushChannel.registrationId.value = pushId
    }

    override fun onUnRegister(pushId: String?) {
        MeizuPushChannel.registrationId.value = ""
    }
}
