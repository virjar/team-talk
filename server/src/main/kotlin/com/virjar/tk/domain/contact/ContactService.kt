package com.virjar.tk.domain.contact

import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.protocol.NotifyType

class ContactService(
    uid: String,
    private val contactStore: ContactStore,
    private val syncEventService: SyncEventService,
) : com.virjar.tk.rpc.gen.ContactRpcStub(uid) {
    override suspend fun list(): List<Contact> = contactStore.listFriends(uid)

    override suspend fun apply(targetUid: String, remark: String?): ContactApply {
        require(uid != targetUid) { "不能向自己发起好友申请" }
        if (contactStore.isFriend(uid, targetUid)) {
            throw IllegalArgumentException("已经是好友")
        }
        val apply = contactStore.createApply(uid, targetUid, remark)
        syncEventService.emitEvent(targetUid, NotifyType.CONTACT_APPLY, apply)
        return apply
    }

    override suspend fun accept(token: String): ContactApply {
        val apply = contactStore.acceptApply(token) ?: throw IllegalArgumentException("申请不存在或已处理")
        // 通知双方：好友关系已建立（各自视角的 Contact，契约：CONTACT_ACCEPTED 发 Contact）
        val fromSide = contactStore.getFriend(apply.fromUid, apply.toUid)
        val toSide = contactStore.getFriend(apply.toUid, apply.fromUid)
        fromSide?.let { syncEventService.emitEvent(apply.fromUid, NotifyType.CONTACT_ACCEPTED, it) }
        toSide?.let { syncEventService.emitEvent(apply.toUid, NotifyType.CONTACT_ACCEPTED, it) }
        return apply
    }

    override suspend fun reject(token: String): ContactApply {
        return contactStore.rejectApply(token) ?: throw IllegalArgumentException("申请不存在或已处理")
    }

    override suspend fun delete(friendUid: String) {
        contactStore.removeFriend(uid, friendUid)
        // 各自视角的 Contact（契约：CONTACT_DELETED 发 Contact）
        syncEventService.emitEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = friendUid))
        syncEventService.emitEvent(friendUid, NotifyType.CONTACT_DELETED, Contact(uid = friendUid, friendUid = uid))
    }

    override suspend fun setRemark(friendUid: String, remark: String?) {
        contactStore.setRemark(uid, friendUid, remark)
    }

    override suspend fun blacklist(targetUid: String) {
        contactStore.blacklist(uid, targetUid)
    }

    override suspend fun removeFromBlacklist(targetUid: String) {
        contactStore.removeFromBlacklist(uid, targetUid)
    }

    override suspend fun listBlacklist(): List<Contact> = contactStore.listBlacklist(uid)

    override suspend fun listApplies(): List<ContactApply> = contactStore.listPendingApplies(uid)
}
