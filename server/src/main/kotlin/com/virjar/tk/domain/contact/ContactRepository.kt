package com.virjar.tk.domain.contact

import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyRecord

data class ContactApplyCreation(
    val apply: ContactApply,
    /** false 表示同方向待处理申请已存在，本次仅复用且不应重复通知。 */
    val created: Boolean,
)

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
    fun createApply(fromUid: String, toUid: String, remark: String?): ContactApplyCreation
    fun acceptApply(token: String, receiverUid: String): ContactApply?
    fun rejectApply(token: String, receiverUid: String): ContactApply?
    fun listPendingApplies(uid: String): List<ContactApply>
    fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord>
    fun getPendingApply(uid: String, targetUid: String): ContactApplyRecord?
}
