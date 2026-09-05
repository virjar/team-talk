package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.model.Message

/** 解码进一个投影恢复页的已编码 Rocks 值的默认上限。 */
const val DEFAULT_PENDING_PROJECTION_PAGE_BYTES: Long = 32L * 1024 * 1024

/** 权威消息归档边界。 */
interface MessageRepository {
    /**
     * 追加一条新的权威消息。归档拥有 [Message.serverSeq]：调用方传零，实现在与消息、
     * 幂等身份和投影可靠发件箱相同的持久化批次中分配下一个聊天本地序号。
     *
     * 一次精确的 `chatId + clientMsgId` 重放返回原始存储的消息，而不消耗另一个序号。
     */
    fun appendMessage(
        message: Message,
        idempotencyCandidate: Message,
        projectionTarget: MessageProjectionTarget,
    ): Message
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
    fun getPendingProjectionOperations(
        limit: Int = 100,
        maxEncodedBytes: Long = DEFAULT_PENDING_PROJECTION_PAGE_BYTES,
    ): List<MessageProjectionOperation>
    fun getPendingProjectionOperations(
        chatId: String,
        seq: Long,
        limit: Int = 100,
        maxEncodedBytes: Long = DEFAULT_PENDING_PROJECTION_PAGE_BYTES,
    ): List<MessageProjectionOperation>
    fun isProjectionPending(operation: MessageProjectionOperation): Boolean
    fun markProjectionComplete(operation: MessageProjectionOperation)

    fun getAttachmentChatIds(path: String): Set<String>

    fun isAttachmentReferencedByAny(path: String, chatIds: Set<String>): Boolean =
        chatIds.isNotEmpty() && getAttachmentChatIds(path).any(chatIds::contains)

    fun getReferencedAttachmentPaths(paths: Set<String>): Set<String> =
        paths.filterTo(linkedSetOf()) { path -> getAttachmentChatIds(path).isNotEmpty() }
}
