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

data class MessageAdmissionFacts(
    val chat: Chat,
    val sender: Member?,
    val senderMuted: Boolean,
    val activeMemberUids: List<String>,
)

data class MessageAdmission(
    val serverSeq: Long,
    val chatType: Int,
    val recipientUids: List<String>,
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
     * Authority-fenced message admission and max-seq allocation in one locked Chat snapshot.
     * [afterChatLocked] is the cross-domain User/Bot/grant lock seam and must run before Member/Mute.
     */
    fun admitMessage(
        transaction: PgTransactionContext,
        chatId: String,
        senderUid: String,
        nowMillis: Long,
        afterChatLocked: () -> Unit = {},
        authorize: (MessageAdmissionFacts) -> Unit,
    ): MessageAdmission = error("Transactional message admission is not implemented")
    /**
     * Lock the active chat, required human Users and memberships in that order, authorize against
     * the snapshot, then add/reactivate members and establish Conversation rows in the UoW.
     */
    fun addMembers(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        requiredHumanUids: Set<String> = emptySet(),
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition
    /**
     * Lock the active chat, then operator + target Users in one sorted acquisition. The operator
     * must be an active human; a distinct target must still exist as a human but may be disabled
     * so administrators can remove a banned account. A self-leaver must be active. Memberships,
     * authorization and projection deletion then share the same locked transaction snapshot.
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

    fun transferOwner(
        transaction: PgTransactionContext,
        chatId: String,
        oldOwnerUid: String,
        newOwnerUid: String,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("Transactional owner transfer is not implemented")

    fun setRole(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        role: Int,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("Transactional member role mutation is not implemented")

    fun setMemberMute(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        expiresAt: Long?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("Transactional member mute mutation is not implemented")

    fun isMuted(chatId: String, uid: String): Boolean
    fun setMuteAll(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String?,
        mutedAll: Boolean,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("Transactional group mute mutation is not implemented")
    fun getMutedMembers(chatId: String): List<String>
}
