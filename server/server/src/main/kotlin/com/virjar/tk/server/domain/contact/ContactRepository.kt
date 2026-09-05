package com.virjar.tk.server.domain.contact

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.ContactApplyRecord

data class ContactApplyCreation(
    val apply: ContactApply,
    /** false 表示同方向待处理申请已存在，本次仅复用且不应重复通知。 */
    val created: Boolean,
)

/** 已接受的关系加上两条已提交的通知投影。 */
data class ContactApplyAcceptance(
    val fromSide: Contact,
    val toSide: Contact,
)

object ContactDecisionType {
    const val ACCEPT = 1
    const val REJECT = 2

    fun requireValid(value: Int): Int {
        require(value == ACCEPT || value == REJECT) { "好友申请处理类型非法" }
        return value
    }
}

/** 在响应变得模棱两可之前获准进入的不可变命令。 */
data class ContactDecisionCommand(
    val operationId: String,
    val issuedAt: Long,
    val receiverUid: String,
    val token: String,
    val decision: Int,
    val requestFingerprint: String,
)

/** 重放返回 [apply]，而不会再次发出原始的关系事件。 */
data class ContactDecisionResult(
    val apply: ContactApply,
    val firstCommit: Boolean,
    val acceptance: ContactApplyAcceptance? = null,
)

/**
 * 一次双方变更是否移除了各自可观察的联系人投影。
 *
 * 拉黑行是一个私有授权事实，而不是活跃的联系人投影。因此仅创建或保留该行绝不能制造
 * CONTACT_DELETED 事件。只有当本命令确实移除了用户可观察的活跃好友关系或待处理申请时，
 * 该事件才是正当的。
 */
data class ContactPairMutationResult(
    val actorChanged: Boolean,
    val targetChanged: Boolean,
)

/** 联系人领域拥有的持久化端口。 */
interface ContactRepository {
    fun listFriends(uid: String): List<Contact>
    fun listFriendUids(uid: String): Set<String>
    /** 加入既有资料/事件事务的同一次有界受众读取。 */
    fun listFriendUids(transaction: PgWriteTransactionContext, uid: String): Set<String> = listFriendUids(uid)
    fun isFriend(uid: String, friendUid: String): Boolean
    fun isBlocked(uid: String, targetUid: String): Boolean
    fun isBlockedEither(uid: String, targetUid: String): Boolean =
        isBlocked(uid, targetUid) || isBlocked(targetUid, uid)

    fun addFriend(transaction: PgWriteTransactionContext, uid: String, friendUid: String, remark: String? = null)
    fun removeFriend(
        transaction: PgWriteTransactionContext,
        uid: String,
        friendUid: String,
    ): ContactPairMutationResult
    fun setRemark(transaction: PgWriteTransactionContext, uid: String, friendUid: String, remark: String?)
    fun blacklist(
        transaction: PgWriteTransactionContext,
        uid: String,
        targetUid: String,
    ): ContactPairMutationResult
    fun removeFromBlacklist(transaction: PgWriteTransactionContext, uid: String, targetUid: String)
    fun listBlacklist(uid: String): List<Contact>
    fun createApply(
        transaction: PgWriteTransactionContext,
        fromUid: String,
        toUid: String,
        remark: String?,
    ): ContactApplyCreation
    fun decideApply(
        transaction: PgWriteTransactionContext,
        command: ContactDecisionCommand,
    ): ContactDecisionResult?
    fun listPendingApplies(uid: String): List<ContactApply>
    fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord>
    fun getPendingApply(uid: String, targetUid: String): ContactApplyRecord?
}
