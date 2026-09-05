package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.bot.AutomationBot
import com.virjar.tk.server.domain.organization.ManagedChatMemberRoleChange
import com.virjar.tk.server.domain.organization.ManagedChatProjectionCursor
import com.virjar.tk.server.domain.organization.ManagedChatProjectionFailure
import com.virjar.tk.server.domain.organization.ManagedChatProjectionMutation
import com.virjar.tk.server.domain.organization.ManagedChatProjectionTask
import com.virjar.tk.server.domain.organization.OrganizationHierarchy
import com.virjar.tk.server.domain.organization.OrganizationHierarchyNode
import com.virjar.tk.server.domain.organization.OrganizationManagedChatProjectionStore
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.AutomationBotGrants
import com.virjar.tk.server.infra.db.AutomationBots
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupInviteLinks
import com.virjar.tk.server.infra.db.GroupMemberMutes
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.PostgresHealthProbePolicy
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.math.min

/** 组织拥有聊天投影的全量替换写入器。 */
class ExposedOrganizationManagedChatProjectionStore(
    private val database: Database,
) : OrganizationManagedChatProjectionStore {
    override fun listPending(
        after: ManagedChatProjectionCursor?,
        limit: Int,
        includeDeferred: Boolean,
        nowMillis: Long,
    ): List<ManagedChatProjectionTask> = transaction(database) {
        OrganizationManagedChatProjections.selectAll().where {
            val pending = OrganizationManagedChatProjections.desiredRevision neq
                OrganizationManagedChatProjections.appliedRevision
            val afterCursor = after?.let { cursor ->
                (OrganizationManagedChatProjections.desiredRevision greater cursor.revision) or
                    (
                        (OrganizationManagedChatProjections.desiredRevision eq cursor.revision) and
                            (OrganizationManagedChatProjections.unitId greater cursor.unitId)
                        )
            } ?: (OrganizationManagedChatProjections.desiredRevision greater 0L)
            val due = if (includeDeferred) {
                OrganizationManagedChatProjections.nextAttemptAt greater -1L
            } else {
                OrganizationManagedChatProjections.nextAttemptAt lessEq nowMillis
            }
            pending and afterCursor and due
        }.orderBy(
            OrganizationManagedChatProjections.desiredRevision to SortOrder.ASC,
            OrganizationManagedChatProjections.unitId to SortOrder.ASC,
        ).limit(limit).map(ResultRow::toProjectionTask)
    }

    override fun apply(
        transaction: PgWriteTransactionContext,
        task: ManagedChatProjectionTask,
    ): ManagedChatProjectionMutation = inWriteTransaction(transaction) {
        // 组织状态/投影是外层版本 fence。从此点起
        // 调用方持有 ChatLifecycleGate，数据库锁遵循 组织事实 -> Chat ->
        // Bot/授权 -> 成员/会话 -> SyncStreams。下方的 User 行被刻意
        // 不加锁读取；参见 readUsers() 中的跨领域锁顺序不变量。
        OrganizationState.selectAll().where { OrganizationState.id eq STATE_ID }
            .forUpdate().single()
        val projection = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.unitId eq task.unitId
        }.forUpdate().singleOrNull() ?: return@inWriteTransaction stale(task)
        if (projection[OrganizationManagedChatProjections.chatId] != task.chatId ||
            projection[OrganizationManagedChatProjections.desiredRevision] != task.revision ||
            projection[OrganizationManagedChatProjections.desiredActive] != task.desiredActive ||
            projection[OrganizationManagedChatProjections.appliedRevision] == task.revision
        ) {
            return@inWriteTransaction stale(task)
        }

        val desiredFacts = if (task.desiredActive) readDesiredOrganizationFacts(task) else null
        val discoveredBotRows = discoverBotRows(task.chatId)
        val chatRow = Chats.selectAll().where { Chats.chatId eq task.chatId }
            .forUpdate().singleOrNull()

        if (chatRow != null) {
            require(chatRow[Chats.chatType] == GROUP_CHAT_TYPE) {
                "受管群 ID 与非群聊冲突: ${task.chatId}"
            }
        }
        val stableBotRows = discoverBotRows(task.chatId)
        check(stableBotRows == discoveredBotRows) {
            "受管群机器人集合在 Chat 锁定前发生变化，请重试"
        }
        val currentMemberUids = GroupMembers.selectAll().where {
            GroupMembers.chatId eq task.chatId
        }.map { it[GroupMembers.uid] }
        val desiredHumanUids = desiredFacts?.memberUids.orEmpty()
        val discoveredBotUids = discoveredBotRows.values.map { it.userUid }
        val observedUserUids = (currentMemberUids + desiredHumanUids + discoveredBotUids).distinct().sorted()
        val observedUsers = readUsers(observedUserUids)
        if (task.desiredActive) {
            require(observedUsers.keys.containsAll(desiredHumanUids)) {
                "受管群组织成员引用了不存在的用户"
            }
        }

        val lockedBots = lockBots(stableBotRows)
        val lockedGrantBotIds = lockGrants(task.chatId)
        if (task.desiredActive) {
            check(lockedBots.keys.containsAll(lockedGrantBotIds)) {
                "受管群授权引用了不存在的机器人"
            }
        }
        val activeBotUids = if (task.desiredActive) {
            lockedGrantBotIds.mapNotNullTo(linkedSetOf()) { botId ->
                lockedBots[botId]?.takeIf { it.status == AutomationBot.STATUS_ACTIVE }?.userUid
            }
        } else {
            deactivateBotFacts(task.chatId)
            emptySet()
        }
        if (task.desiredActive) {
            require(observedUsers.keys.containsAll(activeBotUids)) {
                "受管群机器人授权引用了不存在的服务身份"
            }
            activeBotUids.forEach { uid ->
                val role = observedUsers.getValue(uid)[Users.role]
                check(role == UserRole.BOT || role == UserRole.SYSTEM) {
                    "机器人授权引用了非服务身份: $uid"
                }
            }
        }

        val desiredProjectionUids = if (task.desiredActive) {
            (desiredFacts!!.memberUids + activeBotUids).toSortedSet().also { desired ->
                GroupPolicy.requireFinalMemberCount(desired.size)
            }
        } else {
            sortedSetOf()
        }

        // 所有现有聊天的写入器在 authority/Chat/User/Bot 锁之后使用
        // Invite -> Member -> Mute -> 每用户用量 -> Conversation。让对账保持同一顺序，
        // 使并发的邀请命令或解散无法形成投影行死锁。
        GroupInviteLinks.selectAll().where { GroupInviteLinks.chatId eq task.chatId }
            .orderBy(GroupInviteLinks.token, SortOrder.ASC).forUpdate().toList()
        val memberRows = GroupMembers.selectAll().where { GroupMembers.chatId eq task.chatId }
            .orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().toList()
        val muteRows = GroupMemberMutes.selectAll().where { GroupMemberMutes.chatId eq task.chatId }
            .orderBy(GroupMemberMutes.uid, SortOrder.ASC).forUpdate().toList()
        val observedConversationRows = Conversations.selectAll().where {
            Conversations.chatId eq task.chatId
        }.orderBy(Conversations.uid, SortOrder.ASC).toList()
        val observedConversationUids = observedConversationRows.map { it[Conversations.uid] }
        val usage = ConversationUsageLedger.lock(observedConversationUids + desiredProjectionUids)
        val conversationRows = Conversations.selectAll().where { Conversations.chatId eq task.chatId }
            .orderBy(Conversations.uid, SortOrder.ASC).forUpdate().toList()
        check(conversationRows.map { it[Conversations.id] } == observedConversationRows.map { it[Conversations.id] }) {
            "受管群会话投影在 Chat 锁定后发生变化"
        }

        val currentActive = memberRows.asSequence()
            .filter { it[GroupMembers.status] == ACTIVE }
            .mapTo(linkedSetOf()) { it[GroupMembers.uid] }
        val projectionUids = (currentActive + conversationRows.map { it[Conversations.uid] }).toSet()
        val result = if (task.desiredActive) {
            applyPositive(
                task,
                desiredFacts!!,
                desiredProjectionUids,
                chatRow,
                memberRows,
                currentActive,
                projectionUids,
                muteRows.mapTo(linkedSetOf()) { it[GroupMemberMutes.uid] },
                conversationRows,
                usage,
            )
        } else {
            applyNegative(task, chatRow, projectionUids, conversationRows, usage)
        }
        markApplied(task)
        result
    }

    override fun recordFailure(
        transaction: PgWriteTransactionContext,
        task: ManagedChatProjectionTask,
        detail: String,
        nowMillis: Long,
    ) {
        inWriteTransaction(transaction) {
            val row = OrganizationManagedChatProjections.selectAll().where {
                OrganizationManagedChatProjections.unitId eq task.unitId
            }.forUpdate().singleOrNull() ?: return@inWriteTransaction
            if (row[OrganizationManagedChatProjections.chatId] != task.chatId ||
                row[OrganizationManagedChatProjections.desiredRevision] != task.revision ||
                row[OrganizationManagedChatProjections.desiredActive] != task.desiredActive ||
                row[OrganizationManagedChatProjections.appliedRevision] == task.revision
            ) return@inWriteTransaction
            val attempts = row[OrganizationManagedChatProjections.attemptCount] + 1
            val delay = min(MAX_RETRY_DELAY_MS, INITIAL_RETRY_DELAY_MS shl min(attempts - 1, 6))
            OrganizationManagedChatProjections.update({
                (OrganizationManagedChatProjections.unitId eq task.unitId) and
                    (OrganizationManagedChatProjections.chatId eq task.chatId) and
                    (OrganizationManagedChatProjections.desiredRevision eq task.revision) and
                    (OrganizationManagedChatProjections.desiredActive eq task.desiredActive)
            }) {
                it[attemptCount] = attempts
                it[nextAttemptAt] = nowMillis + delay
                it[lastFailure] = detail.take(1000)
                it[updatedAt] = nowMillis
            }
        }
    }

    override fun countPending(): Long = transaction(database) {
        PostgresHealthProbePolicy.run(this) {
            OrganizationManagedChatProjections.selectAll().where {
                OrganizationManagedChatProjections.desiredRevision neq
                    OrganizationManagedChatProjections.appliedRevision
            }.count()
        }
    }

    override fun currentFailure(): ManagedChatProjectionFailure? = transaction(database) {
        PostgresHealthProbePolicy.run(this) {
            OrganizationManagedChatProjections.selectAll().where {
                (OrganizationManagedChatProjections.desiredRevision neq
                    OrganizationManagedChatProjections.appliedRevision) and
                    OrganizationManagedChatProjections.lastFailure.isNotNull()
            }.orderBy(
                OrganizationManagedChatProjections.desiredRevision to SortOrder.ASC,
                OrganizationManagedChatProjections.unitId to SortOrder.ASC,
            ).limit(1).singleOrNull()?.let { row ->
                ManagedChatProjectionFailure(
                    unitId = row[OrganizationManagedChatProjections.unitId],
                    chatId = row[OrganizationManagedChatProjections.chatId],
                    revision = row[OrganizationManagedChatProjections.desiredRevision],
                    attemptCount = row[OrganizationManagedChatProjections.attemptCount],
                    detail = row[OrganizationManagedChatProjections.lastFailure].orEmpty(),
                )
            }
        }
    }

    private fun readDesiredOrganizationFacts(task: ManagedChatProjectionTask): DesiredOrganizationFacts {
        // OrganizationState 已经锁定。每个组织事实写入器在触及
        // Units/Memberships 之前都必须获取该单例，因此这里的普通 MVCC 读取
        // 对本事务是稳定的。不要对这两张表加行锁：Document 的权限顺序是 User -> Unit ->
        // Membership，而 Bot 是 Chat -> User；持有 Unit/Membership 再等待 Chat
        // 会产生跨领域的 Unit -> Chat -> User -> Unit 死锁。
        val active = OrganizationUnits.selectAll()
            .where { OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE }
            .orderBy(OrganizationUnits.unitId, SortOrder.ASC)
            .limit(OrganizationCapacityPolicy.MAX_ACTIVE_UNITS + 1)
            .toList()
        require(active.size <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS) {
            OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
        }
        val unit = active.singleOrNull { it[OrganizationUnits.unitId] == task.unitId }
            ?: throw IllegalStateException("待启用的组织节点不存在: ${task.unitId}")
        require(unit[OrganizationUnits.groupChatId] == task.chatId) {
            "组织节点不再声明当前受管群"
        }
        val leaderUid = unit[OrganizationUnits.leaderUid]
            ?: throw IllegalStateException("受管部门群缺少负责人: ${task.unitId}")
        val hierarchy = OrganizationHierarchy.validate(
            active.map { row ->
                OrganizationHierarchyNode(
                    unitId = row[OrganizationUnits.unitId],
                    parentId = row[OrganizationUnits.parentId],
                )
            },
        )
        val subtree = hierarchy.descendants(task.unitId)
        val memberships = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.unitId inList subtree.toList()
        }.orderBy(
            OrganizationMemberships.unitId to SortOrder.ASC,
            OrganizationMemberships.uid to SortOrder.ASC,
        ).toList()
        val memberUids = memberships.mapTo(linkedSetOf()) { it[OrganizationMemberships.uid] }
        memberUids += leaderUid
        return DesiredOrganizationFacts(
            name = "${unit[OrganizationUnits.name]}部门群",
            ownerUid = leaderUid,
            memberUids = memberUids,
        )
    }

    private fun applyPositive(
        task: ManagedChatProjectionTask,
        facts: DesiredOrganizationFacts,
        desired: Set<String>,
        chatRow: ResultRow?,
        memberRows: List<ResultRow>,
        currentActive: Set<String>,
        projectionUids: Set<String>,
        muteUids: Set<String>,
        conversationRows: List<ResultRow>,
        usage: MutableMap<String, LockedConversationUsage>,
    ): ManagedChatProjectionMutation {
        val previousGroup = GroupChats.selectAll().where {
            GroupChats.chatId eq task.chatId
        }.singleOrNull()
        val chatMetadataChanged = chatRow == null || chatRow[Chats.status] != ACTIVE ||
            previousGroup == null || previousGroup[GroupChats.name] != facts.name ||
            previousGroup[GroupChats.avatar] != null || previousGroup[GroupChats.creator] != facts.ownerUid
        val conversationRowsByUid = conversationRows.associateBy { it[Conversations.uid] }
        val insertedConversationUids = desired - conversationRowsByUid.keys
        val deletedConversationRows = conversationRows.filter {
            it[Conversations.uid] !in desired
        }
        val usageDeltas = linkedMapOf<String, ConversationUsageDelta>()
        insertedConversationUids.forEach { uid ->
            usageDeltas[uid] = conversationUsageDeltaForInsert()
        }
        deletedConversationRows.forEach { row ->
            usageDeltas[row[Conversations.uid]] = conversationUsageDeltaForDelete(row)
        }
        // 容量是第一个可变投影操作。任何拒绝在外层事务回滚时，
        // 都会使 Chat、Member、Conversation 与台账保持不变。
        ConversationUsageLedger.apply(usage, usageDeltas)
        val now = System.currentTimeMillis()
        if (chatRow == null) {
            Chats.insert {
                it[chatId] = task.chatId
                it[chatType] = GROUP_CHAT_TYPE
                it[maxSeq] = 0L
                it[status] = ACTIVE
                it[createdAt] = now
                it[updatedAt] = now
            }
            GroupChats.insert {
                it[chatId] = task.chatId
                it[name] = facts.name
                it[avatar] = null
                it[creator] = facts.ownerUid
                it[notice] = ""
                it[mutedAll] = false
                it[updatedAt] = now
            }
        } else {
            Chats.update({ Chats.chatId eq task.chatId }) {
                it[status] = ACTIVE
                it[updatedAt] = now
            }
            val updated = GroupChats.update({ GroupChats.chatId eq task.chatId }) {
                it[name] = facts.name
                it[avatar] = null
                it[creator] = facts.ownerUid
                it[updatedAt] = now
            }
            if (updated == 0) {
                GroupChats.insert {
                    it[chatId] = task.chatId
                    it[name] = facts.name
                    it[avatar] = null
                    it[creator] = facts.ownerUid
                    it[notice] = ""
                    it[mutedAll] = false
                    it[updatedAt] = now
                }
            }
        }
        // 受管群绝不消费用户生成的邀请状态，重新激活后也是如此。
        GroupInviteLinks.deleteWhere { GroupInviteLinks.chatId eq task.chatId }

        val existingByUid = memberRows.associateBy { it[GroupMembers.uid] }
        val remaining = currentActive intersect desired
        val roleChanges = remaining.sorted().mapNotNull { uid ->
            val previousRole = existingByUid.getValue(uid)[GroupMembers.role]
            val currentRole = if (uid == facts.ownerUid) OWNER_ROLE else MEMBER_ROLE
            if (previousRole == currentRole) {
                null
            } else {
                ManagedChatMemberRoleChange(uid, previousRole, currentRole)
            }
        }
        // 安装新群主之前先清掉旧群主；在单个逐行 pass 中同时做这两件事，
        // 会在字典序 uid 顺序翻转时瞬时违反活跃群主部分唯一索引。
        GroupMembers.update({ GroupMembers.chatId eq task.chatId }) {
            it[chatType] = GROUP_CHAT_TYPE
            it[role] = MEMBER_ROLE
        }
        memberRows.forEach { row ->
            val uid = row[GroupMembers.uid]
            val keep = uid in desired
            GroupMembers.update({
                (GroupMembers.chatId eq task.chatId) and (GroupMembers.uid eq uid)
            }) {
                it[role] = if (keep && uid == facts.ownerUid) OWNER_ROLE else MEMBER_ROLE
                it[status] = if (keep) ACTIVE else INACTIVE
                if (keep && row[GroupMembers.status] != ACTIVE) it[joinedAt] = now
            }
        }
        desired.filterNot(existingByUid::containsKey).forEach { uid ->
            GroupMembers.insert {
                it[chatId] = task.chatId
                it[chatType] = GROUP_CHAT_TYPE
                it[GroupMembers.uid] = uid
                it[role] = if (uid == facts.ownerUid) OWNER_ROLE else MEMBER_ROLE
                it[status] = ACTIVE
                it[joinedAt] = now
            }
        }
        val removed = (projectionUids - desired).sorted()
        val added = (desired - currentActive).sorted()
        if (deletedConversationRows.isNotEmpty()) {
            val deletedConversationUids = deletedConversationRows.map { it[Conversations.uid] }
            check(Conversations.deleteWhere {
                (Conversations.chatId eq task.chatId) and
                    (Conversations.uid inList deletedConversationUids)
            } == deletedConversationRows.size) {
                "受管群会话投影在锁定后发生变化"
            }
        }
        val staleMuteUids = (muteUids - desired).sorted()
        if (staleMuteUids.isNotEmpty()) {
            GroupMemberMutes.deleteWhere {
                (GroupMemberMutes.chatId eq task.chatId) and (GroupMemberMutes.uid inList staleMuteUids)
            }
        }
        insertedConversationUids.forEach { uid ->
            Conversations.insert {
                it[Conversations.uid] = uid
                it[chatId] = task.chatId
                it[chatType] = GROUP_CHAT_TYPE
                it[lastMsgSeq] = 0L
                it[updatedAt] = now
            }
        }
        if (desired.isNotEmpty()) {
            Conversations.update({
                (Conversations.chatId eq task.chatId) and (Conversations.uid inList desired.toList())
            }) {
                it[chatType] = GROUP_CHAT_TYPE
            }
        }
        val maxSeq = chatRow?.get(Chats.maxSeq) ?: 0L
        val group = GroupChats.selectAll().where { GroupChats.chatId eq task.chatId }.single()
        val chat = Chat(
            chatId = task.chatId,
            chatType = GROUP_CHAT_TYPE,
            name = facts.name,
            avatar = group[GroupChats.avatar],
            creator = facts.ownerUid,
            memberCount = desired.size,
            maxSeq = maxSeq,
            notice = group[GroupChats.notice],
            mutedAll = group[GroupChats.mutedAll],
        )
        return ManagedChatProjectionMutation(
            task = task,
            applied = true,
            chat = chat,
            addedUids = added,
            removedUids = removed,
            remainingUids = remaining.sorted(),
            roleChanges = roleChanges,
            chatMetadataChanged = chatMetadataChanged,
        )
    }

    private fun applyNegative(
        task: ManagedChatProjectionTask,
        chatRow: ResultRow?,
        projectionUids: Set<String>,
        conversationRows: List<ResultRow>,
        usage: MutableMap<String, LockedConversationUsage>,
    ): ManagedChatProjectionMutation {
        ConversationUsageLedger.apply(
            usage,
            conversationRows.associate { row ->
                row[Conversations.uid] to conversationUsageDeltaForDelete(row)
            },
        )
        val now = System.currentTimeMillis()
        if (chatRow != null) {
            Chats.update({ Chats.chatId eq task.chatId }) {
                it[status] = INACTIVE
                it[updatedAt] = now
            }
        }
        GroupMembers.update({ GroupMembers.chatId eq task.chatId }) {
            it[role] = MEMBER_ROLE
            it[status] = INACTIVE
        }
        check(Conversations.deleteWhere { Conversations.chatId eq task.chatId } == conversationRows.size) {
            "受管群会话投影在锁定后发生变化"
        }
        GroupMemberMutes.deleteWhere { GroupMemberMutes.chatId eq task.chatId }
        GroupInviteLinks.deleteWhere { GroupInviteLinks.chatId eq task.chatId }
        val group = GroupChats.selectAll().where { GroupChats.chatId eq task.chatId }.singleOrNull()
        val chat = if (chatRow != null || projectionUids.isNotEmpty()) {
            Chat(
                chatId = task.chatId,
                chatType = GROUP_CHAT_TYPE,
                name = group?.get(GroupChats.name),
                avatar = group?.get(GroupChats.avatar),
                creator = group?.get(GroupChats.creator),
                memberCount = 0,
                maxSeq = chatRow?.get(Chats.maxSeq) ?: 0L,
                notice = group?.get(GroupChats.notice),
                mutedAll = group?.get(GroupChats.mutedAll) ?: false,
            )
        } else {
            null
        }
        return ManagedChatProjectionMutation(
            task = task,
            applied = true,
            chat = chat,
            removedUids = projectionUids.sorted(),
        )
    }

    private fun discoverBotRows(chatId: String): Map<String, BotSnapshot> {
        val botIds = (
            AutomationBots.selectAll().where { AutomationBots.managedChatId eq chatId }
                .map { it[AutomationBots.botId] } +
                AutomationBotGrants.selectAll().where { AutomationBotGrants.chatId eq chatId }
                    .map { it[AutomationBotGrants.botId] }
            ).distinct().sorted()
        if (botIds.isEmpty()) return emptyMap()
        return AutomationBots.selectAll().where { AutomationBots.botId inList botIds }
            .associate { row ->
                row[AutomationBots.botId] to BotSnapshot(
                    userUid = row[AutomationBots.userUid],
                    status = row[AutomationBots.status],
                )
            }
    }

    private fun readUsers(uids: List<String>): Map<String, ResultRow> {
        if (uids.isEmpty()) return emptyMap()
        // User uid 与全局角色目前没有删除/修改命令。组织事实
        // 命令在发布版本之前锁定并校验身份，而 Bot 命令
        // 拥有服务身份有效性。刻意不在此 FOR UPDATE：在读取组织 Memberships 之后
        // 取 User 锁会颠倒 Document 的 User -> Unit ->
        // Membership 顺序；在 Chat 之前取则会颠倒 Bot 的 Chat -> User 顺序。如果
        // 将来引入身份可变性，必须先重新设计此锁协议。
        return Users.selectAll().where { Users.uid inList uids }
            .orderBy(Users.uid, SortOrder.ASC)
            .associateBy { it[Users.uid] }
    }

    private fun lockBots(discovered: Map<String, BotSnapshot>): Map<String, BotSnapshot> {
        if (discovered.isEmpty()) return emptyMap()
        val rows = AutomationBots.selectAll().where { AutomationBots.botId inList discovered.keys.toList() }
            .orderBy(AutomationBots.botId, SortOrder.ASC).forUpdate().toList()
        check(rows.size == discovered.size) { "机器人聚合在投影锁定前发生变化" }
        return rows.associate { row ->
            val botId = row[AutomationBots.botId]
            val snapshot = BotSnapshot(row[AutomationBots.userUid], row[AutomationBots.status])
            check(discovered[botId]?.userUid == snapshot.userUid) { "机器人服务身份在锁定前发生变化" }
            botId to snapshot
        }
    }

    private fun lockGrants(chatId: String): Set<String> = AutomationBotGrants.selectAll().where {
        AutomationBotGrants.chatId eq chatId
    }.orderBy(AutomationBotGrants.botId, SortOrder.ASC).forUpdate()
        .mapTo(linkedSetOf()) { it[AutomationBotGrants.botId] }

    private fun deactivateBotFacts(chatId: String) {
        AutomationBots.update({ AutomationBots.managedChatId eq chatId }) {
            it[status] = AutomationBot.STATUS_DISABLED
            it[updatedAt] = System.currentTimeMillis()
        }
        AutomationBotGrants.deleteWhere { AutomationBotGrants.chatId eq chatId }
    }

    private fun markApplied(task: ManagedChatProjectionTask) {
        val changed = OrganizationManagedChatProjections.update({
            (OrganizationManagedChatProjections.unitId eq task.unitId) and
                (OrganizationManagedChatProjections.chatId eq task.chatId) and
                (OrganizationManagedChatProjections.desiredRevision eq task.revision) and
                (OrganizationManagedChatProjections.desiredActive eq task.desiredActive)
        }) {
            it[appliedRevision] = task.revision
            it[attemptCount] = 0
            it[nextAttemptAt] = 0L
            it[lastFailure] = null
            it[updatedAt] = System.currentTimeMillis()
        }
        check(changed == 1) { "Organization projection revision changed while locked" }
    }

    private fun stale(task: ManagedChatProjectionTask) = ManagedChatProjectionMutation(task, applied = false)

    private inline fun <T> inWriteTransaction(context: PgWriteTransactionContext, block: () -> T): T {
        context.requireExposedTransaction()
        return block()
    }

    private data class DesiredOrganizationFacts(
        val name: String,
        val ownerUid: String,
        val memberUids: Set<String>,
    )

    private data class BotSnapshot(val userUid: String, val status: Int)

    private companion object {
        const val STATE_ID = 1
        const val GROUP_CHAT_TYPE = 2
        const val ACTIVE = 1
        const val INACTIVE = 0
        const val MEMBER_ROLE = 0
        const val OWNER_ROLE = 2
        const val INITIAL_RETRY_DELAY_MS = 1_000
        const val MAX_RETRY_DELAY_MS = 60_000
    }
}

private fun ResultRow.toProjectionTask() = ManagedChatProjectionTask(
    unitId = this[OrganizationManagedChatProjections.unitId],
    chatId = this[OrganizationManagedChatProjections.chatId],
    revision = this[OrganizationManagedChatProjections.desiredRevision],
    desiredActive = this[OrganizationManagedChatProjections.desiredActive],
)
