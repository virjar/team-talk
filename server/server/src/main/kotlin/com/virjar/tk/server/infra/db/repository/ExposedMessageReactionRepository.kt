package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.message.MessageReactionRepository
import com.virjar.tk.server.domain.message.MessageReactionRow
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.MessageReactions
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.server.infra.db.requireExposedTransaction
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** PostgreSQL 访问：message_reactions 聚合行。 */
class ExposedMessageReactionRepository(
    private val database: Database,
) : MessageReactionRepository {

    override fun upsert(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
        now: Long,
    ): Boolean {
        transaction.requireExposedTransaction()
        val inserted = MessageReactions.insert {
            it[MessageReactions.chatId] = chatId
            it[MessageReactions.serverSeq] = serverSeq
            it[MessageReactions.emoji] = emoji
            it[MessageReactions.uid] = uid
            it[createdAt] = now
        }.insertedCount == 1
        if (!inserted) {
            // 幂等重试不刷新 createdAt，聚合状态保持首次事实。
            return false
        }
        return true
    }

    override fun delete(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
    ): Boolean {
        transaction.requireExposedTransaction()
        return MessageReactions.deleteWhere {
            (MessageReactions.chatId eq chatId) and
                (MessageReactions.serverSeq eq serverSeq) and
                (MessageReactions.emoji eq emoji) and
                (MessageReactions.uid eq uid)
        } == 1
    }

    override fun deleteForMessage(
        transaction: PgWriteTransactionContext,
        chatId: String,
        serverSeq: Long,
    ): Int {
        transaction.requireExposedTransaction()
        return MessageReactions.deleteWhere {
            (MessageReactions.chatId eq chatId) and (MessageReactions.serverSeq eq serverSeq)
        }
    }

    override fun countUserEmojis(
        transaction: PgReadTransactionContext,
        chatId: String,
        serverSeq: Long,
        uid: String,
    ): Int {
        transaction.requireExposedReadTransaction()
        return MessageReactions.selectAll().where {
            (MessageReactions.chatId eq chatId) and
                (MessageReactions.serverSeq eq serverSeq) and
                (MessageReactions.uid eq uid)
        }.count().toInt()
    }

    override fun exists(
        transaction: PgReadTransactionContext,
        chatId: String,
        serverSeq: Long,
        emoji: String,
        uid: String,
    ): Boolean {
        transaction.requireExposedReadTransaction()
        return MessageReactions.selectAll().where {
            (MessageReactions.chatId eq chatId) and
                (MessageReactions.serverSeq eq serverSeq) and
                (MessageReactions.emoji eq emoji) and
                (MessageReactions.uid eq uid)
        }.any()
    }

    override fun listRange(
        chatId: String,
        fromSeq: Long,
        toSeq: Long,
        maxRows: Int,
    ): List<MessageReactionRow> = transaction(database) {
        val rows = MessageReactions.selectAll().where {
            (MessageReactions.chatId eq chatId) and
                (MessageReactions.serverSeq greaterEq fromSeq) and
                (MessageReactions.serverSeq lessEq toSeq)
        }.orderBy(MessageReactions.serverSeq, SortOrder.ASC)
            .orderBy(MessageReactions.emoji, SortOrder.ASC)
            .orderBy(MessageReactions.uid, SortOrder.ASC)
            .limit(maxRows + 1)
            .map { row ->
                MessageReactionRow(
                    chatId = row[MessageReactions.chatId],
                    serverSeq = row[MessageReactions.serverSeq],
                    emoji = row[MessageReactions.emoji],
                    uid = row[MessageReactions.uid],
                )
            }
        check(rows.size <= maxRows) { "回应查询行数超过上限 $maxRows，请缩小 seq 窗口" }
        rows
    }
}
