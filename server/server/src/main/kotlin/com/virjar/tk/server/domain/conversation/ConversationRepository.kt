package com.virjar.tk.server.domain.conversation

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Conversation

/** 一个有界的持久化页，加上下一次查询的排他键。 */
data class ConversationPageSlice(
    val items: List<Conversation>,
    /** 本页最后一个 chatId，供下一页排他读取；null 表示已到末页。 */
    val nextChatId: String?,
)

/** 持久化的已读快照，加上必须观察到其回执的活跃接收者。 */
data class ConversationReadMutation(
    val conversation: Conversation,
    /** 仅当本命令推进了操作者的持久化 readSeq 时为真。 */
    val actorChanged: Boolean,
    /** 仅包含持久化投影确实推进的对端；缺失/已删除的行被排除。 */
    val advancedPeerUids: List<String>,
)

/** 会话领域拥有的持久化端口。 */
interface ConversationRepository {
    /**
     * 只能返回由活跃、可读聊天与活跃成员关系支撑的行。实现必须在稳定的键集（keyset）
     * 顺序与 limit+1 之前应用这些谓词。按 chatId 降序读取，排除 [afterChatId] 本身。
     */
    fun listConversationPage(
        uid: String,
        afterChatId: String?,
        pageSize: Int,
    ): ConversationPageSlice
    fun getConversation(uid: String, chatId: String): Conversation?

    /**
     * 用户拥有的变更必须加入 [PgUnitOfWork][com.virjar.tk.server.domain.transaction.PgUnitOfWork]。
     * 从同一数据库事务返回快照，防止事件描述稍后或部分提交的行。
     */
    fun setPin(transaction: PgWriteTransactionContext, uid: String, chatId: String, pinned: Boolean): Conversation
    fun setMute(transaction: PgWriteTransactionContext, uid: String, chatId: String, muted: Boolean): Conversation
    fun setDraft(transaction: PgWriteTransactionContext, uid: String, chatId: String, draft: String?): Conversation
    fun markRead(
        transaction: PgWriteTransactionContext,
        uid: String,
        chatId: String,
        readSeq: Long,
    ): ConversationReadMutation

    /** 隐藏活跃成员的常驻行；成员关系拥有的容量槽保持保留。 */
    fun deleteConversation(transaction: PgWriteTransactionContext, uid: String, chatId: String): Boolean

}
