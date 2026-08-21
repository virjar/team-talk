package com.virjar.tk.domain.chat

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member

/** Locked aggregate facts exposed to the domain policy before a member is removed. */
data class GroupMemberRemovalFacts(
    val chat: Chat,
    val operator: Member?,
    val target: Member?,
)

/** Snapshot produced by the same transaction that deactivated the target membership. */
data class GroupMemberRemoval(
    val chat: Chat,
    val remainingMemberUids: List<String>,
)

/** Locked aggregate facts exposed before a member-add command mutates the group. */
data class GroupMemberAdditionFacts(
    val chat: Chat,
    val operator: Member?,
    val requestedUids: List<String>,
)

/** Snapshot produced by the same transaction that establishes member conversations. */
data class GroupMemberAddition(
    val chat: Chat,
    val addedUids: List<String>,
    val activeMemberUids: List<String>,
)

data class LockedChat(
    val chat: Chat,
    val active: Boolean,
)

/** Actual projection changes made while removing a service identity from one chat. */
data class ServiceMemberProjectionCleanup(
    val chat: Chat,
    val membershipDeactivated: Boolean,
    val conversationDeleted: Boolean,
    val muteDeleted: Boolean,
    val remainingMemberUids: List<String>,
)

/** Persistence port for membership, roles and mute state. */
interface ChatMemberRepository {
    fun getMembers(chatId: String): List<Member>
    fun getMember(chatId: String, uid: String): Member?
    fun getMemberUids(chatId: String): List<String>
    fun getActiveChatIds(uid: String): Set<String> = emptySet()
    /** Same read enlisted in an owning aggregate transaction after the bot row is locked. */
    fun getActiveChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        getActiveChatIds(uid)
    /** Membership (active or inactive), Conversation and mute projection union for recovery. */
    fun getProjectedChatIds(uid: String): Set<String> = getActiveChatIds(uid)
    fun getProjectedChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        getProjectedChatIds(uid)
    fun isMember(chatId: String, uid: String): Boolean
    /**
     * Lock chat rows in lexical id order without touching memberships. Bot commands use this as
     * the first database lock so every process follows chat -> user -> bot/grant -> membership ordering.
     */
    fun lockChats(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = error("Transactional chat locking is not implemented")

    /** Read an actor after its chat and the owning application row have been locked. */
    fun getActiveMember(transaction: PgTransactionContext, chatId: String, uid: String): Member? =
        getMember(chatId, uid)
    /**
     * Lock the active chat and memberships, authorize against that snapshot, then add/reactivate
     * members and establish Conversation rows inside the caller's PG unit of work.
     */
    fun addMembers(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition
    /**
     * Lock the active chat and all active memberships, re-run [authorize] against those facts,
     * then deactivate [targetUid] and delete its conversation/mute projections in the caller's
     * [com.virjar.tk.domain.transaction.PgUnitOfWork]. The callback cannot suspend or escape the
     * database transaction, so authorization and mutation share one locked snapshot.
     */
    fun removeMember(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval

    /**
     * Service-domain reconciliation variant. Missing membership is an idempotent no-op, while a
     * present membership still uses the same locked snapshot and projection deletion as
     * [removeMember].
     */
    fun removeMemberIfPresent(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        requireActiveChat: Boolean = true,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = error("Transactional idempotent membership removal is not implemented")

    /**
     * Remove every service projection even when the member is already inactive/missing or the
     * referenced chat row is dangling. The caller has already locked every existing chat, then
     * the service identity and bot aggregate.
     */
    fun cleanupServiceMemberProjection(
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = error("Service projection cleanup is not implemented")

    fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String)
    fun setRole(chatId: String, uid: String, role: Int)
    fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long)
    fun unmuteMember(chatId: String, uid: String)
    fun isMuted(chatId: String, uid: String): Boolean
    fun setMuteAll(chatId: String, mutedAll: Boolean)
    fun getMutedMembers(chatId: String): List<String>
}
