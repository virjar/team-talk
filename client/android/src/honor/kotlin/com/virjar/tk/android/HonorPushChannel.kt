package com.virjar.tk.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.hihonor.push.sdk.HonorPushCallback
import com.hihonor.push.sdk.HonorPushClient
import com.hihonor.push.sdk.mcs.HonorMessageService
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在已授权的荣耀设备主进程调用；接入形态与 HMS Push 一致。 */
internal object HonorPushChannel : OemPushChannel {
    override val vendor = "honor"
    override val displayName = "荣耀"
    override val available = Build.MANUFACTURER.equals("HONOR", ignoreCase = true)
    override val registrationId = MutableStateFlow("")
    override val registrationFailureCode = MutableStateFlow<Long?>(null)
    override val privacyUrl = null

    override fun initialize(context: Context) {
        if (!available) return
        registrationFailureCode.value = null
        if (!HonorPushClient.getInstance().checkSupportHonorPush(context)) {
            registrationFailureCode.value = -1L
            return
        }
        HonorPushClient.getInstance().getPushToken(object : HonorPushCallback<String> {
            override fun onSuccess(token: String) {
                registrationFailureCode.value = null
                registrationId.value = token
            }

            override fun onFailure(code: Int, error: String) {
                registrationFailureCode.value = code.toLong()
                Log.w("HonorPush", "Registration failed code=$code")
            }
        })
    }

    override fun unregister(context: Context) {
        if (!available) return
        try {
            HonorPushClient.getInstance().deletePushToken(object : HonorPushCallback<String> {
                override fun onSuccess(token: String) = Unit
                override fun onFailure(code: Int, error: String) = Unit
            })
        } catch (_: Exception) {
            // 荣耀本地缓存随下次 getPushToken 重建；失败不阻塞本地关闭流程。
        }
        registrationId.value = ""
        registrationFailureCode.value = null
        clearNotifications(context)
    }

    override fun clearNotifications(context: Context) {
        clearForeignAndroidNotifications(context)
    }
}

class HonorPushService : HonorMessageService() {
    override fun onNewToken(token: String) {
        HonorPushChannel.registrationFailureCode.value = null
        HonorPushChannel.registrationId.value = token
    }
}
