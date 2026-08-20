package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.contact.ContactApplyAcceptance
import com.virjar.tk.domain.contact.ContactApplyCreation
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.infra.db.FriendApplies
import com.virjar.tk.infra.db.Friends
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.model.User
import com.virjar.tk.model.UserRole
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedContactRepository(private val userRepo: UserRepository) : ContactRepository {
    override fun listFriends(uid: String): List<Contact> {
        val friendRows = transaction {
            Friends.selectAll().where { (Friends.uid eq uid) and (Friends.status eq 1) }
                .map { row -> row[Friends.friendUid] to row[Friends.remark] }
        }
        return friendRows.mapNotNull { (friendUid, remark) ->
            val friendUser = userRepo.findByUid(friendUid) ?: return@mapNotNull null
            Contact(uid = uid, friendUid = friendUid, remark = remark, status = 1, user = friendUser)
        }
    }

    override fun listFriendUids(uid: String): Set<String> = transaction {
        Friends.selectAll().where { (Friends.uid eq uid) and (Friends.status eq 1) }
            .mapTo(linkedSetOf()) { it[Friends.friendUid] }
    }

    override fun isFriend(uid: String, friendUid: String): Boolean {
        return transaction {
            Friends.selectAll().where {
                (Friends.uid eq uid) and (Friends.friendUid eq friendUid) and (Friends.status eq 1)
            }.count() > 0
        }
    }

    override fun isBlocked(uid: String, targetUid: String): Boolean = transaction {
        Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq targetUid) and (Friends.status eq 2)
        }.limit(1).any()
    }

    override fun addFriend(
        transaction: PgTransactionContext,
        uid: String,
        friendUid: String,
        remark: String?,
    ) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, friendUid)
            val now = System.currentTimeMillis()
            val firstUpdated = Friends.update({
                (Friends.uid eq uid) and (Friends.friendUid eq friendUid)
            }) {
                it[Friends.remark] = remark
                it[Friends.status] = 1
            }
            if (firstUpdated == 0) {
                Friends.insert {
                    it[Friends.uid] = uid
                    it[Friends.friendUid] = friendUid
                    it[Friends.remark] = remark
                    it[Friends.status] = 1
                    it[Friends.createdAt] = now
                }
            }
            val secondUpdated = Friends.update({
                (Friends.uid eq friendUid) and (Friends.friendUid eq uid)
            }) {
                it[Friends.status] = 1
            }
            if (secondUpdated == 0) {
                Friends.insert {
                    it[Friends.uid] = friendUid
                    it[Friends.friendUid] = uid
                    it[Friends.status] = 1
                    it[Friends.createdAt] = now
                }
            }
        }
    }

    override fun removeFriend(transaction: PgTransactionContext, uid: String, friendUid: String) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, friendUid)
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq friendUid) }) {
                it[status] = 0
            }
            Friends.update({ (Friends.uid eq friendUid) and (Friends.friendUid eq uid) }) {
                it[status] = 0
            }
        }
    }

    override fun setRemark(
        transaction: PgTransactionContext,
        uid: String,
        friendUid: String,
        remark: String?,
    ) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, friendUid)
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq friendUid) }) {
                it[Friends.remark] = remark
            }
        }
    }

    override fun blacklist(transaction: PgTransactionContext, uid: String, targetUid: String) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, targetUid)
            val existing = Friends.selectAll().where {
                (Friends.uid eq uid) and (Friends.friendUid eq targetUid)
            }.count()
            if (existing > 0) {
                Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq targetUid) }) {
                    it[status] = 2
                }
            } else {
                Friends.insertIgnore {
                    it[Friends.uid] = uid
                    it[Friends.friendUid] = targetUid
                    it[Friends.status] = 2
                    it[Friends.createdAt] = System.currentTimeMillis()
                }
            }
            // Blocking terminates both users' friendship projections. Removing the block later
            // must not silently recreate a relationship without a fresh request.
            Friends.update({
                (Friends.uid eq targetUid) and (Friends.friendUid eq uid) and (Friends.status eq 1)
            }) {
                it[status] = 0
            }

            // 拉黑在关系语义上同时终止双方之间尚未处理的申请。否则 apply 先完成、
            // blacklist 后完成时仍会留下可处理 token 和红点，解除拉黑后还会复活旧请求。
            val updatedAt = System.currentTimeMillis()
            FriendApplies.update({
                ((((FriendApplies.fromUid eq uid) and (FriendApplies.toUid eq targetUid)) or
                    ((FriendApplies.fromUid eq targetUid) and (FriendApplies.toUid eq uid))) and
                    (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING))
            }) {
                it[status] = ContactApplyRecord.STATUS_REJECTED
                it[FriendApplies.updatedAt] = updatedAt
            }
        }
    }

    override fun removeFromBlacklist(transaction: PgTransactionContext, uid: String, targetUid: String) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, targetUid)
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq targetUid) and (Friends.status eq 2) }) {
                it[status] = 0
            }
        }
    }

    override fun listBlacklist(uid: String): List<Contact> {
        val blacklisted = transaction {
            Friends.selectAll().where { (Friends.uid eq uid) and (Friends.status eq 2) }
                .map { it[Friends.friendUid] }
        }
        return blacklisted.mapNotNull { targetUid ->
            val user = userRepo.findByUid(targetUid) ?: return@mapNotNull null
            Contact(uid = uid, friendUid = targetUid, status = 2, user = user)
        }
    }

    // ── 好友申请 ──

    override fun createApply(
        transaction: PgTransactionContext,
        fromUid: String,
        toUid: String,
        remark: String?,
    ): ContactApplyCreation = inWriteTransaction(transaction) {
            val users = lockUserPair(fromUid, toUid)
            val target = users.getValue(toUid).toUser()
            require(target.role == UserRole.HUMAN) { "不能向机器人或系统账户发起好友申请" }

            val blocked = Friends.selectAll().where {
                ((((Friends.uid eq fromUid) and (Friends.friendUid eq toUid)) or
                    ((Friends.uid eq toUid) and (Friends.friendUid eq fromUid))) and
                    (Friends.status eq 2))
            }.limit(1).any()
            require(!blocked) { "黑名单关系下不能发起好友申请" }

            val alreadyFriends = Friends.selectAll().where {
                ((((Friends.uid eq fromUid) and (Friends.friendUid eq toUid)) or
                    ((Friends.uid eq toUid) and (Friends.friendUid eq fromUid))) and
                    (Friends.status eq 1))
            }.limit(1).any()
            require(!alreadyFriends) { "已经是好友" }

            val existing = FriendApplies.selectAll().where {
                (FriendApplies.fromUid eq fromUid) and
                    (FriendApplies.toUid eq toUid) and
                    (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING)
            }.orderBy(FriendApplies.id, SortOrder.DESC).limit(1).firstOrNull()
            if (existing != null) {
                val row = existing.toFriendApplyRow()
                return@inWriteTransaction ContactApplyCreation(
                    apply = row.toContactApply(users.getValue(row.fromUid).toUser()),
                    created = false,
                )
            }

            val reversePending = FriendApplies.selectAll().where {
                (FriendApplies.fromUid eq toUid) and
                    (FriendApplies.toUid eq fromUid) and
                    (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING)
            }.limit(1).any()
            require(!reversePending) { "对方已申请你，请处理现有申请" }

            val token = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            FriendApplies.insert {
                it[FriendApplies.fromUid] = fromUid
                it[FriendApplies.toUid] = toUid
                it[FriendApplies.token] = token
                it[FriendApplies.remark] = remark
                it[FriendApplies.status] = ContactApplyRecord.STATUS_PENDING
                it[FriendApplies.createdAt] = now
                it[FriendApplies.updatedAt] = now
            }
            val inserted = FriendApplies.selectAll().where { FriendApplies.token eq token }.single()
            val row = inserted.toFriendApplyRow()
            ContactApplyCreation(
                apply = row.toContactApply(users.getValue(row.fromUid).toUser()),
                created = true,
            )
        }

    override fun acceptApply(
        transaction: PgTransactionContext,
        token: String,
        receiverUid: String,
    ): ContactApplyAcceptance? = inWriteTransaction(transaction) {
            // token 行先只用于解析稳定不变的双方 uid；真正的状态读取必须等取得 pair lock 后重做。
            // 不能先 FOR UPDATE 申请行再锁 User，否则会与 createApply 的 User -> Apply 顺序相反。
            val identity = FriendApplies.selectAll().where {
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }.singleOrNull() ?: return@inWriteTransaction null
            val fromUid = identity[FriendApplies.fromUid]
            val toUid = identity[FriendApplies.toUid]
            val users = lockUserPair(fromUid, toUid)

            val row = FriendApplies.selectAll().where {
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }.forUpdate().singleOrNull()
                ?: return@inWriteTransaction null

            if (row[FriendApplies.status] != ContactApplyRecord.STATUS_PENDING) return@inWriteTransaction null

            val blocked = Friends.selectAll().where {
                (((Friends.uid eq fromUid) and (Friends.friendUid eq toUid)) or
                    ((Friends.uid eq toUid) and (Friends.friendUid eq fromUid))) and
                    (Friends.status eq 2)
            }.limit(1).any()
            require(!blocked) { "对方已在黑名单中，不能建立好友关系" }

            val updatedAt = System.currentTimeMillis()
            // 建立好友后，双方之间任何遗留 pending 都不应继续出现在 methodId 9 的红点中。
            FriendApplies.update({
                ((((FriendApplies.fromUid eq fromUid) and (FriendApplies.toUid eq toUid)) or
                    ((FriendApplies.fromUid eq toUid) and (FriendApplies.toUid eq fromUid))) and
                    (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING))
            }) {
                it[status] = ContactApplyRecord.STATUS_ACCEPTED
                it[FriendApplies.updatedAt] = updatedAt
            }

            // 双向添加好友。删除好友或解除黑名单后，唯一键对应的行仍以 status=0
            // 保留；insertIgnore 不会恢复这种既有行，因此必须先更新、缺行时再插入。
            val now = System.currentTimeMillis()
            val fromUpdated = Friends.update({
                (Friends.uid eq fromUid) and (Friends.friendUid eq toUid)
            }) {
                it[Friends.status] = 1
            }
            if (fromUpdated == 0) {
                Friends.insert {
                    it[Friends.uid] = fromUid
                    it[Friends.friendUid] = toUid
                    it[Friends.status] = 1
                    it[Friends.createdAt] = now
                }
            }
            val toUpdated = Friends.update({
                (Friends.uid eq toUid) and (Friends.friendUid eq fromUid)
            }) {
                it[Friends.status] = 1
            }
            if (toUpdated == 0) {
                Friends.insert {
                    it[Friends.uid] = toUid
                    it[Friends.friendUid] = fromUid
                    it[Friends.status] = 1
                    it[Friends.createdAt] = now
                }
            }

            val accepted = row.toFriendApplyRow().copy(
                token = null,
                status = ContactApplyRecord.STATUS_ACCEPTED,
                updatedAt = updatedAt,
            )
            val fromContact = activeContact(
                uid = fromUid,
                friendUid = toUid,
                friendUser = users.getValue(toUid).toUser(),
            )
            val toContact = activeContact(
                uid = toUid,
                friendUid = fromUid,
                friendUser = users.getValue(fromUid).toUser(),
            )
            ContactApplyAcceptance(
                apply = accepted.toContactApply(users.getValue(fromUid).toUser()),
                fromSide = fromContact,
                toSide = toContact,
            )
        }

    override fun rejectApply(
        transaction: PgTransactionContext,
        token: String,
        receiverUid: String,
    ): ContactApply? = inWriteTransaction(transaction) {
            val identity = FriendApplies.selectAll().where {
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }.singleOrNull() ?: return@inWriteTransaction null
            val fromUid = identity[FriendApplies.fromUid]
            val toUid = identity[FriendApplies.toUid]
            val users = lockUserPair(fromUid, toUid)

            val row = FriendApplies.selectAll().where {
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }.forUpdate().singleOrNull()
                ?: return@inWriteTransaction null
            if (row[FriendApplies.status] != ContactApplyRecord.STATUS_PENDING) return@inWriteTransaction null
            val updatedAt = System.currentTimeMillis()
            FriendApplies.update({
                (FriendApplies.fromUid eq fromUid) and
                    (FriendApplies.toUid eq toUid) and
                    (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING)
            }) {
                it[status] = ContactApplyRecord.STATUS_REJECTED
                it[FriendApplies.updatedAt] = updatedAt
            }
            row.toFriendApplyRow().copy(
                token = null,
                status = ContactApplyRecord.STATUS_REJECTED,
                updatedAt = updatedAt,
            ).toContactApply(users.getValue(fromUid).toUser())
        }

    override fun listPendingApplies(uid: String): List<ContactApply> {
        val applies = transaction {
            FriendApplies.selectAll().where { (FriendApplies.toUid eq uid) and (FriendApplies.status eq 0) }
                .orderBy(FriendApplies.id, SortOrder.DESC)
                .limit(MAX_PENDING_APPLIES)
                .map { it.toFriendApplyRow() }
        }
        return applies.map { row ->
            row.toContactApply(userRepo.findByUid(row.fromUid))
        }
    }

    override fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord> {
        val rows = transaction {
            FriendApplies.selectAll().where {
                val belongsToUser = (FriendApplies.fromUid eq uid) or (FriendApplies.toUid eq uid)
                if (beforeId > 0) {
                    belongsToUser and (FriendApplies.id less beforeId)
                } else {
                    belongsToUser
                }
            }
                .orderBy(FriendApplies.id, SortOrder.DESC)
                .limit(limit)
                .map { it.toFriendApplyRow() }
        }
        return rows.map { it.toRecord(uid) }
    }

    override fun getPendingApply(uid: String, targetUid: String): ContactApplyRecord? {
        val row = transaction {
            val alreadyFriends = Friends.selectAll().where {
                ((((Friends.uid eq uid) and (Friends.friendUid eq targetUid)) or
                    ((Friends.uid eq targetUid) and (Friends.friendUid eq uid))) and
                    (Friends.status eq 1))
            }.limit(1).any()
            if (alreadyFriends) return@transaction null

            FriendApplies.selectAll().where {
                ((((FriendApplies.fromUid eq uid) and (FriendApplies.toUid eq targetUid)) or
                    ((FriendApplies.fromUid eq targetUid) and (FriendApplies.toUid eq uid))) and
                    (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING))
            }.orderBy(FriendApplies.id, SortOrder.DESC).limit(1).firstOrNull()?.toFriendApplyRow()
        } ?: return null
        return row.toRecord(uid)
    }

    /**
     * 两人关系写操作的唯一串行化锁。调用方必须已经位于 Exposed transaction 内；本方法不创建
     * 嵌套事务。所有入口都让 PostgreSQL 依相同的 uid 排序取得 Users 行锁，避免 A→B / B→A 死锁。
     */
    private fun lockUserPair(firstUid: String, secondUid: String): Map<String, ResultRow> {
        val expected = setOf(firstUid, secondUid)
        val locked = Users.selectAll()
            .where { Users.uid inList expected.toList() }
            .orderBy(Users.uid, SortOrder.ASC)
            .forUpdate()
            .associateBy { it[Users.uid] }
        require(locked.keys == expected) { "联系人双方用户必须存在" }
        return locked
    }

    private inline fun <T> inWriteTransaction(
        context: PgTransactionContext,
        block: Transaction.() -> T,
    ): T = context.requireExposedTransaction().block()

    private fun activeContact(uid: String, friendUid: String, friendUser: User): Contact {
        val row = Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq friendUid) and (Friends.status eq 1)
        }.single()
        return Contact(
            uid = uid,
            friendUid = friendUid,
            remark = row[Friends.remark],
            status = 1,
            user = friendUser,
        )
    }

    private fun ResultRow.toUser() = User(
        uid = this[Users.uid],
        username = this[Users.username],
        name = this[Users.name],
        avatar = this[Users.avatar],
        phone = this[Users.phone],
        sex = this[Users.sex],
        role = this[Users.role],
        status = this[Users.status],
    )

    private fun FriendApplyRow.toRecord(viewerUid: String): ContactApplyRecord {
        require(viewerUid == fromUid || viewerUid == toUid) { "申请记录不属于当前用户" }
        val incoming = viewerUid == toUid
        val peerUid = if (incoming) fromUid else toUid
        return ContactApplyRecord(
            id = id,
            fromUid = fromUid,
            toUid = toUid,
            direction = if (incoming) {
                ContactApplyRecord.DIRECTION_INCOMING
            } else {
                ContactApplyRecord.DIRECTION_OUTGOING
            },
            token = token.takeIf { incoming && status == ContactApplyRecord.STATUS_PENDING },
            remark = remark,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            peerUser = userRepo.findByUid(peerUid),
        )
    }

    private fun FriendApplyRow.toContactApply(fromUser: User?): ContactApply = ContactApply(
        id = id,
        fromUid = fromUid,
        toUid = toUid,
        token = token,
        remark = remark,
        status = status,
        createdAt = createdAt,
        fromUser = fromUser,
    )

    private fun ResultRow.toFriendApplyRow(): FriendApplyRow = FriendApplyRow(
        id = this[FriendApplies.id].value,
        fromUid = this[FriendApplies.fromUid],
        toUid = this[FriendApplies.toUid],
        token = this[FriendApplies.token],
        remark = this[FriendApplies.remark],
        status = this[FriendApplies.status],
        createdAt = this[FriendApplies.createdAt],
        updatedAt = this[FriendApplies.updatedAt],
    )

    private data class FriendApplyRow(
        val id: Long,
        val fromUid: String,
        val toUid: String,
        val token: String?,
        val remark: String?,
        val status: Int,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private companion object {
        const val MAX_PENDING_APPLIES = 100
    }
}
