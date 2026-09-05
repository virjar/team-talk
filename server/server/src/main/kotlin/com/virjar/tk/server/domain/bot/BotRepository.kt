package com.virjar.tk.server.domain.bot

import com.virjar.tk.server.domain.chat.RequiredChatParticipants
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import kotlinx.serialization.Serializable

@Serializable
data class AutomationBot(
    val botId: String,
    val userUid: String,
    val name: String,
    val status: Int,
    /** 群创建机器人所属的群；null 表示系统管理员管理的全局机器人。 */
    val managedChatId: String? = null,
    /** 群创建机器人的人类创建者；null 表示系统管理员管理的全局机器人。 */
    val createdByUid: String? = null,
    val grantedChatIds: List<String> = emptyList(),
    val lastUsedAt: Long? = null,
    val createdAt: Long,
) {
    companion object {
        const val STATUS_DISABLED = 0
        const val STATUS_ACTIVE = 1
    }
}

/** 一次凭证变更的无秘密持久化身份。 */
data class BotCredentialCommandReceipt(
    val actorUid: String,
    val operationId: String,
    val commandKind: Int,
    val chatId: String,
    val botId: String,
    /** 在回执中重复记录，使重放能够保持"服务身份先于机器人"的锁定顺序。 */
    val botUserUid: String,
    val requestFingerprint: String,
    val tokenHash: String,
    val createdAt: Long,
) {
    companion object {
        const val KIND_CREATE = 1
        const val KIND_ROTATE = 2
    }
}

interface BotRepository : RequiredChatParticipants {
    fun create(transaction: PgWriteTransactionContext, bot: AutomationBot, tokenHash: String): AutomationBot
    fun list(): List<AutomationBot>
    fun listForChat(chatId: String): List<AutomationBot>
    fun countActiveManagedForChat(transaction: PgReadTransactionContext, chatId: String): Long
    fun countActiveManagedForCreator(transaction: PgReadTransactionContext, createdByUid: String): Long
    fun countActiveManagedForCreatorInChat(
        transaction: PgReadTransactionContext,
        createdByUid: String,
        chatId: String,
    ): Long
    /** 跨服务器进程串行化一个创建者的全局配额。 */
    fun lockCreatorQuota(transaction: PgWriteTransactionContext, createdByUid: String)
    /** 在获取所属机器人行之前锁定并校验 BOT/SYSTEM 身份。 */
    fun lockServiceIdentity(transaction: PgWriteTransactionContext, userUid: String)
    fun isServiceIdentity(userUid: String): Boolean
    fun find(botId: String): AutomationBot?
    /** 锁定机器人状态行，并从同一个聚合事务返回授权列表。 */
    fun findForUpdate(transaction: PgWriteTransactionContext, botId: String): AutomationBot?
    fun findByTokenHash(tokenHash: String): AutomationBot?
    /** 在 [findForUpdate] 之后调用，以便在同一已锁定快照中比对出示的凭据。 */
    fun tokenMatches(transaction: PgReadTransactionContext, botId: String, tokenHash: String): Boolean
    fun updateTokenHash(transaction: PgWriteTransactionContext, botId: String, tokenHash: String)

    /** 调用方先锁定人类操作者行；该行串行化了"回执不存在"时的创建。 */
    fun findCredentialCommandForUpdate(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        operationId: String,
    ): BotCredentialCommandReceipt?

    fun createCredentialCommand(
        transaction: PgWriteTransactionContext,
        receipt: BotCredentialCommandReceipt,
    )

    fun countCredentialCommandsForBot(transaction: PgReadTransactionContext, botId: String): Long

    fun setStatus(transaction: PgWriteTransactionContext, botId: String, status: Int)
    fun touch(transaction: PgWriteTransactionContext, botId: String, timestamp: Long)

    fun grant(transaction: PgWriteTransactionContext, botId: String, chatId: String): Boolean
    fun revokeGrant(transaction: PgWriteTransactionContext, botId: String, chatId: String): Boolean
    fun listGrants(botId: String): List<String>
    fun isGranted(botId: String, chatId: String): Boolean

    override fun deactivateForChat(transaction: PgWriteTransactionContext, chatId: String)
}
