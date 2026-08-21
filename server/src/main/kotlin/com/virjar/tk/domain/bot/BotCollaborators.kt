package com.virjar.tk.domain.bot

import com.virjar.tk.model.Message
import com.virjar.tk.domain.chat.GroupMemberAddition
import com.virjar.tk.domain.chat.GroupMemberAdditionFacts
import com.virjar.tk.domain.chat.GroupMemberRemoval
import com.virjar.tk.domain.chat.GroupMemberRemovalFacts
import com.virjar.tk.domain.chat.LockedChat
import com.virjar.tk.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Member

/** Minimal identity returned when the bot domain provisions a non-login service account. */
data class BotAccountIdentity(
    val uid: String,
    val name: String,
)

fun interface BotAccountProvisioner {
    /** Persist the non-login identity in the caller's aggregate transaction. */
    fun createServiceAccount(transaction: PgTransactionContext, name: String): BotAccountIdentity
}

/** Group membership projection owned by the chat domain, exposed without its full service API. */
interface BotGroupMembership {
    fun activeChatIds(uid: String): Set<String>
    fun activeChatIds(transaction: PgTransactionContext, uid: String): Set<String>
    fun projectedChatIds(uid: String): Set<String>
    fun projectedChatIds(transaction: PgTransactionContext, uid: String): Set<String>

    fun lockChats(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat>

    fun getActiveMember(transaction: PgTransactionContext, chatId: String, uid: String): Member?

    /** Add/reactivate membership and Conversation inside the caller's aggregate transaction. */
    fun addServiceMember(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition

    /** Remove membership and Conversation if still active in the locked transaction snapshot. */
    fun removeServiceMemberIfPresent(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        requireActiveChat: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval?

    fun cleanupServiceMemberProjection(
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup?

    /** Publish cache invalidation only after the aggregate transaction commits. */
    fun invalidateCommittedMembershipChange(chatId: String)
}

/** Message acceptance boundary used by notification bots. */
fun interface BotMessageSender {
    suspend fun send(senderUid: String, message: Message): Long
}
