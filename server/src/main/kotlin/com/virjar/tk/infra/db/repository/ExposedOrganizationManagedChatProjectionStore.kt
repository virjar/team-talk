package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.bot.AutomationBot
import com.virjar.tk.domain.organization.ManagedChatProjectionCursor
import com.virjar.tk.domain.organization.ManagedChatProjectionFailure
import com.virjar.tk.domain.organization.ManagedChatProjectionMutation
import com.virjar.tk.domain.organization.ManagedChatProjectionTask
import com.virjar.tk.domain.organization.OrganizationManagedChatProjectionStore
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.AutomationBotGrants
import com.virjar.tk.infra.db.AutomationBots
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupInviteLinks
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.infra.db.OrganizationMemberships
import com.virjar.tk.infra.db.OrganizationState
import com.virjar.tk.infra.db.OrganizationUnits
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Chat
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.model.UserRole
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
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.math.min

/** Full-replacement writer for the organization-owned chat projection. */
class ExposedOrganizationManagedChatProjectionStore : OrganizationManagedChatProjectionStore {
    override fun listPending(
        after: ManagedChatProjectionCursor?,
        limit: Int,
        includeDeferred: Boolean,
        nowMillis: Long,
    ): List<ManagedChatProjectionTask> = transaction {
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
        transaction: PgTransactionContext,
        task: ManagedChatProjectionTask,
    ): ManagedChatProjectionMutation = inWriteTransaction(transaction) {
        // Organization state/projection are the outer revision fence. From this point onward the
        // caller holds ChatLifecycleGate and database locks follow organization facts -> Chat ->
        // Bot/grant -> membership/conversation -> SyncStreams. User rows are intentionally read
        // without locks below; see readUsers() for the cross-domain lock-order invariant.
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

        // All existing-chat writers use Invite -> Member -> Mute -> Conversation after
        // authority/Chat/User/Bot locks. Keep reconciliation on the same order so a concurrent
        // invite command or dissolution cannot form a projection-row deadlock.
        GroupInviteLinks.selectAll().where { GroupInviteLinks.chatId eq task.chatId }
            .orderBy(GroupInviteLinks.token, SortOrder.ASC).forUpdate().toList()
        val memberRows = GroupMembers.selectAll().where { GroupMembers.chatId eq task.chatId }
            .orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().toList()
        val muteRows = GroupMemberMutes.selectAll().where { GroupMemberMutes.chatId eq task.chatId }
            .orderBy(GroupMemberMutes.uid, SortOrder.ASC).forUpdate().toList()
        val conversationRows = Conversations.selectAll().where { Conversations.chatId eq task.chatId }
            .orderBy(Conversations.uid, SortOrder.ASC).forUpdate().toList()

        val currentActive = memberRows.asSequence()
            .filter { it[GroupMembers.status] == ACTIVE }
            .mapTo(linkedSetOf()) { it[GroupMembers.uid] }
        val projectionUids = (currentActive + conversationRows.map { it[Conversations.uid] }).toSet()
        val result = if (task.desiredActive) {
            applyPositive(
                task,
                desiredFacts!!,
                activeBotUids,
                chatRow,
                memberRows,
                currentActive,
                projectionUids,
                muteRows.mapTo(linkedSetOf()) { it[GroupMemberMutes.uid] },
            )
        } else {
            applyNegative(task, chatRow, projectionUids)
        }
        markApplied(task)
        result
    }

    override fun recordFailure(
        transaction: PgTransactionContext,
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

    override fun countPending(): Long = transaction {
        OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.desiredRevision neq
                OrganizationManagedChatProjections.appliedRevision
        }.count()
    }

    override fun currentFailure(): ManagedChatProjectionFailure? = transaction {
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

    private fun readDesiredOrganizationFacts(task: ManagedChatProjectionTask): DesiredOrganizationFacts {
        // OrganizationState is already locked. Every organization fact writer must acquire that
        // singleton before touching Units/Memberships, so ordinary MVCC reads here are stable for
        // this transaction. Do not row-lock either table: Document authority is User -> Unit ->
        // Membership while Bot is Chat -> User; holding Unit/Membership while waiting for Chat
        // would create a cross-domain Unit -> Chat -> User -> Unit deadlock.
        val units = OrganizationUnits.selectAll()
            .orderBy(OrganizationUnits.unitId, SortOrder.ASC)
            .toList()
        val active = units.filter { it[OrganizationUnits.status] == OrganizationUnit.STATUS_ACTIVE }
        val unit = active.singleOrNull { it[OrganizationUnits.unitId] == task.unitId }
            ?: throw IllegalStateException("待启用的组织节点不存在: ${task.unitId}")
        require(unit[OrganizationUnits.groupChatId] == task.chatId) {
            "组织节点不再声明当前受管群"
        }
        val leaderUid = unit[OrganizationUnits.leaderUid]
            ?: throw IllegalStateException("受管部门群缺少负责人: ${task.unitId}")
        val models = active.associate { row ->
            val unitId = row[OrganizationUnits.unitId]
            unitId to UnitSnapshot(unitId, row[OrganizationUnits.parentId])
        }
        val subtree = descendants(task.unitId, models)
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
        botUids: Set<String>,
        chatRow: ResultRow?,
        memberRows: List<ResultRow>,
        currentActive: Set<String>,
        projectionUids: Set<String>,
        muteUids: Set<String>,
    ): ManagedChatProjectionMutation {
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
        // Managed chats never consume user-generated invite state, including after reactivation.
        GroupInviteLinks.deleteWhere { GroupInviteLinks.chatId eq task.chatId }

        val desired = (facts.memberUids + botUids).toSortedSet()
        val existingByUid = memberRows.associateBy { it[GroupMembers.uid] }
        // Clear the old owner before installing the new one; doing both in one row-by-row pass can
        // transiently violate the active-owner partial unique index when lexical uid order flips.
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
        if (removed.isNotEmpty()) {
            Conversations.deleteWhere {
                (Conversations.chatId eq task.chatId) and (Conversations.uid inList removed)
            }
        }
        val staleMuteUids = (muteUids - desired).sorted()
        if (staleMuteUids.isNotEmpty()) {
            GroupMemberMutes.deleteWhere {
                (GroupMemberMutes.chatId eq task.chatId) and (GroupMemberMutes.uid inList staleMuteUids)
            }
        }
        desired.forEach { uid ->
            Conversations.insertIgnore {
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
            remainingUids = (currentActive intersect desired).sorted(),
            finalUids = desired.toList(),
        )
    }

    private fun applyNegative(
        task: ManagedChatProjectionTask,
        chatRow: ResultRow?,
        projectionUids: Set<String>,
    ): ManagedChatProjectionMutation {
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
        Conversations.deleteWhere { Conversations.chatId eq task.chatId }
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
        // User uid and global role currently have no delete/change command. Organization fact
        // commands lock and validate identities before publishing a revision, while Bot commands
        // own service-identity validity. Deliberately do not FOR UPDATE here: taking a User lock
        // after reading organization Memberships would invert Document's User -> Unit ->
        // Membership order; taking it before Chat would invert Bot's Chat -> User order. If
        // identity mutability is introduced, this lock protocol must be redesigned first.
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

    private fun descendants(unitId: String, units: Map<String, UnitSnapshot>): Set<String> {
        val children = units.values.groupBy(UnitSnapshot::parentId)
        val result = linkedSetOf<String>()
        fun visit(id: String) {
            check(result.add(id)) { "组织架构存在循环: $id" }
            children[id].orEmpty().forEach { visit(it.unitId) }
        }
        visit(unitId)
        return result
    }

    private inline fun <T> inWriteTransaction(context: PgTransactionContext, block: () -> T): T {
        context.requireExposedTransaction()
        return block()
    }

    private data class DesiredOrganizationFacts(
        val name: String,
        val ownerUid: String,
        val memberUids: Set<String>,
    )

    private data class UnitSnapshot(val unitId: String, val parentId: String?)
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
