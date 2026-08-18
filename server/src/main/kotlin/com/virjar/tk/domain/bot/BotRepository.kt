package com.virjar.tk.domain.bot

import com.virjar.tk.domain.chat.RequiredChatParticipants
import kotlinx.serialization.Serializable

@Serializable
data class AutomationBot(
    val botId: String,
    val userUid: String,
    val name: String,
    val status: Int,
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
    fun create(bot: AutomationBot, tokenHash: String): AutomationBot
    fun list(): List<AutomationBot>
    fun find(botId: String): AutomationBot?
    fun findByTokenHash(tokenHash: String): AutomationBot?
    fun updateTokenHash(botId: String, tokenHash: String)
    fun setStatus(botId: String, status: Int)
    fun touch(botId: String, timestamp: Long)

    fun grant(botId: String, chatId: String)
    fun revokeGrant(botId: String, chatId: String)
    fun listGrants(botId: String): List<String>
    fun isGranted(botId: String, chatId: String): Boolean
}
