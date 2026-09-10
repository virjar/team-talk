package com.virjar.tk.android

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 单个厂商通道的实现；每个厂商源集提供一个对象，部署配置决定哪些进入安装包。
 * 一台设备只会有自己的制造商对应的通道 available。
 */
internal interface OemPushChannel {
    /** 与服务端注册 RPC 一致的通道标识：xiaomi/huawei/honor/oppo/vivo/meizu。 */
    val vendor: String
    val displayName: String
    val available: Boolean
    val registrationId: StateFlow<String>
    val registrationFailureCode: StateFlow<Long?>
    val privacyUrl: String?
    fun initialize(context: Context)
    fun unregister(context: Context)
    fun clearNotifications(context: Context)
}

/**
 * 厂商通道没有各自的清除 API 时，取消所有不属于本地消息/任务通道的通知；
 * 返回前台时本地通道已自行清理，这里只会移除厂商投递的系统通知。
 */
internal fun clearForeignAndroidNotifications(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.activeNotifications
        .filter { it.notification.channelId != MESSAGE_CHANNEL && it.notification.channelId != TASK_CHANNEL }
        .forEach { manager.cancel(it.tag, it.id) }
}

/** 安装级用户选择；厂商 SDK 的注册身份与 TeamTalk 账号凭据分开持有。 */
internal class AndroidOemPushSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("oem_push", Context.MODE_PRIVATE)
    /** 设备制造商固定，启动时一次性解析；找不到对应厂商通道时整个功能不可用。 */
    val channel: OemPushChannel? = oemPushChannels.firstOrNull { it.available }
    val available = channel != null
    val enabled = MutableStateFlow(available && preferences.getBoolean("enabled", false))
    // A non-empty registration may already be active even when its RPC response was lost.
    // Older enabled installations conservatively keep the same ownership until a server response.
    val pendingUnregister = MutableStateFlow(available && preferences.getBoolean("pending_unregister", enabled.value))
    val needsConsent: Boolean get() = available && !preferences.contains("enabled")
    val status = MutableStateFlow(if (!available) "此设备无可用厂商通道" else
        if (!enabled.value && pendingUnregister.value) "正在关闭${channel!!.displayName}推送，等待联网确认" else "未开启")
    val notificationSettingsRevision = MutableStateFlow(0L)

    fun notificationSettingsChanged() { notificationSettingsRevision.value += 1 }

    fun setEnabled(value: Boolean) {
        val channel = channel ?: return
        val wasEnabled = enabled.value
        preferences.edit().putBoolean("enabled", value && available)
            .putBoolean("pending_unregister", pendingUnregister.value).apply()
        enabled.value = value && available
        if (enabled.value) restore() else {
            if (wasEnabled) channel.unregister(context)
            status.value = if (pendingUnregister.value) {
                "正在关闭${channel.displayName}推送，等待联网确认"
            } else "未开启"
        }
    }

    /** Persist before admitting a registration RPC; a process restart must retain an unknown binding. */
    fun serverRegistrationMayExist(value: Boolean): Boolean {
        if (pendingUnregister.value == value && preferences.contains("pending_unregister")) return true
        if (!preferences.edit().putBoolean("pending_unregister", value).commit()) {
            status.value = "推送状态保存失败，请关闭后重新开启"
            return false
        }
        pendingUnregister.value = value
        return true
    }

    fun restore() {
        val channel = channel ?: return
        if (enabled.value) {
            status.value = "正在连接${channel.displayName}推送"
            channel.initialize(context)
        }
    }

    fun clearVendorNotifications() {
        if (enabled.value) channel?.clearNotifications(context)
    }

    fun logout() {
        if (enabled.value) channel?.unregister(context)
    }
}

/** 只把设备厂商的注册身份绑定到当前已认证设备；不另开数据库或后台登录会话。 */
internal class AndroidOemPushRegistration(
    private val context: Context,
    session: ClientSession,
    settings: AndroidOemPushSettings,
    foreground: StateFlow<Boolean>,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Unknown/active server bindings retain the sole notification owner until removal is confirmed.
    val usesVendorNotifications: StateFlow<Boolean> = settings.pendingUnregister

    init {
        val channel = settings.channel
        if (channel != null) {
            settings.restore()
            scope.launch {
                val registrationState = combine(channel.registrationId, channel.registrationFailureCode) {
                    registration, failureCode -> registration to failureCode
                }
                combine(session.connectionState, registrationState, settings.enabled, foreground,
                    settings.notificationSettingsRevision) {
                    connection, registration, enabled, _, _ ->
                    Triple(connection, registration, enabled to
                        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                }.distinctUntilChanged().collect { (connection, registrationStateValue, selection) ->
                    val (registration, failureCode) = registrationStateValue
                    val (enabled, allowed) = selection
                    if (enabled && failureCode != null) {
                        settings.status.value = "${channel.displayName}推送注册失败（错误码 $failureCode），请关闭后重新开启"
                        // With no admitted server registration, the existing TCP observer remains usable.
                        // A previous/unknown binding cannot safely switch notification owners here.
                        return@collect
                    }
                    if (connection != ConnectionState.AUTHENTICATED) return@collect
                    if (enabled && registration.isBlank()) {
                        settings.status.value = "正在连接${channel.displayName}推送"
                        return@collect
                    }
                    val token = registration.takeIf { enabled && allowed }.orEmpty()
                    // RPC 响应丢失时服务端可能已经绑定成功，提前停止 TCP 本地提醒避免双响。
                    if (token.isNotEmpty() && !settings.serverRegistrationMayExist(true)) return@collect
                    while (true) {
                        // Let an admitted RPC finish before applying a newer selection. Cancelling a bind
                        // on a foreground/setting change could race its late server write with unbinding.
                        if (session.connectionState.value != ConnectionState.AUTHENTICATED ||
                            settings.enabled.value != enabled || channel.registrationId.value != registration ||
                            channel.registrationFailureCode.value != failureCode ||
                            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() != allowed
                        ) break
                        when (val result = session.deviceRepo.setOemPushRegistration(
                            channel.vendor, token, BuildConfig.APPLICATION_ID, session.deploymentIdentity.fingerprint,
                        )) {
                            is Outcome.Success -> {
                                if (!settings.serverRegistrationMayExist(token.isNotEmpty() && result.value)) break
                                settings.status.value = when {
                                    !enabled -> "未开启"
                                    !allowed -> "系统通知权限已关闭"
                                    result.value -> "已接入${channel.displayName}推送"
                                    else -> "服务器未启用${channel.displayName}推送"
                                }
                                break
                            }
                            is Outcome.Failure -> {
                                settings.status.value = if (!enabled && settings.pendingUnregister.value) {
                                    "正在关闭${channel.displayName}推送，等待联网确认"
                                } else "推送注册未完成，等待重试"
                                if (result.error is AppError.Business || result.error is AppError.AuthExpired) {
                                    Log.w("OemPush", "Server registration rejected")
                                    break
                                }
                                delay(30_000)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() { scope.cancel() }
}
