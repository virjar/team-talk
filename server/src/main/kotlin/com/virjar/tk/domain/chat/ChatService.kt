package com.virjar.tk.domain.chat

import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Member
import com.virjar.tk.model.UserRole
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatService(
    private val chatStore: ChatStore,
    private val access: ChatAccess,
    private val userStore: UserStore,
    private val managedChats: ManagedChatPolicy,
    private val contacts: ContactStore,
    private val requiredParticipants: RequiredChatParticipants,
    private val lifecycleGate: ChatLifecycleGate,
    private val unitOfWork: PgUnitOfWork,
) {

    // ── 创建聊天 ──

    suspend fun createPersonalChat(uid: String, targetUid: String): Chat {
        require(uid != targetUid) { "不能和自己创建私聊" }
        // Fast fail for the common case; the repository repeats this check after locking both
        // User rows so a concurrent blacklist write cannot race the actual creation.
        require(!contacts.isBlockedEither(uid, targetUid)) { "黑名单关系下不能创建私聊" }
        return unitOfWork.write {
            val creation = chatStore.createPersonalChat(transaction, uid, targetUid)
            if (creation.created) {
                creation.recipientUids.forEach { recipient ->
                    appendEvent(recipient, NotifyType.CHAT_CREATED, creation.chat)
                }
                afterCommit { chatStore.invalidateCommittedCommand(creation.chat.chatId) }
            }
            creation.chat
        }
    }

    suspend fun createGroup(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat {
        require(name.isNotBlank()) { "群名不能为空" }
        return unitOfWork.write {
            val creation = chatStore.createGroupChat(
                transaction,
                name,
                avatar,
                creatorUid,
                memberUids,
            )
            creation.recipientUids.forEach { recipient ->
                appendEvent(recipient, NotifyType.CHAT_CREATED, creation.chat)
            }
            afterCommit { chatStore.invalidateCommittedCommand(creation.chat.chatId) }
            creation.chat
        }
    }

    fun getChat(chatId: String): Chat? = chatStore.getChat(chatId)

    /** Client-facing detail lookup. Knowing a chat id must not reveal private/group metadata. */
    suspend fun getChatFor(uid: String, chatId: String): Chat =
        access.requireMemberChat(uid, chatId)

    suspend fun updateGroup(operatorUid: String, chatId: String, name: String? = null, avatar: String? = null, notice: String? = null) =
        lifecycleGate.withChat(chatId) { updateGroupInternal(operatorUid, chatId, name, avatar, notice) }

    private suspend fun updateGroupInternal(
        operatorUid: String,
        chatId: String,
        name: String?,
        avatar: String?,
        notice: String?,
    ) {
        unitOfWork.write {
            val authority = lockReadyAuthority(transaction, chatId)
            if ((name != null || avatar != null) && authority.managed) {
                throw IllegalArgumentException("受管部门群名称和头像由组织架构维护")
            }
            val mutation = chatStore.updateGroup(
                transaction,
                chatId,
                operatorUid,
                name,
                avatar,
                notice,
            ) { facts -> requireAdmin(facts.operator) }
            mutation.recipientUids.forEach { uid ->
                appendEvent(uid, NotifyType.CHAT_UPDATED, mutation.chat)
            }
            afterCommit { chatStore.invalidateCommittedCommand(chatId) }
        }
    }

    suspend fun dissolveGroup(operatorUid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        deactivateGroupWithDurableEvents(chatId, operatorUid) { facts ->
            require(facts.chat.chatType == 2) { "单聊不能解散，请删除自己的会话视图" }
            requireOwner(facts.operator)
        }
    }

    suspend fun leaveGroup(uid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        removeMemberInternal(uid, chatId, uid)
    }

    // ── 成员管理 ──

    fun getMembers(chatId: String): List<Member> =
        chatStore.getMembers(chatId).map { it.copy(user = userStore.findByUid(it.uid)) }

    /** Client-facing member lookup with the same membership boundary as chat details. */
    suspend fun getMembersFor(uid: String, chatId: String): List<Member> =
        withContext(Dispatchers.IO) {
            access.readMembersFor(uid, chatId) { _, members ->
                members.map { member -> member.copy(user = userStore.findByUid(member.uid)) }
            }
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
        addMembersWithDurableEvents(
            chatId = chatId,
            operatorUid = operatorUid,
            uids = targets,
            requiredHumanUids = (targets + operatorUid).toSet(),
        ) { facts ->
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
        requiredHumanUids: Set<String> = emptySet(),
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ) {
        unitOfWork.write {
            requireUserWritableAuthority(transaction, chatId)
            val addition = chatStore.addMembers(
                transaction = transaction,
                chatId = chatId,
                operatorUid = operatorUid,
                uids = uids,
                requiredHumanUids = requiredHumanUids,
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
        removeMemberWithDurableTombstones(
            chatId = chatId,
            operatorUid = operatorUid,
            targetUid = targetUid,
        ) { facts ->
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
     * transaction; otherwise a crash after deactivating the member would leave offline clients
     * permanently authorized.
     */
    private suspend fun removeMemberWithDurableTombstones(
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ) {
        unitOfWork.write {
            requireUserWritableAuthority(transaction, chatId)
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
        unitOfWork.write {
            requireUserWritableAuthority(transaction, chatId)
            val mutation = chatStore.transferOwner(
                transaction,
                chatId,
                operatorUid,
                newOwnerUid,
            ) { facts ->
                require(facts.chat.chatType == 2) { "群聊不存在" }
                requireOwner(facts.operator)
                require(facts.target != null) { "目标不是群成员" }
                require(facts.target.role != 2) { "目标已经是群主" }
            }
            mutation.recipientUids.forEach { uid ->
                appendEvent(uid, NotifyType.MEMBER_ROLE_CHANGED, mutation.chat)
            }
            afterCommit { chatStore.invalidateCommittedCommand(chatId) }
        }
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
        unitOfWork.write {
            requireUserWritableAuthority(transaction, chatId)
            val mutation = chatStore.setRole(
                transaction,
                chatId,
                operatorUid,
                targetUid,
                role,
            ) { facts ->
                require(facts.chat.chatType == 2) { "群聊不存在" }
                requireOwner(facts.operator)
                require(facts.target != null) { "目标不是群成员" }
                require(facts.target.role != 2) { "不能直接修改群主角色，请使用转让群主" }
            }
            mutation.recipientUids.forEach { uid ->
                appendEvent(uid, NotifyType.MEMBER_ROLE_CHANGED, mutation.chat)
            }
            afterCommit { chatStore.invalidateCommittedCommand(chatId) }
        }
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
        mutateMemberMute(operatorUid, chatId, targetUid, expiresAt, NotifyType.MEMBER_MUTED)
    }

    suspend fun unmuteMember(operatorUid: String, chatId: String, targetUid: String) =
        lifecycleGate.withChat(chatId) { unmuteMemberInternal(operatorUid, chatId, targetUid) }

    private suspend fun unmuteMemberInternal(operatorUid: String, chatId: String, targetUid: String) {
        requireCanManageMember(operatorUid, chatId, targetUid)
        mutateMemberMute(operatorUid, chatId, targetUid, null, NotifyType.MEMBER_UNMUTED)
    }

    suspend fun muteAll(operatorUid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        access.requireOwner(operatorUid, chatId)
        mutateMuteAll(operatorUid, chatId, mutedAll = true)
    }

    suspend fun unmuteAll(operatorUid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        access.requireOwner(operatorUid, chatId)
        mutateMuteAll(operatorUid, chatId, mutedAll = false)
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
        unitOfWork.write {
            requireUserWritableAuthority(transaction, chatId)
            chatStore.createInviteLink(
                transaction,
                chatId,
                operatorUid,
                name,
                maxUses,
                expiresAt,
            ) { facts -> requireAdmin(facts.operator) }
        }
    }

    suspend fun listInviteLinks(operatorUid: String, chatId: String): List<InviteLinkRecord> =
        withContext(Dispatchers.IO) {
            access.readAsAdmin(operatorUid, chatId) { _, _ -> chatStore.listInviteLinks(chatId) }
        }

    suspend fun revokeInviteLink(operatorUid: String, token: String) {
        val link = chatStore.getInviteLink(token) ?: throw IllegalArgumentException("邀请链接不存在")
        lifecycleGate.withChat(link.chatId) {
            val current = chatStore.getInviteLink(token)
                ?: throw IllegalArgumentException("邀请链接不存在")
            requireUserManaged(current.chatId)
            access.requireAdmin(operatorUid, current.chatId)
            unitOfWork.write {
                requireUserWritableAuthority(transaction, link.chatId)
                chatStore.revokeInviteLink(
                    transaction,
                    expectedChatId = link.chatId,
                    operatorUid = operatorUid,
                    token = token,
                    nowMillis = System.currentTimeMillis(),
                ) { facts -> requireAdmin(facts.operator) }
            }
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
                requireUserWritableAuthority(transaction, chatId)
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
        deactivateGroupWithDurableEvents(chatId, operatorUid = null) { facts ->
            require(facts.chat.chatType == 2) { "单聊不能解散" }
        }
    }

    /** Chat, bot/grant facts, members, Conversations and tombstones share one commit boundary. */
    private suspend fun deactivateGroupWithDurableEvents(
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ) {
        unitOfWork.write {
            requireUserWritableAuthority(transaction, chatId)
            chatStore.lockForDeactivation(transaction, chatId, operatorUid, authorize)
            requiredParticipants.deactivateForChat(transaction, chatId)
            val deactivation = chatStore.deactivateChat(transaction, chatId)
            deactivation.memberUids.forEach { uid ->
                appendEvent(uid, NotifyType.CHAT_DELETED, deactivation.chat)
                appendEvent(
                    uid,
                    NotifyType.CONVERSATION_DELETED,
                    Conversation(chatId = chatId, chatType = 0),
                )
            }
            afterCommit { chatStore.invalidateCommittedDeactivation(chatId) }
        }
    }

    suspend fun adminMuteAll(chatId: String) = lifecycleGate.withChat(chatId) {
        mutateMuteAll(operatorUid = null, chatId = chatId, mutedAll = true)
    }

    suspend fun adminUnmuteAll(chatId: String) = lifecycleGate.withChat(chatId) {
        mutateMuteAll(operatorUid = null, chatId = chatId, mutedAll = false)
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
    ): Map<String, LockedChat> {
        val orderedChatIds = chatIds.distinct().sorted()
        val authorities = managedChats.lockAuthority(transaction, orderedChatIds)
        authorities.forEach { (_, authority) ->
            require(authority.ready) { "受管群投影尚未收敛" }
        }
        // Existing-chat bot commands share the global projection -> Chat order with every other
        // managed-chat writer. Cleanup callers may inspect an already inactive Chat, but no caller
        // can cross a pending organization revision and then mutate Bot/grant/member projections.
        return chatStore.lockChats(transaction, orderedChatIds, requireActive)
    }

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

    /** Transaction-bound, idempotent service-member removal used by the bot aggregate. */
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

    private suspend fun mutateMemberMute(
        operatorUid: String,
        chatId: String,
        targetUid: String,
        expiresAt: Long?,
        notifyType: NotifyType,
    ) {
        unitOfWork.write {
            lockReadyAuthority(transaction, chatId)
            val mutation = chatStore.setMemberMute(
                transaction,
                chatId,
                operatorUid,
                targetUid,
                expiresAt,
            ) { facts ->
                require(facts.chat.chatType == 2) { "群聊不存在" }
                requireCanManageLocked(facts.operator, facts.target)
            }
            mutation.recipientUids.forEach { uid -> appendEvent(uid, notifyType, mutation.chat) }
            afterCommit { chatStore.invalidateCommittedCommand(chatId) }
        }
    }

    private suspend fun mutateMuteAll(operatorUid: String?, chatId: String, mutedAll: Boolean) {
        unitOfWork.write {
            lockReadyAuthority(transaction, chatId)
            val mutation = chatStore.setMuteAll(
                transaction,
                chatId,
                operatorUid,
                mutedAll,
            ) { facts ->
                require(facts.chat.chatType == 2) { "群聊不存在" }
                if (operatorUid != null) requireOwner(facts.operator)
            }
            mutation.recipientUids.forEach { uid ->
                appendEvent(uid, NotifyType.CHAT_UPDATED, mutation.chat)
            }
            afterCommit { chatStore.invalidateCommittedCommand(chatId) }
        }
    }

    /** Owner may manage admins/members; admins may only manage ordinary members. */
    private suspend fun requireCanManageMember(operatorUid: String, chatId: String, targetUid: String) {
        requireHumanMemberTarget(targetUid)
        access.requireCanManageMember(operatorUid, chatId, targetUid)
    }

    private fun requireCanManageLocked(operator: Member?, target: Member?) {
        val actor = operator ?: throw IllegalArgumentException("操作者不是群成员")
        val subject = target ?: throw IllegalArgumentException("目标不是群成员")
        require(actor.uid != subject.uid) { "不能管理自己" }
        require(actor.role >= 1) { "需要管理员权限" }
        require(actor.role > subject.role) { "不能管理同级或更高角色" }
    }

    private fun requireAdmin(operator: Member?) {
        val actor = operator ?: throw IllegalArgumentException("操作者不是群成员")
        require(actor.role >= 1) { "需要管理员权限" }
    }

    private fun requireOwner(operator: Member?) {
        val actor = operator ?: throw IllegalArgumentException("操作者不是群成员")
        require(actor.role == 2) { "需要群主权限" }
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

    private fun requireUserWritableAuthority(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
    ) {
        val authority = lockReadyAuthority(transaction, chatId)
        require(!authority.managed) {
            "该群由${authority.ownerLabel ?: "组织架构"}维护，不能手工修改成员或生命周期"
        }
    }

    private fun lockReadyAuthority(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
    ): ManagedChatAuthority {
        val authority = managedChats.lockAuthority(transaction, listOf(chatId)).getValue(chatId)
        require(authority.ready) { "受管群投影尚未收敛" }
        return authority
    }

}
