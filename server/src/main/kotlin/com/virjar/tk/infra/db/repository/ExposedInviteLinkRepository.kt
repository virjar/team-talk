package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.InviteLinkRecord
import com.virjar.tk.domain.chat.InviteLinkRepository
import com.virjar.tk.domain.chat.GroupCommandFacts
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupInviteLinks
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import com.virjar.tk.model.UserRole
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** GroupInviteLinks 表访问 + 邀请链接业务记录模型。 */
class ExposedInviteLinkRepository : InviteLinkRepository {

    override fun createInviteLink(
        transaction: PgTransactionContext,
        chatId: String,
        creatorUid: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): String = inWriteTransaction(transaction) {
        require(maxUses >= 0) { "maxUses 不能为负数" }
        require(expiresAt >= 0) { "expiresAt 不能为负数" }
        val chatRow = lockActiveGroup(chatId)
        lockRequiredHumanUser(creatorUid)
        GroupInviteLinks.selectAll().where { GroupInviteLinks.chatId eq chatId }
            .orderBy(GroupInviteLinks.token, SortOrder.ASC)
            .forUpdate()
            .toList()
        val members = lockActiveMembers(chatId)
        authorize(
            GroupCommandFacts(
                chat = chatSnapshot(chatRow, members.size),
                operator = members.firstOrNull { it.uid == creatorUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        val token = UUID.randomUUID().toString()
        GroupInviteLinks.insert {
            it[GroupInviteLinks.token] = token
            it[GroupInviteLinks.chatId] = chatId
            it[GroupInviteLinks.creatorUid] = creatorUid
            it[GroupInviteLinks.name] = name
            it[GroupInviteLinks.maxUses] = maxUses
            it[GroupInviteLinks.expiresAt] = expiresAt
            it[GroupInviteLinks.createdAt] = System.currentTimeMillis()
        }
        token
    }

    override fun listInviteLinks(chatId: String): List<InviteLinkRecord> {
        return transaction {
            GroupInviteLinks.selectAll()
                .where { GroupInviteLinks.chatId eq chatId }
                .orderBy(GroupInviteLinks.createdAt, SortOrder.DESC)
                .map { it.toInviteLinkRecord() }
        }
    }

    override fun revokeInviteLink(
        transaction: PgTransactionContext,
        expectedChatId: String,
        operatorUid: String,
        token: String,
        nowMillis: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): InviteLinkRecord = inWriteTransaction(transaction) {
        val chatRow = lockActiveGroup(expectedChatId)
        lockRequiredHumanUser(operatorUid)
        val inviteRow = GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("邀请链接不存在")
        require(inviteRow[GroupInviteLinks.chatId] == expectedChatId) { "邀请链接归属已变更" }
        val members = lockActiveMembers(expectedChatId)
        authorize(
            GroupCommandFacts(
                chat = chatSnapshot(chatRow, members.size),
                operator = members.firstOrNull { it.uid == operatorUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
            it[GroupInviteLinks.revokedAt] = nowMillis
        }
        inviteRow.toInviteLinkRecord().copy(revokedAt = nowMillis)
    }

    override fun getInviteLink(token: String): InviteLinkRecord? {
        return transaction {
            GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
                .map { it.toInviteLinkRecord() }.singleOrNull()
        }
    }

    private fun lockActiveGroup(chatId: String): ResultRow {
        val row = Chats.selectAll().where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("聊天不存在")
        require(row[Chats.status] == 1 && row[Chats.chatType] == 2) { "群聊不存在" }
        require(GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull() != null) {
            "群聊数据不完整"
        }
        return row
    }

    private fun lockRequiredHumanUser(uid: String) {
        val row = Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull()
            ?: throw IllegalArgumentException("用户不存在")
        require(row[Users.status] == 1) { "用户已停用" }
        require(row[Users.role] == UserRole.HUMAN) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
    }

    private fun lockActiveMembers(chatId: String): List<Member> = GroupMembers.selectAll().where {
        (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
    }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().map { row ->
        Member(
            uid = row[GroupMembers.uid],
            chatId = row[GroupMembers.chatId],
            role = row[GroupMembers.role],
            nickname = row[GroupMembers.nickname],
            joinedAt = row[GroupMembers.joinedAt],
        )
    }

    private fun chatSnapshot(chatRow: ResultRow, memberCount: Int): Chat {
        val chatId = chatRow[Chats.chatId]
        val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.single()
        return Chat(
            chatId = chatId,
            chatType = chatRow[Chats.chatType],
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

internal fun ResultRow.toInviteLinkRecord() = InviteLinkRecord(
    token = this[GroupInviteLinks.token],
    chatId = this[GroupInviteLinks.chatId],
    creatorUid = this[GroupInviteLinks.creatorUid],
    name = this[GroupInviteLinks.name],
    maxUses = this[GroupInviteLinks.maxUses],
    useCount = this[GroupInviteLinks.useCount],
    expiresAt = this[GroupInviteLinks.expiresAt],
    revokedAt = this[GroupInviteLinks.revokedAt],
    createdAt = this[GroupInviteLinks.createdAt],
)
