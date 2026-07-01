package com.virjar.tk.domain.conversation

import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.model.Conversation
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ConversationRepository(
    private val chatRepo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    private val userRepo: UserRepository,
) {

    fun listConversations(uid: String): List<Conversation> {
        val rows = transaction {
            Conversations.selectAll()
                .where { Conversations.uid eq uid }
                .orderBy(Conversations.updatedAt, SortOrder.DESC)
                .map { it.toConversationRow() }
        }
        return rows.map { enrichConversation(uid, it) }
    }

    fun getConversation(uid: String, chatId: String): Conversation? {
        val row = transaction {
            Conversations.selectAll()
                .where { (Conversations.uid eq uid) and (Conversations.chatId eq chatId) }
                .map { it.toConversationRow() }
                .singleOrNull()
        } ?: return null
        return enrichConversation(uid, row)
    }

    fun upsertConversation(uid: String, chatId: String, chatType: Int, lastMsgSeq: Long, lastMsgType: Int = 0, lastMsgPreview: String? = null) {
        transaction {
            val existing = Conversations.selectAll()
                .where { (Conversations.uid eq uid) and (Conversations.chatId eq chatId) }
                .singleOrNull()

            if (existing != null) {
                val currentSeq = existing[Conversations.lastMsgSeq]
                if (lastMsgSeq > currentSeq) {
                    Conversations.update({
                        (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
                    }) {
                        it[Conversations.lastMsgSeq] = lastMsgSeq
                        if (lastMsgPreview != null) it[Conversations.lastMessage] = lastMsgPreview
                        it[Conversations.lastMessageType] = lastMsgType
                        it[Conversations.version] = existing[Conversations.version] + 1
                        it[Conversations.updatedAt] = System.currentTimeMillis()
                    }
                }
            } else {
                Conversations.insert {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = chatType
                    it[Conversations.lastMsgSeq] = lastMsgSeq
                    if (lastMsgPreview != null) it[Conversations.lastMessage] = lastMsgPreview
                    it[Conversations.lastMessageType] = lastMsgType
                    it[Conversations.version] = 1
                    it[Conversations.updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    fun markRead(uid: String, chatId: String, readSeq: Long) {
        transaction {
            val existing = Conversations.selectAll()
                .where { (Conversations.uid eq uid) and (Conversations.chatId eq chatId) }
                .singleOrNull()

            if (existing != null) {
                // 只增不减（readSeq 是单调递增的水位线，天然可合并）
                val currentReadSeq = existing[Conversations.readSeq]
                if (readSeq <= currentReadSeq) return@transaction
                Conversations.update({
                    (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
                }) {
                    it[Conversations.readSeq] = readSeq
                    it[Conversations.version] = existing[Conversations.version] + 1
                    it[Conversations.updatedAt] = System.currentTimeMillis()
                }
            } else {
                // 会话行不存在（如建群后未收到消息就 markRead）→ 创建行持久化 readSeq。
                // 之前这里静默 no-op 导致 readSeq 丢失，换设备登录后全未读。
                Conversations.insert {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = 1 // 未知 chatType 时默认 personal，listConversations 会用 chat 表覆盖
                    it[Conversations.lastMsgSeq] = 0
                    it[Conversations.readSeq] = readSeq
                    it[Conversations.version] = 1
                    it[Conversations.updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * 确保会话行存在（如已存在则跳过）。建群/建私聊时为所有成员预创建，
     * 保证 markRead 有行可更新，readSeq 可靠持久化。
     */
    fun ensureConversation(uid: String, chatId: String, chatType: Int) {
        transaction {
            val exists = Conversations.selectAll()
                .where { (Conversations.uid eq uid) and (Conversations.chatId eq chatId) }
                .count() > 0
            if (!exists) {
                Conversations.insert {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = chatType
                    it[Conversations.lastMsgSeq] = 0
                    it[Conversations.readSeq] = 0
                    it[Conversations.version] = 1
                    it[Conversations.updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    fun setPin(uid: String, chatId: String, pinned: Boolean) {
        transaction {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.isPinned] = pinned
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun setMute(uid: String, chatId: String, muted: Boolean) {
        transaction {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.isMuted] = muted
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun setDraft(uid: String, chatId: String, draft: String?) {
        transaction {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.draft] = draft
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun deleteConversation(uid: String, chatId: String) {
        transaction {
            Conversations.deleteWhere {
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }
        }
    }

    fun getConversationsAfter(uid: String, afterVersion: Long, limit: Int = 100): List<Conversation> {
        val rows = transaction {
            Conversations.selectAll()
                .where { (Conversations.uid eq uid) and (Conversations.version greater afterVersion) }
                .orderBy(Conversations.version)
                .limit(limit)
                .map { it.toConversationRow() }
        }
        return rows.map { enrichConversation(uid, it) }
    }

    private fun enrichConversation(uid: String, row: ConversationRow): Conversation {
        val chat = chatRepo.getChat(row.chatId)
        var chatName: String? = null
        var chatAvatar: String? = null

        if (chat != null) {
            if (chat.chatType == 2) {
                chatName = chat.name
                chatAvatar = chat.avatar
            } else {
                val members = memberRepo.getMembers(row.chatId)
                val otherUid = members.firstOrNull { it.uid != uid }?.uid
                if (otherUid != null) {
                    val otherUser = userRepo.findByUid(otherUid)
                    chatName = otherUser?.name ?: otherUid
                    chatAvatar = otherUser?.avatar
                }
            }
        }

        val unreadCount = maxOf(0, (row.lastMsgSeq - row.readSeq).toInt())

        return Conversation(
            chatId = row.chatId,
            chatType = row.chatType,
            chatName = chatName,
            chatAvatar = chatAvatar,
            lastMessage = row.lastMessage,
            lastMessageType = row.lastMessageType,
            lastSeq = row.lastMsgSeq,
            readSeq = row.readSeq,
            unreadCount = unreadCount,
            isPinned = row.isPinned,
            isMuted = row.isMuted,
            draft = row.draft,
        )
    }
}

private data class ConversationRow(
    val chatId: String,
    val chatType: Int,
    val lastMsgSeq: Long,
    val lastMessage: String?,
    val lastMessageType: Int,
    val readSeq: Long,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val draft: String?,
    val version: Long,
)

private fun ResultRow.toConversationRow() = ConversationRow(
    chatId = this[Conversations.chatId],
    chatType = this[Conversations.chatType],
    lastMsgSeq = this[Conversations.lastMsgSeq],
    lastMessage = this[Conversations.lastMessage],
    lastMessageType = this[Conversations.lastMessageType],
    readSeq = this[Conversations.readSeq],
    isPinned = this[Conversations.isPinned],
    isMuted = this[Conversations.isMuted],
    draft = this[Conversations.draft],
    version = this[Conversations.version],
)
