package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ReliableCommandContract
import java.util.UUID

enum class PendingContactDecisionType(val code: Long) {
    ACCEPT(1),
    REJECT(2),
    ;

    companion object {
        fun fromCode(code: Long): PendingContactDecisionType = entries.firstOrNull { it.code == code }
            ?: throw IllegalStateException("Persisted contact decision type is invalid")
    }
}

/** 保留到确定响应的、deployment/account 范围的好友申请决策。 */
data class PendingContactDecision(
    val operationId: String,
    val token: String,
    val decision: PendingContactDecisionType,
    val createdAt: Long,
) {
    fun hasSamePayload(other: PendingContactDecision): Boolean =
        token == other.token && decision == other.decision

    fun requireCanonical(): PendingContactDecision {
        check(operationId.isCanonicalUuid()) { "Pending contact decision operation id is invalid" }
        check(token.isCanonicalUuid()) { "Pending contact decision token is invalid" }
        check(createdAt >= 0L) { "Pending contact decision timestamp is invalid" }
        return this
    }
}

/** 保留到确定响应的、deployment/account 范围的邀请链接创建。 */
data class PendingInviteLinkCreation(
    val operationId: String,
    val chatId: String,
    val name: String,
    val maxUses: Int,
    val expiresAt: Long,
    val createdAt: Long,
) {
    fun hasSamePayload(other: PendingInviteLinkCreation): Boolean =
        chatId == other.chatId && name == other.name && maxUses == other.maxUses &&
            expiresAt == other.expiresAt

    fun requireCanonical(): PendingInviteLinkCreation {
        check(operationId.isCanonicalUuid()) { "Pending invite creation operation id is invalid" }
        check(chatId.isCanonicalUuid()) { "Pending invite creation chat id is invalid" }
        check(name == name.trim() && name.length <= MAX_INVITE_LINK_NAME_LENGTH) {
            "Pending invite creation name is invalid"
        }
        check(name.none(Char::isISOControl)) { "Pending invite creation name contains controls" }
        check(maxUses >= 0 && expiresAt >= 0L && createdAt >= 0L) {
            "Pending invite creation capacity is invalid"
        }
        return this
    }
}

class PendingReliableCommandConflictException(message: String) : IllegalStateException(message)

/** 服务器必须拒绝一条保留社交命令的最早墙钟时刻。 */
internal fun nextReliableSocialCommandExpiryAt(
    contactDecisions: List<PendingContactDecision>,
    inviteLinkCreations: List<PendingInviteLinkCreation>,
): Long? {
    var earliest: Long? = null
    fun include(issuedAt: Long) {
        val expiryAt = ReliableCommandContract.firstExpiredAt(issuedAt)
        earliest = earliest?.let { minOf(it, expiryAt) } ?: expiryAt
    }
    contactDecisions.forEach { include(it.createdAt) }
    inviteLinkCreations.forEach { include(it.createdAt) }
    return earliest
}

internal fun String.isCanonicalUuid(): Boolean =
    length == UUID_TEXT_LENGTH && runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

internal const val MAX_PENDING_CONTACT_DECISIONS = 128
internal const val MAX_PENDING_INVITE_LINK_CREATIONS = 128
internal const val MAX_INVITE_LINK_NAME_LENGTH = 200
private const val UUID_TEXT_LENGTH = 36
