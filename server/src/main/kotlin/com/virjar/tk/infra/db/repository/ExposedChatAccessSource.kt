package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.ChatAccessSnapshot
import com.virjar.tk.domain.chat.ChatAccessSource
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection

/**
 * PostgreSQL-backed authorization source. Every call bypasses [com.virjar.tk.domain.chat.ChatStore]
 * so membership removal or chat deactivation committed by another server is visible immediately.
 */
class ExposedChatAccessSource(
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatAccessSource {
    override suspend fun load(chatId: String, memberUids: Set<String>): ChatAccessSnapshot =
        authorizationRead { readSnapshot(chatId, memberUids) }

    override suspend fun loadAllMembers(chatId: String): ChatAccessSnapshot =
        authorizationRead { readSnapshot(chatId, requestedMemberUids = null) }

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
        val activeChatIds = membershipChats.select(GroupMembers.chatId).where {
            (GroupMembers.uid eq uid) and
                (GroupMembers.status eq ACTIVE) and
                (Chats.status eq ACTIVE)
        }.mapTo(linkedSetOf()) { it[GroupMembers.chatId] }
        if (activeChatIds.isEmpty()) return emptySet()

        // Negative desired state is also a fence. A correctly applied negative projection makes
        // Chats inactive, but retaining this condition keeps corrupted/partially restored data
        // fail-closed instead of reviving access.
        val deniedManagedChatIds = OrganizationManagedChatProjections.selectAll().where {
            (OrganizationManagedChatProjections.desiredActive eq false) or
                (OrganizationManagedChatProjections.desiredRevision neq
                    OrganizationManagedChatProjections.appliedRevision) or
                OrganizationManagedChatProjections.lastFailure.isNotNull()
        }.mapTo(hashSetOf()) { it[OrganizationManagedChatProjections.chatId] }

        return activeChatIds.filterTo(linkedSetOf()) { it !in deniedManagedChatIds }
    }

    private fun readSnapshot(
        chatId: String,
        requestedMemberUids: Set<String>?,
    ): ChatAccessSnapshot {
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
        }.count().toInt()
        val chat = chatRow.toChat(groupRow, memberCount)

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

    private fun activeMembers(chatId: String): List<Member> =
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq ACTIVE)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).map { row -> row.toMember() }

    private suspend fun <T> authorizationRead(block: suspend () -> T): T =
        newSuspendedTransaction(
            context = dbDispatcher,
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
