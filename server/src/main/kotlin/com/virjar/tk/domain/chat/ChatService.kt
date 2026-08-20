package com.virjar.tk.domain.chat

import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import com.virjar.tk.protocol.NotifyType

class ChatService(
    private val chatStore: ChatStore,
    private val userStore: UserStore,
    private val events: EventPublisher,
    private val conversationService: ConversationService,
    private val managedChats: ManagedChatPolicy,
    private val contacts: ContactStore,
) {

    // ── 创建聊天 ──

    suspend fun createPersonalChat(uid: String, targetUid: String): Chat {
        require(uid != targetUid) { "不能和自己创建私聊" }
        require(!contacts.isBlockedEither(uid, targetUid)) { "黑名单关系下不能创建私聊" }
        val chat = chatStore.createPersonalChat(uid, targetUid)
        // 预创建会话行，确保 markRead 有行可更新（readSeq 多设备同步基础）
        conversationService.ensureConversations(chat.chatId, chat.chatType, listOf(uid, targetUid))
        notifyChatCreated(chat, listOf(uid, targetUid))
        return chat
    }

    suspend fun createGroup(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat {
        require(name.isNotBlank()) { "群名不能为空" }
        val chat = chatStore.createGroupChat(name, avatar, creatorUid, memberUids)
        val allUids = memberUids + creatorUid
        conversationService.ensureConversations(chat.chatId, chat.chatType, allUids)
        notifyChatCreated(chat, allUids)
        return chat
    }

    fun getChat(chatId: String): Chat? = chatStore.getChat(chatId)

    /** Client-facing detail lookup. Knowing a chat id must not reveal private/group metadata. */
    fun getChatFor(uid: String, chatId: String): Chat? {
        requireMember(uid, chatId)
        return chatStore.getChat(chatId)
    }

    suspend fun updateGroup(operatorUid: String, chatId: String, name: String? = null, avatar: String? = null, notice: String? = null) {
        if ((name != null || avatar != null) && managedChats.managedBy(chatId) != null) {
            throw IllegalArgumentException("受管部门群名称和头像由组织架构维护")
        }
        requireGroupAdmin(operatorUid, chatId)
        chatStore.updateGroup(chatId, name, avatar, notice)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun dissolveGroup(operatorUid: String, chatId: String) {
        requireUserManaged(chatId)
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        require(chat.chatType == 2) { "单聊不能解散，请删除自己的会话视图" }
        requireOwner(operatorUid, chatId)
        val memberUids = chatStore.getMemberUids(chatId)
        chatStore.deactivateChat(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_DELETED, chat)
    }

    suspend fun leaveGroup(uid: String, chatId: String) {
        requireUserManaged(chatId)
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        require(chat.chatType == 2) { "单聊不能退出，请删除自己的会话视图" }
        removeMember(uid, chatId, uid)
    }

    // ── 成员管理 ──

    fun getMembers(chatId: String): List<Member> =
        chatStore.getMembers(chatId).map { it.copy(user = userStore.findByUid(it.uid)) }

    /** Client-facing member lookup with the same membership boundary as chat details. */
    fun getMembersFor(uid: String, chatId: String): List<Member> {
        requireMember(uid, chatId)
        return getMembers(chatId)
    }

    suspend fun addMembers(operatorUid: String, chatId: String, uids: List<String>) {
        requireUserManaged(chatId)
        requireGroupAdmin(operatorUid, chatId)
        chatStore.addMembers(chatId, uids)
        val chat = chatStore.getChat(chatId) ?: return
        // 新成员预创建会话行
        conversationService.ensureConversations(chatId, chat.chatType, uids)
        val allMemberUids = chatStore.getMemberUids(chatId)
        for (uid in uids) {
            events.emitEvent(uid, NotifyType.CHAT_CREATED, chat)
        }
        events.emitEvents(allMemberUids, NotifyType.MEMBER_ADDED, chat)
    }

    suspend fun removeMember(operatorUid: String, chatId: String, targetUid: String) {
        requireUserManaged(chatId)
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
        conversationService.deleteConversation(targetUid, chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_REMOVED, chat)
    }

    suspend fun transferOwner(operatorUid: String, chatId: String, newOwnerUid: String) {
        requireUserManaged(chatId)
        requireOwner(operatorUid, chatId)
        chatStore.getMember(chatId, newOwnerUid) ?: throw IllegalArgumentException("目标不是群成员")
        chatStore.transferOwner(chatId, operatorUid, newOwnerUid)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_ROLE_CHANGED, chat)
    }

    suspend fun setRole(operatorUid: String, chatId: String, targetUid: String, role: Int) {
        requireUserManaged(chatId)
        requireOwner(operatorUid, chatId)
        if (role !in 0..1) throw IllegalArgumentException("角色只能是 0(member) 或 1(admin)")
        require(operatorUid != targetUid) { "群主不能修改自己的角色，请先转让群主" }
        val target = chatStore.getMember(chatId, targetUid)
            ?: throw IllegalArgumentException("目标不是群成员")
        require(target.role != 2) { "不能直接修改群主角色，请使用转让群主" }
        chatStore.setRole(chatId, targetUid, role)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_ROLE_CHANGED, chat)
    }

    // ── 禁言 ──

    suspend fun muteMember(operatorUid: String, chatId: String, targetUid: String, durationSeconds: Int) {
        requireCanManageMember(operatorUid, chatId, targetUid)
        require(durationSeconds > 0) { "禁言时长必须大于 0" }
        val expiresAt = System.currentTimeMillis() + durationSeconds * 1000L
        chatStore.muteMember(chatId, targetUid, operatorUid, expiresAt)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_MUTED, chat)
    }

    suspend fun unmuteMember(operatorUid: String, chatId: String, targetUid: String) {
        requireCanManageMember(operatorUid, chatId, targetUid)
        chatStore.unmuteMember(chatId, targetUid)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_UNMUTED, chat)
    }

    suspend fun muteAll(operatorUid: String, chatId: String) {
        requireOwner(operatorUid, chatId)
        chatStore.setMuteAll(chatId, true)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun unmuteAll(operatorUid: String, chatId: String) {
        requireOwner(operatorUid, chatId)
        chatStore.setMuteAll(chatId, false)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    // ── 邀请链接 ──

    fun createInviteLink(operatorUid: String, chatId: String, name: String, maxUses: Int, expiresAt: Long): String {
        requireUserManaged(chatId)
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
        requireUserManaged(chat.chatId)
        if (chatStore.isMember(link.chatId, uid)) return chat

        chatStore.addMembers(link.chatId, listOf(uid))
        chatStore.incrementInviteUseCount(token)

        val updatedChat = chatStore.getChat(link.chatId) ?: chat
        // 新成员预创建会话行
        conversationService.ensureConversations(link.chatId, updatedChat.chatType, listOf(uid))
        val memberUids = chatStore.getMemberUids(link.chatId)
        notifyChatCreated(updatedChat, memberUids)
        return updatedChat
    }

    fun getInviteInfo(token: String): InviteLinkRecord {
        return chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
    }

    // ── 管理端操作（免权限检查，广播链路复用）──

    suspend fun adminDissolve(chatId: String) {
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        val memberUids = chatStore.getMemberUids(chatId)
        chatStore.deactivateChat(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_DELETED, chat)
    }

    suspend fun adminMuteAll(chatId: String) {
        chatStore.setMuteAll(chatId, true)
        val memberUids = chatStore.getMemberUids(chatId)
        val chat = chatStore.getChat(chatId) ?: return
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun adminUnmuteAll(chatId: String) {
        chatStore.setMuteAll(chatId, false)
        val memberUids = chatStore.getMemberUids(chatId)
        val chat = chatStore.getChat(chatId) ?: return
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    /** 创建或重新激活稳定 ID 的受管群；用于组织领域的可恢复 reconciliation。 */
    suspend fun adminEnsureManagedGroup(
        chatId: String,
        name: String,
        ownerUid: String,
        memberUids: List<String>,
    ): Chat {
        val chat = chatStore.createGroupChat(
            name = name,
            avatar = null,
            creatorUid = ownerUid,
            memberUids = memberUids.filter { it != ownerUid },
            requestedChatId = chatId,
        )
        val allUids = (memberUids + ownerUid).distinct()
        conversationService.ensureConversations(chat.chatId, chat.chatType, allUids)
        notifyChatCreated(chat, allUids)
        return chat
    }

    /**
     * 以外部领域的成员集合为唯一事实源收敛受管群。该操作幂等，服务启动时可以安全重放。
     */
    suspend fun adminReconcileManagedGroup(chatId: String, name: String, ownerUid: String, desiredUids: Set<String>) {
        val desired = desiredUids + ownerUid
        var chat = chatStore.getChat(chatId)
            ?: adminEnsureManagedGroup(chatId, name, ownerUid, desired.toList())

        chatStore.updateGroup(chatId, name, null, null)

        val before = chatStore.getMembers(chatId)
        val currentOwner = before.firstOrNull { it.role == 2 }?.uid
        if (!chatStore.isMember(chatId, ownerUid)) {
            chatStore.addMembers(chatId, listOf(ownerUid))
            conversationService.ensureConversations(chatId, chat.chatType, listOf(ownerUid))
        }
        if (currentOwner != null && currentOwner != ownerUid) {
            chatStore.transferOwner(chatId, currentOwner, ownerUid)
        } else if (currentOwner == null) {
            chatStore.setRole(chatId, ownerUid, 2)
        }

        val current = chatStore.getMemberUids(chatId).toSet()
        val added = desired - current
        if (added.isNotEmpty()) {
            chatStore.addMembers(chatId, added.toList())
            conversationService.ensureConversations(chatId, chat.chatType, added.toList())
            chat = chatStore.getChat(chatId) ?: chat
            for (uid in added) events.emitEvent(uid, NotifyType.CHAT_CREATED, chat)
        }

        val removed = current - desired
        for (uid in removed) {
            chatStore.removeMember(chatId, uid)
            conversationService.deleteConversation(uid, chatId)
            events.emitEvent(uid, NotifyType.CHAT_DELETED, chat)
        }

        val updated = chatStore.getChat(chatId) ?: chat
        events.emitEvents(desired.toList(), NotifyType.CHAT_UPDATED, updated)
    }

    suspend fun adminDisableManagedGroup(chatId: String) {
        adminDissolve(chatId)
    }

    /** 将受治理的服务身份加入群；允许普通群和受管群，调用者必须自行持有应用授权事实。 */
    suspend fun adminAddServiceMember(chatId: String, uid: String) {
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        require(chat.chatType == 2) { "机器人只能授权到群聊" }
        if (chatStore.isMember(chatId, uid)) return
        chatStore.addMembers(chatId, listOf(uid))
        conversationService.ensureConversations(chatId, chat.chatType, listOf(uid))
        events.emitEvent(uid, NotifyType.CHAT_CREATED, chatStore.getChat(chatId) ?: chat)
        events.emitEvents(chatStore.getMemberUids(chatId), NotifyType.MEMBER_ADDED, chatStore.getChat(chatId) ?: chat)
    }

    suspend fun adminRemoveServiceMember(chatId: String, uid: String) {
        val chat = chatStore.getChat(chatId) ?: return
        if (!chatStore.isMember(chatId, uid)) return
        chatStore.removeMember(chatId, uid)
        conversationService.deleteConversation(uid, chatId)
        events.emitEvent(uid, NotifyType.CHAT_DELETED, chat)
        events.emitEvents(chatStore.getMemberUids(chatId), NotifyType.MEMBER_REMOVED, chat)
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

    private fun requireMember(uid: String, chatId: String) {
        chatStore.getMember(chatId, uid) ?: throw IllegalArgumentException("不是聊天成员")
    }

    /** Owner may manage admins/members; admins may only manage ordinary members. */
    private fun requireCanManageMember(operatorUid: String, chatId: String, targetUid: String) {
        require(operatorUid != targetUid) { "不能管理自己" }
        val actor = chatStore.getMember(chatId, operatorUid)
            ?: throw IllegalArgumentException("操作者不是群成员")
        require(actor.role >= 1) { "需要管理员权限" }
        val target = chatStore.getMember(chatId, targetUid)
            ?: throw IllegalArgumentException("目标不是群成员")
        require(actor.role > target.role) { "不能管理同级或更高角色" }
    }

    private fun requireUserManaged(chatId: String) {
        managedChats.managedBy(chatId)?.let { owner ->
            throw IllegalArgumentException("该群由${owner}维护，不能手工修改成员或生命周期")
        }
    }

    private suspend fun notifyChatCreated(chat: Chat, uids: List<String>) {
        events.emitEvents(uids, NotifyType.CHAT_CREATED, chat)
    }
}
