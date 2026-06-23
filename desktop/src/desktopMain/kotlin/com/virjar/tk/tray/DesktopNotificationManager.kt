package com.virjar.tk.tray

import com.virjar.tk.model.Conversation

/**
 * 桌面端新消息通知管理器。
 *
 * 监听会话未读数变化，当主窗口失焦且新消息到达时弹出系统通知。
 * 同一 chat 的多条消息合并为一条通知，避免消息风暴。
 *
 * 用法：在 Composable 中每次 conversations 更新时调用 [onConversationsChanged]。
 */
object DesktopNotificationManager {

    /** 上一次各 chat 的 unreadCount 快照，用于检测增量。 */
    private val lastUnread = mutableMapOf<String, Int>()

    /** 通知点击回调 —— 传入 chatId，用于恢复窗口并跳转到对应会话。 */
    private var onNotificationClick: ((String) -> Unit)? = null

    private var enabled = false

    fun start(clickCallback: (chatId: String) -> Unit) {
        onNotificationClick = clickCallback
        enabled = true
    }

    fun stop() {
        enabled = false
        onNotificationClick = null
        lastUnread.clear()
    }

    /**
     * 当 conversations 列表发生变化时调用。
     * @param conversations 当前会话列表
     * @param isWindowFocused 主窗口当前是否获得焦点（true 时抑制通知）
     */
    fun onConversationsChanged(conversations: List<Conversation>, isWindowFocused: Boolean) {
        if (!enabled || !AppTray.isActive) return

        for (conv in conversations) {
            if (conv.isMuted) continue // 免打扰的会话不弹通知
            val prev = lastUnread[conv.chatId] ?: 0
            val curr = conv.unreadCount
            if (curr > prev && !isWindowFocused) {
                val increment = curr - prev
                val title = conv.title()
                val body = if (increment == 1) "1 条新消息" else "$increment 条新消息"
                AppTray.showNotification(title, body)
                // 通知点击时跳转到对应会话
                onNotificationClick?.invoke(conv.chatId)
            }
            lastUnread[conv.chatId] = curr
        }
    }

    /** 重置指定 chat 的未读计数（用户打开该会话时调用）。 */
    fun resetChat(chatId: String) {
        lastUnread[chatId] = 0
    }
}

/** 会话标题：群名 > 对方用户名 > chatId 前 8 位 */
private fun Conversation.title(): String =
    chatName?.takeIf { it.isNotBlank() } ?: chatId.take(8)
