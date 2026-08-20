package com.virjar.tk.domain.chat

/** 其他领域要求受管群长期保留的服务身份，例如已授权通知机器人。 */
fun interface RequiredChatParticipants {
    fun forChat(chatId: String): Set<String>

    /** Revoke external-domain participation before a chat is deactivated or later re-created. */
    fun onChatDeactivated(chatId: String) = Unit
}
