package com.virjar.tk.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction

data class InviteLinkRow(
    val id: Long,
    val token: String,
    val channelId: String,
    val creatorUid: String,
    val name: String?,
    val maxUses: Int?,
    val useCount: Int,
    val expiresAt: Long?,
    val revokedAt: Long?,
    val createdAt: Long,
)

object InviteLinkDao {

    fun create(
        channelId: String,
        creatorUid: String,
        token: String,
        name: String?,
        maxUses: Int?,
        expiresAt: Long?,
    ): InviteLinkRow = transaction {
        val now = System.currentTimeMillis()
        val id = GroupInviteLinks.insert {
            it[GroupInviteLinks.token] = token
            it[GroupInviteLinks.channelId] = channelId
            it[GroupInviteLinks.creatorUid] = creatorUid
            it[GroupInviteLinks.name] = name
            it[GroupInviteLinks.maxUses] = maxUses
            it[GroupInviteLinks.expiresAt] = expiresAt
            it[GroupInviteLinks.createdAt] = now
        } get GroupInviteLinks.id

        InviteLinkRow(
            id = id.value,
            token = token,
            channelId = channelId,
            creatorUid = creatorUid,
            name = name,
            maxUses = maxUses,
            useCount = 0,
            expiresAt = expiresAt,
            revokedAt = null,
            createdAt = now,
        )
    }

    fun findByToken(token: String): InviteLinkRow? = transaction {
        GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
            .map { it.toInviteLinkRow() }
            .singleOrNull()
    }

    fun findActiveByChannel(channelId: String): List<InviteLinkRow> = transaction {
        val now = System.currentTimeMillis()
        GroupInviteLinks.selectAll().where {
            (GroupInviteLinks.channelId eq channelId) and
            (GroupInviteLinks.revokedAt.isNull()) and
            (GroupInviteLinks.expiresAt.isNull().or(GroupInviteLinks.expiresAt greater now))
        }.map { it.toInviteLinkRow() }
    }

    fun countActiveByChannel(channelId: String): Int = transaction {
        val now = System.currentTimeMillis()
        GroupInviteLinks.selectAll().where {
            (GroupInviteLinks.channelId eq channelId) and
            (GroupInviteLinks.revokedAt.isNull()) and
            (GroupInviteLinks.expiresAt.isNull().or(GroupInviteLinks.expiresAt greater now))
        }.count().toInt()
    }

    fun revoke(token: String) = transaction {
        GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
            it[revokedAt] = System.currentTimeMillis()
        }
    }

    fun incrementUseCount(token: String) = transaction {
        val row = GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }.singleOrNull()
        if (row != null) {
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
                it[useCount] = row[GroupInviteLinks.useCount] + 1
            }
        }
    }

    private fun ResultRow.toInviteLinkRow() = InviteLinkRow(
        id = this[GroupInviteLinks.id].value,
        token = this[GroupInviteLinks.token],
        channelId = this[GroupInviteLinks.channelId],
        creatorUid = this[GroupInviteLinks.creatorUid],
        name = this[GroupInviteLinks.name],
        maxUses = this[GroupInviteLinks.maxUses],
        useCount = this[GroupInviteLinks.useCount],
        expiresAt = this[GroupInviteLinks.expiresAt],
        revokedAt = this[GroupInviteLinks.revokedAt],
        createdAt = this[GroupInviteLinks.createdAt],
    )
}
