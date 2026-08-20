package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.InviteLinkRecord
import com.virjar.tk.domain.chat.InviteLinkRepository
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.GroupInviteLinks
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** GroupInviteLinks 表访问 + 邀请链接业务记录模型。 */
class ExposedInviteLinkRepository : InviteLinkRepository {

    override fun createInviteLink(chatId: String, creatorUid: String, name: String, maxUses: Int, expiresAt: Long): String {
        require(maxUses >= 0) { "maxUses 不能为负数" }
        require(expiresAt >= 0) { "expiresAt 不能为负数" }
        val token = UUID.randomUUID().toString()
        transaction {
            require(Chats.selectAll().where {
                (Chats.chatId eq chatId) and (Chats.status eq 1)
            }.forUpdate().singleOrNull() != null) { "聊天不存在" }
            GroupInviteLinks.insert {
                it[GroupInviteLinks.token] = token
                it[GroupInviteLinks.chatId] = chatId
                it[GroupInviteLinks.creatorUid] = creatorUid
                it[GroupInviteLinks.name] = name
                it[GroupInviteLinks.maxUses] = maxUses
                it[GroupInviteLinks.expiresAt] = expiresAt
                it[GroupInviteLinks.createdAt] = System.currentTimeMillis()
            }
        }
        return token
    }

    override fun listInviteLinks(chatId: String): List<InviteLinkRecord> {
        return transaction {
            GroupInviteLinks.selectAll()
                .where { GroupInviteLinks.chatId eq chatId }
                .orderBy(GroupInviteLinks.createdAt, SortOrder.DESC)
                .map { it.toInviteLinkRecord() }
        }
    }

    override fun revokeInviteLink(token: String) {
        transaction {
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
                it[GroupInviteLinks.revokedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun getInviteLink(token: String): InviteLinkRecord? {
        return transaction {
            GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
                .map { it.toInviteLinkRecord() }.singleOrNull()
        }
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
