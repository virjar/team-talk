package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/** 其他领域要求受管群长期保留的服务身份，例如已授权通知机器人。 */
interface RequiredChatParticipants {
    /** 在所属聊天聚合事务内撤回外部领域的参与。 */
    fun deactivateForChat(transaction: PgWriteTransactionContext, chatId: String)
}
