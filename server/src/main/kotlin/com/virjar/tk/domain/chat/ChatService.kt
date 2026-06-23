package com.virjar.tk.domain.chat

import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import com.virjar.tk.protocol.NotifyType

class ChatService(
    private val chatStore: ChatStore,
    private val userStore: UserStore,
    private val syncEventService: SyncEventService,
) {

    // ── 创建聊天 ──

    suspend fun createPersonalChat(uid: String, targetUid: String): Chat {
        require(uid != targetUid) { "不能和自己创建私聊" }
        val chat = chatStore.createPersonalChat(uid, targetUid)
        notifyChatCreated(chat, listOf(uid, targetUid))
        return chat
    }

    suspend fun createGroup(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat {
        require(name.isNotBlank()) { "群名不能为空" }
        require(memberUids.isNotEmpty()) { "至少需要一个成员" }
        val chat = chatStore.createGroupChat(name, avatar, creatorUid, memberUids)
        val allUids = memberUids + creatorUid
        notifyChatCreated(chat, allUids)
        return chat
    }

    fun getChat(chatId: String): Chat? = chatStore.getChat(chatId)

    suspend fun updateGroup(operatorUid: String, chatId: String, name: String? = null, avatar: String? = null, notice: String? = null) {
        requireGroupAdmin(operatorUid, chatId)
        chatStore.updateGroup(chatId, name, avatar, notice)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun deleteChat(operatorUid: String, chatId: String) {
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        if (chat.chatType == 2) {
            requireOwner(operatorUid, chatId)
        }
        val memberUids = chatStore.getMemberUids(chatId)
        chatStore.deleteChat(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.CHAT_DELETED, chat)
    }

    // ── 成员管理 ──

    fun getMembers(chatId: String): List<Member> =
        chatStore.getMembers(chatId).map { it.copy(user = userStore.findByUid(it.uid)) }

    suspend fun addMembers(operatorUid: String, chatId: String, uids: List<String>) {
        requireGroupAdmin(operatorUid, chatId)
        chatStore.addMembers(chatId, uids)
        val chat = chatStore.getChat(chatId) ?: return
        val allMemberUids = chatStore.getMemberUids(chatId)
        for (uid in uids) {
            syncEventService.emitEvent(uid, NotifyType.CHAT_CREATED, chat)
        }
        syncEventService.emitEvents(allMemberUids, NotifyType.MEMBER_ADDED, chat)
    }

    suspend fun removeMember(operatorUid: String, chatId: String, targetUid: String) {
        val member = chatStore.getMember(chatId, operatorUid)
            ?: throw IllegalArgumentException("操作者不是群成员")

        if (operatorUid == targetUid) {
            if (member.role == 2) throw IllegalArgumentException("群主不能退出，请先转让群主")
            chatStore.removeMember(chatId, targetUid)
        } else {
            requireGroupAdmin(operatorUid, chatId)
            val target = chatStore.getMember(chatId, targetUid)
                ?: throw IllegalArgumentException("目标不是群成员")
            if (target.role == 2) throw IllegalArgumentException("不能踢出群主")
            if (target.role == 1 && member.role != 2) throw IllegalArgumentException("只有群主能踢管理员")
            chatStore.removeMember(chatId, targetUid)
        }

        val memberUids = chatStore.getMemberUids(chatId) + targetUid
        val chat = chatStore.getChat(chatId) ?: return
        syncEventService.emitEvents(memberUids, NotifyType.MEMBER_REMOVED, chat)
    }

    suspend fun transferOwner(operatorUid: String, chatId: String, newOwnerUid: String) {
        requireOwner(operatorUid, chatId)
        chatStore.getMember(chatId, newOwnerUid) ?: throw IllegalArgumentException("目标不是群成员")
        chatStore.transferOwner(chatId, operatorUid, newOwnerUid)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MEMBER_ROLE_CHANGED, chat)
    }

    suspend fun setRole(operatorUid: String, chatId: String, targetUid: String, role: Int) {
        requireOwner(operatorUid, chatId)
        if (role !in 0..1) throw IllegalArgumentException("角色只能是 0(member) 或 1(admin)")
        chatStore.setRole(chatId, targetUid, role)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MEMBER_ROLE_CHANGED, chat)
    }

    // ── 禁言 ──

    suspend fun muteMember(operatorUid: String, chatId: String, targetUid: String, durationSeconds: Int) {
        requireGroupAdmin(operatorUid, chatId)
        val expiresAt = System.currentTimeMillis() + durationSeconds * 1000L
        chatStore.muteMember(chatId, targetUid, operatorUid, expiresAt)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MEMBER_MUTED, chat)
    }

    suspend fun unmuteMember(operatorUid: String, chatId: String, targetUid: String) {
        requireGroupAdmin(operatorUid, chatId)
        chatStore.unmuteMember(chatId, targetUid)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MEMBER_UNMUTED, chat)
    }

    suspend fun muteAll(operatorUid: String, chatId: String) {
        requireOwner(operatorUid, chatId)
        chatStore.setMuteAll(chatId, true)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun unmuteAll(operatorUid: String, chatId: String) {
        requireOwner(operatorUid, chatId)
        chatStore.setMuteAll(chatId, false)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    // ── 邀请链接 ──

    fun createInviteLink(operatorUid: String, chatId: String, name: String, maxUses: Int, expiresAt: Long): String {
        requireGroupAdmin(operatorUid, chatId)
        return chatStore.createInviteLink(chatId, operatorUid, name, maxUses, expiresAt)
    }

    fun listInviteLinks(operatorUid: String, chatId: String): List<InviteLinkRecord> {
        requireGroupAdmin(operatorUid, chatId)
        return chatStore.listInviteLinks(chatId)
    }

    fun revokeInviteLink(operatorUid: String, token: String) {
        val link = chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
        requireGroupAdmin(operatorUid, link.chatId)
        chatStore.revokeInviteLink(token)
    }

    suspend fun joinByInvite(uid: String, token: String): Chat {
        val link = chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
        if (link.revokedAt > 0) throw IllegalArgumentException("邀请链接已失效")
        if (link.maxUses > 0 && link.useCount >= link.maxUses) throw IllegalArgumentException("邀请链接已用完")
        if (link.expiresAt > 0 && link.expiresAt < System.currentTimeMillis()) throw IllegalArgumentException("邀请链接已过期")

        val chat = chatStore.getChat(link.chatId) ?: throw IllegalArgumentException("聊天不存在")
        if (chatStore.isMember(link.chatId, uid)) return chat

        chatStore.addMembers(link.chatId, listOf(uid))
        chatStore.incrementInviteUseCount(token)

        val updatedChat = chatStore.getChat(link.chatId) ?: chat
        val memberUids = chatStore.getMemberUids(link.chatId)
        notifyChatCreated(updatedChat, memberUids)
        return updatedChat
    }

    fun getInviteInfo(token: String): InviteLinkRecord {
        return chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
    }

    // ── 权限检查 ──

    private fun requireGroupAdmin(uid: String, chatId: String) {
        val member = chatStore.getMember(chatId, uid)
            ?: throw IllegalArgumentException("不是群成员")
        if (member.role < 1) throw IllegalArgumentException("需要管理员权限")
    }

    private fun requireOwner(uid: String, chatId: String) {
        val member = chatStore.getMember(chatId, uid)
            ?: throw IllegalArgumentException("不是群成员")
        if (member.role != 2) throw IllegalArgumentException("需要群主权限")
    }

    private suspend fun notifyChatCreated(chat: Chat, uids: List<String>) {
        syncEventService.emitEvents(uids, NotifyType.CHAT_CREATED, chat)
    }
}
