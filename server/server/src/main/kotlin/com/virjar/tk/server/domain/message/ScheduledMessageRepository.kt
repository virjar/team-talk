package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/** 一条权威的定时发送调度行；状态契约与协议 [com.virjar.tk.protocol.model.ScheduledMessage] 一致。 */
data class ScheduledMessageRow(
    val scheduledId: String,
    val chatId: String,
    val senderUid: String,
    val messageType: Int,
    val body: MessageBody?,
    val deliverAt: Long,
    val status: Int,
    val operationId: String,
    val createdAt: Long,
    val settledAt: Long?,
)

/** 定时发送调度的持久化端口（MSG-01/F3）；变更在调用方写事务中执行。 */
interface ScheduledMessageRepository {
    /** 按 (senderUid, operationId) 返回既有调度；幂等重放的判据。 */
    fun findByOperation(
        transaction: PgWriteTransactionContext,
        senderUid: String,
        operationId: String,
    ): ScheduledMessageRow?

    fun insert(
        transaction: PgWriteTransactionContext,
        row: ScheduledMessageRow,
    )

    /** 到点待投递的调度，deliverAt 升序；单实例 worker 独占，无并发抢占语义。 */
    fun findDue(
        transaction: PgWriteTransactionContext,
        now: Long,
        limit: Int,
    ): List<ScheduledMessageRow>

    /** 发起者的某会话待发列表，deliverAt 升序。 */
    fun listWaiting(
        transaction: PgWriteTransactionContext,
        senderUid: String,
        chatId: String,
    ): List<ScheduledMessageRow>

    /** 精确读取一条调度（取消与投递后结算用）。 */
    fun get(
        transaction: PgWriteTransactionContext,
        scheduledId: String,
    ): ScheduledMessageRow?

    /** CAS 终态：仅 WAITING 可被结算为 DELIVERED/CANCELLED/FAILED；返回是否由本调用变更。 */
    fun settle(
        transaction: PgWriteTransactionContext,
        scheduledId: String,
        fromStatus: Int,
        toStatus: Int,
        settledAt: Long,
    ): Boolean
}
