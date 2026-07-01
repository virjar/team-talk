package com.virjar.tk.domain.chat

import com.virjar.tk.infra.db.GroupInviteLinks
import com.virjar.tk.model.InviteLink
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** GroupInviteLinks 表访问 + 邀请链接业务记录模型。 */
class InviteLinkRepository {

    fun createInviteLink(chatId: String, creatorUid: String, name: String, maxUses: Int, expiresAt: Long): String {
        val token = UUID.randomUUID().toString()
        transaction {
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

    fun listInviteLinks(chatId: String): List<InviteLinkRecord> {
        return transaction {
            GroupInviteLinks.selectAll()
                .where { GroupInviteLinks.chatId eq chatId }
                .orderBy(GroupInviteLinks.createdAt, SortOrder.DESC)
                .map { it.toInviteLink() }
        }
    }

    fun revokeInviteLink(token: String) {
        transaction {
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
                it[GroupInviteLinks.revokedAt] = System.currentTimeMillis()
            }
        }
    }

    fun getInviteLink(token: String): InviteLinkRecord? {
        return transaction {
            GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
                .map { it.toInviteLink() }.singleOrNull()
        }
    }

    /**
     * 原子自增使用次数（UPDATE ... SET useCount = useCount + 1）。
     * 避免读-改-写竞态导致并发邀请时丢失计数 / 超出 maxUses。
     */
    fun incrementInviteUseCount(token: String) {
        transaction {
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[GroupInviteLinks.useCount] = GroupInviteLinks.useCount + 1
                }
            }
        }
    }
}

data class InviteLinkRecord(
    val token: String,
    val chatId: String,
    val creatorUid: String,
    val name: String,
    val maxUses: Int,
    val useCount: Int,
    val expiresAt: Long,
    val revokedAt: Long,
    val createdAt: Long,
)

internal fun ResultRow.toInviteLink() = InviteLinkRecord(
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

fun InviteLinkRecord.toModel() = InviteLink(
    token = token,
    chatId = chatId,
    name = name,
    maxUses = maxUses,
    useCount = useCount,
    expiresAt = expiresAt,
    revokedAt = revokedAt,
)
