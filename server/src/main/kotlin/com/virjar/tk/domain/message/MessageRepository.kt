package com.virjar.tk.domain.message

import com.virjar.tk.model.Message

/** Authoritative message archive boundary. */
interface MessageRepository {
    fun storeMessage(
        message: Message,
        idempotencyCandidate: Message,
        projectionTarget: MessageProjectionTarget,
    ): Long
    fun getMessage(chatId: String, seq: Long): Message?
    /**
     * `chatId + clientMsgId` 是全会话唯一的消息身份。返回首次接受的消息；
     * 换发送者复用同键，或原发送者用同键提交不同首发内容，都必须拒绝。
     * 摘要必须独立保存，不能拿可能已编辑的当前正文代替首次请求内容。
     */
    fun findIdempotentMessage(candidate: Message): Message?
    fun getHistory(chatId: String, fromSeq: Long, limit: Int, forward: Boolean = false): List<Message>
    fun updateMessage(
        chatId: String,
        seq: Long,
        message: Message,
        operation: MessageOperationType,
        projectionTarget: MessageProjectionTarget,
    ): MessageProjectionOperation
    fun getPendingProjectionOperations(limit: Int = 100): List<MessageProjectionOperation>
    fun getPendingProjectionOperations(chatId: String, seq: Long, limit: Int = 100): List<MessageProjectionOperation>
    fun isProjectionPending(operation: MessageProjectionOperation): Boolean
    fun markProjectionComplete(operation: MessageProjectionOperation)

    fun getAttachmentChatIds(path: String): Set<String>
}
