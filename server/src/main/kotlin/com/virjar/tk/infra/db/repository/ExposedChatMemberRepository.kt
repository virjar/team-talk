package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.domain.chat.GroupMemberAddition
import com.virjar.tk.domain.chat.GroupMemberAdditionFacts
import com.virjar.tk.domain.chat.GroupMemberRemoval
import com.virjar.tk.domain.chat.GroupMemberRemovalFacts
import com.virjar.tk.domain.chat.LockedChat
import com.virjar.tk.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * GroupMembers / GroupMemberMutes 表访问。
 *
 * 注意：[setMuteAll] 操作的是 GroupChats.mutedAll 字段（全群禁言开关），
 * 语义上归属"禁言管理"，因此放在本类而非 [ChatRepository]。
 */
class ExposedChatMemberRepository : ChatMemberRepository {

    // ── 成员查询 ──

    override fun getMembers(chatId: String): List<Member> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it.toMember() }
        }
    }

    override fun getMember(chatId: String, uid: String): Member? {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .map { it.toMember() }.singleOrNull()
        }
    }

    override fun getMemberUids(chatId: String): List<String> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    override fun getActiveChatIds(uid: String): Set<String> = transaction {
        GroupMembers.selectAll()
            .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
            .mapTo(linkedSetOf()) { it[GroupMembers.chatId] }
    }

    override fun getActiveChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        inWriteTransaction(transaction) {
            GroupMembers.selectAll()
                .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .mapTo(linkedSetOf()) { it[GroupMembers.chatId] }
        }

    override fun getProjectedChatIds(uid: String): Set<String> = transaction {
        projectedChatIdsInternal(uid)
    }

    override fun getProjectedChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        inWriteTransaction(transaction) { projectedChatIdsInternal(uid) }

    override fun lockChats(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = inWriteTransaction(transaction) {
        val requested = chatIds.distinct().sorted()
        if (requested.isEmpty()) return@inWriteTransaction emptyMap()
        val rows = Chats.selectAll()
            .where { Chats.chatId inList requested }
            .orderBy(Chats.chatId, SortOrder.ASC)
            .forUpdate()
            .toList()
        if (requireActive) require(rows.size == requested.size) { "聊天不存在" }
        if (requireActive) {
            require(rows.all { it[Chats.status] == 1 }) { "聊天不存在" }
        }
        rows.associate { row ->
            row[Chats.chatId] to LockedChat(
                chat = chatSnapshot(row, memberCount = 0),
                active = row[Chats.status] == 1,
            )
        }
    }

    override fun getActiveMember(
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = inWriteTransaction(transaction) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.singleOrNull()?.toMember()
    }

    override fun isMember(chatId: String, uid: String): Boolean {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .count() > 0
        }
    }

    // ── 成员变更 ──

    override fun addMembers(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = inWriteTransaction(transaction) {
        val requestedUids = uids.distinct()
        val chatRow = lockActiveChat(chatId)
        val activeMembers = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate()
            .map(ResultRow::toMember)
        val membersByUid = activeMembers.associateBy(Member::uid)
        val before = chatSnapshot(chatRow, activeMembers.size)
        authorize(
            GroupMemberAdditionFacts(
                chat = before,
                operator = membersByUid[operatorUid],
                requestedUids = requestedUids,
            ),
        )

        val now = System.currentTimeMillis()
        val addedUids = ArrayList<String>(requestedUids.size)
        for (uid in requestedUids) {
            val existing = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
            }.forUpdate().singleOrNull()
            when {
                existing == null -> {
                    GroupMembers.insert {
                        it[GroupMembers.chatId] = chatId
                        it[GroupMembers.chatType] = before.chatType
                        it[GroupMembers.uid] = uid
                        it[GroupMembers.role] = 0
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                    addedUids += uid
                }

                existing[GroupMembers.status] != 1 -> {
                    GroupMembers.update({
                        (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                    }) {
                        // A normal/system reconciliation reactivation is a fresh ordinary role.
                        // Explicit managed roles are assigned through the dedicated role command.
                        it[GroupMembers.role] = 0
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                    addedUids += uid
                }
            }
            Conversations.insertIgnore {
                it[Conversations.uid] = uid
                it[Conversations.chatId] = chatId
                it[Conversations.chatType] = before.chatType
                it[Conversations.lastMsgSeq] = 0
                it[Conversations.updatedAt] = now
            }
        }

        val activeUids = (activeMembers.asSequence().map(Member::uid) + addedUids.asSequence())
            .distinct()
            .sorted()
            .toList()
        GroupMemberAddition(
            chat = before.copy(memberCount = activeUids.size),
            addedUids = addedUids,
            activeMemberUids = activeUids,
        )
    }

    override fun removeMember(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval = removeMemberInternal(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = targetUid,
        allowMissing = false,
        authorize = authorize,
    ) ?: error("Required membership removal unexpectedly became a no-op")

    override fun removeMemberIfPresent(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        requireActiveChat: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = removeMemberInternal(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = targetUid,
        allowMissing = true,
        requireActiveChat = requireActiveChat,
        authorize = authorize,
    )

    override fun cleanupServiceMemberProjection(
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = inWriteTransaction(transaction) {
        // The owning bot command locked every existing Chat before its service identity and bot.
        // Never re-query a missing Chat here: if the id were concurrently recreated, taking that
        // new row after the User/Bot locks would invert the global Chat -> User -> Bot order.
        val activeMembers = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().toList()
        val activeByUid = activeMembers.associateBy { it[GroupMembers.uid] }
        val targetRow = activeByUid[uid] ?: GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.forUpdate().singleOrNull()
        val conversationRow = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        }.forUpdate().singleOrNull()
        val muteRows = GroupMemberMutes.selectAll().where {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
        }.forUpdate().toList()
        require(targetRow?.get(GroupMembers.role) != 2) {
            "服务身份不能作为群主，拒绝清理损坏的成员投影"
        }

        val membershipDeactivated = targetRow?.get(GroupMembers.status) == 1 &&
            GroupMembers.update({
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }) { it[GroupMembers.status] = 0 } > 0
        val conversationDeleted = conversationRow != null && Conversations.deleteWhere {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        } > 0
        val muteDeleted = muteRows.isNotEmpty() && GroupMemberMutes.deleteWhere {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
        } > 0
        if (!membershipDeactivated && !conversationDeleted && !muteDeleted) {
            return@inWriteTransaction null
        }

        val chatType = when {
            lockedChat != null -> lockedChat.chat.chatType
            targetRow != null -> targetRow[GroupMembers.chatType]
            conversationRow != null -> conversationRow[Conversations.chatType]
            else -> 0
        }
        val remainingUids = activeMembers.asSequence()
            .map { it[GroupMembers.uid] }
            .filter { it != uid }
            .toList()
        val chat = if (lockedChat != null) {
            lockedChat.chat.copy(memberCount = remainingUids.size)
        } else {
            Chat(chatId = chatId, chatType = chatType, memberCount = remainingUids.size)
        }
        ServiceMemberProjectionCleanup(
            chat = chat,
            membershipDeactivated = membershipDeactivated,
            conversationDeleted = conversationDeleted,
            muteDeleted = muteDeleted,
            remainingMemberUids = remainingUids,
        )
    }

    private fun removeMemberInternal(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        allowMissing: Boolean,
        requireActiveChat: Boolean = true,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = inWriteTransaction(transaction) {
        val chatRow = lockChat(chatId, requireActiveChat)
        val activeMembers = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate()
            .map(ResultRow::toMember)
        val membersByUid = activeMembers.associateBy(Member::uid)
        val chat = chatSnapshot(chatRow, activeMembers.size)

        authorize(
            GroupMemberRemovalFacts(
                chat = chat,
                operator = membersByUid[operatorUid],
                target = membersByUid[targetUid],
            ),
        )

        if (membersByUid[targetUid] == null && allowMissing) return@inWriteTransaction null

        val changed = GroupMembers.update({
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq targetUid) and
                (GroupMembers.status eq 1)
        }) { it[GroupMembers.status] = 0 }
        check(changed == 1) { "Locked target membership changed before removal" }
        Conversations.deleteWhere {
            (Conversations.chatId eq chatId) and (Conversations.uid eq targetUid)
        }
        GroupMemberMutes.deleteWhere {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq targetUid)
        }

        val remainingUids = activeMembers.asSequence()
            .map(Member::uid)
            .filter { it != targetUid }
            .toList()
        GroupMemberRemoval(
            chat = chat.copy(memberCount = remainingUids.size),
            remainingMemberUids = remainingUids,
        )
    }

    override fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String) {
        transaction {
            lockActiveChat(chatId)
            val members = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid inList listOf(oldOwnerUid, newOwnerUid)) and
                    (GroupMembers.status eq 1)
            }.associateBy { it[GroupMembers.uid] }
            require(members[oldOwnerUid]?.get(GroupMembers.role) == 2) { "操作者不是群主" }
            require(members.containsKey(newOwnerUid)) { "目标不是群成员" }
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq oldOwnerUid) }) {
                it[GroupMembers.role] = 1
            }
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq newOwnerUid) }) {
                it[GroupMembers.role] = 2
            }
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.creator] = newOwnerUid }
        }
    }

    override fun setRole(chatId: String, uid: String, role: Int) {
        transaction {
            lockActiveChat(chatId)
            val member = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }.singleOrNull() ?: throw IllegalArgumentException("目标不是群成员")
            require(member[GroupMembers.role] != 2 || role == 2) {
                "不能直接修改群主角色，请使用转让群主"
            }
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) }) {
                it[GroupMembers.role] = role
            }
            if (role == 2) {
                GroupChats.update({ GroupChats.chatId eq chatId }) {
                    it[GroupChats.creator] = uid
                }
            }
        }
    }

    // ── 禁言（单成员 / 全群） ──

    override fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long) {
        transaction {
            lockActiveChat(chatId)
            require(GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }.count() == 1L) { "目标不是群成员" }
            GroupMemberMutes.upsert(GroupMemberMutes.chatId, GroupMemberMutes.uid) {
                it[GroupMemberMutes.chatId] = chatId
                it[GroupMemberMutes.uid] = uid
                it[GroupMemberMutes.operatorUid] = operatorUid
                it[GroupMemberMutes.expiresAt] = expiresAt
                it[GroupMemberMutes.createdAt] = System.currentTimeMillis()
            }
        }
    }

    override fun unmuteMember(chatId: String, uid: String) {
        transaction {
            lockActiveChat(chatId)
            GroupMemberMutes.deleteWhere {
                (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
            }
        }
    }

    override fun isMuted(chatId: String, uid: String): Boolean {
        return transaction {
            val now = System.currentTimeMillis()
            GroupMemberMutes.selectAll()
                .where { (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid) and (GroupMemberMutes.expiresAt greater now) }
                .count() > 0
        }
    }

    override fun setMuteAll(chatId: String, mutedAll: Boolean) {
        transaction {
            lockActiveChat(chatId)
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.mutedAll] = mutedAll }
        }
    }

    override fun getMutedMembers(chatId: String): List<String> {
        return transaction {
            val now = System.currentTimeMillis()
            GroupMemberMutes.selectAll()
                .where { (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.expiresAt greater now) }
                .map { it[GroupMemberMutes.uid] }
        }
    }

    private fun lockActiveChat(chatId: String): ResultRow = lockChat(chatId, requireActive = true)

    private fun projectedChatIdsInternal(uid: String): Set<String> = buildSet {
        addAll(
            GroupMembers.selectAll().where {
                (GroupMembers.uid eq uid) and (GroupMembers.status eq 1)
            }.map { it[GroupMembers.chatId] },
        )
        addAll(
            Conversations.selectAll().where { Conversations.uid eq uid }
                .map { it[Conversations.chatId] },
        )
        addAll(
            GroupMemberMutes.selectAll().where { GroupMemberMutes.uid eq uid }
                .map { it[GroupMemberMutes.chatId] },
        )
    }

    private fun lockChat(chatId: String, requireActive: Boolean): ResultRow {
        val row = Chats.selectAll().where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull() ?: throw IllegalArgumentException("聊天不存在")
        if (requireActive) require(row[Chats.status] == 1) { "聊天不存在" }
        return row
    }

    private fun chatSnapshot(chatRow: ResultRow, memberCount: Int): Chat {
        val chatId = chatRow[Chats.chatId]
        val chatType = chatRow[Chats.chatType]
        if (chatType == 1) {
            return Chat(chatId = chatId, chatType = chatType, maxSeq = chatRow[Chats.maxSeq])
        }
        val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
            ?: throw IllegalStateException("群聊数据不完整")
        return Chat(
            chatId = chatId,
            chatType = chatType,
            name = group[GroupChats.name],
            avatar = group[GroupChats.avatar],
            creator = group[GroupChats.creator],
            memberCount = memberCount,
            maxSeq = chatRow[Chats.maxSeq],
            notice = group[GroupChats.notice],
            mutedAll = group[GroupChats.mutedAll],
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

private fun ResultRow.toMember() = Member(
    uid = this[GroupMembers.uid],
    chatId = this[GroupMembers.chatId],
    role = this[GroupMembers.role],
    nickname = this[GroupMembers.nickname],
    joinedAt = this[GroupMembers.joinedAt],
)
