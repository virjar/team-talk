package com.virjar.tk.domain.contact

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyRecord

/**
 * 联系人领域的无状态门面。
 *
 * 好友关系是授权与消息投递的权威事实，因此不保留进程内 UID 缓存：无界缓存会随活跃用户
 * 增长，且并发 load 与 mutation 会把提交前的旧集合重新写回。所有判断都直接读取数据库。
 * 写操作必须携带 [PgTransactionContext]，从类型上禁止脱离聚合事务单独提交。
 */
class ContactStore(private val repo: ContactRepository) {
    fun isFriend(uid: String, friendUid: String): Boolean = repo.isFriend(uid, friendUid)

    fun isBlocked(uid: String, targetUid: String): Boolean = repo.isBlocked(uid, targetUid)

    fun isBlockedEither(uid: String, targetUid: String): Boolean =
        repo.isBlocked(uid, targetUid) || repo.isBlocked(targetUid, uid)

    fun getFriendUids(uid: String): Set<String> = repo.listFriendUids(uid)

    fun listFriends(uid: String): List<Contact> = repo.listFriends(uid)

    fun addFriend(transaction: PgTransactionContext, uid: String, friendUid: String, remark: String? = null) =
        repo.addFriend(transaction, uid, friendUid, remark)

    fun removeFriend(transaction: PgTransactionContext, uid: String, friendUid: String) =
        repo.removeFriend(transaction, uid, friendUid)

    fun setRemark(transaction: PgTransactionContext, uid: String, friendUid: String, remark: String?) =
        repo.setRemark(transaction, uid, friendUid, remark)

    fun blacklist(transaction: PgTransactionContext, uid: String, targetUid: String) =
        repo.blacklist(transaction, uid, targetUid)

    fun removeFromBlacklist(transaction: PgTransactionContext, uid: String, targetUid: String) =
        repo.removeFromBlacklist(transaction, uid, targetUid)

    fun listBlacklist(uid: String): List<Contact> = repo.listBlacklist(uid)

    // ── 好友申请（纯 DB，不缓存） ──

    fun createApply(
        transaction: PgTransactionContext,
        fromUid: String,
        toUid: String,
        remark: String?,
    ): ContactApplyCreation = repo.createApply(transaction, fromUid, toUid, remark)

    fun acceptApply(
        transaction: PgTransactionContext,
        token: String,
        receiverUid: String,
    ): ContactApplyAcceptance? = repo.acceptApply(transaction, token, receiverUid)

    fun rejectApply(transaction: PgTransactionContext, token: String, receiverUid: String): ContactApply? =
        repo.rejectApply(transaction, token, receiverUid)

    fun listPendingApplies(uid: String): List<ContactApply> = repo.listPendingApplies(uid)

    fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord> =
        repo.listApplyRecords(uid, beforeId, limit)

    fun getPendingApply(uid: String, targetUid: String): ContactApplyRecord? = repo.getPendingApply(uid, targetUid)
}
