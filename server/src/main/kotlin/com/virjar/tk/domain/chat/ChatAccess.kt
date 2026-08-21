package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Member

/** One coherent, authoritative snapshot used by a chat authorization decision. */
data class ChatAccessSnapshot(
    val chat: Chat?,
    val members: List<Member> = emptyList(),
)

/**
 * Current PostgreSQL chat facts needed by authorization policies.
 *
 * Implementations must not read a process-local chat/member cache. A returned chat must be active
 * and, when externally managed, its desired projection revision must already be applied. All facts
 * in one snapshot are read from one database snapshot.
 */
interface ChatAccessSource {
    suspend fun load(chatId: String, memberUids: Set<String>): ChatAccessSnapshot
    suspend fun loadAllMembers(chatId: String): ChatAccessSnapshot
    suspend fun listAccessibleChatIds(uid: String): Set<String>

    /**
     * Runs a bounded, non-suspending protected read inside the same repeatable-read PostgreSQL
     * transaction that produced [ChatAccessSnapshot]. Synchronous Exposed read adapters invoked by
     * [block] therefore join the active transaction instead of opening a check/use gap.
     */
    suspend fun <T> read(
        chatId: String,
        memberUids: Set<String>,
        includeAllMembers: Boolean,
        block: (ChatAccessSnapshot) -> T,
    ): T

    /** Multi-chat counterpart used by global search, conversation and attachment projections. */
    suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T
}

/**
 * One authorization vocabulary for every server feature that is scoped to a chat.
 *
 * Domain services still own operation-specific rules (mute, block, managed-group policy, and
 * attachment ownership). Basic existence, group type, membership, and role checks live here so a
 * new entry point cannot accidentally invent weaker semantics or a different role threshold.
 */
interface ChatAccess {
    suspend fun findChat(chatId: String): Chat?
    suspend fun findMember(uid: String, chatId: String): Member?
    suspend fun requireChat(chatId: String): Chat
    suspend fun requireGroup(chatId: String): Chat
    suspend fun requireMember(uid: String, chatId: String, message: String = "不是聊天成员"): Member
    suspend fun requireMemberChat(uid: String, chatId: String, message: String = "不是聊天成员"): Chat
    suspend fun requireGroupMember(uid: String, chatId: String, message: String = "不是群成员"): Member
    suspend fun requireAdmin(uid: String, chatId: String): Member
    suspend fun requireOwner(uid: String, chatId: String): Member
    suspend fun requireCanManageMember(
        operatorUid: String,
        chatId: String,
        targetUid: String,
    ): Pair<Member, Member>
    suspend fun listMembersFor(uid: String, chatId: String): List<Member>
    suspend fun listAccessibleChatIds(uid: String): Set<String>
    suspend fun <T> readAsMember(
        uid: String,
        chatId: String,
        message: String = "不是聊天成员",
        block: (Chat, Member) -> T,
    ): T
    suspend fun <T> readAsGroupMember(
        uid: String,
        chatId: String,
        message: String = "不是群成员",
        block: (Chat, Member) -> T,
    ): T
    suspend fun <T> readAsAdmin(uid: String, chatId: String, block: (Chat, Member) -> T): T
    suspend fun <T> readMembersFor(uid: String, chatId: String, block: (Chat, List<Member>) -> T): T
    suspend fun <T> readChatMembers(chatId: String, block: (Chat, List<Member>) -> T): T
    suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T
}

class ChatAccessPolicy(
    private val source: ChatAccessSource,
) : ChatAccess {
    override suspend fun findChat(chatId: String): Chat? =
        source.load(chatId, emptySet()).chat

    override suspend fun findMember(uid: String, chatId: String): Member? =
        source.load(chatId, setOf(uid)).member(uid)

    override suspend fun requireChat(chatId: String): Chat =
        findChat(chatId) ?: throw ChatAccessDeniedException("聊天不存在")

    override suspend fun requireGroup(chatId: String): Chat {
        val chat = findChat(chatId) ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        return chat
    }

    override suspend fun requireMember(uid: String, chatId: String, message: String): Member =
        findMember(uid, chatId) ?: throw ChatAccessDeniedException(message)

    override suspend fun requireMemberChat(uid: String, chatId: String, message: String): Chat {
        val snapshot = source.load(chatId, setOf(uid))
        val chat = snapshot.chat ?: throw ChatAccessDeniedException(message)
        if (snapshot.member(uid) == null) throw ChatAccessDeniedException(message)
        return chat
    }

    override suspend fun requireGroupMember(uid: String, chatId: String, message: String): Member {
        val snapshot = source.load(chatId, setOf(uid))
        val chat = snapshot.chat ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        return snapshot.member(uid) ?: throw ChatAccessDeniedException(message)
    }

    override suspend fun requireAdmin(uid: String, chatId: String): Member {
        val member = requireGroupMember(uid, chatId)
        if (member.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
        return member
    }

    override suspend fun requireOwner(uid: String, chatId: String): Member {
        val member = requireGroupMember(uid, chatId)
        if (member.role != ROLE_OWNER) throw ChatAccessDeniedException("需要群主权限")
        return member
    }

    override suspend fun requireCanManageMember(
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

    override suspend fun listMembersFor(uid: String, chatId: String): List<Member> {
        val snapshot = source.loadAllMembers(chatId)
        if (snapshot.chat == null || snapshot.member(uid) == null) {
            throw ChatAccessDeniedException("不是聊天成员")
        }
        return snapshot.members
    }

    override suspend fun listAccessibleChatIds(uid: String): Set<String> =
        source.listAccessibleChatIds(uid)

    override suspend fun <T> readAsMember(
        uid: String,
        chatId: String,
        message: String,
        block: (Chat, Member) -> T,
    ): T = source.read(chatId, setOf(uid), includeAllMembers = false) { snapshot ->
        val chat = snapshot.chat ?: throw ChatAccessDeniedException(message)
        val member = snapshot.member(uid) ?: throw ChatAccessDeniedException(message)
        block(chat, member)
    }

    override suspend fun <T> readAsGroupMember(
        uid: String,
        chatId: String,
        message: String,
        block: (Chat, Member) -> T,
    ): T = source.read(chatId, setOf(uid), includeAllMembers = false) { snapshot ->
        val chat = snapshot.chat ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        val member = snapshot.member(uid) ?: throw ChatAccessDeniedException(message)
        block(chat, member)
    }

    override suspend fun <T> readAsAdmin(uid: String, chatId: String, block: (Chat, Member) -> T): T =
        readAsGroupMember(uid, chatId) { chat, member ->
            if (member.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
            block(chat, member)
        }

    override suspend fun <T> readMembersFor(uid: String, chatId: String, block: (Chat, List<Member>) -> T): T =
        source.read(chatId, setOf(uid), includeAllMembers = true) { snapshot ->
            val chat = snapshot.chat
            if (chat == null || snapshot.member(uid) == null) {
                throw ChatAccessDeniedException("不是聊天成员")
            }
            block(chat, snapshot.members)
        }

    override suspend fun <T> readChatMembers(chatId: String, block: (Chat, List<Member>) -> T): T =
        source.read(chatId, emptySet(), includeAllMembers = true) { snapshot ->
            val chat = snapshot.chat ?: throw ChatAccessDeniedException("聊天不存在")
            block(chat, snapshot.members)
        }

    override suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
        source.readAccessibleChatIds(uid, block)

    private fun ChatAccessSnapshot.member(uid: String): Member? = members.firstOrNull { it.uid == uid }

    private companion object {
        const val ROLE_ADMIN = 1
        const val ROLE_OWNER = 2
    }
}

class ChatAccessDeniedException(message: String) : IllegalArgumentException(message)
