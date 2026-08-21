package com.virjar.tk.domain.chat

import com.virjar.tk.domain.transaction.PgTransactionContext

/** 其他领域要求受管群长期保留的服务身份，例如已授权通知机器人。 */
interface RequiredChatParticipants {
    fun forChat(chatId: String): Set<String>

    /** Revoke external-domain participation inside the owning chat aggregate transaction. */
    fun deactivateForChat(transaction: PgTransactionContext, chatId: String)
}
