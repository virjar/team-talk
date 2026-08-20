package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.conversation.ConversationReadMutation
import com.virjar.tk.domain.conversation.ConversationRepository
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Conversation
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedConversationRepository(
    private val chatRepo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    private val userRepo: UserRepository,
) : ConversationRepository {

    override fun listConversations(uid: String): List<Conversation> {
        val rows = transaction {
            activeConversationJoin().selectAll()
                .where {
                    (Conversations.uid eq uid) and
                        (GroupMembers.uid eq uid) and
                        (GroupMembers.status eq 1) and
                        (Chats.status eq 1)
                }
                .orderBy(Conversations.updatedAt, SortOrder.DESC)
                .map { it.toConversationRow() }
        }
        return rows.mapNotNull { enrichConversation(uid, it) }
    }

    override fun getConversation(uid: String, chatId: String): Conversation? {
        val row = transaction {
            activeConversationJoin().selectAll()
                .where {
                    (Conversations.uid eq uid) and
                        (Conversations.chatId eq chatId) and
                        (GroupMembers.uid eq uid) and
                        (GroupMembers.status eq 1) and
                        (Chats.status eq 1)
                }
                .map { it.toConversationRow() }
                .singleOrNull()
        } ?: return null
        return enrichConversation(uid, row)
    }

    /**
     * 确保会话行存在（如已存在则跳过）。建群/建私聊时为所有成员预创建，
     * 保证 markRead 有行可更新，readSeq 可靠持久化。
     */
    override fun ensureConversation(uid: String, chatId: String, chatType: Int) {
        transaction {
            if (!lockActiveMembership(uid, chatId)) return@transaction
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

    override fun setPin(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
        pinned: Boolean,
    ): Conversation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val existing = requireConversation(uid, chatId)
        if (existing[Conversations.isPinned] != pinned) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.isPinned] = pinned
                it[Conversations.version] = existing[Conversations.version] + 1L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
        requireCommittedSnapshot(uid, chatId, chat)
    }

    override fun setMute(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
        muted: Boolean,
    ): Conversation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val existing = requireConversation(uid, chatId)
        if (existing[Conversations.isMuted] != muted) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.isMuted] = muted
                it[Conversations.version] = existing[Conversations.version] + 1L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
        requireCommittedSnapshot(uid, chatId, chat)
    }

    override fun setDraft(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
        draft: String?,
    ): Conversation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val existing = requireConversation(uid, chatId)
        if (existing[Conversations.draft] != draft) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.draft] = draft
                it[Conversations.version] = existing[Conversations.version] + 1L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
        requireCommittedSnapshot(uid, chatId, chat)
    }

    override fun markRead(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
        readSeq: Long,
    ): ConversationReadMutation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val maxSeq = chat[Chats.maxSeq]
        require(readSeq in 0L..maxSeq) {
            "readSeq 必须在当前会话序号范围 0..$maxSeq 内"
        }

        // Lock every active member's projection in uid order. Both the actor readSeq and all
        // peerReadSeq values are max-watermarks; a later transaction can only observe and advance
        // the values committed by the earlier one.
        val memberUids = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).map { it[GroupMembers.uid] }
        val rowsByUid = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid inList memberUids)
        }.orderBy(Conversations.uid, SortOrder.ASC).forUpdate()
            .associateBy { it[Conversations.uid] }
        val actor = requireNotNull(rowsByUid[uid]) { "会话不存在" }
        val authoritativeReadSeq = maxOf(actor[Conversations.readSeq], readSeq)
        val now = System.currentTimeMillis()

        val actorChanged = authoritativeReadSeq > actor[Conversations.readSeq]
        if (actorChanged) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.readSeq] = authoritativeReadSeq
                it[Conversations.version] = actor[Conversations.version] + 1L
                it[Conversations.updatedAt] = now
            }
        }

        val advancedPeerUids = mutableListOf<String>()
        for (peerUid in memberUids) {
            if (peerUid == uid) continue
            val peer = rowsByUid[peerUid] ?: continue
            if (authoritativeReadSeq <= peer[Conversations.peerReadSeq]) continue
            Conversations.update({
                (Conversations.uid eq peerUid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.peerReadSeq] = authoritativeReadSeq
                it[Conversations.version] = peer[Conversations.version] + 1L
                it[Conversations.updatedAt] = now
            }
            advancedPeerUids += peerUid
        }

        val snapshot = requireCommittedSnapshot(uid, chatId, chat)
        ConversationReadMutation(
            conversation = snapshot,
            actorChanged = actorChanged,
            advancedPeerUids = advancedPeerUids,
        )
    }

    override fun deleteConversation(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
    ): Boolean = inWriteTransaction(transaction) {
        requireActiveMembership(uid, chatId)
        Conversations.deleteWhere {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        } > 0
    }

    override fun deleteConversationProjection(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
    ) {
        inWriteTransaction(transaction) {
            // Lifecycle cleanup runs after membership/chat deactivation, so it cannot require an
            // active aggregate. Lock the aggregate row when it still exists to serialize against
            // member-owned mutations from another process.
            Chats.selectAll().where { Chats.chatId eq chatId }.forUpdate().singleOrNull()
            Conversations.deleteWhere {
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }
        }
    }

    private fun enrichConversation(uid: String, row: ConversationRow): Conversation? {
        // Revalidate after the projection query. This makes the adapter fail closed even if a
        // deactivate/removal commits between selecting rows and resolving display metadata.
        val chat = chatRepo.getChat(row.chatId) ?: return null
        if (memberRepo.getMember(row.chatId, uid) == null) return null
        var chatName: String? = null
        var chatAvatar: String? = null

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

        val unreadCount = (row.lastMsgSeq - row.readSeq)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

        return Conversation(
            chatId = row.chatId,
            chatType = chat.chatType,
            chatName = chatName,
            chatAvatar = chatAvatar,
            lastMessage = row.lastMessage,
            lastMessageType = row.lastMessageType,
            lastSeq = row.lastMsgSeq,
            readSeq = row.readSeq,
            unreadCount = unreadCount,
            isPinned = row.isPinned,
            isMuted = row.isMuted,
            peerReadSeq = row.peerReadSeq,
            draft = row.draft,
        )
    }

    private fun activeConversationJoin() = Conversations.join(
        otherTable = GroupMembers,
        joinType = JoinType.INNER,
        onColumn = Conversations.chatId,
        otherColumn = GroupMembers.chatId,
    ).join(
        otherTable = Chats,
        joinType = JoinType.INNER,
        onColumn = Conversations.chatId,
        otherColumn = Chats.chatId,
    )

    /** Serialize projection creation with deactivateChat's lock on the same aggregate row. */
    private fun lockActiveMembership(uid: String, chatId: String): Boolean {
        if (Chats.selectAll().where {
                (Chats.chatId eq chatId) and (Chats.status eq 1)
            }.forUpdate().singleOrNull() == null
        ) return false
        return GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.count() > 0
    }

    private fun requireActiveMembership(uid: String, chatId: String): ResultRow {
        val chat = Chats.selectAll().where {
            (Chats.chatId eq chatId) and (Chats.status eq 1)
        }.forUpdate().singleOrNull()
        require(chat != null) { "聊天不存在或已解散" }

        val member = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.forUpdate().singleOrNull()
        require(member != null) { "不是聊天成员" }
        return chat
    }

    private fun requireConversation(uid: String, chatId: String): ResultRow =
        requireNotNull(Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.forUpdate().singleOrNull()) { "会话不存在" }

    private fun requireCommittedSnapshot(uid: String, chatId: String, chat: ResultRow): Conversation =
        checkNotNull(committedSnapshot(uid, chatId, chat)) {
            "Conversation projection disappeared while its row lock was held"
        }

    /** Build the event payload from the row state visible inside the active write transaction. */
    private fun committedSnapshot(uid: String, chatId: String, chat: ResultRow): Conversation? {
        val row = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull()?.toConversationRow() ?: return null

        val chatType = chat[Chats.chatType]
        val display = if (chatType == 2) {
            GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()?.let {
                it[GroupChats.name] to it[GroupChats.avatar]
            }
        } else {
            val otherUid = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.status eq 1) and
                    (GroupMembers.uid neq uid)
            }.orderBy(GroupMembers.uid, SortOrder.ASC).limit(1)
                .firstOrNull()?.get(GroupMembers.uid)
            otherUid?.let { peerUid ->
                val peer = Users.selectAll().where { Users.uid eq peerUid }.singleOrNull()
                (peer?.get(Users.name) ?: peerUid) to peer?.get(Users.avatar)
            }
        }

        val unreadCount = (row.lastMsgSeq - row.readSeq)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        return Conversation(
            chatId = row.chatId,
            chatType = chatType,
            chatName = display?.first,
            chatAvatar = display?.second,
            lastMessage = row.lastMessage,
            lastMessageType = row.lastMessageType,
            lastSeq = row.lastMsgSeq,
            readSeq = row.readSeq,
            unreadCount = unreadCount,
            isPinned = row.isPinned,
            isMuted = row.isMuted,
            peerReadSeq = row.peerReadSeq,
            draft = row.draft,
        )
    }

    private inline fun <T> inWriteTransaction(
        context: PgTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
        return block()
    }
}

private data class ConversationRow(
    val chatId: String,
    val chatType: Int,
    val lastMsgSeq: Long,
    val lastMessage: String?,
    val lastMessageType: Int,
    val readSeq: Long,
    val peerReadSeq: Long,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val draft: String?,
)

private fun ResultRow.toConversationRow() = ConversationRow(
    chatId = this[Conversations.chatId],
    chatType = this[Conversations.chatType],
    lastMsgSeq = this[Conversations.lastMsgSeq],
    lastMessage = this[Conversations.lastMessage],
    lastMessageType = this[Conversations.lastMessageType],
    readSeq = this[Conversations.readSeq],
    peerReadSeq = this[Conversations.peerReadSeq],
    isPinned = this[Conversations.isPinned],
    isMuted = this[Conversations.isMuted],
    draft = this[Conversations.draft],
)
