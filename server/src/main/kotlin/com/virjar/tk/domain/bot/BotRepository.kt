package com.virjar.tk.domain.bot

import com.virjar.tk.domain.chat.RequiredChatParticipants
import com.virjar.tk.domain.transaction.PgTransactionContext
import kotlinx.serialization.Serializable

@Serializable
data class AutomationBot(
    val botId: String,
    val userUid: String,
    val name: String,
    val status: Int,
    /** Owning group for a group-created bot; null denotes a system-admin managed global bot. */
    val managedChatId: String? = null,
    /** Human creator for a group-created bot; null denotes a system-admin managed global bot. */
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

interface BotRepository : RequiredChatParticipants {
    fun create(transaction: PgTransactionContext, bot: AutomationBot, tokenHash: String): AutomationBot
    fun list(): List<AutomationBot>
    fun listForChat(chatId: String): List<AutomationBot>
    fun countActiveManagedForChat(transaction: PgTransactionContext, chatId: String): Long
    fun countActiveManagedForCreator(transaction: PgTransactionContext, createdByUid: String): Long
    fun countActiveManagedForCreatorInChat(
        transaction: PgTransactionContext,
        createdByUid: String,
        chatId: String,
    ): Long
    /** Serialize a creator's global quota across server processes. */
    fun lockCreatorQuota(transaction: PgTransactionContext, createdByUid: String)
    /** Lock and validate BOT/SYSTEM identity before acquiring the owning bot row. */
    fun lockServiceIdentity(transaction: PgTransactionContext, userUid: String)
    fun isServiceIdentity(userUid: String): Boolean
    fun find(botId: String): AutomationBot?
    /** Locks the bot status row and returns grants from the same aggregate transaction. */
    fun findForUpdate(transaction: PgTransactionContext, botId: String): AutomationBot?
    fun findByTokenHash(tokenHash: String): AutomationBot?
    fun updateTokenHash(transaction: PgTransactionContext, botId: String, tokenHash: String)
    fun setStatus(transaction: PgTransactionContext, botId: String, status: Int)
    fun touch(botId: String, timestamp: Long)

    fun grant(transaction: PgTransactionContext, botId: String, chatId: String): Boolean
    fun revokeGrant(transaction: PgTransactionContext, botId: String, chatId: String): Boolean
    fun listGrants(botId: String): List<String>
    fun isGranted(botId: String, chatId: String): Boolean

    override fun deactivateForChat(transaction: PgTransactionContext, chatId: String)
}
