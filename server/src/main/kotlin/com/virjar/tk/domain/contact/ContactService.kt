package com.virjar.tk.domain.contact

import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.protocol.NotifyType

class ContactService(
    private val contactStore: ContactStore,
    private val syncEventService: SyncEventService,
) {
    fun listFriends(uid: String): List<Contact> = contactStore.listFriends(uid)

    suspend fun apply(fromUid: String, toUid: String, remark: String?): ContactApply {
        require(fromUid != toUid) { "不能向自己发起好友申请" }
        if (contactStore.isFriend(fromUid, toUid)) {
            throw IllegalArgumentException("已经是好友")
        }
        val apply = contactStore.createApply(fromUid, toUid, remark)
        syncEventService.emitEvent(toUid, NotifyType.CONTACT_APPLY, apply)
        return apply
    }

    suspend fun accept(token: String): ContactApply {
        val apply = contactStore.acceptApply(token) ?: throw IllegalArgumentException("申请不存在或已处理")
        // 通知双方：好友关系已建立（各自视角的 Contact，契约：CONTACT_ACCEPTED 发 Contact）
        val fromSide = contactStore.listFriends(apply.fromUid).find { it.friendUid == apply.toUid }
        val toSide = contactStore.listFriends(apply.toUid).find { it.friendUid == apply.fromUid }
        fromSide?.let { syncEventService.emitEvent(apply.fromUid, NotifyType.CONTACT_ACCEPTED, it) }
        toSide?.let { syncEventService.emitEvent(apply.toUid, NotifyType.CONTACT_ACCEPTED, it) }
        return apply
    }

    suspend fun reject(token: String): ContactApply {
        return contactStore.rejectApply(token) ?: throw IllegalArgumentException("申请不存在或已处理")
    }

    suspend fun deleteFriend(uid: String, friendUid: String) {
        contactStore.removeFriend(uid, friendUid)
        // 各自视角的 Contact（契约：CONTACT_DELETED 发 Contact）
        syncEventService.emitEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = friendUid))
        syncEventService.emitEvent(friendUid, NotifyType.CONTACT_DELETED, Contact(uid = friendUid, friendUid = uid))
    }

    fun setRemark(uid: String, friendUid: String, remark: String?) {
        contactStore.setRemark(uid, friendUid, remark)
    }

    fun blacklist(uid: String, targetUid: String) {
        contactStore.blacklist(uid, targetUid)
    }

    fun removeFromBlacklist(uid: String, targetUid: String) {
        contactStore.removeFromBlacklist(uid, targetUid)
    }

    fun listBlacklist(uid: String): List<Contact> = contactStore.listBlacklist(uid)

    fun listPendingApplies(uid: String): List<ContactApply> = contactStore.listPendingApplies(uid)
}
