package com.virjar.tk.domain.chat

import com.virjar.tk.model.InviteLink

/** Persistence port for group invite links. */
interface InviteLinkRepository {
    fun createInviteLink(chatId: String, creatorUid: String, name: String, maxUses: Int, expiresAt: Long): String
    fun listInviteLinks(chatId: String): List<InviteLinkRecord>
    fun revokeInviteLink(token: String)
    fun getInviteLink(token: String): InviteLinkRecord?
    fun incrementInviteUseCount(token: String)
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

fun InviteLinkRecord.toModel() = InviteLink(
    token = token,
    chatId = chatId,
    name = name,
    maxUses = maxUses,
    useCount = useCount,
    expiresAt = expiresAt,
    revokedAt = revokedAt,
)
