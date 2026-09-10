package com.virjar.tk.android

import android.content.Context
import android.os.Build
import com.vivo.push.PushClient
import com.vivo.push.sdk.OpenClientPushMessageReceiver
import kotlinx.coroutines.flow.MutableStateFlow

/** 只在已授权的 vivo 设备主进程调用。 */
internal object VivoPushChannel : OemPushChannel {
    override val vendor = "vivo"
    override val displayName = "vivo"
    override val available = Build.MANUFACTURER.equals("vivo", ignoreCase = true)
    override val registrationId = MutableStateFlow("")
    override val registrationFailureCode = MutableStateFlow<Long?>(null)
    override val privacyUrl = null

    override fun initialize(context: Context) {
        if (!available) return
        registrationFailureCode.value = null
        val client = PushClient.getInstance(context) ?: run {
            registrationFailureCode.value = -1L
            return
        }
        client.initialize()
        client.turnOnPush { code, _ ->
            if (code == 0) {
                registrationFailureCode.value = null
                client.regId?.takeIf { it.isNotEmpty() }?.let { registrationId.value = it }
            } else {
                registrationFailureCode.value = code.toLong()
            }
        }
        // regId 可能早于 turnOnPush 回调就绪；开机缓存的值也在此立即发布。
        client.regId?.takeIf { it.isNotEmpty() }?.let { registrationId.value = it }
    }

    override fun unregister(context: Context) {
        if (!available) return
        try {
            PushClient.getInstance(context)?.turnOffPush(null)
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

class VivoPushReceiver : OpenClientPushMessageReceiver() {
    override fun onReceiveRegId(context: Context, regId: String) {
        if (regId.isEmpty()) return
        VivoPushChannel.registrationFailureCode.value = null
        VivoPushChannel.registrationId.value = regId
    }
}
