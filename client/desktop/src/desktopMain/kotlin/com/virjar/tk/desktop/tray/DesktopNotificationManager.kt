package com.virjar.tk.desktop.tray

import com.virjar.tk.protocol.model.Conversation

/**
 * 桌面端新消息通知管理器。
 *
 * 监听会话未读数变化，当主窗口失焦且新消息到达时弹出系统通知。
 * 同一 chat 的多条消息合并为一条通知，避免消息风暴。
 *
 * 用法：在 Composable 中每次 conversations 更新时调用 [onConversationsChanged]。
 */
object DesktopNotificationManager {
    private val tracker = DesktopUnreadNotificationTracker()
    private var enabled = false

    /** 开始展示通知；真实用户动作由 [AppTray] 自己的 AWT action listener 处理。 */
    fun start() {
        enabled = true
    }

    fun stop() {
        enabled = false
        tracker.clear()
    }

    /**
     * 当 conversations 列表发生变化时调用。
     * @param conversations 当前会话列表
     * @param isWindowFocused 主窗口当前是否获得焦点（true 时抑制通知）
     */
    fun onConversationsChanged(conversations: List<Conversation>, isWindowFocused: Boolean) {
        // 即使托盘正在启动或不可用，也要让基线保持最新。否则稍后的第一条消息
        // 会把仅仅来自 LocalCache 的所有未读项重新通知一遍。
        val notifications = tracker.onConversationsChanged(conversations, isWindowFocused)
        if (!enabled || !AppTray.isActive) return
        notifications.forEach { notification ->
            AppTray.showNotification(notification.title, notification.body)
        }
    }

    /** 重置指定 chat 的未读计数（用户打开该会话时调用）。 */
    fun resetChat(chatId: String) {
        tracker.resetChat(chatId)
    }
}

internal data class DesktopUnreadNotification(
    val chatId: String,
    val title: String,
    val body: String,
)

/** 纯未读增量策略。它只返回展示内容，没有任何合成点击/用户动作路径。 */
internal class DesktopUnreadNotificationTracker {
    private val lastUnread = mutableMapOf<String, Int>()

    fun onConversationsChanged(
        conversations: List<Conversation>,
        isWindowFocused: Boolean,
    ): List<DesktopUnreadNotification> = buildList {
        // 该输入是完整的 LocalCache 投影。遗忘已墓碑化的会话，
        // 这样进程级 desktop tracker 不会在一次长时间会话中保留每个见过的会话。
        lastUnread.keys.retainAll(conversations.mapTo(mutableSetOf(), Conversation::chatId))
        for (conversation in conversations) {
            val previous = lastUnread[conversation.chatId] ?: 0
            val current = conversation.unreadCount
            if (!conversation.isMuted && current > previous && !isWindowFocused) {
                val increment = current - previous
                add(
                    DesktopUnreadNotification(
                        chatId = conversation.chatId,
                        title = conversation.title(),
                        body = if (increment == 1) "1 条新消息" else "$increment 条新消息",
                    ),
                )
            }
            lastUnread[conversation.chatId] = current
        }
    }

    fun resetChat(chatId: String) {
        lastUnread[chatId] = 0
    }

    fun clear() {
        lastUnread.clear()
    }
}

/** 会话标题：群名 > 对方用户名 > chatId 前 8 位 */
private fun Conversation.title(): String =
    chatName?.takeIf { it.isNotBlank() } ?: chatId.take(8)
