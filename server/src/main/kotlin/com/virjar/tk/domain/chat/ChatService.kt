package com.virjar.tk.domain.chat

import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Conversation
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
    private val unitOfWork: PgUnitOfWork,
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
        deactivateGroupWithDurableEvents(chatId) { transaction, chat ->
            require(chat.chatType == 2) { "单聊不能解散，请删除自己的会话视图" }
            val actor = chatStore.getActiveMember(transaction, chatId, operatorUid)
                ?: throw IllegalArgumentException("操作者不是群成员")
            require(actor.role == 2) { "需要群主权限" }
        }
    }

    suspend fun leaveGroup(uid: String, chatId: String) = lifecycleGate.withChat(chatId) {
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
        // Fail closed before resolving target identities; the transaction callback below repeats
        // the authoritative role check under the locked membership snapshot.
        access.requireAdmin(operatorUid, chatId)
        val targets = uids.distinct()
        targets.forEach(::requireHumanMemberTarget)
        addMembersWithDurableEvents(chatId, operatorUid, targets) { facts ->
            require(facts.chat.chatType == 2) { "单聊不能添加成员" }
            val operator = facts.operator ?: throw IllegalArgumentException("操作者不是群成员")
            require(operator.role >= 1) { "需要管理员权限" }
        }
    }

    /** Membership, Conversation creation and recipient events commit as one aggregate write. */
    private suspend fun addMembersWithDurableEvents(
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ) {
        unitOfWork.write {
            val addition = chatStore.addMembers(
                transaction = transaction,
                chatId = chatId,
                operatorUid = operatorUid,
                uids = uids,
                authorize = authorize,
            )
            if (addition.addedUids.isEmpty()) return@write
            addition.addedUids.forEach { uid ->
                appendEvent(uid, NotifyType.CHAT_CREATED, addition.chat)
            }
            addition.activeMemberUids.forEach { uid ->
                appendEvent(uid, NotifyType.MEMBER_ADDED, addition.chat)
            }
            afterCommit { chatStore.invalidateCommittedMembershipChange(chatId) }
        }
    }

    suspend fun removeMember(operatorUid: String, chatId: String, targetUid: String) =
        lifecycleGate.withChat(chatId) { removeMemberInternal(operatorUid, chatId, targetUid) }

    private suspend fun removeMemberInternal(operatorUid: String, chatId: String, targetUid: String) {
        requireUserManaged(chatId)
        removeMemberWithDurableTombstones(chatId, operatorUid, targetUid) { facts ->
            require(facts.chat.chatType == 2) { "单聊不能退出，请删除自己的会话视图" }
            val operator = facts.operator
                ?: throw IllegalArgumentException("操作者不是群成员")
            val target = facts.target
                ?: throw IllegalArgumentException("目标不是群成员")
            if (operatorUid == targetUid) {
                if (operator.role == 2) {
                    throw IllegalArgumentException("群主不能退出，请先转让群主")
                }
            } else {
                if (operator.role < 1) throw IllegalArgumentException("需要管理员权限")
                requireHumanMemberTarget(targetUid)
                if (target.role == 2) throw IllegalArgumentException("不能踢出群主")
                if (target.role == 1 && operator.role != 2) {
                    throw IllegalArgumentException("只有群主能踢管理员")
                }
            }
        }
    }

    /**
     * Membership authority, conversation deletion and every per-user tombstone share one PG
     * transaction. Internal managed/service-member callers use this boundary too; otherwise a
     * crash after deactivating the member would leave offline clients permanently authorized.
     */
    private suspend fun removeMemberWithDurableTombstones(
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ) {
        unitOfWork.write {
            val removal = chatStore.removeMember(
                transaction = transaction,
                chatId = chatId,
                operatorUid = operatorUid,
                targetUid = targetUid,
                authorize = authorize,
            )

            // The target's local authority must be a chat tombstone, never a MEMBER_REMOVED
            // refresh hint. Remaining members keep the group and only refresh its member view.
            // Persist the privacy tombstone first in the target's ordered stream. If the client
            // stops between these two events, content is already gone; the list tombstone is a
            // compatibility projection and is idempotent after CHAT_DELETED.
            appendEvent(targetUid, NotifyType.CHAT_DELETED, removal.chat)
            appendEvent(
                targetUid,
                NotifyType.CONVERSATION_DELETED,
                Conversation(chatId = chatId, chatType = 0),
            )
            removal.remainingMemberUids.forEach { uid ->
                appendEvent(uid, NotifyType.MEMBER_REMOVED, removal.chat)
            }
            afterCommit { chatStore.invalidateCommittedMembershipChange(chatId) }
        }
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
            unitOfWork.write {
                val result = chatStore.joinByInvite(
                    transaction = transaction,
                    uid = uid,
                    token = token,
                    nowMillis = System.currentTimeMillis(),
                )
                if (result.joined) {
                    result.members.forEach { member ->
                        appendEvent(member.uid, NotifyType.CHAT_CREATED, result.chat)
                    }
                    afterCommit { chatStore.invalidateCommittedInviteJoin(chatId) }
                }
                result.chat
            }
        }
    }

    fun getInviteInfo(token: String): InviteLinkRecord {
        return chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
    }

    // ── 管理端操作（免权限检查，广播链路复用）──

    suspend fun adminDissolve(chatId: String) = lifecycleGate.withChat(chatId) {
        deactivateGroupWithDurableEvents(chatId) { _, _ -> Unit }
    }

    /** Chat, bot/grant facts, members, Conversations and tombstones share one commit boundary. */
    private suspend fun deactivateGroupWithDurableEvents(
        chatId: String,
        authorize: (com.virjar.tk.domain.transaction.PgTransactionContext, Chat) -> Unit,
    ) {
        unitOfWork.write {
            val chat = chatStore.lockChats(transaction, listOf(chatId), requireActive = true)
                .getValue(chatId).chat
            authorize(transaction, chat)
            requiredParticipants.deactivateForChat(transaction, chatId)
            val deactivation = chatStore.deactivateChat(transaction, chatId)
            deactivation.memberUids.forEach { uid ->
                appendEvent(uid, NotifyType.CHAT_DELETED, deactivation.chat)
            }
            afterCommit { chatStore.invalidateCommittedDeactivation(chatId) }
        }
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
            addMembersWithDurableEvents(chatId, ownerUid, listOf(ownerUid)) { facts ->
                require(facts.chat.chatType == 2) { "受管群主只能存在于群聊" }
            }
        }
        if (currentOwner != null && currentOwner != ownerUid) {
            chatStore.transferOwner(chatId, currentOwner, ownerUid)
        } else if (currentOwner == null) {
            chatStore.setRole(chatId, ownerUid, 2)
        }

        val current = chatStore.getMemberUids(chatId).toSet()
        val added = desired - current
        if (added.isNotEmpty()) {
            addMembersWithDurableEvents(chatId, ownerUid, added.toList()) { facts ->
                require(facts.chat.chatType == 2) { "受管成员只能存在于群聊" }
            }
            chat = chatStore.getChat(chatId) ?: chat
        }

        val removed = current - desired
        for (uid in removed) {
            removeMemberWithDurableTombstones(chatId, uid, uid) { facts ->
                require(facts.chat.chatType == 2) { "受管成员只能存在于群聊" }
                val target = facts.target ?: throw IllegalArgumentException("受管成员不是群成员")
                require(target.role != 2) { "受管群主必须先完成权威转移" }
            }
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
        addMembersWithDurableEvents(chatId, uid, listOf(uid)) { facts ->
            require(facts.chat.chatType == 2) { "机器人只能授权到群聊" }
        }
    }

    /** Read side used only to reconcile service-domain projections. */
    internal fun activeChatIdsForServiceMember(uid: String): Set<String> =
        chatStore.getActiveChatIds(uid)

    internal fun activeChatIdsForServiceMember(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        uid: String,
    ): Set<String> = chatStore.getActiveChatIds(transaction, uid)

    internal fun projectedChatIdsForServiceMember(uid: String): Set<String> =
        chatStore.getProjectedChatIds(uid)

    internal fun projectedChatIdsForServiceMember(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        uid: String,
    ): Set<String> = chatStore.getProjectedChatIds(transaction, uid)

    internal fun lockChatsForServiceMember(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = chatStore.lockChats(transaction, chatIds, requireActive)

    internal fun getActiveMemberForService(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = chatStore.getActiveMember(transaction, chatId, uid)

    /**
     * Transaction-bound service membership mutation. The external domain owns event intents and
     * post-commit invalidation so it can atomically include its own authorization fact.
     */
    internal fun addServiceMember(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = chatStore.addMembers(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        uids = listOf(uid),
        authorize = authorize,
    )

    suspend fun adminRemoveServiceMember(chatId: String, uid: String) = lifecycleGate.withChat(chatId) {
        adminRemoveServiceMemberWithinLifecycle(chatId, uid)
    }

    /** Caller must already hold [lifecycleGate] for [chatId]. */
    internal suspend fun adminRemoveServiceMemberWithinLifecycle(chatId: String, uid: String) {
        chatStore.getChat(chatId) ?: return
        if (!chatStore.isMember(chatId, uid)) return
        removeMemberWithDurableTombstones(chatId, uid, uid) { facts ->
            require(facts.chat.chatType == 2) { "服务身份只能存在于群聊" }
            val target = facts.target ?: throw IllegalArgumentException("服务身份不是群成员")
            require(target.role != 2) { "不能移除群主" }
        }
    }

    /** Transaction-bound, idempotent counterpart to [adminRemoveServiceMemberWithinLifecycle]. */
    internal fun removeServiceMemberIfPresent(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        requireActiveChat: Boolean = true,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = chatStore.removeMemberIfPresent(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = uid,
        requireActiveChat = requireActiveChat,
        authorize = authorize,
    )

    internal fun cleanupServiceMemberProjection(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = chatStore.cleanupServiceMemberProjection(
        transaction,
        chatId,
        uid,
        lockedChat,
    )

    internal fun invalidateCommittedServiceMembershipChange(chatId: String) {
        chatStore.invalidateCommittedMembershipChange(chatId)
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
