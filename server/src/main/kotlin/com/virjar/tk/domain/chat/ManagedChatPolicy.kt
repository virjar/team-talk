package com.virjar.tk.domain.chat

import com.virjar.tk.domain.transaction.PgTransactionContext

data class ManagedChatAuthority(
    val managed: Boolean,
    val ready: Boolean,
    val ownerLabel: String? = null,
)

/** External-domain ownership and revision readiness for a chat id. */
interface ManagedChatPolicy {
    fun authority(chatId: String): ManagedChatAuthority

    /**
     * Transaction-bound pre-Chat fence. It locks only existing per-chat projection rows, never
     * global OrganizationState, and returns ownership/readiness from that locked snapshot. The
     * global order for an existing chat is this fence, then Chat, then any User/Bot rows. Chat
     * creation is the only User -> Chat exception because its new id has no authority row or Chat.
     * Callers must never enter this fence while already holding a User row lock.
     */
    fun lockAuthority(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
    ): Map<String, ManagedChatAuthority>

    fun managedBy(chatId: String): String? = authority(chatId).ownerLabel
    fun isProjectionReady(chatId: String): Boolean = authority(chatId).ready
}

object UnmanagedChatPolicy : ManagedChatPolicy {
    override fun authority(chatId: String) = ManagedChatAuthority(managed = false, ready = true)

    override fun lockAuthority(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
    ): Map<String, ManagedChatAuthority> = chatIds.distinct().associateWith(::authority)
}

fun interface ManagedChatProjectionCache {
    fun invalidateManagedChat(chatId: String)
}
