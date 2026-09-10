package com.virjar.tk.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.heytap.msp.push.HeytapPushManager
import com.heytap.msp.push.callback.ICallBackResultListener
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在已授权的 OPPO/一加（ColorOS）设备主进程调用。 */
internal object OppoPushChannel : OemPushChannel {
    override val vendor = "oppo"
    override val displayName = "OPPO"
    override val available = Build.MANUFACTURER.equals("OPPO", ignoreCase = true) ||
        Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
    override val registrationId = MutableStateFlow("")
    override val registrationFailureCode = MutableStateFlow<Long?>(null)
    override val privacyUrl = null

    override fun initialize(context: Context) {
        if (!available) return
        registrationFailureCode.value = null
        HeytapPushManager.init(context, false)
        if (!HeytapPushManager.isSupportPush(context)) {
            registrationFailureCode.value = -1L
            return
        }
        // OPPO 官方注册接口要求 appKey 与 appSecret 都进入客户端。
        HeytapPushManager.register(context, BuildConfig.OPPO_PUSH_APP_KEY, BuildConfig.OPPO_PUSH_APP_SECRET,
            object : ICallBackResultListener {
                override fun onRegister(responseCode: Int, registerID: String) {
                    if (responseCode == 0 && registerID.isNotEmpty()) {
                        registrationFailureCode.value = null
                        registrationId.value = registerID
                    } else {
                        registrationFailureCode.value = responseCode.toLong()
                        Log.w("OppoPush", "Registration failed code=$responseCode")
                    }
                }

                override fun onUnRegister(responseCode: Int) {
                    if (responseCode == 0) registrationId.value = ""
                }

                override fun onGetPushStatus(responseCode: Int, status: Int) = Unit
                override fun onGetNotificationStatus(responseCode: Int, status: Int) = Unit
                override fun onSetPushTime(responseCode: Int, seconds: Int) = Unit
                override fun onError(errorCode: Int, message: String?) {
                    registrationFailureCode.value = errorCode.toLong()
                    Log.w("OppoPush", "Registration failed code=$errorCode")
                }
            })
    }

    override fun unregister(context: Context) {
        if (!available) return
        try {
            HeytapPushManager.unRegister()
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
