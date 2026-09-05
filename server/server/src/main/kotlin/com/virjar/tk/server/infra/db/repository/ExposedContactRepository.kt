package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.contact.ContactApplyAcceptance
import com.virjar.tk.server.domain.contact.ContactApplyCreation
import com.virjar.tk.server.domain.contact.ContactDecisionCommand
import com.virjar.tk.server.domain.contact.ContactDecisionResult
import com.virjar.tk.server.domain.contact.ContactDecisionType
import com.virjar.tk.server.domain.contact.ContactPairMutationResult
import com.virjar.tk.server.domain.contact.ContactPolicy
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.server.infra.db.FriendApplies
import com.virjar.tk.server.infra.db.Friends
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

internal data class ContactCapacityLimits(
    val friendsPerUser: Int = ContactPolicy.MAX_FRIENDS_PER_USER,
    val blacklistEntriesPerUser: Int = ContactPolicy.MAX_BLACKLIST_ENTRIES_PER_USER,
    val outgoingPendingAppliesPerUser: Int = ContactPolicy.MAX_OUTGOING_PENDING_APPLIES_PER_USER,
    val incomingPendingAppliesPerUser: Int = ContactPolicy.MAX_INCOMING_PENDING_APPLIES_PER_USER,
    val terminalApplyRecordsPerUser: Int = ContactPolicy.MAX_TERMINAL_APPLY_RECORDS_PER_USER,
) {
    val maximumApplyRowsPerUser: Int = run {
        val total = outgoingPendingAppliesPerUser.toLong() + incomingPendingAppliesPerUser.toLong() +
            terminalApplyRecordsPerUser.toLong()
        require(total < Int.MAX_VALUE) { "好友申请总容量过大" }
        total.toInt()
    }

    init {
        require(
            friendsPerUser > 0 &&
                blacklistEntriesPerUser > 0 &&
                outgoingPendingAppliesPerUser > 0 &&
                incomingPendingAppliesPerUser > 0 &&
                terminalApplyRecordsPerUser > 0
        ) { "联系人容量必须为正数" }
    }
}

class ExposedContactRepository internal constructor(
    private val database: Database,
    private val userRepo: UserRepository,
    private val capacity: ContactCapacityLimits = ContactCapacityLimits(),
    hooks: ContactRepositoryHooks = ContactRepositoryHooks.None,
) : ContactRepository {
    private val decisionReceipts = ExposedContactDecisionReceiptStore(hooks)

    override fun listFriends(uid: String): List<Contact> = transaction(database) {
        val contacts = friendUserJoin().selectAll()
            .where { (Friends.uid eq uid) and (Friends.status eq 1) }
            .orderBy(Friends.friendUid, SortOrder.ASC)
            .limit(capacity.friendsPerUser + 1)
            .map { row ->
                Contact(
                    uid = uid,
                    friendUid = row[Friends.friendUid],
                    remark = row[Friends.remark],
                    status = 1,
                    user = row.toUser(),
                )
            }
        check(contacts.size <= capacity.friendsPerUser) { "联系人持久化数量超出边界" }
        contacts
    }

    private fun friendUserJoin(): Join = Friends.join(
        otherTable = Users,
        joinType = JoinType.INNER,
        additionalConstraint = { Friends.friendUid eq Users.uid },
    )

    private fun applySenderJoin(): Join = FriendApplies.join(
        otherTable = Users,
        joinType = JoinType.LEFT,
        additionalConstraint = { FriendApplies.fromUid eq Users.uid },
    )

    private fun applyPeerJoin(viewerUid: String): Join = FriendApplies.join(
        otherTable = Users,
        joinType = JoinType.LEFT,
        additionalConstraint = {
            (((FriendApplies.fromUid eq viewerUid) and (FriendApplies.toUid eq Users.uid)) or
                ((FriendApplies.toUid eq viewerUid) and (FriendApplies.fromUid eq Users.uid)))
        },
    )

    override fun listFriendUids(uid: String): Set<String> = transaction(database) {
        listFriendUidsInCurrentTransaction(uid)
    }

    override fun listFriendUids(transaction: PgWriteTransactionContext, uid: String): Set<String> =
        inWriteTransaction(transaction) {
            listFriendUidsInCurrentTransaction(uid)
        }

    private fun listFriendUidsInCurrentTransaction(uid: String): Set<String> {
        val friendUids = Friends.selectAll().where { (Friends.uid eq uid) and (Friends.status eq 1) }
            .orderBy(Friends.friendUid, SortOrder.ASC)
            .limit(capacity.friendsPerUser + 1)
            .mapTo(linkedSetOf()) { it[Friends.friendUid] }
        check(friendUids.size <= capacity.friendsPerUser) { "联系人持久化数量超出边界" }
        return friendUids
    }

    override fun isFriend(uid: String, friendUid: String): Boolean {
        return transaction(database) {
            Friends.selectAll().where {
                (Friends.uid eq uid) and (Friends.friendUid eq friendUid) and (Friends.status eq 1)
            }.count() > 0
        }
    }

    override fun isBlocked(uid: String, targetUid: String): Boolean = transaction(database) {
        Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq targetUid) and (Friends.status eq 2)
        }.limit(1).any()
    }

    override fun addFriend(
        transaction: PgWriteTransactionContext,
        uid: String,
        friendUid: String,
        remark: String?,
    ) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, friendUid)
            requireRelationshipCapacity(uid, friendUid, 1, capacity.friendsPerUser, "联系人")
            requireRelationshipCapacity(friendUid, uid, 1, capacity.friendsPerUser, "联系人")
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

    override fun removeFriend(
        transaction: PgWriteTransactionContext,
        uid: String,
        friendUid: String,
    ): ContactPairMutationResult = inWriteTransaction(transaction) {
        lockUserPair(uid, friendUid)
        val actorRemoved = Friends.update({
            (Friends.uid eq uid) and
                (Friends.friendUid eq friendUid) and
                (Friends.status eq 1)
        }) {
            it[status] = 0
        } > 0
        val targetRemoved = Friends.update({
            (Friends.uid eq friendUid) and
                (Friends.friendUid eq uid) and
                (Friends.status eq 1)
        }) {
            it[status] = 0
        } > 0
        ContactPairMutationResult(
            actorChanged = actorRemoved,
            targetChanged = targetRemoved,
        )
    }

    override fun setRemark(
        transaction: PgWriteTransactionContext,
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

    override fun blacklist(
        transaction: PgWriteTransactionContext,
        uid: String,
        targetUid: String,
    ): ContactPairMutationResult = inWriteTransaction(transaction) {
        lockUserPair(uid, targetUid)
        requireRelationshipCapacity(
            uid,
            targetUid,
            status = 2,
            maximum = capacity.blacklistEntriesPerUser,
            label = "黑名单",
        )
        val actorStatus = Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq targetUid)
        }.limit(1).singleOrNull()?.get(Friends.status)
        val actorFriendshipRemoved = actorStatus == 1
        when (actorStatus) {
            2 -> Unit
            null -> Friends.insert {
                it[Friends.uid] = uid
                it[Friends.friendUid] = targetUid
                it[Friends.status] = 2
                it[Friends.createdAt] = System.currentTimeMillis()
            }
            0, 1 -> {
                val previousStatus = requireNotNull(actorStatus)
                val updated = Friends.update({
                    (Friends.uid eq uid) and
                        (Friends.friendUid eq targetUid) and
                        (Friends.status eq previousStatus)
                }) {
                    it[status] = 2
                }
                check(updated == 1) { "黑名单关系迁移丢失了已锁定行" }
            }
            else -> error("未知的联系人状态: $actorStatus")
        }
        // 拉黑会终止双方用户的好友投影。之后解除拉黑
        // 绝不能在没有新请求的情况下静默重建关系。
        val targetFriendshipRemoved = Friends.update({
            (Friends.uid eq targetUid) and (Friends.friendUid eq uid) and (Friends.status eq 1)
        }) {
            it[status] = 0
        } > 0

        // 拉黑在关系语义上同时终止双方之间尚未处理的申请。否则 apply 先完成、
        // blacklist 后完成时仍会留下可处理 token 和红点，解除拉黑后还会复活旧请求。
        val updatedAt = System.currentTimeMillis()
        val pendingApplyRemoved = FriendApplies.update({
            ((((FriendApplies.fromUid eq uid) and (FriendApplies.toUid eq targetUid)) or
                ((FriendApplies.fromUid eq targetUid) and (FriendApplies.toUid eq uid))) and
                (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING))
        }) {
            it[status] = ContactApplyRecord.STATUS_REJECTED
            it[FriendApplies.token] = null
            it[FriendApplies.updatedAt] = updatedAt
        } > 0
        if (pendingApplyRemoved) pruneTerminalApplyHistory(uid, targetUid)
        ContactPairMutationResult(
            actorChanged = actorFriendshipRemoved || pendingApplyRemoved,
            targetChanged = targetFriendshipRemoved || pendingApplyRemoved,
        )
    }

    override fun removeFromBlacklist(transaction: PgWriteTransactionContext, uid: String, targetUid: String) {
        inWriteTransaction(transaction) {
            lockUserPair(uid, targetUid)
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq targetUid) and (Friends.status eq 2) }) {
                it[status] = 0
            }
        }
    }

    override fun listBlacklist(uid: String): List<Contact> = transaction(database) {
        val contacts = friendUserJoin().selectAll()
            .where { (Friends.uid eq uid) and (Friends.status eq 2) }
            .orderBy(Friends.friendUid, SortOrder.ASC)
            .limit(capacity.blacklistEntriesPerUser + 1)
            .map { row ->
                Contact(
                    uid = uid,
                    friendUid = row[Friends.friendUid],
                    status = 2,
                    user = row.toUser(),
                )
            }
        check(contacts.size <= capacity.blacklistEntriesPerUser) { "黑名单持久化数量超出边界" }
        contacts
    }

    // ── 好友申请 ──

    override fun createApply(
        transaction: PgWriteTransactionContext,
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

            requirePendingApplyCapacity(fromUid, toUid)
            requireRelationshipCapacity(fromUid, toUid, 1, capacity.friendsPerUser, "联系人")
            requireRelationshipCapacity(toUid, fromUid, 1, capacity.friendsPerUser, "联系人")

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

    override fun decideApply(
        transaction: PgWriteTransactionContext,
        command: ContactDecisionCommand,
    ): ContactDecisionResult? = inWriteTransaction(transaction) {
        requireCanonicalDecisionCommand(command)
        ReliableCommandPolicy.requireActiveIssuedAt(
            command.issuedAt,
            System.currentTimeMillis(),
            "好友申请处理",
        )
        decisionReceipts.reserveOrReplay(command)?.let { replay ->
            // 唯一插入的落败者可能曾等待获胜者。不要返回一个
            // 在等待该事务期间越过有限重放边界的结果。
            ReliableCommandPolicy.requireActiveIssuedAt(
                command.issuedAt,
                System.currentTimeMillis(),
                "好友申请处理",
            )
            return@inWriteTransaction ContactDecisionResult(
                apply = replay,
                firstCommit = false,
            )
        }

        // token 行先只用于解析稳定不变的双方 uid；真正的状态读取必须等取得 pair lock 后重做。
        // 不能先 FOR UPDATE 申请行再锁 User，否则会与 createApply 的 User -> Apply 顺序相反。
        val identity = FriendApplies.selectAll().where {
            (FriendApplies.token eq command.token) and
                (FriendApplies.toUid eq command.receiverUid)
        }.singleOrNull() ?: return@inWriteTransaction null
        val fromUid = identity[FriendApplies.fromUid]
        val toUid = identity[FriendApplies.toUid]
        val users = lockUserPair(fromUid, toUid)
        val decisionNow = System.currentTimeMillis()
        ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, decisionNow, "好友申请处理")
        decisionReceipts.pruneExpiredAndRequireCapacity(command.receiverUid, decisionNow)

        val row = FriendApplies.selectAll().where {
            (FriendApplies.token eq command.token) and
                (FriendApplies.toUid eq command.receiverUid)
        }.forUpdate().singleOrNull()
            ?: return@inWriteTransaction null

        if (row[FriendApplies.status] != ContactApplyRecord.STATUS_PENDING) return@inWriteTransaction null

        val updatedAt = System.currentTimeMillis()
        val result = when (command.decision) {
            ContactDecisionType.ACCEPT -> acceptPendingApply(row, users, fromUid, toUid, updatedAt)
            ContactDecisionType.REJECT -> rejectPendingApply(row, users, fromUid, toUid, updatedAt)
            else -> error("Validated contact decision type became invalid")
        }
        pruneTerminalApplyHistory(fromUid, toUid)
        decisionReceipts.complete(command, result.apply)
        result
    }

    private fun acceptPendingApply(
        row: ResultRow,
        users: Map<String, ResultRow>,
        fromUid: String,
        toUid: String,
        updatedAt: Long,
    ): ContactDecisionResult {
        val blocked = Friends.selectAll().where {
            (((Friends.uid eq fromUid) and (Friends.friendUid eq toUid)) or
                ((Friends.uid eq toUid) and (Friends.friendUid eq fromUid))) and
                (Friends.status eq 2)
        }.limit(1).any()
        require(!blocked) { "对方已在黑名单中，不能建立好友关系" }

        requireRelationshipCapacity(fromUid, toUid, 1, capacity.friendsPerUser, "联系人")
        requireRelationshipCapacity(toUid, fromUid, 1, capacity.friendsPerUser, "联系人")

        FriendApplies.update({
            ((((FriendApplies.fromUid eq fromUid) and (FriendApplies.toUid eq toUid)) or
                ((FriendApplies.fromUid eq toUid) and (FriendApplies.toUid eq fromUid))) and
                (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING))
        }) {
            it[status] = ContactApplyRecord.STATUS_ACCEPTED
            it[FriendApplies.token] = null
            it[FriendApplies.updatedAt] = updatedAt
        }

        // 已删除/拉黑的关系行保留其唯一身份，因此在插入之前先复活。
        val fromUpdated = Friends.update({
            (Friends.uid eq fromUid) and (Friends.friendUid eq toUid)
        }) { it[Friends.status] = 1 }
        if (fromUpdated == 0) {
            Friends.insert {
                it[Friends.uid] = fromUid
                it[Friends.friendUid] = toUid
                it[Friends.status] = 1
                it[Friends.createdAt] = updatedAt
            }
        }
        val toUpdated = Friends.update({
            (Friends.uid eq toUid) and (Friends.friendUid eq fromUid)
        }) { it[Friends.status] = 1 }
        if (toUpdated == 0) {
            Friends.insert {
                it[Friends.uid] = toUid
                it[Friends.friendUid] = fromUid
                it[Friends.status] = 1
                it[Friends.createdAt] = updatedAt
            }
        }

        val apply = row.toFriendApplyRow().copy(
            token = null,
            status = ContactApplyRecord.STATUS_ACCEPTED,
            updatedAt = updatedAt,
        ).toContactApply(users.getValue(fromUid).toUser())
        return ContactDecisionResult(
            apply = apply,
            firstCommit = true,
            acceptance = ContactApplyAcceptance(
                fromSide = activeContact(fromUid, toUid, users.getValue(toUid).toUser()),
                toSide = activeContact(toUid, fromUid, users.getValue(fromUid).toUser()),
            ),
        )
    }

    private fun rejectPendingApply(
        row: ResultRow,
        users: Map<String, ResultRow>,
        fromUid: String,
        toUid: String,
        updatedAt: Long,
    ): ContactDecisionResult {
        FriendApplies.update({
            (FriendApplies.fromUid eq fromUid) and
                (FriendApplies.toUid eq toUid) and
                (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING)
        }) {
            it[status] = ContactApplyRecord.STATUS_REJECTED
            it[FriendApplies.token] = null
            it[FriendApplies.updatedAt] = updatedAt
        }
        return ContactDecisionResult(
            apply = row.toFriendApplyRow().copy(
                token = null,
                status = ContactApplyRecord.STATUS_REJECTED,
                updatedAt = updatedAt,
            ).toContactApply(users.getValue(fromUid).toUser()),
            firstCommit = true,
        )
    }

    override fun listPendingApplies(uid: String): List<ContactApply> {
        return transaction(database) {
            val pending = applySenderJoin().selectAll()
                .where { (FriendApplies.toUid eq uid) and (FriendApplies.status eq 0) }
                .orderBy(FriendApplies.id, SortOrder.DESC)
                .limit(capacity.incomingPendingAppliesPerUser + 1)
                .map { row ->
                    row.toFriendApplyRow().toContactApply(row.toJoinedUserOrNull())
                }
            check(pending.size <= capacity.incomingPendingAppliesPerUser) {
                "收到的待处理好友申请持久化数量超出边界"
            }
            pending
        }
    }

    override fun listApplyRecords(uid: String, beforeId: Long, limit: Int): List<ContactApplyRecord> {
        return transaction(database) {
            applyPeerJoin(uid).selectAll().where {
                val belongsToUser = (FriendApplies.fromUid eq uid) or (FriendApplies.toUid eq uid)
                if (beforeId > 0) {
                    belongsToUser and (FriendApplies.id less beforeId)
                } else {
                    belongsToUser
                }
            }
                .orderBy(FriendApplies.id, SortOrder.DESC)
                .limit(limit)
                .map { row -> row.toFriendApplyRow().toRecord(uid, row.toJoinedUserOrNull()) }
        }
    }

    override fun getPendingApply(uid: String, targetUid: String): ContactApplyRecord? {
        val row = transaction(database) {
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
        return row.toRecord(uid, userRepo.findByUid(if (uid == row.toUid) row.fromUid else row.toUid))
    }

    private fun requireCanonicalDecisionCommand(command: ContactDecisionCommand) {
        check(command.operationId == UUID.fromString(command.operationId).toString()) {
            "好友申请操作标识不是规范 UUID"
        }
        ContactDecisionType.requireValid(command.decision)
        check(command.issuedAt >= 0L) { "好友申请操作签发时间非法" }
        check(
            command.requestFingerprint.length == 64 &&
                command.requestFingerprint.all { it in '0'..'9' || it in 'a'..'f' },
        ) { "好友申请操作指纹非法" }
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

    /**
     * 调用方持有两行 User，包括每个申请状态迁移使用的发送者/接收者聚合 fence。
     * 因此计数与插入构成一个可序列化的配额
     * 决策，即使一个账户并发地向许多不同的对端申请。
     */
    private fun requirePendingApplyCapacity(fromUid: String, toUid: String) {
        val outgoing = FriendApplies.selectAll().where {
            (FriendApplies.fromUid eq fromUid) and
                (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING)
        }.count()
        require(outgoing < capacity.outgoingPendingAppliesPerUser.toLong()) {
            "发出的待处理好友申请数量已达上限"
        }

        val incoming = FriendApplies.selectAll().where {
            (FriendApplies.toUid eq toUid) and
                (FriendApplies.status eq ContactApplyRecord.STATUS_PENDING)
        }.count()
        require(incoming < capacity.incomingPendingAppliesPerUser.toLong()) {
            "收到的待处理好友申请数量已达上限"
        }
    }

    /**
     * 为每个参与者只保留固定数量的终结便利历史。
     *
     * 一行同时属于两份历史。因此只要任一方超出其预算，
     * 它就符合删除条件。来自两个用户的候选行在删除之前
     * 按全局 id 顺序锁定，因此两个不相交的对事务
     * 清理同一旧行时不会形成申请行锁环。外层的 User 锁
     * 在此有界清理运行期间阻止当前对的新行或状态迁移。
     */
    private fun pruneTerminalApplyHistory(vararg participantUids: String) {
        val deletions = linkedSetOf<EntityID<Long>>()
        participantUids.distinct().sorted().forEach { uid ->
            val terminalIds = FriendApplies.select(FriendApplies.id).where {
                (((FriendApplies.fromUid eq uid) or (FriendApplies.toUid eq uid)) and
                    (FriendApplies.status neq ContactApplyRecord.STATUS_PENDING))
            }
                .orderBy(
                    FriendApplies.updatedAt to SortOrder.DESC,
                    FriendApplies.id to SortOrder.DESC,
                )
                .limit(capacity.maximumApplyRowsPerUser + 1)
                .map { it[FriendApplies.id] }
            check(terminalIds.size <= capacity.maximumApplyRowsPerUser) {
                "好友申请持久化数量超出可清理边界"
            }
            deletions += terminalIds.drop(capacity.terminalApplyRecordsPerUser)
        }
        if (deletions.isEmpty()) return

        val orderedIds = deletions.sortedBy { it.value }
        val lockedIds = FriendApplies.select(FriendApplies.id)
            .where { FriendApplies.id inList orderedIds }
            .orderBy(FriendApplies.id, SortOrder.ASC)
            .forUpdate()
            .map { it[FriendApplies.id] }
        lockedIds.forEach { applyId ->
            check(FriendApplies.deleteWhere { FriendApplies.id eq applyId } == 1) {
                "已锁定的好友申请清理行丢失"
            }
        }
    }

    /** 调用方持有拥有者 User 行，它是每次关系写入的聚合 fence。 */
    private fun requireRelationshipCapacity(
        ownerUid: String,
        peerUid: String,
        status: Int,
        maximum: Int,
        label: String,
    ) {
        val alreadyProjected = Friends.selectAll().where {
            (Friends.uid eq ownerUid) and (Friends.friendUid eq peerUid) and (Friends.status eq status)
        }.limit(1).any()
        if (alreadyProjected) return

        val current = Friends.selectAll().where {
            (Friends.uid eq ownerUid) and (Friends.status eq status)
        }.count()
        require(current < maximum.toLong()) { "${label}数量已达上限" }
    }

    private inline fun <T> inWriteTransaction(
        context: PgWriteTransactionContext,
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
        avatar = toUserAvatar(),
        phone = this[Users.phone],
        sex = this[Users.sex],
        role = this[Users.role],
        status = this[Users.status],
        revision = this[Users.revision],
    )

    private fun ResultRow.toJoinedUserOrNull(): User? =
        if (getOrNull(Users.uid) == null) null else toUser()

    private fun FriendApplyRow.toRecord(viewerUid: String, peerUser: User?): ContactApplyRecord {
        require(viewerUid == fromUid || viewerUid == toUid) { "申请记录不属于当前用户" }
        val incoming = viewerUid == toUid
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
            peerUser = peerUser,
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

}
