package com.virjar.tk.server.domain.bot

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/** 通知机器人使用的消息接收边界。 */
fun interface BotMessageSender {
    /**
     * [authorizeAfterChatLock] 在消息准入事务中、托管权威与 Chat 已锁定之后、但成员行之前
     * 运行。生产适配器必须对每条新消息的准入恰好调用它一次。
     */
    suspend fun send(
        senderUid: String,
        message: Message,
        authorizeAfterChatLock: (PgWriteTransactionContext) -> Unit,
    ): Long
}

/** 生产实现：复用 MessageService 完整的准入、幂等与事件链。 */
class MessageServiceBotSender(
    private val messages: MessageService,
) : BotMessageSender {
    override suspend fun send(
        senderUid: String,
        message: Message,
        authorizeAfterChatLock: (PgWriteTransactionContext) -> Unit,
    ): Long = messages.sendMessage(senderUid, message, authorizeAfterChatLock)
}
