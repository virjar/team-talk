package com.virjar.tk.domain.contact

import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyLookup
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.model.UserRole
import com.virjar.tk.protocol.NotifyType

class ContactService(
    private val contactStore: ContactStore,
    private val events: EventPublisher,
    private val users: UserStore,
) {
    fun list(uid: String): List<Contact> = contactStore.listFriends(uid)

    suspend fun apply(uid: String, targetUid: String, remark: String?): ContactApply {
        require(uid != targetUid) { "不能向自己发起好友申请" }
        requireHumanTarget(targetUid)
        // 好友、黑名单与 pending 必须在仓储持有双方行锁时一起判断。这里若先读再写，
        // accept / blacklist 与 apply 并发时会产生“已是好友或已拉黑但仍有 pending”的非法组合。
        val creation = contactStore.createApply(uid, targetUid, remark)
        if (creation.created) {
            events.emitEvent(targetUid, NotifyType.CONTACT_APPLY, creation.apply)
        }
        // token 是收件人的处理凭据。即使旧 apply RPC 返回 ContactApply，也不能回显给发件人。
        return creation.apply.copy(token = null)
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

    /** 只返回当前用户收到且仍待处理的申请。 */
    fun listPendingApplies(uid: String): List<ContactApply> = contactStore.listPendingApplies(uid)

    fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord> {
        require(beforeId >= 0) { "beforeId 不能为负数" }
        require(limit in 1..MAX_APPLY_RECORD_PAGE_SIZE) {
            "好友申请记录数量必须在 1..$MAX_APPLY_RECORD_PAGE_SIZE 之间"
        }
        return contactStore.listApplyRecords(uid, beforeId, limit)
    }

    fun getPendingApply(uid: String, targetUid: String): ContactApplyLookup {
        if (uid == targetUid) return ContactApplyLookup()
        val target = users.findByUid(targetUid) ?: return ContactApplyLookup()
        if (target.role != UserRole.HUMAN) return ContactApplyLookup()
        return ContactApplyLookup(contactStore.getPendingApply(uid, targetUid))
    }

    private fun requireHumanTarget(targetUid: String) {
        val target = users.findByUid(targetUid) ?: throw IllegalArgumentException("用户不存在")
        require(target.role == UserRole.HUMAN) { "不能向机器人或系统账户发起好友申请" }
    }

    companion object {
        const val MAX_APPLY_RECORD_PAGE_SIZE = 100
    }
}
