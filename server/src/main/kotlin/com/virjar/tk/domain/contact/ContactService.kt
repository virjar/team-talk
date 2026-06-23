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
        // 通知双方：好友关系已建立
        val contact = contactStore.listFriends(apply.fromUid).find { it.friendUid == apply.toUid }
        if (contact != null) {
            syncEventService.emitEvent(apply.fromUid, NotifyType.CONTACT_ACCEPTED, contact)
            syncEventService.emitEvent(apply.toUid, NotifyType.CONTACT_ACCEPTED, contact)
        }
        return apply
    }

    suspend fun reject(token: String): ContactApply {
        return contactStore.rejectApply(token) ?: throw IllegalArgumentException("申请不存在或已处理")
    }

    suspend fun deleteFriend(uid: String, friendUid: String) {
        contactStore.removeFriend(uid, friendUid)
        val contact = Contact(uid = "", friendUid = friendUid)
        syncEventService.emitEvent(uid, NotifyType.CONTACT_DELETED, contact)
        syncEventService.emitEvent(friendUid, NotifyType.CONTACT_DELETED, contact)
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
