package com.virjar.tk.domain.chat

import com.virjar.tk.model.InviteLink

/** Persistence port for group invite links. */
interface InviteLinkRepository {
    fun createInviteLink(chatId: String, creatorUid: String, name: String, maxUses: Int, expiresAt: Long): String
    fun listInviteLinks(chatId: String): List<InviteLinkRecord>
    fun revokeInviteLink(token: String)
    fun getInviteLink(token: String): InviteLinkRecord?
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

/** Pure invite policy reused inside the persistence transaction and covered without a database. */
fun InviteLinkRecord.requireJoinable(nowMillis: Long) {
    require(maxUses >= 0) { "邀请链接次数非法" }
    require(expiresAt >= 0) { "邀请链接过期时间非法" }
    require(revokedAt == 0L) { "邀请链接已失效" }
    require(maxUses == 0 || useCount < maxUses) { "邀请链接已用完" }
    require(expiresAt == 0L || expiresAt >= nowMillis) { "邀请链接已过期" }
}

fun InviteLinkRecord.toModel() = InviteLink(
    token = token,
    chatId = chatId,
    name = name,
    maxUses = maxUses,
    useCount = useCount,
    expiresAt = expiresAt,
    revokedAt = revokedAt,
)
