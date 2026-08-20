package com.virjar.tk.http

import kotlinx.serialization.Serializable

/** Safe group-facing bot metadata. Webhook credentials are never included in list responses. */
@Serializable
data class GroupBotSummary(
    val botId: String,
    val name: String,
    val status: Int,
    val lastUsedAt: Long?,
    val createdAt: Long,
    val apiPath: String,
    val groupManaged: Boolean,
    /** The current caller created this group-scoped bot. */
    val createdByMe: Boolean,
    /** Only the creator may rotate and receive a replacement credential. */
    val canRotateToken: Boolean,
    /** Creator, group admin or owner may remove a group-scoped bot. */
    val canRemove: Boolean,
)

/** One-time credential response returned only when a group bot is created or rotated. */
@Serializable
data class GroupBotCredentials(
    val bot: GroupBotSummary,
    val webhookToken: String,
)

@Serializable
data class CreateGroupBotRequest(
    val name: String,
)
