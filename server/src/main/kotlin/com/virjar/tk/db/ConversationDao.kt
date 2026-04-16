package com.virjar.tk.db

import com.virjar.tk.protocol.ChannelType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class ConversationRow(
    val id: Long,
    val uid: String,
    val channelId: String,
    val channelType: ChannelType,
    val lastMsgSeq: Long,
    val unreadCount: Int,
    val readSeq: Long,
    val isMuted: Boolean,
    val isPinned: Boolean,
    val draft: String,
    val version: Long,
)

object ConversationDao {

    /** 启动时批量加载所有会话（同步事务） */
    fun loadAll(): List<ConversationRow> = transaction {
        Conversations.selectAll()
            .orderBy(Conversations.isPinned, SortOrder.DESC)
            .orderBy(Conversations.updatedAt, SortOrder.DESC)
            .map { it.toConversationRow() }
    }

    fun createOrUpdate(
        uid: String,
        channelId: String,
        channelType: ChannelType,
        lastMsgSeq: Long = 0,
    ): ConversationRow = transaction {
        val existing = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
        }.singleOrNull()

        val now = System.currentTimeMillis()

        if (existing != null) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
            }) {
                if (lastMsgSeq > 0) it[Conversations.lastMsgSeq] = lastMsgSeq
                it[version] = now
                it[updatedAt] = now
            }
            existing.toConversationRow().copy(
                lastMsgSeq = if (lastMsgSeq > 0) lastMsgSeq else existing[Conversations.lastMsgSeq],
                version = now,
            )
        } else {
            val id = Conversations.insert {
                it[Conversations.uid] = uid
                it[Conversations.channelId] = channelId
                it[Conversations.channelType] = channelType.code
                if (lastMsgSeq > 0) it[Conversations.lastMsgSeq] = lastMsgSeq
                it[version] = now
                it[updatedAt] = now
            } get Conversations.id

            ConversationRow(
                id = id.value,
                uid = uid,
                channelId = channelId,
                channelType = channelType,
                lastMsgSeq = lastMsgSeq,
                unreadCount = 0,
                readSeq = 0,
                isMuted = false,
                isPinned = false,
                draft = "",
                version = now,
            )
        }
    }

    fun incrementUnread(uid: String, channelId: String) = transaction {
        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
        }) {
            with(SqlExpressionBuilder) {
                it.update(unreadCount, unreadCount + 1)
            }
            it[version] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun markRead(uid: String, channelId: String, readSeq: Long) = transaction {
        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
        }) {
            it[Conversations.readSeq] = readSeq
            it[unreadCount] = 0
            it[version] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun updateDraft(uid: String, channelId: String, draft: String) = transaction {
        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
        }) {
            it[Conversations.draft] = draft
            it[version] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun updatePin(uid: String, channelId: String, pinned: Boolean) = transaction {
        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
        }) {
            it[isPinned] = pinned
            it[version] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun updateMute(uid: String, channelId: String, muted: Boolean) = transaction {
        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
        }) {
            it[isMuted] = muted
            it[version] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun delete(uid: String, channelId: String) = transaction {
        Conversations.deleteWhere {
            with(SqlExpressionBuilder) {
                (Conversations.uid eq uid) and (Conversations.channelId eq channelId)
            }
        }
    }

    fun findByUser(uid: String, version: Long = 0): List<ConversationRow> = transaction {
        Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.version greater version)
        }.orderBy(Conversations.isPinned, SortOrder.DESC)
         .orderBy(Conversations.updatedAt, SortOrder.DESC)
         .map { it.toConversationRow() }
    }

    private fun ResultRow.toConversationRow() = ConversationRow(
        id = this[Conversations.id].value,
        uid = this[Conversations.uid],
        channelId = this[Conversations.channelId],
        channelType = ChannelType.fromCode(this[Conversations.channelType]),
        lastMsgSeq = this[Conversations.lastMsgSeq],
        unreadCount = this[Conversations.unreadCount],
        readSeq = this[Conversations.readSeq],
        isMuted = this[Conversations.isMuted],
        isPinned = this[Conversations.isPinned],
        draft = this[Conversations.draft],
        version = this[Conversations.version],
    )
}
