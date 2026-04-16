package com.virjar.tk.tray

import com.virjar.tk.util.AppLog
import java.awt.Toolkit

class DesktopNotificationManager {

    private data class NotificationEntry(
        val channelId: String,
        val channelType: Int,
        var count: Int,
        var latestText: String,
    )

    private val activeNotifications = mutableMapOf<String, NotificationEntry>()

    /**
     * Display a system notification for a new message.
     *
     * @param channelId      Channel ID
     * @param channelType    Channel type
     * @param channelName    Channel name (used as notification title)
     * @param contentPreview Message summary (used as notification body)
     */
    fun onNewMessage(
        channelId: String,
        channelType: Int,
        channelName: String,
        contentPreview: String,
    ) {
        val entry = activeNotifications[channelId]
        if (entry != null) {
            entry.count++
            entry.latestText = contentPreview
            showNotification(channelName, "${entry.count} 条新消息: $contentPreview")
        } else {
            activeNotifications[channelId] = NotificationEntry(channelId, channelType, 1, contentPreview)
            showNotification(channelName, contentPreview)
        }
    }

    fun clearChannel(channelId: String) {
        activeNotifications.remove(channelId)
    }

    private fun showNotification(title: String, body: String) {
        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("linux") -> showLinuxNotification(title, body)
            else -> showFallbackNotification()
        }
    }

    private fun showLinuxNotification(title: String, body: String) {
        try {
            ProcessBuilder("notify-send", "-a", "TeamTalk", title, body)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            AppLog.w("Notification", "notify-send failed", e)
            showFallbackNotification()
        }
    }

    private fun showFallbackNotification() {
        try {
            Toolkit.getDefaultToolkit().beep()
        } catch (_: Exception) {
        }
    }
}
