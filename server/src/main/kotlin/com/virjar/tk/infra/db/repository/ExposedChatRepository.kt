package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.AdminPage
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatDeactivation
import com.virjar.tk.domain.chat.InviteJoinResult
import com.virjar.tk.domain.chat.personalChatKey
import com.virjar.tk.domain.chat.requireJoinable
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupInviteLinks
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Chats 表访问 + Chat 视图组装。
 *
 * 成员管理见 [ChatMemberRepository]，邀请链接见 [InviteLinkRepository]。
 *
 * [createPersonalChat] / [createGroupChat] atomically initialize GroupMembers and Conversations;
 * these rows are required parts of the newly created chat, not best-effort service projections.
 */
class ExposedChatRepository : ChatRepository {

    // ── Chat CRUD ──

    override fun createPersonalChat(uid1: String, uid2: String): Chat {
        val pairKey = personalChatKey(uid1, uid2)
        return transaction {
            val existingChatId = findPersonalChatIdInternal(uid1, uid2)
            if (existingChatId != null) {
                return@transaction getChatByIdInternal(existingChatId)!!
            }

            val chatId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val inserted = Chats.insertIgnore {
                it[Chats.chatId] = chatId
                it[Chats.chatType] = 1
                it[Chats.personalKey] = pairKey
                it[Chats.maxSeq] = 0
                it[Chats.status] = 1
                it[Chats.createdAt] = now
                it[Chats.updatedAt] = now
            }
            if (inserted.insertedCount == 0) {
                val winnerChatId = Chats.selectAll()
                    .where { (Chats.personalKey eq pairKey) and (Chats.status eq 1) }
                    .singleOrNull()
                    ?.get(Chats.chatId)
                    ?: error("私聊唯一键冲突，但未找到已建立的会话")
                return@transaction getChatByIdInternal(winnerChatId)!!
            }
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 1
                it[GroupMembers.uid] = uid1
                it[GroupMembers.role] = 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 1
                it[GroupMembers.uid] = uid2
                it[GroupMembers.role] = 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            // 为双方创建 conversation 记录
            for (uid in listOf(uid1, uid2)) {
                ensureConversation(uid = uid, chatId = chatId, chatType = 1, now = now)
            }
            Chat(chatId = chatId, chatType = 1)
        }
    }

    override fun createGroupChat(
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
        requestedChatId: String?,
    ): Chat {
        return transaction {
            val chatId = requestedChatId ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val existing = Chats.selectAll().where { Chats.chatId eq chatId }.singleOrNull()
            if (existing != null) {
                require(existing[Chats.chatType] == 2) { "受管群 ID 与非群聊冲突: $chatId" }
                Chats.update({ Chats.chatId eq chatId }) {
                    it[status] = 1
                    it[updatedAt] = now
                }
                GroupChats.update({ GroupChats.chatId eq chatId }) {
                    it[GroupChats.name] = name
                    it[GroupChats.avatar] = avatar
                    it[GroupChats.creator] = creatorUid
                    it[GroupChats.updatedAt] = now
                }
                GroupMembers.update({ GroupMembers.chatId eq chatId }) {
                    it[role] = 0
                }
                ensureActiveGroupMember(chatId, creatorUid, role = 2, now = now)
                memberUids.filter { it != creatorUid }.forEach {
                    ensureActiveGroupMember(chatId, it, role = 0, now = now)
                }
                return@transaction getChatByIdInternal(chatId)!!
            }
            Chats.insert {
                it[Chats.chatId] = chatId
                it[Chats.chatType] = 2
                it[Chats.maxSeq] = 0
                it[Chats.status] = 1
                it[Chats.createdAt] = now
                it[Chats.updatedAt] = now
            }
            GroupChats.insert {
                it[GroupChats.chatId] = chatId
                it[GroupChats.name] = name
                it[GroupChats.avatar] = avatar
                it[GroupChats.creator] = creatorUid
                it[GroupChats.mutedAll] = false
                it[GroupChats.updatedAt] = now
            }
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 2
                it[GroupMembers.uid] = creatorUid
                it[GroupMembers.role] = 2
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            for (uid in memberUids.distinct()) {
                GroupMembers.insertIgnore {
                    it[GroupMembers.chatId] = chatId
                    it[GroupMembers.chatType] = 2
                    it[GroupMembers.uid] = uid
                    it[GroupMembers.role] = 0
                    it[GroupMembers.status] = 1
                    it[GroupMembers.joinedAt] = now
                }
            }
            // 为所有成员创建 conversation 记录，确保会话列表能显示群聊
            val allUids = (memberUids + creatorUid).distinct()
            for (uid in allUids) {
                ensureConversation(uid = uid, chatId = chatId, chatType = 2, now = now)
            }
            Chat(
                chatId = chatId, chatType = 2, name = name, avatar = avatar,
                creator = creatorUid, memberCount = allUids.size,
            )
        }
    }

    override fun joinByInvite(
        transaction: PgTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = inWriteTransaction(transaction) {
        // Resolve the owning chat without a lock, then acquire the aggregate locks in the global
        // chat -> invite order also used by dissolution. The locked re-read below is authoritative.
        val resolvedChatId = GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.token eq token }
            .singleOrNull()
            ?.get(GroupInviteLinks.chatId)
            ?: throw IllegalArgumentException("邀请链接不存在")
        val chatRow = Chats.selectAll()
            .where { Chats.chatId eq resolvedChatId }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("聊天不存在")
        val inviteRow = GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.token eq token }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("邀请链接不存在")
        val chatId = inviteRow[GroupInviteLinks.chatId]
        require(chatId == resolvedChatId) { "邀请链接归属已变更" }
        require(chatRow[Chats.status] == 1) { "聊天不存在" }
        require(chatRow[Chats.chatType] == 2) { "邀请链接只能用于群聊" }
        require(GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull() != null) {
            "群聊数据不完整"
        }

        val membership = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.forUpdate().singleOrNull()
        val joined = membership?.get(GroupMembers.status) != 1
        if (joined) {
            // Apply mutable link policy only when this command changes membership. An already
            // active member may be retrying a response that was lost after the first commit; an
            // exhausted/revoked link must not turn that committed success into a later failure.
            inviteRow.toInviteLinkRecord().requireJoinable(nowMillis)
            if (membership == null) {
                GroupMembers.insert {
                    it[GroupMembers.chatId] = chatId
                    it[GroupMembers.chatType] = 2
                    it[GroupMembers.uid] = uid
                    it[GroupMembers.role] = 0
                    it[GroupMembers.status] = 1
                    it[GroupMembers.joinedAt] = nowMillis
                }
            } else {
                GroupMembers.update({
                    (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                }) {
                    // Invite joins are always ordinary user membership. Managed reconciliation
                    // has a separate role-aware internal path (ensureActiveGroupMember/setRole).
                    it[GroupMembers.role] = 0
                    it[GroupMembers.status] = 1
                    it[GroupMembers.joinedAt] = nowMillis
                }
            }
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
                with(SqlExpressionBuilder) {
                    it[GroupInviteLinks.useCount] = GroupInviteLinks.useCount + 1
                }
            }
        }

        ensureConversation(uid = uid, chatId = chatId, chatType = 2, now = nowMillis)
        val chat = getChatByIdInternal(chatId) ?: throw IllegalArgumentException("聊天不存在")
        val members = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .map(ResultRow::toMemberSnapshot)
        InviteJoinResult(chat = chat, joined = joined, members = members)
    }

    private inline fun <T> inWriteTransaction(context: PgTransactionContext, block: () -> T): T {
        context.requireExposedTransaction()
        return block()
    }

    private fun ensureActiveGroupMember(chatId: String, uid: String, role: Int, now: Long) {
        val existing = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.singleOrNull()
        if (existing == null) {
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[chatType] = 2
                it[GroupMembers.uid] = uid
                it[GroupMembers.role] = role
                it[status] = 1
                it[joinedAt] = now
            }
        } else {
            GroupMembers.update({
                (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
            }) {
                it[GroupMembers.role] = role
                it[status] = 1
            }
        }
    }

    override fun getChat(chatId: String): Chat? {
        return transaction { getChatByIdInternal(chatId) }
    }

    override fun updateGroup(chatId: String, name: String?, avatar: String?, notice: String?) {
        transaction {
            require(Chats.selectAll().where {
                (Chats.chatId eq chatId) and (Chats.status eq 1)
            }.forUpdate().singleOrNull() != null) { "聊天不存在" }
            name?.let { v -> GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.name] = v } }
            avatar?.let { v -> GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.avatar] = v } }
            notice?.let { v -> GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.notice] = v } }
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.updatedAt] = System.currentTimeMillis() }
            Chats.update({ Chats.chatId eq chatId }) { it[Chats.updatedAt] = System.currentTimeMillis() }
        }
    }

    override fun deactivateChat(chatId: String) {
        transaction {
            deactivateChatInternal(chatId, allowMissing = true)
        }
    }

    override fun deactivateChat(transaction: PgTransactionContext, chatId: String): ChatDeactivation =
        inWriteTransaction(transaction) {
            deactivateChatInternal(chatId, allowMissing = false)
                ?: throw IllegalArgumentException("聊天不存在")
        }

    private fun deactivateChatInternal(chatId: String, allowMissing: Boolean): ChatDeactivation? {
        // The caller may already hold this row. Re-locking is safe and documents that no member or
        // Conversation mutation occurs without the chat aggregate fence.
        val chatRow = Chats.selectAll()
            .where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull()
        if (chatRow == null || chatRow[Chats.status] != 1) {
            if (allowMissing) return null
            throw IllegalArgumentException("聊天不存在")
        }
        GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.chatId eq chatId }
            .orderBy(GroupInviteLinks.id)
            .forUpdate()
            .toList()
        val memberRows = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .orderBy(GroupMembers.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        val chat = getChatByIdInternal(chatId) ?: throw IllegalStateException("聊天数据不完整")
        val memberUids = memberRows.map { it[GroupMembers.uid] }

        Chats.update({ Chats.id eq chatRow[Chats.id] }) {
            it[Chats.status] = 0
            it[Chats.updatedAt] = System.currentTimeMillis()
        }
        GroupMembers.update({ GroupMembers.chatId eq chatId }) {
            it[GroupMembers.status] = 0
        }
        Conversations.deleteWhere { Conversations.chatId eq chatId }
        GroupMemberMutes.deleteWhere { GroupMemberMutes.chatId eq chatId }
        GroupInviteLinks.deleteWhere { GroupInviteLinks.chatId eq chatId }
        return ChatDeactivation(chat, memberUids)
    }

    override fun getMemberUids(chatId: String): List<String> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    override fun updateMaxSeq(chatId: String, seq: Long) {
        transaction {
            Chats.update({ (Chats.chatId eq chatId) and (Chats.maxSeq less seq) }) {
                it[Chats.maxSeq] = seq
                it[Chats.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    // ── 查询 ──

    override fun findPersonalChatId(uid1: String, uid2: String): String? {
        return transaction { findPersonalChatIdInternal(uid1, uid2) }
    }

    // ── 管理端查询（全局视图/分页）──

    override fun getChatById(chatId: String): Chat? = transaction {
        Chats.selectAll().where { Chats.chatId eq chatId }
            .map { buildChatFromRow(it) }.singleOrNull()
    }

    override fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat> = transaction {
        val condition = if (query.isNullOrBlank()) {
            Op.TRUE and (Chats.chatType eq 2)
        } else {
            (Chats.chatType eq 2) and (GroupChats.name like "%$query%")
        }
        val filtered = (Chats innerJoin GroupChats).selectAll().where { condition }
        val total = filtered.count()
        val items = filtered.orderBy(Chats.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(size).offset(((page - 1) * size).toLong())
            .map { buildChatFromRow(it) }
        AdminPage(total, items)
    }

    override fun countGroups(): Long = transaction {
        Chats.selectAll().where { Chats.chatType eq 2 }.count()
    }

    override fun countEventsSince(since: Long): Long = transaction {
        com.virjar.tk.infra.db.SyncEvents.selectAll().where { com.virjar.tk.infra.db.SyncEvents.createdAt greater since }.count()
    }

    override fun listUserChats(uid: String): List<Chat> {
        return transaction {
            val chatIds = GroupMembers.selectAll()
                .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.chatId] }.toSet()

            Chats.selectAll()
                .where { (Chats.chatId inList chatIds) and (Chats.status eq 1) }
                .map { row -> buildChatFromRow(row) }
        }
    }

    // ── 内部辅助（在 transaction 内调用） ──

    private fun findPersonalChatIdInternal(uid1: String, uid2: String): String? {
        return Chats.selectAll()
            .where {
                (Chats.personalKey eq personalChatKey(uid1, uid2)) and
                    (Chats.chatType eq 1) and
                    (Chats.status eq 1)
            }
            .singleOrNull()
            ?.get(Chats.chatId)
    }

    private fun ensureConversation(uid: String, chatId: String, chatType: Int, now: Long) {
        Conversations.insertIgnore {
            it[Conversations.uid] = uid
            it[Conversations.chatId] = chatId
            it[Conversations.chatType] = chatType
            it[Conversations.lastMsgSeq] = 0
            it[Conversations.updatedAt] = now
        }
    }

    private fun getChatByIdInternal(chatId: String): Chat? {
        val row = Chats.selectAll()
            .where { (Chats.chatId eq chatId) and (Chats.status eq 1) }
            .singleOrNull() ?: return null
        return buildChatFromRow(row)
    }

    private fun buildChatFromRow(row: ResultRow): Chat {
        val chatId = row[Chats.chatId]
        val chatType = row[Chats.chatType]
        val maxSeq = row[Chats.maxSeq]

        if (chatType == 1) {
            return Chat(chatId = chatId, chatType = 1, maxSeq = maxSeq)
        }

        val gc = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
        val memberCount = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .count().toInt()

        return Chat(
            chatId = chatId,
            chatType = 2,
            name = gc?.get(GroupChats.name) ?: "",
            avatar = gc?.get(GroupChats.avatar),
            creator = gc?.get(GroupChats.creator),
            memberCount = memberCount,
            maxSeq = maxSeq,
            notice = gc?.get(GroupChats.notice),
            mutedAll = gc?.get(GroupChats.mutedAll) ?: false,
        )
    }
}

private fun ResultRow.toMemberSnapshot() = Member(
    uid = this[GroupMembers.uid],
    chatId = this[GroupMembers.chatId],
    role = this[GroupMembers.role],
    nickname = this[GroupMembers.nickname],
    joinedAt = this[GroupMembers.joinedAt],
)
