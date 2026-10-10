package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.log.AppLog
import com.virjar.tk.desktop.tray.AppTray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 消息稍后提醒（MSG-01/F4）的桌面呈现：轮询本地提醒表，到点经系统托盘通知弹出，
 * 收据（notified）保证重启不重复弹出。提醒表是纯本地事实，不进协议；
 * 点击通知定位到原消息依赖托盘点击回调，为既有平台边界（同任务通知）。
 */
@Composable
internal fun DesktopMessageNotifications(
    localCache: LocalCache,
    windowActive: Boolean,
    connectionState: ConnectionState,
) {
    val presentation by rememberUpdatedState(windowActive to connectionState)
    LaunchedEffect(localCache) {
        while (true) {
            val (active, connection) = presentation
            if (!active && connection == ConnectionState.AUTHENTICATED && AppTray.isActive) {
                val due = withContext(Dispatchers.IO) { localCache.dueMessageReminders(System.currentTimeMillis()) }
                for (reminder in due) {
                    val (stillActive, stillConnected) = presentation
                    if (stillActive || stillConnected != ConnectionState.AUTHENTICATED || !AppTray.isActive) break
                    AppLog.trace("Notification", "posting message reminder chatId=${reminder.chatId}")
                    AppTray.showNotification("消息提醒", reminder.snippet)
                    withContext(Dispatchers.IO) {
                        localCache.markMessageReminderNotified(reminder.chatId, reminder.clientMsgId)
                    }
                }
            }
            delay(30_000L)
        }
    }
}
