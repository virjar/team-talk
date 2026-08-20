package com.virjar.tk.domain.chat

import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import com.virjar.tk.model.UserRole
import com.virjar.tk.protocol.NotifyType

class ChatService(
    private val chatStore: ChatStore,
    private val access: ChatAccess,
    private val userStore: UserStore,
    private val events: EventPublisher,
    private val conversationService: ConversationService,
    private val managedChats: ManagedChatPolicy,
    private val contacts: ContactStore,
    private val requiredParticipants: RequiredChatParticipants,
    private val lifecycleGate: ChatLifecycleGate,
) {

    // ── 创建聊天 ──

    suspend fun createPersonalChat(uid: String, targetUid: String): Chat {
        require(uid != targetUid) { "不能和自己创建私聊" }
        require(!contacts.isBlockedEither(uid, targetUid)) { "黑名单关系下不能创建私聊" }
        val chat = chatStore.createPersonalChat(uid, targetUid)
        notifyChatCreated(chat, listOf(uid, targetUid))
        return chat
    }

    suspend fun createGroup(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat {
        require(name.isNotBlank()) { "群名不能为空" }
        val chat = chatStore.createGroupChat(name, avatar, creatorUid, memberUids)
        val allUids = memberUids + creatorUid
        notifyChatCreated(chat, allUids)
        return chat
    }

    fun getChat(chatId: String): Chat? = chatStore.getChat(chatId)

    /** Client-facing detail lookup. Knowing a chat id must not reveal private/group metadata. */
    fun getChatFor(uid: String, chatId: String): Chat? {
        access.requireMember(uid, chatId)
        return chatStore.getChat(chatId)
    }

    suspend fun updateGroup(operatorUid: String, chatId: String, name: String? = null, avatar: String? = null, notice: String? = null) =
        lifecycleGate.withChat(chatId) { updateGroupInternal(operatorUid, chatId, name, avatar, notice) }

    private suspend fun updateGroupInternal(
        operatorUid: String,
        chatId: String,
        name: String?,
        avatar: String?,
        notice: String?,
    ) {
        if ((name != null || avatar != null) && managedChats.managedBy(chatId) != null) {
            throw IllegalArgumentException("受管部门群名称和头像由组织架构维护")
        }
        access.requireAdmin(operatorUid, chatId)
        chatStore.updateGroup(chatId, name, avatar, notice)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun dissolveGroup(operatorUid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        requireUserManaged(chatId)
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        require(chat.chatType == 2) { "单聊不能解散，请删除自己的会话视图" }
        access.requireOwner(operatorUid, chatId)
        val memberUids = chatStore.getMemberUids(chatId)
        requiredParticipants.onChatDeactivated(chatId)
        chatStore.deactivateChat(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_DELETED, chat)
    }

    suspend fun leaveGroup(uid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        requireUserManaged(chatId)
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        require(chat.chatType == 2) { "单聊不能退出，请删除自己的会话视图" }
        removeMemberInternal(uid, chatId, uid)
    }

    // ── 成员管理 ──

    fun getMembers(chatId: String): List<Member> =
        chatStore.getMembers(chatId).map { it.copy(user = userStore.findByUid(it.uid)) }

    /** Client-facing member lookup with the same membership boundary as chat details. */
    fun getMembersFor(uid: String, chatId: String): List<Member> {
        access.requireMember(uid, chatId)
        return getMembers(chatId)
    }

    suspend fun addMembers(operatorUid: String, chatId: String, uids: List<String>) =
        lifecycleGate.withChat(chatId) { addMembersInternal(operatorUid, chatId, uids) }

    private suspend fun addMembersInternal(operatorUid: String, chatId: String, uids: List<String>) {
        requireUserManaged(chatId)
        access.requireAdmin(operatorUid, chatId)
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

    suspend fun removeMember(operatorUid: String, chatId: String, targetUid: String) =
        lifecycleGate.withChat(chatId) { removeMemberInternal(operatorUid, chatId, targetUid) }

    private suspend fun removeMemberInternal(operatorUid: String, chatId: String, targetUid: String) {
        requireUserManaged(chatId)
        val member = access.requireGroupMember(operatorUid, chatId, "操作者不是群成员")

        if (operatorUid == targetUid) {
            if (member.role == 2) throw IllegalArgumentException("群主不能退出，请先转让群主")
            chatStore.removeMember(chatId, targetUid)
        } else {
            access.requireAdmin(operatorUid, chatId)
            requireHumanMemberTarget(targetUid)
            val target = access.requireGroupMember(targetUid, chatId, "目标不是群成员")
            if (target.role == 2) throw IllegalArgumentException("不能踢出群主")
            if (target.role == 1 && member.role != 2) throw IllegalArgumentException("只有群主能踢管理员")
            chatStore.removeMember(chatId, targetUid)
        }

        val memberUids = chatStore.getMemberUids(chatId) + targetUid
        val chat = chatStore.getChat(chatId) ?: return
        conversationService.deleteConversationProjection(targetUid, chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_REMOVED, chat)
    }

    suspend fun transferOwner(operatorUid: String, chatId: String, newOwnerUid: String) =
        lifecycleGate.withChat(chatId) { transferOwnerInternal(operatorUid, chatId, newOwnerUid) }

    private suspend fun transferOwnerInternal(operatorUid: String, chatId: String, newOwnerUid: String) {
        requireUserManaged(chatId)
        access.requireOwner(operatorUid, chatId)
        requireHumanMemberTarget(newOwnerUid)
        access.requireGroupMember(newOwnerUid, chatId, "目标不是群成员")
        chatStore.transferOwner(chatId, operatorUid, newOwnerUid)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_ROLE_CHANGED, chat)
    }

    suspend fun setRole(operatorUid: String, chatId: String, targetUid: String, role: Int) =
        lifecycleGate.withChat(chatId) { setRoleInternal(operatorUid, chatId, targetUid, role) }

    private suspend fun setRoleInternal(operatorUid: String, chatId: String, targetUid: String, role: Int) {
        requireUserManaged(chatId)
        access.requireOwner(operatorUid, chatId)
        if (role !in 0..1) throw IllegalArgumentException("角色只能是 0(member) 或 1(admin)")
        require(operatorUid != targetUid) { "群主不能修改自己的角色，请先转让群主" }
        requireHumanMemberTarget(targetUid)
        val target = access.requireGroupMember(targetUid, chatId, "目标不是群成员")
        require(target.role != 2) { "不能直接修改群主角色，请使用转让群主" }
        chatStore.setRole(chatId, targetUid, role)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_ROLE_CHANGED, chat)
    }

    // ── 禁言 ──

    suspend fun muteMember(operatorUid: String, chatId: String, targetUid: String, durationSeconds: Int) =
        lifecycleGate.withChat(chatId) { muteMemberInternal(operatorUid, chatId, targetUid, durationSeconds) }

    private suspend fun muteMemberInternal(
        operatorUid: String,
        chatId: String,
        targetUid: String,
        durationSeconds: Int,
    ) {
        requireCanManageMember(operatorUid, chatId, targetUid)
        require(durationSeconds > 0) { "禁言时长必须大于 0" }
        val expiresAt = System.currentTimeMillis() + durationSeconds * 1000L
        chatStore.muteMember(chatId, targetUid, operatorUid, expiresAt)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_MUTED, chat)
    }

    suspend fun unmuteMember(operatorUid: String, chatId: String, targetUid: String) =
        lifecycleGate.withChat(chatId) { unmuteMemberInternal(operatorUid, chatId, targetUid) }

    private suspend fun unmuteMemberInternal(operatorUid: String, chatId: String, targetUid: String) {
        requireCanManageMember(operatorUid, chatId, targetUid)
        chatStore.unmuteMember(chatId, targetUid)
        val chat = chatStore.getChat(chatId) ?: return
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MEMBER_UNMUTED, chat)
    }

    suspend fun muteAll(operatorUid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        access.requireOwner(operatorUid, chatId)
        chatStore.setMuteAll(chatId, true)
        val chat = chatStore.getChat(chatId) ?: return@withChat
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun unmuteAll(operatorUid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        access.requireOwner(operatorUid, chatId)
        chatStore.setMuteAll(chatId, false)
        val chat = chatStore.getChat(chatId) ?: return@withChat
        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    // ── 邀请链接 ──

    suspend fun createInviteLink(
        operatorUid: String,
        chatId: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
    ): String = lifecycleGate.withChat(chatId) {
        require(maxUses >= 0) { "maxUses 不能为负数，0 表示不限次数" }
        require(expiresAt >= 0) { "expiresAt 不能为负数，0 表示永不过期" }
        requireUserManaged(chatId)
        access.requireAdmin(operatorUid, chatId)
        chatStore.createInviteLink(chatId, operatorUid, name, maxUses, expiresAt)
    }

    fun listInviteLinks(operatorUid: String, chatId: String): List<InviteLinkRecord> {
        access.requireAdmin(operatorUid, chatId)
        return chatStore.listInviteLinks(chatId)
    }

    suspend fun revokeInviteLink(operatorUid: String, token: String) {
        val link = chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
        lifecycleGate.withChat(link.chatId) {
            val current = chatStore.getInviteLink(token)
                ?: throw IllegalArgumentException("邀请链接不存在")
            access.requireAdmin(operatorUid, current.chatId)
            chatStore.revokeInviteLink(token)
        }
    }

    suspend fun joinByInvite(uid: String, token: String): Chat {
        // Managed-chat ownership is a separate domain policy. The repository repeats all mutable
        // invite/chat/member validation inside its aggregate transaction.
        val chatId = chatStore.getInviteLink(token)?.chatId
            ?: throw IllegalArgumentException("邀请链接不存在")
        return lifecycleGate.withChat(chatId) {
            // Refetch policy and all mutable chat/member facts after entering the gate.
            val currentChatId = chatStore.getInviteLink(token)?.chatId
                ?: throw IllegalArgumentException("邀请链接不存在")
            require(currentChatId == chatId) { "邀请链接归属已变更" }
            requireUserManaged(chatId)
            val result = chatStore.joinByInvite(uid, token, System.currentTimeMillis())
            if (result.joined) {
                notifyChatCreated(result.chat, result.members.map { it.uid })
            }
            result.chat
        }
    }

    fun getInviteInfo(token: String): InviteLinkRecord {
        return chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
    }

    // ── 管理端操作（免权限检查，广播链路复用）──

    suspend fun adminDissolve(chatId: String) = lifecycleGate.withChat(chatId) {
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        val memberUids = chatStore.getMemberUids(chatId)
        requiredParticipants.onChatDeactivated(chatId)
        chatStore.deactivateChat(chatId)
        events.emitEvents(memberUids, NotifyType.CHAT_DELETED, chat)
    }

    suspend fun adminMuteAll(chatId: String) = lifecycleGate.withChat(chatId) {
        chatStore.setMuteAll(chatId, true)
        val memberUids = chatStore.getMemberUids(chatId)
        val chat = chatStore.getChat(chatId) ?: return@withChat
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    suspend fun adminUnmuteAll(chatId: String) = lifecycleGate.withChat(chatId) {
        chatStore.setMuteAll(chatId, false)
        val memberUids = chatStore.getMemberUids(chatId)
        val chat = chatStore.getChat(chatId) ?: return@withChat
        events.emitEvents(memberUids, NotifyType.CHAT_UPDATED, chat)
    }

    /** 创建或重新激活稳定 ID 的受管群；用于组织领域的可恢复 reconciliation。 */
    suspend fun adminEnsureManagedGroup(
        chatId: String,
        name: String,
        ownerUid: String,
        memberUids: List<String>,
    ): Chat = lifecycleGate.withChat(chatId) {
        adminEnsureManagedGroupInternal(chatId, name, ownerUid, memberUids)
    }

    private suspend fun adminEnsureManagedGroupInternal(
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
        lifecycleGate.withChat(chatId) {
            reconcileManagedGroupInternal(chatId, name, ownerUid, desiredUids)
        }
    }

    private suspend fun reconcileManagedGroupInternal(
        chatId: String,
        name: String,
        ownerUid: String,
        desiredUids: Set<String>,
    ) {
        // Read required service participants under the same lifecycle gate used by bot grants.
        // A grant created before this snapshot is retained; one created afterwards waits for the
        // reconciliation to finish and then adds itself, so neither ordering can remove it.
        val desired = desiredUids + ownerUid + requiredParticipants.forChat(chatId)
        var chat = chatStore.getChat(chatId)
            ?: adminEnsureManagedGroupInternal(chatId, name, ownerUid, desired.toList())

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
            conversationService.deleteConversationProjection(uid, chatId)
            events.emitEvent(uid, NotifyType.CHAT_DELETED, chat)
        }

        val updated = chatStore.getChat(chatId) ?: chat
        events.emitEvents(desired.toList(), NotifyType.CHAT_UPDATED, updated)
    }

    suspend fun adminDisableManagedGroup(chatId: String) {
        adminDissolve(chatId)
    }

    /** 将受治理的服务身份加入群；允许普通群和受管群，调用者必须自行持有应用授权事实。 */
    suspend fun adminAddServiceMember(chatId: String, uid: String) = lifecycleGate.withChat(chatId) {
        adminAddServiceMemberWithinLifecycle(chatId, uid)
    }

    /** Caller must already hold [lifecycleGate] for [chatId]. */
    internal suspend fun adminAddServiceMemberWithinLifecycle(chatId: String, uid: String) {
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        require(chat.chatType == 2) { "机器人只能授权到群聊" }
        if (chatStore.isMember(chatId, uid)) return
        chatStore.addMembers(chatId, listOf(uid))
        conversationService.ensureConversations(chatId, chat.chatType, listOf(uid))
        events.emitEvent(uid, NotifyType.CHAT_CREATED, chatStore.getChat(chatId) ?: chat)
        events.emitEvents(chatStore.getMemberUids(chatId), NotifyType.MEMBER_ADDED, chatStore.getChat(chatId) ?: chat)
    }

    suspend fun adminRemoveServiceMember(chatId: String, uid: String) = lifecycleGate.withChat(chatId) {
        adminRemoveServiceMemberWithinLifecycle(chatId, uid)
    }

    /** Caller must already hold [lifecycleGate] for [chatId]. */
    internal suspend fun adminRemoveServiceMemberWithinLifecycle(chatId: String, uid: String) {
        val chat = chatStore.getChat(chatId) ?: return
        if (!chatStore.isMember(chatId, uid)) return
        chatStore.removeMember(chatId, uid)
        conversationService.deleteConversationProjection(uid, chatId)
        events.emitEvent(uid, NotifyType.CHAT_DELETED, chat)
        events.emitEvents(chatStore.getMemberUids(chatId), NotifyType.MEMBER_REMOVED, chat)
    }

    /** Owner may manage admins/members; admins may only manage ordinary members. */
    private fun requireCanManageMember(operatorUid: String, chatId: String, targetUid: String) {
        requireHumanMemberTarget(targetUid)
        access.requireCanManageMember(operatorUid, chatId, targetUid)
    }

    private fun requireHumanMemberTarget(uid: String) {
        require(userStore.findByUid(uid)?.role == UserRole.HUMAN) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
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
