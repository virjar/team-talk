package com.virjar.tk.server.domain.contact

import com.virjar.tk.server.domain.command.canonicalOperationId
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.ContactApplyLookup
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.protocol.NotifyType
import java.util.UUID

class ContactService(
    private val contacts: ContactRepository,
    private val unitOfWork: PgUnitOfWork,
    private val users: UserRepository,
) {
    fun list(uid: String): List<Contact> = contacts.listFriends(uid)

    suspend fun apply(uid: String, targetUid: String, remark: String?): ContactApply {
        require(uid != targetUid) { "不能向自己发起好友申请" }
        requireHumanTarget(targetUid)
        // 好友、黑名单与 pending 必须在仓储持有双方行锁时一起判断。这里若先读再写，
        // accept / blacklist 与 apply 并发时会产生“已是好友或已拉黑但仍有 pending”的非法组合。
        val creation = unitOfWork.write {
            val result = contacts.createApply(transaction, uid, targetUid, remark)
            if (result.created) {
                appendEvent(targetUid, NotifyType.CONTACT_APPLY, result.apply)
            }
            result
        }
        // token 是收件人的处理凭据。即使旧 apply RPC 返回 ContactApply，也不能回显给发件人。
        return creation.apply.copy(token = null)
    }

    suspend fun accept(uid: String, operationId: String, issuedAt: Long, token: String): ContactApply =
        decide(uid, operationId, issuedAt, token, ContactDecisionType.ACCEPT)

    suspend fun reject(uid: String, operationId: String, issuedAt: Long, token: String): ContactApply =
        decide(uid, operationId, issuedAt, token, ContactDecisionType.REJECT)

    private suspend fun decide(
        uid: String,
        operationId: String,
        issuedAt: Long,
        token: String,
        decision: Int,
    ): ContactApply {
        val command = canonicalDecisionCommand(uid, operationId, issuedAt, token, decision)
        return unitOfWork.write {
            val result = contacts.decideApply(transaction, command)
                ?: throw IllegalArgumentException("申请不存在、无权处理或已处理")
            if (result.firstCommit) {
                // CONTACT_APPLY 同时也是持久化的申请状态变更提示。两个账户可能都有其他在线
                // 设备在显示历史/资料/徽章；进程本地的 RPC 完成无法让那些会话收敛。终态载荷
                // 不携带决策凭据，且精确的命令重放绝不能再次追加这些事件。
                val update = result.apply.copy(token = null)
                appendEvent(result.apply.fromUid, NotifyType.CONTACT_APPLY, update)
                appendEvent(result.apply.toUid, NotifyType.CONTACT_APPLY, update)
            }
            // 两个视角的 payload 与关系行在同一快照中生成并原子落入各自 durable stream。
            result.acceptance?.takeIf { result.firstCommit }?.let { accepted ->
                appendEvent(result.apply.fromUid, NotifyType.CONTACT_ACCEPTED, accepted.fromSide)
                appendEvent(result.apply.toUid, NotifyType.CONTACT_ACCEPTED, accepted.toSide)
            }
            result.apply
        }
    }

    private fun canonicalDecisionCommand(
        receiverUid: String,
        operationId: String,
        issuedAt: Long,
        token: String,
        decision: Int,
    ): ContactDecisionCommand {
        val canonicalOperation = canonicalOperationId(operationId, "好友申请处理")
        val canonicalIssuedAt = ReliableCommandPolicy.requireActiveIssuedAt(
            issuedAt,
            System.currentTimeMillis(),
            "好友申请处理",
        )
        val canonicalToken = token.takeIf { it.length == UUID_TEXT_LENGTH }
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?.takeIf { it == token }
        require(canonicalToken != null) { "好友申请处理凭据非法" }
        val canonicalDecision = ContactDecisionType.requireValid(decision)
        return ContactDecisionCommand(
            operationId = canonicalOperation,
            issuedAt = canonicalIssuedAt,
            receiverUid = receiverUid,
            token = canonicalToken,
            decision = canonicalDecision,
            requestFingerprint = reliableCommandFingerprint(
                "contact-decision-v1",
                receiverUid,
                canonicalIssuedAt.toString(),
                canonicalDecision.toString(),
                canonicalToken,
            ),
        )
    }

    suspend fun delete(uid: String, friendUid: String) {
        unitOfWork.write {
            val mutation = contacts.removeFriend(transaction, uid, friendUid)
            // 各自视角的 Contact（契约：CONTACT_DELETED 发 Contact）。无操作命令不得污染
            // 任一方的 durable stream；非对称旧数据也只通知真正失去活跃投影的一方。
            if (mutation.actorChanged) {
                appendEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = friendUid))
            }
            if (mutation.targetChanged) {
                appendEvent(friendUid, NotifyType.CONTACT_DELETED, Contact(uid = friendUid, friendUid = uid))
            }
        }
    }

    suspend fun setRemark(uid: String, friendUid: String, remark: String?) {
        unitOfWork.write {
            contacts.setRemark(transaction, uid, friendUid, remark)
        }
    }

    suspend fun blacklist(uid: String, targetUid: String) {
        require(uid != targetUid) { "不能拉黑自己" }
        unitOfWork.write {
            val mutation = contacts.blacklist(transaction, uid, targetUid)
            if (mutation.actorChanged) {
                appendEvent(uid, NotifyType.CONTACT_DELETED, Contact(uid = uid, friendUid = targetUid))
            }
            if (mutation.targetChanged) {
                appendEvent(targetUid, NotifyType.CONTACT_DELETED, Contact(uid = targetUid, friendUid = uid))
            }
        }
    }

    suspend fun removeFromBlacklist(uid: String, targetUid: String) {
        unitOfWork.write {
            contacts.removeFromBlacklist(transaction, uid, targetUid)
        }
    }

    fun listBlacklist(uid: String): List<Contact> = contacts.listBlacklist(uid)

    /** 只返回当前用户收到且仍待处理的申请。 */
    fun listPendingApplies(uid: String): List<ContactApply> = contacts.listPendingApplies(uid)

    fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord> {
        require(beforeId >= 0) { "beforeId 不能为负数" }
        require(limit in 1..MAX_APPLY_RECORD_PAGE_SIZE) {
            "好友申请记录数量必须在 1..$MAX_APPLY_RECORD_PAGE_SIZE 之间"
        }
        return contacts.listApplyRecords(uid, beforeId, limit)
    }

    fun getPendingApply(uid: String, targetUid: String): ContactApplyLookup {
        if (uid == targetUid) return ContactApplyLookup()
        val target = users.findByUid(targetUid) ?: return ContactApplyLookup()
        if (target.role != UserRole.HUMAN) return ContactApplyLookup()
        return ContactApplyLookup(contacts.getPendingApply(uid, targetUid))
    }

    private fun requireHumanTarget(targetUid: String) {
        val target = users.findByUid(targetUid) ?: throw IllegalArgumentException("用户不存在")
        require(target.role == UserRole.HUMAN) { "不能向机器人或系统账户发起好友申请" }
    }

    companion object {
        const val MAX_APPLY_RECORD_PAGE_SIZE = 100
        private const val UUID_TEXT_LENGTH = 36
    }
}
