package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Member

/** Read-only chat facts needed by authorization policies. */
interface ChatAccessSource {
    fun getChat(chatId: String): Chat?
    fun getMember(chatId: String, uid: String): Member?
}

/**
 * One authorization vocabulary for every server feature that is scoped to a chat.
 *
 * Domain services still own operation-specific rules (mute, block, managed-group policy, and
 * attachment ownership). Basic existence, group type, membership, and role checks live here so a
 * new entry point cannot accidentally invent weaker semantics or a different role threshold.
 */
interface ChatAccess {
    fun findChat(chatId: String): Chat?
    fun findMember(uid: String, chatId: String): Member?
    fun requireChat(chatId: String): Chat
    fun requireGroup(chatId: String): Chat
    fun requireMember(uid: String, chatId: String, message: String = "不是聊天成员"): Member
    fun requireGroupMember(uid: String, chatId: String, message: String = "不是群成员"): Member
    fun requireAdmin(uid: String, chatId: String): Member
    fun requireOwner(uid: String, chatId: String): Member
    fun requireCanManageMember(operatorUid: String, chatId: String, targetUid: String): Pair<Member, Member>
}

class ChatAccessPolicy(
    private val source: ChatAccessSource,
) : ChatAccess {
    override fun findChat(chatId: String): Chat? = source.getChat(chatId)

    override fun findMember(uid: String, chatId: String): Member? = source.getMember(chatId, uid)

    override fun requireChat(chatId: String): Chat =
        findChat(chatId) ?: throw ChatAccessDeniedException("聊天不存在")

    override fun requireGroup(chatId: String): Chat {
        val chat = findChat(chatId) ?: throw ChatAccessDeniedException("群聊不存在")
        if (chat.chatType != ChatType.GROUP.code) throw ChatAccessDeniedException("群聊不存在")
        return chat
    }

    override fun requireMember(uid: String, chatId: String, message: String): Member =
        findMember(uid, chatId) ?: throw ChatAccessDeniedException(message)

    override fun requireGroupMember(uid: String, chatId: String, message: String): Member {
        requireGroup(chatId)
        return requireMember(uid, chatId, message)
    }

    override fun requireAdmin(uid: String, chatId: String): Member {
        val member = requireGroupMember(uid, chatId)
        if (member.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
        return member
    }

    override fun requireOwner(uid: String, chatId: String): Member {
        val member = requireGroupMember(uid, chatId)
        if (member.role != ROLE_OWNER) throw ChatAccessDeniedException("需要群主权限")
        return member
    }

    override fun requireCanManageMember(
        operatorUid: String,
        chatId: String,
        targetUid: String,
    ): Pair<Member, Member> {
        if (operatorUid == targetUid) throw ChatAccessDeniedException("不能管理自己")
        val actor = requireGroupMember(operatorUid, chatId, "操作者不是群成员")
        if (actor.role < ROLE_ADMIN) throw ChatAccessDeniedException("需要管理员权限")
        val target = requireGroupMember(targetUid, chatId, "目标不是群成员")
        if (actor.role <= target.role) throw ChatAccessDeniedException("不能管理同级或更高角色")
        return actor to target
    }

    private companion object {
        const val ROLE_ADMIN = 1
        const val ROLE_OWNER = 2
    }
}

class ChatAccessDeniedException(message: String) : IllegalArgumentException(message)
