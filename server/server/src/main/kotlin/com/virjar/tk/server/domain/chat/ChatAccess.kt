package com.virjar.tk.server.domain.chat

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Member

/** 一次聊天授权决策所使用的、一致的权威快照。 */
data class ChatAccessSnapshot(
    val chat: Chat?,
    val members: List<Member> = emptyList(),
)

/**
 * 授权策略所需的当前 PostgreSQL 聊天事实。
 *
 * 实现绝不能读取进程本地的聊天/成员缓存。返回的聊天必须是活跃的，并且当它被外部托管时，
 * 其期望的投影修订必须已经应用。一个快照中的所有事实都从同一个数据库快照读取。
 */
interface ChatAccessSource {
    suspend fun load(chatId: String, memberUids: Set<String>): ChatAccessSnapshot
    suspend fun listAccessibleChatIds(uid: String): Set<String>

    /**
     * 在产生 [ChatAccessSnapshot] 的同一个可重复读 PostgreSQL 事务中，运行一个有界的、
     * 不挂起的受保护读取。因此由 [block] 调用的同步 Exposed 读适配器会加入当前事务，
     * 而不是打开一个"检查/使用"之间的缺口。
     */
    suspend fun <T> read(
        chatId: String,
        memberUids: Set<String>,
        includeAllMembers: Boolean,
        block: (ChatAccessSnapshot) -> T,
    ): T

    /** 全局搜索、会话与附件投影使用的多聊天对应版本。 */
    suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T
}

/**
 * 面向所有以聊天为作用域的服务器特性的统一授权词汇表。
 *
 * 领域服务仍然拥有各自操作特有的规则（禁言、拉黑、受管群策略与附件所有权）。基本的
 * 存在性、群类型、成员关系与角色检查放在这里，使新的入口点无法意外地发明更弱的语义
 * 或不同的角色阈值。
 */
class ChatAccess(
    private val source: ChatAccessSource,
) {
    suspend fun findMember(uid: String, chatId: String): Member? =
        source.load(chatId, setOf(uid)).member(uid)

    suspend fun requireMember(uid: String, chatId: String, message: String = "不是聊天成员"): Member =
        findMember(uid, chatId) ?: throw ChatAccessDeniedException(message)

    suspend fun requireMemberChat(uid: String, chatId: String, message: String = "不是聊天成员"): Chat {
        val snapshot = source.load(chatId, setOf(uid))
        val chat = snapshot.chat ?: throw ChatAccessDeniedException(message)
        if (snapshot.member(uid) == null) throw ChatAccessDeniedException(message)
        return chat
    }

    suspend fun requireGroupMember(uid: String, chatId: String, message: String = "不是群成员"): Member {
        val snapshot = source.load(chatId, setOf(uid))
        val chat = snapshot.chat ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        return snapshot.member(uid) ?: throw ChatAccessDeniedException(message)
    }

    suspend fun requireAdmin(uid: String, chatId: String): Member {
        val member = requireGroupMember(uid, chatId)
        if (member.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
        return member
    }

    suspend fun requireOwner(uid: String, chatId: String): Member {
        val member = requireGroupMember(uid, chatId)
        if (member.role != ROLE_OWNER) throw ChatAccessDeniedException("需要群主权限")
        return member
    }

    suspend fun requireCanManageMember(
        operatorUid: String,
        chatId: String,
        targetUid: String,
    ): Pair<Member, Member> {
        if (operatorUid == targetUid) throw ChatAccessDeniedException("不能管理自己")
        val snapshot = source.load(chatId, setOf(operatorUid, targetUid))
        val chat = snapshot.chat ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        val actor = snapshot.member(operatorUid)
            ?: throw ChatAccessDeniedException("操作者不是群成员")
        if (actor.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
        val target = snapshot.member(targetUid)
            ?: throw ChatAccessDeniedException("目标不是群成员")
        if (actor.role <= target.role) throw ChatAccessDeniedException("不能管理同级或更高角色")
        return actor to target
    }

    suspend fun listAccessibleChatIds(uid: String): Set<String> =
        source.listAccessibleChatIds(uid)

    suspend fun <T> readAsMember(
        uid: String,
        chatId: String,
        message: String = "不是聊天成员",
        block: (Chat, Member) -> T,
    ): T = source.read(chatId, setOf(uid), includeAllMembers = false) { snapshot ->
        val chat = snapshot.chat ?: throw ChatAccessDeniedException(message)
        val member = snapshot.member(uid) ?: throw ChatAccessDeniedException(message)
        block(chat, member)
    }

    suspend fun <T> readAsGroupMember(
        uid: String,
        chatId: String,
        message: String = "不是群成员",
        block: (Chat, Member) -> T,
    ): T = source.read(chatId, setOf(uid), includeAllMembers = false) { snapshot ->
        val chat = snapshot.chat ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        val member = snapshot.member(uid) ?: throw ChatAccessDeniedException(message)
        block(chat, member)
    }

    suspend fun <T> readAsAdmin(uid: String, chatId: String, block: (Chat, Member) -> T): T =
        readAsGroupMember(uid, chatId) { chat, member ->
            if (member.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
            block(chat, member)
        }

    suspend fun <T> readMembersFor(uid: String, chatId: String, block: (Chat, List<Member>) -> T): T =
        source.read(chatId, setOf(uid), includeAllMembers = true) { snapshot ->
            val chat = snapshot.chat
            if (chat == null || snapshot.member(uid) == null) {
                throw ChatAccessDeniedException("不是聊天成员")
            }
            block(chat, snapshot.members)
        }

    suspend fun <T> readChatMembers(chatId: String, block: (Chat, List<Member>) -> T): T =
        source.read(chatId, emptySet(), includeAllMembers = true) { snapshot ->
            val chat = snapshot.chat ?: throw ChatAccessDeniedException("聊天不存在")
            block(chat, snapshot.members)
        }

    suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
        source.readAccessibleChatIds(uid, block)

    private fun ChatAccessSnapshot.member(uid: String): Member? = members.firstOrNull { it.uid == uid }

    private companion object {
        const val ROLE_ADMIN = 1
        const val ROLE_OWNER = 2
    }
}

class ChatAccessDeniedException(message: String) : IllegalArgumentException(message)
