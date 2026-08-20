package com.virjar.tk.domain.bot

import com.virjar.tk.model.Message

/** Minimal identity returned when the bot domain provisions a non-login service account. */
data class BotAccountIdentity(
    val uid: String,
    val name: String,
)

fun interface BotAccountProvisioner {
    fun createServiceAccount(name: String): BotAccountIdentity
}

/** Group membership projection owned by the chat domain, exposed without its full service API. */
interface BotGroupMembership {
    suspend fun addServiceMember(chatId: String, uid: String)
    suspend fun removeServiceMember(chatId: String, uid: String)
}

/** Message acceptance boundary used by notification bots. */
fun interface BotMessageSender {
    suspend fun send(senderUid: String, message: Message): Long
}
