package com.virjar.tk.domain.chat

import com.virjar.tk.model.Member

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
    fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String)
    fun setRole(chatId: String, uid: String, role: Int)
    fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long)
    fun unmuteMember(chatId: String, uid: String)
    fun isMuted(chatId: String, uid: String): Boolean
    fun setMuteAll(chatId: String, mutedAll: Boolean)
    fun getMutedMembers(chatId: String): List<String>
}
