package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MessageBodyRegistry
import com.virjar.tk.server.domain.message.ScheduledMessageRepository
import com.virjar.tk.server.domain.message.ScheduledMessageRow
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.ScheduledMessages
import com.virjar.tk.server.infra.db.requireExposedTransaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import com.virjar.tk.protocol.model.ScheduledMessage

/** PostgreSQL 访问：scheduled_messages 调度行（MSG-01/F3）。 */
class ExposedScheduledMessageRepository(
    @Suppress("unused") private val database: Database,
) : ScheduledMessageRepository {

    override fun findByOperation(
        transaction: PgWriteTransactionContext,
        senderUid: String,
        operationId: String,
    ): ScheduledMessageRow? {
        transaction.requireExposedTransaction()
        return ScheduledMessages.selectAll()
            .where { (ScheduledMessages.senderUid eq senderUid) and (ScheduledMessages.operationId eq operationId) }
            .limit(1)
            .firstOrNull()
            ?.toRow()
    }

    override fun insert(transaction: PgWriteTransactionContext, row: ScheduledMessageRow) {
        transaction.requireExposedTransaction()
        ScheduledMessages.insert {
            it[scheduledId] = row.scheduledId
            it[chatId] = row.chatId
            it[senderUid] = row.senderUid
            it[messageType] = row.messageType
            it[bodyBytes] = row.body?.let { body -> ExposedBlob(encodeBody(body)) }
            it[deliverAt] = row.deliverAt
            it[status] = row.status
            it[operationId] = row.operationId
            it[createdAt] = row.createdAt
        }
    }

    override fun findDue(transaction: PgWriteTransactionContext, now: Long, limit: Int): List<ScheduledMessageRow> {
        transaction.requireExposedTransaction()
        return ScheduledMessages.selectAll()
            .where { (ScheduledMessages.status eq ScheduledMessage.STATUS_WAITING) and (ScheduledMessages.deliverAt lessEq now) }
            .orderBy(ScheduledMessages.deliverAt, SortOrder.ASC)
            .limit(limit)
            .map { it.toRow() }
    }

    override fun listWaiting(
        transaction: PgWriteTransactionContext,
        senderUid: String,
        chatId: String,
    ): List<ScheduledMessageRow> {
        transaction.requireExposedTransaction()
        return ScheduledMessages.selectAll()
            .where {
                (ScheduledMessages.status inList listOf(0, 3)) and
                    (ScheduledMessages.senderUid eq senderUid) and
                    (ScheduledMessages.chatId eq chatId)
            }
            .orderBy(ScheduledMessages.deliverAt, SortOrder.ASC)
            .map { it.toRow() }
    }

    override fun get(transaction: PgWriteTransactionContext, scheduledId: String): ScheduledMessageRow? {
        transaction.requireExposedTransaction()
        return ScheduledMessages.selectAll()
            .where { ScheduledMessages.scheduledId eq scheduledId }
            .limit(1)
            .firstOrNull()
            ?.toRow()
    }

    override fun settle(
        transaction: PgWriteTransactionContext,
        scheduledId: String,
        fromStatus: Int,
        toStatus: Int,
        settledAt: Long,
    ): Boolean {
        transaction.requireExposedTransaction()
        return ScheduledMessages.update({
            (ScheduledMessages.scheduledId eq scheduledId) and
                (ScheduledMessages.status eq fromStatus)
        }) {
            it[status] = toStatus
            it[ScheduledMessages.settledAt] = settledAt
        } == 1
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRow(): ScheduledMessageRow {
        val type = this[ScheduledMessages.messageType]
        return ScheduledMessageRow(
            scheduledId = this[ScheduledMessages.scheduledId],
            chatId = this[ScheduledMessages.chatId],
            senderUid = this[ScheduledMessages.senderUid],
            messageType = type,
            body = this[ScheduledMessages.bodyBytes]?.bytes?.let { bytes -> decodeBody(type, bytes) },
            deliverAt = this[ScheduledMessages.deliverAt],
            status = this[ScheduledMessages.status],
            operationId = this[ScheduledMessages.operationId],
            createdAt = this[ScheduledMessages.createdAt],
            settledAt = this[ScheduledMessages.settledAt],
        )
    }

    private fun encodeBody(body: com.virjar.tk.protocol.body.MessageBody): ByteArray =
        PacketBuffer().also { body.writeTo(it) }.toByteArray()

    private fun decodeBody(messageType: Int, bytes: ByteArray): com.virjar.tk.protocol.body.MessageBody? =
        MessageBodyRegistry.decode(MessageType.fromCode(messageType), PacketBuffer(bytes))
}
