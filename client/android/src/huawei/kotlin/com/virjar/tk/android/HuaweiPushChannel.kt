package com.virjar.tk.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.huawei.hms.common.ApiException
import com.huawei.hms.push.HmsInstanceId
import com.huawei.hms.push.HmsMessageService
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在已授权的华为设备主进程调用。通知展示交给 HMS Push 系统通道。 */
internal object HuaweiPushChannel : OemPushChannel {
    override val vendor = "huawei"
    override val displayName = "华为"
    override val available = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)
    override val registrationId = MutableStateFlow("")
    override val registrationFailureCode = MutableStateFlow<Long?>(null)
    override val privacyUrl = null

    override fun initialize(context: Context) {
        if (!available) return
        registrationFailureCode.value = null
        // getToken 是阻塞调用，HMS 会在内部缓存命中时同步返回；新 token 经 MESSAGING_EVENT 回调。
        Thread {
            try {
                val token = HmsInstanceId.getInstance(context).getToken(BuildConfig.HUAWEI_PUSH_APP_ID, "HCM")
                if (token.isNullOrEmpty()) registrationFailureCode.value = -1L
                else registrationId.value = token
            } catch (error: Exception) {
                registrationFailureCode.value = (error as? ApiException)?.statusCode?.toLong() ?: -1L
                Log.w("HuaweiPush", "Registration failed")
            }
        }.start()
    }

    override fun unregister(context: Context) {
        if (!available) return
        Thread {
            try {
                HmsInstanceId.getInstance(context).deleteToken(BuildConfig.HUAWEI_PUSH_APP_ID, "HCM")
            } catch (_: Exception) {
                // HMS 本地缓存已随下次 getToken 重建；失败不阻塞本地关闭流程。
            }
        }.start()
        registrationId.value = ""
        registrationFailureCode.value = null
        clearNotifications(context)
    }

    override fun clearNotifications(context: Context) {
        clearForeignAndroidNotifications(context)
    }
}

class HuaweiPushService : HmsMessageService() {
    override fun onNewToken(token: String) {
        HuaweiPushChannel.registrationFailureCode.value = null
        HuaweiPushChannel.registrationId.value = token
    }
}
