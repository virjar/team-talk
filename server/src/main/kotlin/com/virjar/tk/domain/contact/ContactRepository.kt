package com.virjar.tk.domain.contact

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply

/** Persistence port owned by the contact domain. */
interface ContactRepository {
    fun getFriend(uid: String, friendUid: String): Contact?
    fun listFriends(uid: String): List<Contact>
    fun isFriend(uid: String, friendUid: String): Boolean
    fun isBlocked(uid: String, targetUid: String): Boolean
    fun addFriend(uid: String, friendUid: String, remark: String? = null)
    fun removeFriend(uid: String, friendUid: String)
    fun setRemark(uid: String, friendUid: String, remark: String?)
    fun blacklist(uid: String, targetUid: String)
    fun removeFromBlacklist(uid: String, targetUid: String)
    fun listBlacklist(uid: String): List<Contact>
    fun createApply(fromUid: String, toUid: String, remark: String?): ContactApply
    fun acceptApply(token: String, receiverUid: String): ContactApply?
    fun rejectApply(token: String, receiverUid: String): ContactApply?
    fun listPendingApplies(uid: String): List<ContactApply>
}
