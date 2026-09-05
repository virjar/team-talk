package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.command.canonicalOperationId
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.ConversationWirePolicy
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

private const val MAX_GROUP_AVATAR_LENGTH = 500
private const val MAX_INVITE_LINK_NAME_LENGTH = 200
private const val UUID_TEXT_LENGTH = 36

class ChatService(
    private val chatStore: ChatStore,
    private val access: ChatAccess,
    private val users: UserRepository,
    private val managedChats: ManagedChatPolicy,
    private val contacts: ContactRepository,
    private val requiredParticipants: RequiredChatParticipants,
    private val lifecycleGate: ChatLifecycleGate,
    private val unitOfWork: PgUnitOfWork,
) {

    // ── 创建聊天 ──

    suspend fun createPersonalChat(uid: String, targetUid: String): Chat {
        require(uid != targetUid) { "不能和自己创建私聊" }
        // 对常见情况快速失败；仓库会在锁定两行 User 之后重复这个检查，
        // 使并发的拉黑写入无法与实际的创建竞争。
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

    /** 幂等取回当前用户的"保存的消息"私有会话；仅首次创建时向本人发 CHAT_CREATED。 */
    suspend fun getOrCreateSavedChat(uid: String): Chat = unitOfWork.write {
        val creation = chatStore.getOrCreateSavedChat(transaction, uid)
        if (creation.created) {
            appendEvent(uid, NotifyType.CHAT_CREATED, creation.chat)
            afterCommit { chatStore.invalidateCommittedCommand(creation.chat.chatId) }
        }
        creation.chat
    }

    suspend fun createGroup(
        operationId: String,
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
    ): Chat {
        val command = canonicalGroupCreationCommand(operationId, name, avatar, creatorUid, memberUids)
        return unitOfWork.write {
            val creation = chatStore.createGroupChat(transaction, command)
            if (creation.created) {
                creation.recipientUids.forEach { recipient ->
                    appendEvent(recipient, NotifyType.CHAT_CREATED, creation.chat)
                }
                afterCommit { chatStore.invalidateCommittedCommand(creation.chat.chatId) }
            }
            creation.chat
        }
    }

    private fun canonicalGroupCreationCommand(
        operationId: String,
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
    ): GroupCreationCommand {
        val canonicalOperationId = operationId.takeIf { it.length == 36 }
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?.takeIf { it == operationId }
        require(canonicalOperationId != null) { "建群操作标识非法" }

        require(name.length <= ConversationWirePolicy.MAX_CHAT_NAME_LENGTH) {
            "群名不能超过 ${ConversationWirePolicy.MAX_CHAT_NAME_LENGTH} 个字符"
        }
        val canonicalName = name.trim()
        require(canonicalName.isNotEmpty()) { "群名不能为空" }
        require(canonicalName.none(Char::isISOControl)) { "群名包含非法字符" }

        require(avatar == null || avatar.length <= MAX_GROUP_AVATAR_LENGTH) {
            "群头像地址不能超过 $MAX_GROUP_AVATAR_LENGTH 个字符"
        }
        val canonicalAvatar = avatar?.trim()?.takeIf(String::isNotEmpty)
        require(canonicalAvatar == null || canonicalAvatar.none(Char::isISOControl)) {
            "群头像地址包含非法字符"
        }

        val canonicalMembers = GroupPolicy.canonicalInitialMemberUids(creatorUid, memberUids)
        val fingerprint = groupCreationFingerprint(
            "group-create-v1",
            creatorUid,
            canonicalName,
            canonicalAvatar,
            *canonicalMembers.toTypedArray(),
        )
        return GroupCreationCommand(
            operationId = canonicalOperationId,
            creatorUid = creatorUid,
            name = canonicalName,
            avatar = canonicalAvatar,
            memberUids = canonicalMembers,
            requestFingerprint = fingerprint,
        )
    }

    private fun groupCreationFingerprint(vararg fields: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            if (field == null) {
                digest.update(0.toByte())
            } else {
                digest.update(1.toByte())
                val bytes = field.encodeToByteArray()
                digest.update((bytes.size ushr 24).toByte())
                digest.update((bytes.size ushr 16).toByte())
                digest.update((bytes.size ushr 8).toByte())
                digest.update(bytes.size.toByte())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun getChat(chatId: String): Chat? = chatStore.getChat(chatId)

    /** 面向客户端的详情查询。知道聊天 id 绝不能泄露私聊/群元数据。 */
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
        attachUsers(chatStore.getMembers(chatId))

    /** 面向客户端的成员查询，使用与聊天详情相同的成员关系边界。 */
    suspend fun getMembersFor(uid: String, chatId: String): List<Member> =
        withContext(Dispatchers.IO) {
            access.readMembersFor(uid, chatId) { _, members ->
                attachUsers(members)
            }
        }

    private fun attachUsers(members: List<Member>): List<Member> {
        val usersByUid = users.findByUids(members.mapTo(linkedSetOf()) { it.uid })
        return members.map { member -> member.copy(user = usersByUid[member.uid]) }
    }

    suspend fun addMembers(operatorUid: String, chatId: String, uids: List<String>) =
        lifecycleGate.withChat(chatId) { addMembersInternal(operatorUid, chatId, uids) }

    private suspend fun addMembersInternal(operatorUid: String, chatId: String, uids: List<String>) {
        requireUserManaged(chatId)
        // 在解析目标身份之前默认拒绝；下面的交易回调会在已锁定的成员快照下重复
        // 权威角色检查。
        access.requireAdmin(operatorUid, chatId)
        val targets = GroupPolicy.canonicalTargetMemberUids(uids)
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

    /** 成员关系、Conversation 创建与接收者事件作为一个聚合写入提交。 */
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
            appendMembershipAdditionEvents(
                chat = addition.chat,
                addedUids = addition.addedUids,
                activeMemberUids = addition.activeMemberUids,
            )
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
     * 成员关系权威、会话删除与每个按用户区分的墓碑共享一个 PG 事务；否则在停用成员
     * 之后崩溃会让离线客户端保持永久授权。
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

            // 目标的本地权威必须是一个聊天墓碑，绝不能是 MEMBER_REMOVED 刷新提示。
            // 剩余成员保留群组，只刷新其成员视图。一个权威的隐私墓碑会在客户端的单个
            // 本地事务中移除聊天、会话、草稿、可靠发件箱、消息与成员。
            appendEvent(targetUid, NotifyType.CHAT_DELETED, removal.chat)
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
        operationId: String,
        issuedAt: Long,
        operatorUid: String,
        chatId: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
    ): String = lifecycleGate.withChat(chatId) {
        val command = canonicalInviteLinkCreationCommand(
            operationId = operationId,
            issuedAt = issuedAt,
            operatorUid = operatorUid,
            chatId = chatId,
            name = name,
            maxUses = maxUses,
            expiresAt = expiresAt,
        )
        unitOfWork.write {
            // 精确重放可以绕过变更/配额，但绝不能绕过当前授权。否则在首次提交之后
            // 被移除或降级的操作者就可能取回秘密令牌。
            requireUserWritableAuthority(transaction, chatId)
            chatStore.createInviteLink(
                transaction = transaction,
                command = command,
            ) { facts -> requireAdmin(facts.operator) }
        }
    }

    private fun canonicalInviteLinkCreationCommand(
        operationId: String,
        issuedAt: Long,
        operatorUid: String,
        chatId: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
    ): InviteLinkCreationCommand {
        val canonicalOperation = canonicalOperationId(operationId, "邀请链接创建")
        val canonicalIssuedAt = ReliableCommandPolicy.requireActiveIssuedAt(
            issuedAt,
            System.currentTimeMillis(),
            "邀请链接创建",
        )
        val canonicalChatId = chatId.takeIf { it.length == UUID_TEXT_LENGTH }
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?.takeIf { it == chatId }
        require(canonicalChatId != null) { "群聊标识非法" }
        require(name.length <= MAX_INVITE_LINK_NAME_LENGTH) { "邀请链接名称过长" }
        val canonicalName = name.trim()
        require(canonicalName.none(Char::isISOControl)) { "邀请链接名称包含非法字符" }
        require(maxUses >= 0) { "maxUses 不能为负数，0 表示不限次数" }
        require(expiresAt >= 0) { "expiresAt 不能为负数，0 表示永不过期" }
        return InviteLinkCreationCommand(
            operationId = canonicalOperation,
            issuedAt = canonicalIssuedAt,
            creatorUid = operatorUid,
            chatId = canonicalChatId,
            name = canonicalName,
            maxUses = maxUses,
            expiresAt = expiresAt,
            requestFingerprint = reliableCommandFingerprint(
                "invite-link-create-v1",
                operatorUid,
                canonicalIssuedAt.toString(),
                canonicalChatId,
                canonicalName,
                maxUses.toString(),
                expiresAt.toString(),
            ),
        )
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
        // 受管聊天所有权是一个独立的领域策略。仓库会在其聚合事务内重复所有可变的
        // 邀请/聊天/成员校验。
        val chatId = chatStore.getInviteLink(token)?.chatId
            ?: throw IllegalArgumentException("邀请链接不存在")
        return lifecycleGate.withChat(chatId) {
            // 进入闸门后重新获取策略与所有可变的聊天/成员事实。
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
                    appendMembershipAdditionEvents(
                        chat = result.chat,
                        addedUids = listOf(uid),
                        activeMemberUids = result.members.map(Member::uid),
                    )
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

    /** Chat、bot/grant 事实、成员、Conversations 与墓碑共享一个提交边界。 */
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

    /** 仅用于对账服务领域投影的读侧。 */
    internal fun activeChatIdsForServiceMember(uid: String): Set<String> =
        chatStore.getActiveChatIds(uid)

    internal fun activeChatIdsForServiceMember(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        uid: String,
    ): Set<String> = chatStore.getActiveChatIds(transaction, uid)

    internal fun projectedChatIdsForServiceMember(uid: String): Set<String> =
        chatStore.getProjectedChatIds(uid)

    internal fun projectedChatIdsForServiceMember(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        uid: String,
    ): Set<String> = chatStore.getProjectedChatIds(transaction, uid)

    internal fun lockChatsForServiceMember(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> {
        val orderedChatIds = chatIds.distinct().sorted()
        val authorities = managedChats.lockAuthority(transaction, orderedChatIds)
        authorities.forEach { (_, authority) ->
            require(authority.ready) { "受管群投影尚未收敛" }
        }
        // 已有聊天的机器人命令与所有其他受管聊天写入者共享全局的 投影 -> Chat 顺序。
        // 清理调用方可以查看一个已非活跃的 Chat，但任何调用方都不能跨越一个挂起中的
        // 组织修订，然后变更 Bot/grant/member 投影。
        return chatStore.lockChats(transaction, orderedChatIds, requireActive)
    }

    internal fun getActiveMemberForService(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = chatStore.getActiveMember(transaction, chatId, uid)

    /**
     * 事务绑定的服务成员变更。外部领域拥有事件意图与提交后失效，以便原子地纳入其
     * 自身的授权事实。
     */
    internal fun addServiceMember(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
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

    /** 机器人聚合使用的、事务绑定的幂等服务成员移除。 */
    internal fun removeServiceMemberIfPresent(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
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
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
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

    /** 群主可以管理管理员/成员；管理员只能管理普通成员。 */
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
        val actor = operator ?: throw ChatAccessDeniedException("操作者不是群成员")
        if (actor.role < 1) throw ChatAccessDeniedException("需要管理员权限")
    }

    private fun requireOwner(operator: Member?) {
        val actor = operator ?: throw IllegalArgumentException("操作者不是群成员")
        require(actor.role == 2) { "需要群主权限" }
    }

    private fun requireHumanMemberTarget(uid: String) {
        require(users.findByUid(uid)?.role == UserRole.HUMAN) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
    }

    private fun requireUserManaged(chatId: String) {
        managedChats.managedBy(chatId)?.let { owner ->
            throw IllegalArgumentException("该群由${owner}维护，不能手工修改成员或生命周期")
        }
    }

    private fun requireUserWritableAuthority(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
    ) {
        val authority = lockReadyAuthority(transaction, chatId)
        require(!authority.managed) {
            "该群由${authority.ownerLabel ?: "组织架构"}维护，不能手工修改成员或生命周期"
        }
    }

    private fun lockReadyAuthority(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
    ): ManagedChatAuthority {
        val authority = managedChats.lockAuthority(transaction, listOf(chatId)).getValue(chatId)
        require(authority.ready) { "受管群投影尚未收敛" }
        return authority
    }

}
