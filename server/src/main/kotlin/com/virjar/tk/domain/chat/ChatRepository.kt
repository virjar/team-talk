package com.virjar.tk.domain.chat

import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.model.Chat
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Chats 表访问 + Chat 视图组装。
 *
 * 成员管理见 [ChatMemberRepository]，邀请链接见 [InviteLinkRepository]。
 *
 * 注意：[createPersonalChat] / [createGroupChat] 会同时初始化 GroupMembers，
 * 因为这是"创建 chat 时的关联初始化"，不应割裂给两个 Repository。
 */
class ChatRepository {

    // ── Chat CRUD ──

    fun createPersonalChat(uid1: String, uid2: String): Chat {
        return transaction {
            val existingChatId = findPersonalChatIdInternal(uid1, uid2)
            if (existingChatId != null) {
                return@transaction getChatByIdInternal(existingChatId)!!
            }

            val chatId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            Chats.insert {
                it[Chats.chatId] = chatId
                it[Chats.chatType] = 1
                it[Chats.maxSeq] = 0
                it[Chats.status] = 1
                it[Chats.createdAt] = now
                it[Chats.updatedAt] = now
            }
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 1
                it[GroupMembers.uid] = uid1
                it[GroupMembers.role] = 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 1
                it[GroupMembers.uid] = uid2
                it[GroupMembers.role] = 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            // 为双方创建 conversation 记录
            for (uid in listOf(uid1, uid2)) {
                Conversations.insertIgnore {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = 1
                    it[Conversations.lastMsgSeq] = 0
                                        it[Conversations.updatedAt] = now
                }
            }
            Chat(chatId = chatId, chatType = 1)
        }
    }

    fun createGroupChat(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat {
        return transaction {
            val chatId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            Chats.insert {
                it[Chats.chatId] = chatId
                it[Chats.chatType] = 2
                it[Chats.maxSeq] = 0
                it[Chats.status] = 1
                it[Chats.createdAt] = now
                it[Chats.updatedAt] = now
            }
            GroupChats.insert {
                it[GroupChats.chatId] = chatId
                it[GroupChats.name] = name
                it[GroupChats.avatar] = avatar
                it[GroupChats.creator] = creatorUid
                it[GroupChats.mutedAll] = false
                it[GroupChats.updatedAt] = now
            }
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 2
                it[GroupMembers.uid] = creatorUid
                it[GroupMembers.role] = 2
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            for (uid in memberUids) {
                GroupMembers.insertIgnore {
                    it[GroupMembers.chatId] = chatId
                    it[GroupMembers.chatType] = 2
                    it[GroupMembers.uid] = uid
                    it[GroupMembers.role] = 0
                    it[GroupMembers.status] = 1
                    it[GroupMembers.joinedAt] = now
                }
            }
            // 为所有成员创建 conversation 记录，确保会话列表能显示群聊
            val allUids = memberUids + creatorUid
            for (uid in allUids) {
                Conversations.insertIgnore {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = 2
                    it[Conversations.lastMsgSeq] = 0
                                        it[Conversations.updatedAt] = now
                }
            }
            Chat(
                chatId = chatId, chatType = 2, name = name, avatar = avatar,
                creator = creatorUid, memberCount = memberUids.size + 1,
            )
        }
    }

    fun getChat(chatId: String): Chat? {
        return transaction { getChatByIdInternal(chatId) }
    }

    fun updateGroup(chatId: String, name: String? = null, avatar: String? = null, notice: String? = null) {
        transaction {
            name?.let { v -> GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.name] = v } }
            avatar?.let { v -> GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.avatar] = v } }
            notice?.let { v -> GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.notice] = v } }
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.updatedAt] = System.currentTimeMillis() }
            Chats.update({ Chats.chatId eq chatId }) { it[Chats.updatedAt] = System.currentTimeMillis() }
        }
    }

    fun deleteChat(chatId: String) {
        transaction { Chats.update({ Chats.chatId eq chatId }) { it[Chats.status] = 0 } }
    }

    fun getMemberUids(chatId: String): List<String> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    fun updateMaxSeq(chatId: String, seq: Long) {
        transaction {
            Chats.update({ (Chats.chatId eq chatId) and (Chats.maxSeq less seq) }) {
                it[Chats.maxSeq] = seq
                it[Chats.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    // ── 查询 ──

    fun findPersonalChatId(uid1: String, uid2: String): String? {
        return transaction { findPersonalChatIdInternal(uid1, uid2) }
    }

    fun listUserChats(uid: String): List<Chat> {
        return transaction {
            val chatIds = GroupMembers.selectAll()
                .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.chatId] }.toSet()

            Chats.selectAll()
                .where { (Chats.chatId inList chatIds) and (Chats.status eq 1) }
                .map { row -> buildChatFromRow(row) }
        }
    }

    // ── 内部辅助（在 transaction 内调用） ──

    private fun findPersonalChatIdInternal(uid1: String, uid2: String): String? {
        val chatIds = GroupMembers.selectAll()
            .where { (GroupMembers.uid eq uid1) and (GroupMembers.chatType eq 1) and (GroupMembers.status eq 1) }
            .map { it[GroupMembers.chatId] }
        return GroupMembers.selectAll()
            .where { (GroupMembers.uid eq uid2) and (GroupMembers.chatId inList chatIds) and (GroupMembers.status eq 1) }
            .map { it[GroupMembers.chatId] }.firstOrNull()
    }

    private fun getChatByIdInternal(chatId: String): Chat? {
        val row = Chats.selectAll().where { Chats.chatId eq chatId }.singleOrNull() ?: return null
        return buildChatFromRow(row)
    }

    private fun buildChatFromRow(row: ResultRow): Chat {
        val chatId = row[Chats.chatId]
        val chatType = row[Chats.chatType]
        val maxSeq = row[Chats.maxSeq]

        if (chatType == 1) {
            return Chat(chatId = chatId, chatType = 1, maxSeq = maxSeq)
        }

        val gc = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
        val memberCount = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .count().toInt()

        return Chat(
            chatId = chatId,
            chatType = 2,
            name = gc?.get(GroupChats.name) ?: "",
            avatar = gc?.get(GroupChats.avatar),
            creator = gc?.get(GroupChats.creator),
            memberCount = memberCount,
            maxSeq = maxSeq,
            notice = gc?.get(GroupChats.notice),
            mutedAll = gc?.get(GroupChats.mutedAll) ?: false,
        )
    }
}
