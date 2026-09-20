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
     * `pendingServiceReply` 非空时，同一持久化批次还写下一份待回复的服务号指令记录：
     * 原消息与“欠一条回复”要么同时可见，要么都不存在。
     */
    fun appendMessage(
        message: Message,
        idempotencyCandidate: Message,
        projectionTarget: MessageProjectionTarget,
        pendingServiceReply: PendingServiceReply? = null,
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

    /** 精确取回一条仍未结算的服务号回复记录；回复已完成或被终态放弃后不存在。 */
    fun findPendingServiceReply(chatId: String, clientMsgId: String): PendingServiceReply? = null

    /** 按扫描顺序列出仍未结算的服务号回复，供启动恢复排空。 */
    fun pendingServiceReplies(limit: Int = 100): List<PendingServiceReply> = emptyList()

    /** 结算一条服务号回复记录（成功送达或终态放弃），删除“欠回复”事实。 */
    fun markServiceReplySettled(chatId: String, clientMsgId: String) {}

    /**
     * 独立写入一条待回复记录（服务号官方触达：管理员广播等没有原消息的场景）。
     * 键已存在时保持首次冻结内容，不覆盖；随后经指令运行时派发与恢复。
     */
    fun appendPendingServiceReply(pending: PendingServiceReply) {}

    /** `chatId + clientMsgId` 的已提交消息查询；不存在返回 null。 */
    fun findCommittedMessage(chatId: String, clientMsgId: String): Message? = null

    fun getAttachmentChatIds(path: String): Set<String>

    fun isAttachmentReferencedByAny(path: String, chatIds: Set<String>): Boolean =
        chatIds.isNotEmpty() && getAttachmentChatIds(path).any(chatIds::contains)

    fun getReferencedAttachmentPaths(paths: Set<String>): Set<String> =
        paths.filterTo(linkedSetOf()) { path -> getAttachmentChatIds(path).isNotEmpty() }
}
