package com.virjar.tk.domain.contact

import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.infra.db.FriendApplies
import com.virjar.tk.infra.db.Friends
import com.virjar.tk.model.Contact
import com.virjar.tk.model.ContactApply
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ContactRepository(private val userRepo: UserRepository) {

    fun listFriends(uid: String): List<Contact> {
        val friendRows = transaction {
            Friends.selectAll().where { (Friends.uid eq uid) and (Friends.status eq 1) }
                .map { row -> row[Friends.friendUid] to row[Friends.remark] }
        }
        return friendRows.mapNotNull { (friendUid, remark) ->
            val friendUser = userRepo.findByUid(friendUid) ?: return@mapNotNull null
            Contact(uid = uid, friendUid = friendUid, remark = remark, status = 1, user = friendUser)
        }
    }

    fun isFriend(uid: String, friendUid: String): Boolean {
        return transaction {
            Friends.selectAll().where {
                (Friends.uid eq uid) and (Friends.friendUid eq friendUid) and (Friends.status eq 1)
            }.count() > 0
        }
    }

    fun addFriend(uid: String, friendUid: String, remark: String? = null) {
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

    fun removeFriend(uid: String, friendUid: String) {
        transaction {
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq friendUid) }) {
                it[status] = 0
            }
            Friends.update({ (Friends.uid eq friendUid) and (Friends.friendUid eq uid) }) {
                it[status] = 0
            }
        }
    }

    fun setRemark(uid: String, friendUid: String, remark: String?) {
        transaction {
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq friendUid) }) {
                it[Friends.remark] = remark
            }
        }
    }

    fun blacklist(uid: String, targetUid: String) {
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
        }
    }

    fun removeFromBlacklist(uid: String, targetUid: String) {
        transaction {
            Friends.update({ (Friends.uid eq uid) and (Friends.friendUid eq targetUid) and (Friends.status eq 2) }) {
                it[status] = 0
            }
        }
    }

    fun listBlacklist(uid: String): List<Contact> {
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

    fun createApply(fromUid: String, toUid: String, remark: String?): ContactApply {
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

    fun acceptApply(token: String): ContactApply? {
        val result = transaction {
            val row = FriendApplies.selectAll().where { FriendApplies.token eq token }.singleOrNull()
                ?: return@transaction null

            if (row[FriendApplies.status] != 0) return@transaction null

            val fromUid = row[FriendApplies.fromUid]
            val toUid = row[FriendApplies.toUid]

            FriendApplies.update({ FriendApplies.token eq token }) {
                it[status] = 1
                it[updatedAt] = System.currentTimeMillis()
            }

            // 双向添加好友
            Friends.insertIgnore {
                it[Friends.uid] = fromUid
                it[Friends.friendUid] = toUid
                it[Friends.status] = 1
                it[Friends.createdAt] = System.currentTimeMillis()
            }
            Friends.insertIgnore {
                it[Friends.uid] = toUid
                it[Friends.friendUid] = fromUid
                it[Friends.status] = 1
                it[Friends.createdAt] = System.currentTimeMillis()
            }

            Triple(row[FriendApplies.id].value, fromUid, toUid)
        } ?: return null

        return ContactApply(id = result.first, fromUid = result.second, toUid = result.third, status = 1)
    }

    fun rejectApply(token: String): ContactApply? {
        val result = transaction {
            val row = FriendApplies.selectAll().where { FriendApplies.token eq token }.singleOrNull()
                ?: return@transaction null
            if (row[FriendApplies.status] != 0) return@transaction null
            FriendApplies.update({ FriendApplies.token eq token }) {
                it[status] = 2
                it[updatedAt] = System.currentTimeMillis()
            }
            Triple(row[FriendApplies.id].value, row[FriendApplies.fromUid], row[FriendApplies.toUid])
        } ?: return null

        return ContactApply(id = result.first, fromUid = result.second, toUid = result.third, status = 2)
    }

    fun listPendingApplies(uid: String): List<ContactApply> {
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
