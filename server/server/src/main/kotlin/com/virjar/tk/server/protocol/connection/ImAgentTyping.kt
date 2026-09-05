package com.virjar.tk.server.protocol.connection

import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.protocol.model.Message

/** 一次有界权威 TYPING 准入的不可变结果。 */
internal data class TypingDelivery(
    val message: Message,
    val recipientUids: List<String>,
)

/**
 * 在一次权威聊天读取下构建可信的瞬时信封与接收者快照。
 * 调用方刻意只在此函数返回且
 * PostgreSQL 读事务关闭之后才执行网络投递。
 */
internal suspend fun authorizeTypingDelivery(
    access: ChatAccess,
    senderUid: String,
    message: Message,
    nowMillis: Long = System.currentTimeMillis(),
): TypingDelivery {
    val declared = com.virjar.tk.protocol.body.MessageBodyPolicy.canonicalize(message)
    return access.readMembersFor(senderUid, declared.chatId) { _, members ->
        TypingDelivery(
            message = declared.copy(
                senderUid = senderUid,
                serverSeq = 0,
                timestamp = nowMillis,
                flags = 0,
                sendStatus = Message.SEND_STATUS_SENT,
                uploadProgress = 0f,
            ),
            recipientUids = members.asSequence()
                .map { it.uid }
                .filter { it != senderUid }
                .distinct()
                .sorted()
                .toList(),
        )
    }
}
