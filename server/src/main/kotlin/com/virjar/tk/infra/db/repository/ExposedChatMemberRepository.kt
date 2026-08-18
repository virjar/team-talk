package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.model.Member
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * GroupMembers / GroupMemberMutes 表访问。
 *
 * 注意：[setMuteAll] 操作的是 GroupChats.mutedAll 字段（全群禁言开关），
 * 语义上归属"禁言管理"，因此放在本类而非 [ChatRepository]。
 */
class ExposedChatMemberRepository : ChatMemberRepository {

    // ── 成员查询 ──

    override fun getMembers(chatId: String): List<Member> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it.toMember() }
        }
    }

    override fun getMember(chatId: String, uid: String): Member? {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .map { it.toMember() }.singleOrNull()
        }
    }

    override fun getMemberUids(chatId: String): List<String> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    override fun isMember(chatId: String, uid: String): Boolean {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .count() > 0
        }
    }

    // ── 成员变更 ──

    override fun addMembers(chatId: String, uids: List<String>) {
        val now = System.currentTimeMillis()
        transaction {
            for (uid in uids) {
                val existing = GroupMembers.selectAll().where {
                    (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                }.singleOrNull()
                if (existing == null) {
                    GroupMembers.insert {
                        it[GroupMembers.chatId] = chatId
                        it[GroupMembers.chatType] = 2
                        it[GroupMembers.uid] = uid
                        it[GroupMembers.role] = 0
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                } else {
                    GroupMembers.update({
                        (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                    }) {
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                }
            }
        }
    }

    override fun removeMember(chatId: String, uid: String) {
        transaction {
            GroupMembers.update({
                (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
            }) { it[GroupMembers.status] = 0 }
        }
    }

    override fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String) {
        transaction {
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq oldOwnerUid) }) {
                it[GroupMembers.role] = 1
            }
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq newOwnerUid) }) {
                it[GroupMembers.role] = 2
            }
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.creator] = newOwnerUid }
        }
    }

    override fun setRole(chatId: String, uid: String, role: Int) {
        transaction {
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) }) {
                it[GroupMembers.role] = role
            }
        }
    }

    // ── 禁言（单成员 / 全群） ──

    override fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long) {
        transaction {
            GroupMemberMutes.insert {
                it[GroupMemberMutes.chatId] = chatId
                it[GroupMemberMutes.uid] = uid
                it[GroupMemberMutes.operatorUid] = operatorUid
                it[GroupMemberMutes.expiresAt] = expiresAt
                it[GroupMemberMutes.createdAt] = System.currentTimeMillis()
            }
        }
    }

    override fun unmuteMember(chatId: String, uid: String) {
        transaction {
            GroupMemberMutes.deleteWhere {
                (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
            }
        }
    }

    override fun isMuted(chatId: String, uid: String): Boolean {
        return transaction {
            val now = System.currentTimeMillis()
            GroupMemberMutes.selectAll()
                .where { (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid) and (GroupMemberMutes.expiresAt greater now) }
                .count() > 0
        }
    }

    override fun setMuteAll(chatId: String, mutedAll: Boolean) {
        transaction {
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.mutedAll] = mutedAll }
        }
    }

    override fun getMutedMembers(chatId: String): List<String> {
        return transaction {
            val now = System.currentTimeMillis()
            GroupMemberMutes.selectAll()
                .where { (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.expiresAt greater now) }
                .map { it[GroupMemberMutes.uid] }
        }
    }
}

private fun ResultRow.toMember() = Member(
    uid = this[GroupMembers.uid],
    chatId = this[GroupMembers.chatId],
    role = this[GroupMembers.role],
    nickname = this[GroupMembers.nickname],
    joinedAt = this[GroupMembers.joinedAt],
)
