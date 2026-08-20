package com.virjar.tk.domain.contact

import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyLookup
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.model.UserRole
import com.virjar.tk.protocol.NotifyType

class ContactService(
    private val contactStore: ContactStore,
    private val unitOfWork: PgUnitOfWork,
    private val users: UserStore,
) {
    fun list(uid: String): List<Contact> = contactStore.listFriends(uid)

    suspend fun apply(uid: String, targetUid: String, remark: String?): ContactApply {
        require(uid != targetUid) { "不能向自己发起好友申请" }
        requireHumanTarget(targetUid)
        // 好友、黑名单与 pending 必须在仓储持有双方行锁时一起判断。这里若先读再写，
        // accept / blacklist 与 apply 并发时会产生“已是好友或已拉黑但仍有 pending”的非法组合。
        val creation = unitOfWork.write {
            val result = contactStore.createApply(transaction, uid, targetUid, remark)
            if (result.created) {
                appendEvent(targetUid, NotifyType.CONTACT_APPLY, result.apply)
            }
            result
        }
        // token 是收件人的处理凭据。即使旧 apply RPC 返回 ContactApply，也不能回显给发件人。
        return creation.apply.copy(token = null)
    }

    suspend fun accept(uid: String, token: String): ContactApply {
        return unitOfWork.write {
            val accepted = contactStore.acceptApply(transaction, token, uid)
                ?: throw IllegalArgumentException("申请不存在、无权处理或已处理")
            // 两个视角的 payload 与关系行在同一快照中生成并原子落入各自 durable stream。
            appendEvent(accepted.apply.fromUid, NotifyType.CONTACT_ACCEPTED, accepted.fromSide)
            appendEvent(accepted.apply.toUid, NotifyType.CONTACT_ACCEPTED, accepted.toSide)
            accepted.apply
        }
    }

    suspend fun reject(uid: String, token: String): ContactApply {
        return unitOfWork.write {
            contactStore.rejectApply(transaction, token, uid)
                ?: throw IllegalArgumentException("申请不存在、无权处理或已处理")
        }
    }

    suspend fun delete(uid: String, friendUid: String) {
        unitOfWork.write {
            contactStore.removeFriend(transaction, uid, friendUid)
            // 各自视角的 Contact（契约：CONTACT_DELETED 发 Contact）
            appendEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = friendUid))
            appendEvent(friendUid, NotifyType.CONTACT_DELETED, Contact(uid = friendUid, friendUid = uid))
        }
    }

    suspend fun setRemark(uid: String, friendUid: String, remark: String?) {
        unitOfWork.write {
            contactStore.setRemark(transaction, uid, friendUid, remark)
        }
    }

    suspend fun blacklist(uid: String, targetUid: String) {
        require(uid != targetUid) { "不能拉黑自己" }
        unitOfWork.write {
            contactStore.blacklist(transaction, uid, targetUid)
            appendEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = targetUid))
            appendEvent(targetUid, NotifyType.CONTACT_DELETED, Contact(uid = targetUid, friendUid = uid))
        }
    }

    suspend fun removeFromBlacklist(uid: String, targetUid: String) {
        unitOfWork.write {
            contactStore.removeFromBlacklist(transaction, uid, targetUid)
        }
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
