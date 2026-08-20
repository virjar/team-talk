package com.virjar.tk.domain.contact

import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.protocol.NotifyType

class ContactService(
    private val contactStore: ContactStore,
    private val events: EventPublisher,
) {
    fun list(uid: String): List<Contact> = contactStore.listFriends(uid)

    suspend fun apply(uid: String, targetUid: String, remark: String?): ContactApply {
        require(uid != targetUid) { "不能向自己发起好友申请" }
        require(!contactStore.isBlockedEither(uid, targetUid)) { "黑名单关系下不能发起好友申请" }
        if (contactStore.isFriend(uid, targetUid)) {
            throw IllegalArgumentException("已经是好友")
        }
        val apply = contactStore.createApply(uid, targetUid, remark)
        events.emitEvent(targetUid, NotifyType.CONTACT_APPLY, apply)
        return apply
    }

    suspend fun accept(uid: String, token: String): ContactApply {
        val apply = contactStore.acceptApply(token, uid) ?: throw IllegalArgumentException("申请不存在、无权处理或已处理")
        // 通知双方：好友关系已建立（各自视角的 Contact，契约：CONTACT_ACCEPTED 发 Contact）
        val fromSide = contactStore.getFriend(apply.fromUid, apply.toUid)
        val toSide = contactStore.getFriend(apply.toUid, apply.fromUid)
        fromSide?.let { events.emitEvent(apply.fromUid, NotifyType.CONTACT_ACCEPTED, it) }
        toSide?.let { events.emitEvent(apply.toUid, NotifyType.CONTACT_ACCEPTED, it) }
        return apply
    }

    suspend fun reject(uid: String, token: String): ContactApply {
        return contactStore.rejectApply(token, uid) ?: throw IllegalArgumentException("申请不存在、无权处理或已处理")
    }

    suspend fun delete(uid: String, friendUid: String) {
        contactStore.removeFriend(uid, friendUid)
        // 各自视角的 Contact（契约：CONTACT_DELETED 发 Contact）
        events.emitEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = friendUid))
        events.emitEvent(friendUid, NotifyType.CONTACT_DELETED, Contact(uid = friendUid, friendUid = uid))
    }

    fun setRemark(uid: String, friendUid: String, remark: String?) {
        contactStore.setRemark(uid, friendUid, remark)
    }

    suspend fun blacklist(uid: String, targetUid: String) {
        require(uid != targetUid) { "不能拉黑自己" }
        contactStore.blacklist(uid, targetUid)
        events.emitEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = targetUid))
        events.emitEvent(targetUid, NotifyType.CONTACT_DELETED, Contact(uid = targetUid, friendUid = uid))
    }

    fun removeFromBlacklist(uid: String, targetUid: String) {
        contactStore.removeFromBlacklist(uid, targetUid)
    }

    fun listBlacklist(uid: String): List<Contact> = contactStore.listBlacklist(uid)

    fun listApplies(uid: String): List<ContactApply> = contactStore.listPendingApplies(uid)
}
