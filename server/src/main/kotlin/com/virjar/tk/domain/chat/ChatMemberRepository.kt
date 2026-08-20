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

/** Persistence port for membership, roles and mute state. */
interface ChatMemberRepository {
    fun getMembers(chatId: String): List<Member>
    fun getMember(chatId: String, uid: String): Member?
    fun getMemberUids(chatId: String): List<String>
    fun isMember(chatId: String, uid: String): Boolean
    /** Adds/reactivates ordinary members and establishes their conversation rows atomically. */
    fun addMembers(chatId: String, uids: List<String>)
    /** Deactivates membership and removes its conversation/mute rows atomically. */
    fun removeMember(chatId: String, uid: String)

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
