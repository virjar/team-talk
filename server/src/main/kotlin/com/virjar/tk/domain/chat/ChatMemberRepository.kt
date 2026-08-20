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

/** Persistence port for membership, roles and mute state. */
interface ChatMemberRepository {
    fun getMembers(chatId: String): List<Member>
    fun getMember(chatId: String, uid: String): Member?
    fun getMemberUids(chatId: String): List<String>
    fun isMember(chatId: String, uid: String): Boolean
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

    fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String)
    fun setRole(chatId: String, uid: String, role: Int)
    fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long)
    fun unmuteMember(chatId: String, uid: String)
    fun isMuted(chatId: String, uid: String): Boolean
    fun setMuteAll(chatId: String, mutedAll: Boolean)
    fun getMutedMembers(chatId: String): List<String>
}
