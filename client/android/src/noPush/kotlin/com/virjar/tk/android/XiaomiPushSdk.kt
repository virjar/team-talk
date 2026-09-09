package com.virjar.tk.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/** 未启用厂商推送的安装不包含外部 SDK。 */
internal object XiaomiPushSdk {
    val available = false
    val registrationId = MutableStateFlow("")
    val registrationFailureCode = MutableStateFlow<Long?>(null)
    fun initialize(context: Context) = Unit
    fun unregister(context: Context) = Unit
    fun clearNotifications(context: Context) = Unit
}

class XiaomiPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
