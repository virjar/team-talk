package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.infra.db.FriendApplies
import com.virjar.tk.infra.db.Friends
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedContactRepository(private val userRepo: UserRepository) : ContactRepository {

    /** 按 (uid, friendUid) 单行直查（accept 通知两行查询，替代两次全列表扫）。 */
    override fun getFriend(uid: String, friendUid: String): Contact? {
        val row = transaction {
            Friends.selectAll()
                .where { (Friends.uid eq uid) and (Friends.friendUid eq friendUid) and (Friends.status eq 1) }
                .limit(1)
                .firstOrNull()
        } ?: return null
        val friendUser = userRepo.findByUid(friendUid) ?: return null
        return Contact(uid = uid, friendUid = friendUid, remark = row[Friends.remark], status = 1, user = friendUser)
    }

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

    override fun addFriend(uid: String, friendUid: String, remark: String?) {
        transaction {
            Friends.insertIgnore {
                it[Friends.uid] = uid
                it[Friends.friendUid] = friendUid
                it[Friends.remark] = remark
                it[Friends.status] = 1
                it[Friends.createdAt] = System.currentTimeMillis()
            }
            Friends.insertIgnore {
                it[Friends.uid] = friendUid
                it[Friends.friendUid] = uid
                it[Friends.status] = 1
                it[Friends.createdAt] = System.currentTimeMillis()
            }
        }
    }

    override fun removeFriend(uid: String, friendUid: String) {
        transaction {
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq friendUid) }) {
                it[status] = 0
            }
            Friends.update({ (Friends.uid eq friendUid) and (Friends.friendUid eq uid) }) {
                it[status] = 0
            }
        }
    }

    override fun setRemark(uid: String, friendUid: String, remark: String?) {
        transaction {
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq friendUid) }) {
                it[Friends.remark] = remark
            }
        }
    }

    override fun blacklist(uid: String, targetUid: String) {
        transaction {
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
        }
    }

    override fun removeFromBlacklist(uid: String, targetUid: String) {
        transaction {
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

    override fun createApply(fromUid: String, toUid: String, remark: String?): ContactApply {
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val id = transaction {
            FriendApplies.insert {
                it[FriendApplies.fromUid] = fromUid
                it[FriendApplies.toUid] = toUid
                it[FriendApplies.token] = token
                it[FriendApplies.remark] = remark
                it[FriendApplies.status] = 0
                it[FriendApplies.createdAt] = now
                it[FriendApplies.updatedAt] = now
            }
            FriendApplies.selectAll().where { FriendApplies.token eq token }.single()[FriendApplies.id].value
        }
        val fromUser = userRepo.findByUid(fromUid)
        return ContactApply(id = id, fromUid = fromUid, toUid = toUid, token = token, remark = remark, status = 0, createdAt = now, fromUser = fromUser)
    }

    override fun acceptApply(token: String, receiverUid: String): ContactApply? {
        val result = transaction {
            val row = FriendApplies.selectAll().where {
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }.singleOrNull()
                ?: return@transaction null

            if (row[FriendApplies.status] != 0) return@transaction null

            val fromUid = row[FriendApplies.fromUid]
            val toUid = row[FriendApplies.toUid]

            val blocked = Friends.selectAll().where {
                (((Friends.uid eq fromUid) and (Friends.friendUid eq toUid)) or
                    ((Friends.uid eq toUid) and (Friends.friendUid eq fromUid))) and
                    (Friends.status eq 2)
            }.limit(1).any()
            require(!blocked) { "对方已在黑名单中，不能建立好友关系" }

            FriendApplies.update({
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }) {
                it[status] = 1
                it[updatedAt] = System.currentTimeMillis()
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

            Triple(row[FriendApplies.id].value, fromUid, toUid)
        } ?: return null

        return ContactApply(id = result.first, fromUid = result.second, toUid = result.third, status = 1)
    }

    override fun rejectApply(token: String, receiverUid: String): ContactApply? {
        val result = transaction {
            val row = FriendApplies.selectAll().where {
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }.singleOrNull()
                ?: return@transaction null
            if (row[FriendApplies.status] != 0) return@transaction null
            FriendApplies.update({
                (FriendApplies.token eq token) and (FriendApplies.toUid eq receiverUid)
            }) {
                it[status] = 2
                it[updatedAt] = System.currentTimeMillis()
            }
            Triple(row[FriendApplies.id].value, row[FriendApplies.fromUid], row[FriendApplies.toUid])
        } ?: return null

        return ContactApply(id = result.first, fromUid = result.second, toUid = result.third, status = 2)
    }

    override fun listPendingApplies(uid: String): List<ContactApply> {
        val applies = transaction {
            FriendApplies.selectAll().where { (FriendApplies.toUid eq uid) and (FriendApplies.status eq 0) }
                .orderBy(FriendApplies.createdAt, SortOrder.DESC)
                .map { row ->
                    row[FriendApplies.id].value to FriendAppliesRow(
                        fromUid = row[FriendApplies.fromUid],
                        token = row[FriendApplies.token],
                        remark = row[FriendApplies.remark],
                    )
                }
        }
        return applies.map { (id, data) ->
            ContactApply(
                id = id, fromUid = data.fromUid, toUid = uid,
                token = data.token, remark = data.remark, status = 0,
                fromUser = userRepo.findByUid(data.fromUid),
            )
        }
    }

    private data class FriendAppliesRow(val fromUid: String, val token: String, val remark: String?)
}
