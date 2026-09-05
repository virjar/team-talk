package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.chat.ChatAccessSnapshot
import com.virjar.tk.server.domain.chat.ChatAccessSource
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.Member
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection

/**
 * 由 PostgreSQL 支撑的授权源。每次调用都绕过 [com.virjar.tk.server.domain.chat.ChatStore]，
 * 使其他服务器提交的成员移除或聊天停用立即可见。
 */
class ExposedChatAccessSource(
    private val database: Database,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxAccessibleChats: Int = ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER,
) : ChatAccessSource {
    init {
        require(maxAccessibleChats in 1..ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER) {
            "accessible chat capacity is out of range"
        }
    }

    override suspend fun load(chatId: String, memberUids: Set<String>): ChatAccessSnapshot =
        authorizationRead { readSnapshot(chatId, memberUids) }

    override suspend fun listAccessibleChatIds(uid: String): Set<String> =
        authorizationRead { accessibleChatIds(uid) }

    override suspend fun <T> read(
        chatId: String,
        memberUids: Set<String>,
        includeAllMembers: Boolean,
        block: (ChatAccessSnapshot) -> T,
    ): T = authorizationRead {
        block(readSnapshot(chatId, if (includeAllMembers) null else memberUids))
    }

    override suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
        authorizationRead { block(accessibleChatIds(uid)) }

    private fun accessibleChatIds(uid: String): Set<String> {
        val membershipChats = GroupMembers.join(
            otherTable = Chats,
            joinType = JoinType.INNER,
            onColumn = GroupMembers.chatId,
            otherColumn = Chats.chatId,
        )
        val activeChatIds = membershipChats.select(GroupMembers.chatId)
            .where {
                (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq ACTIVE) and
                    (Chats.status eq ACTIVE)
            }
            .orderBy(GroupMembers.chatId, SortOrder.ASC)
            .limit(maxAccessibleChats + 1)
            .mapTo(linkedSetOf()) { it[GroupMembers.chatId] }
        check(activeChatIds.size <= maxAccessibleChats) {
            "Accessible chat projection exceeds the per-user conversation capacity"
        }
        if (activeChatIds.isEmpty()) return emptySet()

        // 负向期望状态也是一道 fence。正确应用的负向投影会使
        // Chats 停用，但保留此条件可让损坏/部分恢复的数据
        // fail-closed，而不是复活访问。
        val deniedManagedChatIds = OrganizationManagedChatProjections.selectAll().where {
            (OrganizationManagedChatProjections.chatId inList activeChatIds.toList()) and
                (
                    (OrganizationManagedChatProjections.desiredActive eq false) or
                        (OrganizationManagedChatProjections.desiredRevision neq
                            OrganizationManagedChatProjections.appliedRevision) or
                        OrganizationManagedChatProjections.lastFailure.isNotNull()
                    )
        }.mapTo(hashSetOf()) { it[OrganizationManagedChatProjections.chatId] }

        return activeChatIds.filterTo(linkedSetOf()) { it !in deniedManagedChatIds }
    }

    private fun readSnapshot(
        chatId: String,
        requestedMemberUids: Set<String>?,
    ): ChatAccessSnapshot {
        require(requestedMemberUids == null || requestedMemberUids.size <= GroupPolicy.MAX_MEMBERS) {
            "Requested member projection exceeds the group capacity"
        }
        if (!projectionAllowsRead(chatId)) return ChatAccessSnapshot(chat = null)

        val chatRow = Chats.selectAll().where {
            (Chats.chatId eq chatId) and (Chats.status eq ACTIVE)
        }.singleOrNull() ?: return ChatAccessSnapshot(chat = null)

        val chatType = chatRow[Chats.chatType]
        val groupRow = if (chatType == GROUP_CHAT_TYPE) {
            GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
                ?: return ChatAccessSnapshot(chat = null)
        } else {
            null
        }
        val memberCount = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq ACTIVE)
        }.count()
        check(memberCount <= GroupPolicy.MAX_MEMBERS.toLong()) {
            "Active chat membership exceeds the group capacity"
        }
        val chat = chatRow.toChat(groupRow, memberCount.toInt())

        val members = when {
            requestedMemberUids == null -> activeMembers(chatId)
            requestedMemberUids.isEmpty() -> emptyList()
            else -> GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid inList requestedMemberUids) and
                    (GroupMembers.status eq ACTIVE)
            }.orderBy(GroupMembers.uid, SortOrder.ASC).map { row -> row.toMember() }
        }
        return ChatAccessSnapshot(chat, members)
    }

    private fun projectionAllowsRead(chatId: String): Boolean {
        val row = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.chatId eq chatId
        }.singleOrNull() ?: return true
        return row[OrganizationManagedChatProjections.desiredActive] &&
            row[OrganizationManagedChatProjections.desiredRevision] ==
            row[OrganizationManagedChatProjections.appliedRevision] &&
            row[OrganizationManagedChatProjections.lastFailure] == null
    }

    private fun activeMembers(chatId: String): List<Member> {
        val rows = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq ACTIVE)
        }.orderBy(GroupMembers.uid, SortOrder.ASC)
            .limit(GroupPolicy.MAX_MEMBERS + 1)
            .map { row -> row.toMember() }
        check(rows.size <= GroupPolicy.MAX_MEMBERS) {
            "Active chat membership exceeds the group capacity"
        }
        return rows
    }

    private suspend fun <T> authorizationRead(block: suspend () -> T): T =
        newSuspendedTransaction(
            context = dbDispatcher,
            db = database,
            transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
            readOnly = true,
        ) {
            maxAttempts = 1
            block()
        }

    private fun ResultRow.toChat(groupRow: ResultRow?, memberCount: Int): Chat {
        val chatId = this[Chats.chatId]
        return Chat(
            chatId = chatId,
            chatType = this[Chats.chatType],
            name = groupRow?.get(GroupChats.name),
            avatar = groupRow?.get(GroupChats.avatar),
            creator = groupRow?.get(GroupChats.creator),
            memberCount = memberCount,
            maxSeq = this[Chats.maxSeq],
            notice = groupRow?.get(GroupChats.notice),
            mutedAll = groupRow?.get(GroupChats.mutedAll) ?: false,
        )
    }

    private fun ResultRow.toMember() = Member(
        uid = this[GroupMembers.uid],
        chatId = this[GroupMembers.chatId],
        role = this[GroupMembers.role],
        nickname = this[GroupMembers.nickname],
        joinedAt = this[GroupMembers.joinedAt],
    )

    private companion object {
        const val ACTIVE = 1
        const val GROUP_CHAT_TYPE = 2
    }
}
