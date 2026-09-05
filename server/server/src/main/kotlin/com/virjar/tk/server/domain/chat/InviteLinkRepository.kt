package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.InviteLink

/** 群邀请链接的持久化端口。 */
interface InviteLinkRepository {
    fun createInviteLink(
        transaction: PgWriteTransactionContext,
        command: InviteLinkCreationCommand,
        authorize: (GroupCommandFacts) -> Unit,
    ): String

    fun listInviteLinks(chatId: String): List<InviteLinkRecord>
    fun revokeInviteLink(
        transaction: PgWriteTransactionContext,
        expectedChatId: String,
        operatorUid: String,
        token: String,
        nowMillis: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): InviteLinkRecord

    fun getInviteLink(token: String): InviteLinkRecord?
}

/** 一个有界的聚合预算，防止邀请历史变成无分页的 RPC/表扫描。 */
object InviteLinkPolicy {
    const val MAX_LINKS_PER_CHAT = 64
    /** 共享时间范围内的硬性计数；未过期的回执绝不会被驱逐。 */
    const val MAX_CREATION_RECEIPTS_PER_ACTOR = 256
}

data class InviteLinkCreationCommand(
    val operationId: String,
    val issuedAt: Long,
    val creatorUid: String,
    val chatId: String,
    val name: String,
    val maxUses: Int,
    val expiresAt: Long,
    val requestFingerprint: String,
)

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

/** 在持久化事务内复用、无需数据库即可覆盖的纯邀请策略。 */
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
