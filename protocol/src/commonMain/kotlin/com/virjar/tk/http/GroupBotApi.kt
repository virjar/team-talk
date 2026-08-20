package com.virjar.tk.http

import kotlinx.serialization.Serializable

/** Optional webhook request header. Reuse the same value when retrying one business event. */
const val BOT_IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/**
 * Target-bound webhook body. The destination group exists only in the URL path.
 *
 * The development protocol has one strict request shape: a JSON `chatId` is never accepted as
 * routing input and the URL remains the only authorization target.
 */
@Serializable
data class GroupBotMessageRequest(
    val markdown: String,
)

/** Minimal acknowledgement for a target-bound webhook delivery. */
@Serializable
data class GroupBotMessageResponse(
    val ok: Boolean,
)

/** Safe group-facing bot metadata. Webhook credentials are never included in list responses. */
@Serializable
data class GroupBotSummary(
    val botId: String,
    val name: String,
    val status: Int,
    val lastUsedAt: Long?,
    val createdAt: Long,
    /** Target-bound `/api/v1/groups/{chatId}/bots/{botId}/messages` path for this list context. */
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
