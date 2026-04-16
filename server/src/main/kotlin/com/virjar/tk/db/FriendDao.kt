package com.virjar.tk.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class FriendRow(
    val id: Long,
    val uid: String,
    val friendUid: String,
    val remark: String,
    val status: Int,
    val version: Long,
)

data class FriendApplyRow(
    val id: Long,
    val fromUid: String,
    val toUid: String,
    val token: String,
    val remark: String,
    val status: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

object FriendDao {

    /** 启动时批量加载所有好友关系对（同步事务） */
    fun loadAllFriendPairs(): List<Pair<String, String>> = transaction {
        Friends.selectAll().where { Friends.status eq 1 }
            .map { it[Friends.uid] to it[Friends.friendUid] }
    }

    fun addFriend(uid: String, friendUid: String, remark: String = ""): FriendRow = transaction {
        val now = System.currentTimeMillis()
        val id = Friends.insert {
            it[Friends.uid] = uid
            it[Friends.friendUid] = friendUid
            it[Friends.remark] = remark
            it[Friends.version] = now
            it[Friends.createdAt] = now
        } get Friends.id

        FriendRow(id.value, uid, friendUid, remark, 1, now)
    }

    fun removeFriend(uid: String, friendUid: String) = transaction {
        Friends.deleteWhere {
            with(SqlExpressionBuilder) {
                (Friends.uid eq uid) and (Friends.friendUid eq friendUid)
            }
        }
    }

    fun updateRemark(uid: String, friendUid: String, remark: String) = transaction {
        Friends.update({
            with(SqlExpressionBuilder) {
                (Friends.uid eq uid) and (Friends.friendUid eq friendUid)
            }
        }) {
            it[Friends.remark] = remark
            it[Friends.version] = System.currentTimeMillis()
        }
    }

    fun getFriends(uid: String, version: Long = 0): List<FriendRow> = transaction {
        Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.status eq 1) and (Friends.version greater version)
        }.map { it.toFriendRow() }
    }

    fun isFriend(uid: String, friendUid: String): Boolean = transaction {
        Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq friendUid) and (Friends.status eq 1)
        }.count() > 0
    }

    /** 轻量查询：只返回好友 uid 列表（PRESENCE 广播用） */
    fun getFriendUids(uid: String): List<String> = transaction {
        Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.status eq 1)
        }.map { it[Friends.friendUid] }
    }

    fun createApply(fromUid: String, toUid: String, remark: String): FriendApplyRow = transaction {
        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString().replace("-", "")
        val id = FriendApplies.insert {
            it[FriendApplies.fromUid] = fromUid
            it[FriendApplies.toUid] = toUid
            it[FriendApplies.token] = token
            it[FriendApplies.remark] = remark
            it[FriendApplies.createdAt] = now
            it[FriendApplies.updatedAt] = now
        } get FriendApplies.id

        FriendApplyRow(id.value, fromUid, toUid, token, remark, 0, now, now)
    }

    fun acceptApply(token: String): FriendApplyRow? = transaction {
        val row = FriendApplies.selectAll().where { FriendApplies.token eq token }
            .singleOrNull() ?: return@transaction null

        if (row[FriendApplies.status] != 0) return@transaction null

        val now = System.currentTimeMillis()
        FriendApplies.update({ FriendApplies.token eq token }) {
            it[status] = 1
            it[updatedAt] = now
        }

        // Create bidirectional friend relationship
        val fromUid = row[FriendApplies.fromUid]
        val toUid = row[FriendApplies.toUid]

        val existing1 = Friends.selectAll().where {
            (Friends.uid eq fromUid) and (Friends.friendUid eq toUid)
        }.singleOrNull()
        if (existing1 == null) {
            Friends.insert {
                it[Friends.uid] = fromUid
                it[Friends.friendUid] = toUid
                it[Friends.version] = now
                it[Friends.createdAt] = now
            }
        } else {
            Friends.update({
                with(SqlExpressionBuilder) {
                    (Friends.uid eq fromUid) and (Friends.friendUid eq toUid)
                }
            }) {
                it[status] = 1
                it[Friends.version] = now
            }
        }

        val existing2 = Friends.selectAll().where {
            (Friends.uid eq toUid) and (Friends.friendUid eq fromUid)
        }.singleOrNull()
        if (existing2 == null) {
            Friends.insert {
                it[Friends.uid] = toUid
                it[Friends.friendUid] = fromUid
                it[Friends.version] = now
                it[Friends.createdAt] = now
            }
        } else {
            Friends.update({
                with(SqlExpressionBuilder) {
                    (Friends.uid eq toUid) and (Friends.friendUid eq fromUid)
                }
            }) {
                it[status] = 1
                it[Friends.version] = now
            }
        }

        FriendApplyRow(
            row[FriendApplies.id].value,
            fromUid, toUid, token, row[FriendApplies.remark], 1, row[FriendApplies.createdAt], now
        )
    }

    fun rejectApply(token: String): Boolean = transaction {
        val updated = FriendApplies.update({ FriendApplies.token eq token }) {
            it[status] = 2
            it[updatedAt] = System.currentTimeMillis()
        }
        updated > 0
    }

    fun getApplies(uid: String, page: Int = 1, pageSize: Int = 20): List<FriendApplyRow> = transaction {
        FriendApplies.selectAll().where { FriendApplies.toUid eq uid }
            .orderBy(FriendApplies.createdAt, SortOrder.DESC)
            .limit(pageSize).offset(((page - 1) * pageSize).toLong())
            .map { it.toFriendApplyRow() }
    }

    fun getBlacklist(uid: String): List<FriendRow> = transaction {
        Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.status eq 2)
        }.map { it.toFriendRow() }
    }

    fun blockUser(uid: String, targetUid: String) = transaction {
        val existing = Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq targetUid)
        }.singleOrNull()

        val now = System.currentTimeMillis()
        if (existing != null) {
            Friends.update({
                with(SqlExpressionBuilder) {
                    (Friends.uid eq uid) and (Friends.friendUid eq targetUid)
                }
            }) {
                it[status] = 2
                it[Friends.version] = now
            }
        } else {
            Friends.insert {
                it[Friends.uid] = uid
                it[Friends.friendUid] = targetUid
                it[status] = 2
                it[Friends.version] = now
                it[Friends.createdAt] = now
            }
        }
    }

    fun unblockUser(uid: String, targetUid: String) = transaction {
        val existing = Friends.selectAll().where {
            (Friends.uid eq uid) and (Friends.friendUid eq targetUid) and (Friends.status eq 2)
        }.singleOrNull()

        if (existing != null) {
            // If there was a bidirectional friendship before, restore to status=1
            // Otherwise delete the record
            val reverseExists = Friends.selectAll().where {
                (Friends.uid eq targetUid) and (Friends.friendUid eq uid) and (Friends.status eq 1)
            }.count() > 0

            if (reverseExists) {
                Friends.update({
                    with(SqlExpressionBuilder) {
                        (Friends.uid eq uid) and (Friends.friendUid eq targetUid)
                    }
                }) {
                    it[status] = 1
                    it[Friends.version] = System.currentTimeMillis()
                }
            } else {
                Friends.deleteWhere {
                    with(SqlExpressionBuilder) {
                        (Friends.uid eq uid) and (Friends.friendUid eq targetUid)
                    }
                }
            }
        }
    }

    private fun ResultRow.toFriendRow() = FriendRow(
        id = this[Friends.id].value,
        uid = this[Friends.uid],
        friendUid = this[Friends.friendUid],
        remark = this[Friends.remark],
        status = this[Friends.status],
        version = this[Friends.version],
    )

    private fun ResultRow.toFriendApplyRow() = FriendApplyRow(
        id = this[FriendApplies.id].value,
        fromUid = this[FriendApplies.fromUid],
        toUid = this[FriendApplies.toUid],
        token = this[FriendApplies.token],
        remark = this[FriendApplies.remark],
        status = this[FriendApplies.status],
        createdAt = this[FriendApplies.createdAt],
        updatedAt = this[FriendApplies.updatedAt],
    )
}
