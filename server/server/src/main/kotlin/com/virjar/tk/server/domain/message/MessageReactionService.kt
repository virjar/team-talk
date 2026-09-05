package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.protocol.NotifyType

/**
 * 表情回应（CLIENT-05）的服务端权威入口。
 *
 * 增删是 row-keyed 幂等命令：重复 add/remove 第二次不再产生状态或事件。聚合计数永远由
 * 本表派生，客户端只投影行级 delta 与快照。回应必须来自当前聊天成员；目标消息必须存在
 * 且未撤回。撤回消息时由 [MessageService] 的投影漏斗在同一个 PostgreSQL 事务里删除该消息
 * 的全部回应，事件与投影原子一致。成员离群后其历史回应保留为事实，但不再接受新回应。
 */
class MessageReactionService(
    private val messages: MessageRepository,
    private val chatStore: ChatStore,
    private val access: ChatAccess,
    private val reactions: MessageReactionRepository,
    private val unitOfWork: PgUnitOfWork,
    private val lifecycleGate: ChatLifecycleGate,
    private val managedChats: ManagedChatPolicy = UnmanagedChatPolicy,
) {

    suspend fun addReaction(uid: String, chatId: String, serverSeq: Long, emoji: String) =
        mutateReaction(uid, chatId, serverSeq, emoji, add = true)

    suspend fun removeReaction(uid: String, chatId: String, serverSeq: Long, emoji: String) =
        mutateReaction(uid, chatId, serverSeq, emoji, add = false)

    private suspend fun mutateReaction(
        uid: String,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        add: Boolean,
    ) {
        MessageBodyPolicy.requireReactionEmoji(emoji, "Reaction 表情")
        require(serverSeq > 0L) { "回应目标 serverSeq 非法" }
        // lifecycle gate 同时覆盖 revoke：目标消息的撤回标志在这个边界内读取是稳定的。
        lifecycleGate.withChat(chatId) {
            val message = messages.getMessage(chatId, serverSeq)
                ?: throw IllegalArgumentException("消息不存在")
            require(message.flags and Message.FLAG_REVOKED == 0) { "已撤回消息不能回应" }

            unitOfWork.write {
                val authority = managedChats.lockAuthority(transaction, listOf(chatId)).getValue(chatId)
                require(authority.ready) { "受管群投影尚未收敛" }
                chatStore.lockChats(transaction, listOf(chatId), requireActive = true)
                chatStore.getActiveMember(transaction, chatId, uid)
                    ?: throw IllegalArgumentException("不是聊天成员")

                val changed = if (add) {
                    upsertUnderUserCapLocked(transaction, chatId, serverSeq, emoji, uid)
                } else {
                    reactions.delete(transaction, chatId, serverSeq, emoji, uid)
                }
                if (changed) {
                    val payload = MessageReactionEventPayload(
                        chatId = chatId,
                        serverSeq = serverSeq,
                        emoji = emoji,
                        actorUid = uid,
                        action = if (add) ACTION_ADD else ACTION_REMOVE,
                    )
                    chatStore.getActiveMemberUids(transaction, chatId).forEach { memberUid ->
                        appendEvent(memberUid, NotifyType.MESSAGE_REACTION, payload)
                    }
                }
            }
        }
    }

    /** 调用方持有 Chat 行锁，因此按用户的不同表情计数无法与自己竞争。 */
    private fun upsertUnderUserCapLocked(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
    ): Boolean {
        if (reactions.exists(transaction, chatId, serverSeq, emoji, uid)) return false
        val distinct = reactions.countUserEmojis(transaction, chatId, serverSeq, uid)
        require(distinct < MAX_DISTINCT_EMOJIS_PER_USER_MESSAGE) {
            "每条消息每人最多 $MAX_DISTINCT_EMOJIS_PER_USER_MESSAGE 个不同回应"
        }
        return reactions.upsert(transaction, chatId, serverSeq, emoji, uid, System.currentTimeMillis())
    }

    suspend fun listReactions(
        uid: String,
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
    ): List<MessageReactionSummary> {
        require(fromSeq in 1L..toSeq) { "回应查询区间非法" }
        require(toSeq - fromSeq < MAX_QUERY_SEQ_SPAN) { "回应查询区间过大" }
        return access.readAsMember(uid, chatId) { _, _ ->
            val rows = reactions.listRange(chatId, fromSeq, toSeq, MAX_QUERY_ROWS)
            rows.groupBy(MessageReactionRow::serverSeq).map { (serverSeq, messageRows) ->
                MessageReactionSummary(
                    serverSeq = serverSeq,
                    groups = messageRows
                        .groupBy(MessageReactionRow::emoji)
                        .map { (emoji, emojiRows) ->
                            MessageReactionGroup(emoji, emojiRows.map(MessageReactionRow::uid))
                        },
                )
            }
        }
    }

    companion object {
        const val ACTION_ADD = 1
        const val ACTION_REMOVE = 0
        const val MAX_DISTINCT_EMOJIS_PER_USER_MESSAGE = 12
        const val MAX_QUERY_ROWS = 20_000
        const val MAX_QUERY_SEQ_SPAN = 10_000L
    }
}
