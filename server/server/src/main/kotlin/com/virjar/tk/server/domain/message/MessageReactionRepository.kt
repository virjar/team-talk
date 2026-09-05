package com.virjar.tk.server.domain.message

import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/** 一条权威的（消息、表情、回应者）行。 */
data class MessageReactionRow(
    val chatId: String,
    val serverSeq: Long,
    val emoji: String,
    val uid: String,
)

/**
 * 服务端权威回应聚合的持久化端口。
 *
 * 变更在调用方的写事务中执行；幂等性以行键保证，因此重复的 add/remove 尝试无需客户端
 * 提供回执即可收敛。
 */
interface MessageReactionRepository {
    /** 插入一行；当该精确回应已存在时返回 false。 */
    fun upsert(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
        now: Long,
    ): Boolean

    /** 删除一条精确行；当它原本就不存在时返回 false。 */
    fun delete(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
    ): Boolean

    /** 移除一条消息的全部回应；由撤回投影漏斗使用。 */
    fun deleteForMessage(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
    ): Int

    /** 一个用户对一条消息的不同表情计数；调用方通过聊天锁串行化。 */
    fun countUserEmojis(
        transaction: PgReadTransactionContext,
        chatId: String,
        serverSeq: Long,
        uid: String,
    ): Int

    /** 精确成员探测，用于让按用户上限对重复保持幂等。 */
    fun exists(
        transaction: PgReadTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
    ): Boolean

    /** 闭 seq 区间内的全部行，按确定性聚合排序。 */
    fun listRange(
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        maxRows: Int,
    ): List<MessageReactionRow>
}
