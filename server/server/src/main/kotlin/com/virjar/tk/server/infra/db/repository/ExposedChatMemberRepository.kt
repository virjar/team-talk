package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.chat.ChatMemberRepository
import com.virjar.tk.server.domain.chat.ChatMutation
import com.virjar.tk.server.domain.chat.GroupCommandFacts
import com.virjar.tk.server.domain.chat.GroupMemberAddition
import com.virjar.tk.server.domain.chat.GroupMemberAdditionFacts
import com.virjar.tk.server.domain.chat.GroupMemberRemoval
import com.virjar.tk.server.domain.chat.GroupMemberRemovalFacts
import com.virjar.tk.server.domain.chat.LockedChat
import com.virjar.tk.server.domain.chat.MessageAdmission
import com.virjar.tk.server.domain.chat.MessageAdmissionFacts
import com.virjar.tk.server.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.GroupMemberMutes
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

internal enum class ChatMemberRepositoryStage {
    BEFORE_CHAT_LOCK,
    AFTER_CHAT_LOCK,
}

/** 序列化容量决策的聚合锁的确定性测试缝隙。 */
internal fun interface ChatMemberRepositoryHooks {
    fun hit(stage: ChatMemberRepositoryStage, chatId: String)

    object None : ChatMemberRepositoryHooks {
        override fun hit(stage: ChatMemberRepositoryStage, chatId: String) = Unit
    }
}

/**
 * GroupMembers / GroupMemberMutes 表访问。
 *
 * 注意：[setMuteAll] 操作的是 GroupChats.mutedAll 字段（全群禁言开关），
 * 语义上归属"禁言管理"，因此放在本类而非 [ChatRepository]。
 */
class ExposedChatMemberRepository internal constructor(
    private val database: Database,
    private val hooks: ChatMemberRepositoryHooks = ChatMemberRepositoryHooks.None,
) : ChatMemberRepository {

    // ── 成员查询 ──

    override fun getMembers(chatId: String): List<Member> {
        return transaction(database) {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it.toMember() }
        }
    }

    override fun getMember(chatId: String, uid: String): Member? {
        return transaction(database) {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .map { it.toMember() }.singleOrNull()
        }
    }

    override fun getMemberUids(chatId: String): List<String> {
        return transaction(database) {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    override fun getActiveChatIds(uid: String): Set<String> = transaction(database) {
        GroupMembers.selectAll()
            .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
            .mapTo(linkedSetOf()) { it[GroupMembers.chatId] }
    }

    override fun getActiveChatIds(transaction: PgReadTransactionContext, uid: String): Set<String> =
        inReadTransaction(transaction) {
            GroupMembers.selectAll()
                .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .mapTo(linkedSetOf()) { it[GroupMembers.chatId] }
        }

    override fun getProjectedChatIds(uid: String): Set<String> = transaction(database) {
        projectedChatIdsInternal(uid)
    }

    override fun getProjectedChatIds(transaction: PgReadTransactionContext, uid: String): Set<String> =
        inReadTransaction(transaction) { projectedChatIdsInternal(uid) }

    override fun lockChats(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = inWriteTransaction(transaction) {
        val requested = chatIds.distinct().sorted()
        if (requested.isEmpty()) return@inWriteTransaction emptyMap()
        val rows = Chats.selectAll()
            .where { Chats.chatId inList requested }
            .orderBy(Chats.chatId, SortOrder.ASC)
            .forUpdate()
            .toList()
        if (requireActive) require(rows.size == requested.size) { "聊天不存在" }
        if (requireActive) {
            require(rows.all { it[Chats.status] == 1 }) { "聊天不存在" }
        }
        rows.associate { row ->
            row[Chats.chatId] to LockedChat(
                chat = chatSnapshot(row, memberCount = 0),
                active = row[Chats.status] == 1,
            )
        }
    }

    override fun getActiveMember(
        transaction: PgReadTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = inReadTransaction(transaction) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.singleOrNull()?.toMember()
    }

    override fun getActiveMemberUids(
        transaction: PgReadTransactionContext,
        chatId: String,
    ): List<String> = inReadTransaction(transaction) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).map { it[GroupMembers.uid] }
    }

    override fun admitMessage(
        transaction: PgWriteTransactionContext,
        chatId: String,
        senderUid: String,
        nowMillis: Long,
        afterChatLocked: () -> Unit,
        authorize: (MessageAdmissionFacts) -> Unit,
    ): MessageAdmission = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        // 跨领域投递授权遵循 projection -> Chat -> User -> Bot/授权 的顺序。
        // 成员资格与禁言锁在每个进程上都严格位于该缝隙之后。
        afterChatLocked()
        val members = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().map(ResultRow::toMember)
        val sender = members.firstOrNull { it.uid == senderUid }
        val senderMuted = GroupMemberMutes.selectAll().where {
            (GroupMemberMutes.chatId eq chatId) and
                (GroupMemberMutes.uid eq senderUid)
        }.forUpdate().singleOrNull()?.get(GroupMemberMutes.expiresAt)?.let { it > nowMillis } == true
        val chat = chatSnapshot(chatRow, members.size)
        authorize(
            MessageAdmissionFacts(
                chat = chat,
                sender = sender,
                senderMuted = senderMuted,
                activeMemberUids = members.map(Member::uid),
            ),
        )
        MessageAdmission(chat.chatType, members.map(Member::uid))
    }

    override fun isMember(chatId: String, uid: String): Boolean {
        return transaction(database) {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .count() > 0
        }
    }

    // ── 成员变更 ──

    override fun addMembers(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        requiredHumanUids: Set<String>,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = inWriteTransaction(transaction) {
        val requestedUids = GroupPolicy.canonicalTargetMemberUids(uids)
        hooks.hit(ChatMemberRepositoryStage.BEFORE_CHAT_LOCK, chatId)
        val chatRow = lockActiveChat(chatId)
        hooks.hit(ChatMemberRepositoryStage.AFTER_CHAT_LOCK, chatId)
        lockRequiredHumanUsers(requiredHumanUids)
        val activeMembers = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate()
            .map(ResultRow::toMember)
        val membersByUid = activeMembers.associateBy(Member::uid)
        val before = chatSnapshot(chatRow, activeMembers.size)
        authorize(
            GroupMemberAdditionFacts(
                chat = before,
                operator = membersByUid[operatorUid],
                requestedUids = requestedUids,
            ),
        )
        val newMemberUids = requestedUids.filterNot(membersByUid::containsKey)
        GroupPolicy.requireAdditionalCapacity(
            activeMemberCount = activeMembers.size,
            newMemberCount = newMemberUids.size,
        )

        val existingConversationUids = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid inList requestedUids)
        }.mapTo(linkedSetOf()) { it[Conversations.uid] }
        val usage = ConversationUsageLedger.lock(requestedUids)
        val missingConversationUids = requestedUids.filterNot(existingConversationUids::contains).toSet()
        ConversationUsageLedger.apply(
            usage,
            missingConversationUids.associateWith { conversationUsageDeltaForInsert() },
        )

        val now = System.currentTimeMillis()
        val addedUids = ArrayList<String>(requestedUids.size)
        for (uid in requestedUids) {
            val existing = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
            }.forUpdate().singleOrNull()
            when {
                existing == null -> {
                    GroupMembers.insert {
                        it[GroupMembers.chatId] = chatId
                        it[GroupMembers.chatType] = before.chatType
                        it[GroupMembers.uid] = uid
                        it[GroupMembers.role] = 0
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                    addedUids += uid
                }

                existing[GroupMembers.status] != 1 -> {
                    GroupMembers.update({
                        (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                    }) {
                        // 普通/系统对账的重新激活是一个全新的普通角色。
                        // 显式的托管角色通过专用角色命令分配。
                        it[GroupMembers.role] = 0
                        it[GroupMembers.status] = 1
                        it[GroupMembers.joinedAt] = now
                    }
                    addedUids += uid
                }
            }
            if (uid in missingConversationUids) {
                Conversations.insert {
                    it[Conversations.uid] = uid
                    it[Conversations.chatId] = chatId
                    it[Conversations.chatType] = before.chatType
                    it[Conversations.lastMsgSeq] = 0
                    it[Conversations.updatedAt] = now
                }
            }
        }

        val activeUids = (activeMembers.asSequence().map(Member::uid) + addedUids.asSequence())
            .distinct()
            .sorted()
            .toList()
        GroupMemberAddition(
            chat = before.copy(memberCount = activeUids.size),
            addedUids = addedUids,
            activeMemberUids = activeUids,
        )
    }

    override fun removeMember(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval = removeMemberInternal(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = targetUid,
        allowMissing = false,
        validateHumanActors = true,
        authorize = authorize,
    ) ?: error("Required membership removal unexpectedly became a no-op")

    override fun removeMemberIfPresent(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        requireActiveChat: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = removeMemberInternal(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = targetUid,
        allowMissing = true,
        requireActiveChat = requireActiveChat,
        validateHumanActors = false,
        authorize = authorize,
    )

    override fun cleanupServiceMemberProjection(
        transaction: PgWriteTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = inWriteTransaction(transaction) {
        // 拥有的机器人命令在其服务身份和机器人之前，先锁定了每个现有的 Chat。
        // 绝不要在此重新查询缺失的 Chat：如果该 id 被并发重建，在 User/Bot 锁
        // 之后再获取那行新数据，就会颠倒全局的 Chat -> User -> Bot 顺序。
        val activeMembers = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().toList()
        val activeByUid = activeMembers.associateBy { it[GroupMembers.uid] }
        val targetRow = activeByUid[uid] ?: GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.forUpdate().singleOrNull()
        val muteRows = GroupMemberMutes.selectAll().where {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
        }.forUpdate().toList()
        val observedConversationRow = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        }.singleOrNull()
        val usage = ConversationUsageLedger.lock(
            if (observedConversationRow == null) emptyList() else listOf(uid),
        )
        val conversationRow = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        }.forUpdate().singleOrNull()
        check(conversationRow?.get(Conversations.id) == observedConversationRow?.get(Conversations.id)) {
            "Conversation projection changed while its Chat set was locked"
        }
        require(targetRow?.get(GroupMembers.role) != 2) {
            "服务身份不能作为群主，拒绝清理损坏的成员投影"
        }

        val membershipDeactivated = targetRow?.get(GroupMembers.status) == 1 &&
            GroupMembers.update({
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1)
            }) { it[GroupMembers.status] = 0 } > 0
        val muteDeleted = muteRows.isNotEmpty() && GroupMemberMutes.deleteWhere {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
        } > 0
        if (conversationRow != null) {
            ConversationUsageLedger.apply(
                usage,
                mapOf(uid to conversationUsageDeltaForDelete(conversationRow)),
            )
        }
        val conversationDeleted = if (conversationRow != null) {
            check(Conversations.deleteWhere {
                (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
            } == 1) { "Locked Conversation projection changed before service cleanup" }
            true
        } else {
            false
        }
        if (!membershipDeactivated && !conversationDeleted && !muteDeleted) {
            return@inWriteTransaction null
        }

        val chatType = when {
            lockedChat != null -> lockedChat.chat.chatType
            targetRow != null -> targetRow[GroupMembers.chatType]
            conversationRow != null -> conversationRow[Conversations.chatType]
            else -> 0
        }
        val remainingUids = activeMembers.asSequence()
            .map { it[GroupMembers.uid] }
            .filter { it != uid }
            .toList()
        val chat = if (lockedChat != null) {
            lockedChat.chat.copy(memberCount = remainingUids.size)
        } else {
            Chat(chatId = chatId, chatType = chatType, memberCount = remainingUids.size)
        }
        ServiceMemberProjectionCleanup(
            chat = chat,
            membershipDeactivated = membershipDeactivated,
            conversationDeleted = conversationDeleted,
            muteDeleted = muteDeleted,
            remainingMemberUids = remainingUids,
        )
    }

    private fun removeMemberInternal(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        allowMissing: Boolean,
        requireActiveChat: Boolean = true,
        validateHumanActors: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = inWriteTransaction(transaction) {
        val chatRow = lockChat(chatId, requireActiveChat)
        if (validateHumanActors) lockRemovalHumanUsers(operatorUid, targetUid)
        val activeMembers = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate()
            .map(ResultRow::toMember)
        val membersByUid = activeMembers.associateBy(Member::uid)
        val chat = chatSnapshot(chatRow, activeMembers.size)

        authorize(
            GroupMemberRemovalFacts(
                chat = chat,
                operator = membersByUid[operatorUid],
                target = membersByUid[targetUid],
            ),
        )

        if (membersByUid[targetUid] == null && allowMissing) return@inWriteTransaction null

        val observedConversationRow = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq targetUid)
        }.singleOrNull()
        val usage = ConversationUsageLedger.lock(
            if (observedConversationRow == null) emptyList() else listOf(targetUid),
        )

        val changed = GroupMembers.update({
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq targetUid) and
                (GroupMembers.status eq 1)
        }) { it[GroupMembers.status] = 0 }
        check(changed == 1) { "Locked target membership changed before removal" }
        GroupMemberMutes.selectAll().where {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq targetUid)
        }.forUpdate().toList()
        val conversationRow = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq targetUid)
        }.forUpdate().singleOrNull()
        check(conversationRow?.get(Conversations.id) == observedConversationRow?.get(Conversations.id)) {
            "Conversation projection changed while its Chat row was locked"
        }
        GroupMemberMutes.deleteWhere {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq targetUid)
        }
        if (conversationRow != null) {
            ConversationUsageLedger.apply(
                usage,
                mapOf(targetUid to conversationUsageDeltaForDelete(conversationRow)),
            )
            check(Conversations.deleteWhere {
                (Conversations.chatId eq chatId) and (Conversations.uid eq targetUid)
            } == 1) { "Locked Conversation projection changed before member removal" }
        }

        val remainingUids = activeMembers.asSequence()
            .map(Member::uid)
            .filter { it != targetUid }
            .toList()
        GroupMemberRemoval(
            chat = chat.copy(memberCount = remainingUids.size),
            remainingMemberUids = remainingUids,
        )
    }

    override fun transferOwner(
        transaction: PgWriteTransactionContext,
        chatId: String,
        oldOwnerUid: String,
        newOwnerUid: String,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        lockRequiredHumanUsers(setOf(oldOwnerUid, newOwnerUid))
        val members = lockActiveMembers(chatId)
        val before = chatSnapshot(chatRow, members.size)
        authorize(
            GroupCommandFacts(
                chat = before,
                operator = members.firstOrNull { it.uid == oldOwnerUid },
                target = members.firstOrNull { it.uid == newOwnerUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        check(GroupMembers.update({
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq oldOwnerUid) and
                (GroupMembers.status eq 1)
        }) { it[GroupMembers.role] = 1 } == 1) { "操作者成员身份在锁定后发生变化" }
        check(GroupMembers.update({
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq newOwnerUid) and
                (GroupMembers.status eq 1)
        }) { it[GroupMembers.role] = 2 } == 1) { "目标成员身份在锁定后发生变化" }
        check(GroupChats.update({ GroupChats.chatId eq chatId }) {
            it[GroupChats.creator] = newOwnerUid
            it[GroupChats.updatedAt] = System.currentTimeMillis()
        } == 1) { "群聊数据不完整" }
        ChatMutation(
            chat = before.copy(creator = newOwnerUid),
            recipientUids = members.map(Member::uid),
        )
    }

    override fun setRole(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        role: Int,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        lockRequiredHumanUsers(setOf(operatorUid, targetUid))
        val members = lockActiveMembers(chatId)
        val before = chatSnapshot(chatRow, members.size)
        authorize(
            GroupCommandFacts(
                chat = before,
                operator = members.firstOrNull { it.uid == operatorUid },
                target = members.firstOrNull { it.uid == targetUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        check(GroupMembers.update({
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq targetUid) and
                (GroupMembers.status eq 1)
        }) { it[GroupMembers.role] = role } == 1) { "目标成员身份在锁定后发生变化" }
        ChatMutation(before, members.map(Member::uid))
    }

    // ── 禁言（单成员 / 全群） ──

    override fun setMemberMute(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        expiresAt: Long?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        lockRequiredHumanUsers(setOf(operatorUid, targetUid))
        val members = lockActiveMembers(chatId)
        val before = chatSnapshot(chatRow, members.size)
        authorize(
            GroupCommandFacts(
                chat = before,
                operator = members.firstOrNull { it.uid == operatorUid },
                target = members.firstOrNull { it.uid == targetUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        GroupMemberMutes.selectAll().where {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq targetUid)
        }.forUpdate().toList()
        if (expiresAt == null) {
            GroupMemberMutes.deleteWhere {
                (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq targetUid)
            }
        } else {
            GroupMemberMutes.upsert(GroupMemberMutes.chatId, GroupMemberMutes.uid) {
                it[GroupMemberMutes.chatId] = chatId
                it[GroupMemberMutes.uid] = targetUid
                it[GroupMemberMutes.operatorUid] = operatorUid
                it[GroupMemberMutes.expiresAt] = expiresAt
                it[GroupMemberMutes.createdAt] = System.currentTimeMillis()
            }
        }
        ChatMutation(before, members.map(Member::uid))
    }

    override fun isMuted(chatId: String, uid: String): Boolean {
        return transaction(database) {
            val now = System.currentTimeMillis()
            GroupMemberMutes.selectAll()
                .where { (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid) and (GroupMemberMutes.expiresAt greater now) }
                .count() > 0
        }
    }

    override fun setMuteAll(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        mutedAll: Boolean,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        operatorUid?.let { lockRequiredHumanUsers(listOf(it)) }
        val members = lockActiveMembers(chatId)
        val before = chatSnapshot(chatRow, members.size)
        authorize(
            GroupCommandFacts(
                chat = before,
                operator = operatorUid?.let { uid -> members.firstOrNull { it.uid == uid } },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        check(GroupChats.update({ GroupChats.chatId eq chatId }) {
            it[GroupChats.mutedAll] = mutedAll
            it[GroupChats.updatedAt] = System.currentTimeMillis()
        } == 1) { "群聊数据不完整" }
        ChatMutation(before.copy(mutedAll = mutedAll), members.map(Member::uid))
    }

    private fun lockActiveChat(chatId: String): ResultRow = lockChat(chatId, requireActive = true)

    private fun lockActiveMembers(chatId: String): List<Member> = GroupMembers.selectAll().where {
        (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
    }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().map(ResultRow::toMember)

    private fun lockRequiredHumanUsers(uids: Collection<String>) {
        val required = uids.distinct().sorted()
        if (required.isEmpty()) return
        val rows = Users.selectAll().where { Users.uid inList required }
            .orderBy(Users.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        require(rows.size == required.size) { "用户不存在" }
        require(rows.all { it[Users.status] == 1 }) { "用户已停用" }
        require(rows.all { it[Users.role] == UserRole.HUMAN }) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
    }

    private fun lockRemovalHumanUsers(operatorUid: String, targetUid: String) {
        val required = listOf(operatorUid, targetUid).distinct().sorted()
        val rows = Users.selectAll().where { Users.uid inList required }
            .orderBy(Users.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        require(rows.size == required.size) { "用户不存在" }
        require(rows.all { it[Users.role] == UserRole.HUMAN }) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
        val usersByUid = rows.associateBy { it[Users.uid] }
        require(usersByUid.getValue(operatorUid)[Users.status] == 1) { "操作者已停用" }
        if (operatorUid == targetUid) {
            require(usersByUid.getValue(targetUid)[Users.status] == 1) { "用户已停用" }
        }
    }

    private fun projectedChatIdsInternal(uid: String): Set<String> = buildSet {
        addAll(
            GroupMembers.selectAll().where {
                (GroupMembers.uid eq uid) and (GroupMembers.status eq 1)
            }.map { it[GroupMembers.chatId] },
        )
        addAll(
            Conversations.selectAll().where { Conversations.uid eq uid }
                .map { it[Conversations.chatId] },
        )
        addAll(
            GroupMemberMutes.selectAll().where { GroupMemberMutes.uid eq uid }
                .map { it[GroupMemberMutes.chatId] },
        )
    }

    private fun lockChat(chatId: String, requireActive: Boolean): ResultRow {
        val row = Chats.selectAll().where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull() ?: throw IllegalArgumentException("聊天不存在")
        if (requireActive) require(row[Chats.status] == 1) { "聊天不存在" }
        return row
    }

    private fun chatSnapshot(chatRow: ResultRow, memberCount: Int): Chat {
        val chatId = chatRow[Chats.chatId]
        val chatType = chatRow[Chats.chatType]
        if (chatType == 1 || chatType == 3) {
            // 私聊与"保存的消息"会话都没有 GroupChats 行；saved 的展示名由会话投影注入。
            return Chat(chatId = chatId, chatType = chatType, maxSeq = chatRow[Chats.maxSeq])
        }
        val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
            ?: throw IllegalStateException("群聊数据不完整")
        return Chat(
            chatId = chatId,
            chatType = chatType,
            name = group[GroupChats.name],
            avatar = group[GroupChats.avatar],
            creator = group[GroupChats.creator],
            memberCount = memberCount,
            maxSeq = chatRow[Chats.maxSeq],
            notice = group[GroupChats.notice],
            mutedAll = group[GroupChats.mutedAll],
        )
    }

    private inline fun <T> inWriteTransaction(
        context: PgWriteTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
        return block()
    }

    private inline fun <T> inReadTransaction(
        context: PgReadTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedReadTransaction()
        return block()
    }
}

private fun ResultRow.toMember() = Member(
    uid = this[GroupMembers.uid],
    chatId = this[GroupMembers.chatId],
    role = this[GroupMembers.role],
    nickname = this[GroupMembers.nickname],
    joinedAt = this[GroupMembers.joinedAt],
)
