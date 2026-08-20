package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.ChatMemberRepository
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
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
            val chatType = lockActiveChat(chatId)[Chats.chatType]
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
                } else if (existing[GroupMembers.status] != 1) {
                    GroupMembers.update({
                        (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                    }) {
                        // A normal rejoin is a fresh ordinary membership. Persisting the old role
                        // would let a previously removed admin silently regain privileges.
                        it[GroupMembers.role] = 0
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                }
                Conversations.insertIgnore {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = chatType
                    it[Conversations.lastMsgSeq] = 0
                    it[Conversations.updatedAt] = now
                }
            }
        }
    }

    override fun removeMember(chatId: String, uid: String) {
        transaction {
            lockActiveChat(chatId)
            val member = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }.singleOrNull() ?: return@transaction
            require(member[GroupMembers.role] != 2) { "群主不能退出，请先转让群主" }
            GroupMembers.update({
                (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
            }) { it[GroupMembers.status] = 0 }
            Conversations.deleteWhere {
                (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
            }
            GroupMemberMutes.deleteWhere {
                (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
            }
        }
    }

    override fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String) {
        transaction {
            lockActiveChat(chatId)
            val members = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid inList listOf(oldOwnerUid, newOwnerUid)) and
                    (GroupMembers.status eq 1)
            }.associateBy { it[GroupMembers.uid] }
            require(members[oldOwnerUid]?.get(GroupMembers.role) == 2) { "操作者不是群主" }
            require(members.containsKey(newOwnerUid)) { "目标不是群成员" }
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
            lockActiveChat(chatId)
            val member = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }.singleOrNull() ?: throw IllegalArgumentException("目标不是群成员")
            require(member[GroupMembers.role] != 2 || role == 2) {
                "不能直接修改群主角色，请使用转让群主"
            }
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) }) {
                it[GroupMembers.role] = role
            }
            if (role == 2) {
                GroupChats.update({ GroupChats.chatId eq chatId }) {
                    it[GroupChats.creator] = uid
                }
            }
        }
    }

    // ── 禁言（单成员 / 全群） ──

    override fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long) {
        transaction {
            lockActiveChat(chatId)
            require(GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }.count() == 1L) { "目标不是群成员" }
            GroupMemberMutes.upsert(GroupMemberMutes.chatId, GroupMemberMutes.uid) {
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
            lockActiveChat(chatId)
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
            lockActiveChat(chatId)
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

    private fun lockActiveChat(chatId: String): ResultRow {
        return Chats.selectAll().where {
            (Chats.chatId eq chatId) and (Chats.status eq 1)
        }.forUpdate().singleOrNull() ?: throw IllegalArgumentException("聊天不存在")
    }
}

private fun ResultRow.toMember() = Member(
    uid = this[GroupMembers.uid],
    chatId = this[GroupMembers.chatId],
    role = this[GroupMembers.role],
    nickname = this[GroupMembers.nickname],
    joinedAt = this[GroupMembers.joinedAt],
)
