package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.organization.ManagedChatProjectionTask
import com.virjar.tk.domain.organization.OrganizationCommandResult
import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.chat.ManagedChatAuthority
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.infra.db.OrganizationMemberships
import com.virjar.tk.infra.db.OrganizationState
import com.virjar.tk.infra.db.OrganizationUnits
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun interface OrganizationLockHooks {
    fun beforeStateAndProjectionLocks()

    object None : OrganizationLockHooks {
        override fun beforeStateAndProjectionLocks() = Unit
    }
}

class ExposedOrganizationRepository(
    private val lockHooks: OrganizationLockHooks = OrganizationLockHooks.None,
) : OrganizationRepository {
    override fun listUnits(): List<OrganizationUnit> = transaction {
        OrganizationUnits.selectAll()
            .where { OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE }
            .orderBy(OrganizationUnits.sortOrder to SortOrder.ASC, OrganizationUnits.name to SortOrder.ASC)
            .map(ResultRow::toOrganizationUnit)
    }

    override fun findUnit(unitId: String): OrganizationUnit? = transaction {
        OrganizationUnits.selectAll().where {
            (OrganizationUnits.unitId eq unitId) and
                (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
        }.singleOrNull()?.toOrganizationUnit()
    }

    override fun createUnit(
        transaction: PgTransactionContext,
        unit: OrganizationUnit,
        enableGroup: Boolean,
    ): OrganizationCommandResult<OrganizationUnit> = inWriteTransaction(transaction) {
        require(unit.sortOrder >= 0) { "sortOrder 不能为负数" }
        val stateRevision = lockStateAndProjectionRevision()
        unit.leaderUid?.let(::lockRequiredUser)
        val before = lockActiveUnits()
        if (unit.parentId == null) {
            require(before.values.none { it.parentId == null }) { "单组织只能有一个根节点" }
        } else {
            require(before.containsKey(unit.parentId)) { "父组织节点不存在: ${unit.parentId}" }
        }
        if (enableGroup) require(unit.leaderUid != null) { "启用部门群前必须设置负责人" }

        val now = System.currentTimeMillis()
        OrganizationUnits.insert {
            it[unitId] = unit.unitId
            it[parentId] = unit.parentId
            it[name] = unit.name
            it[leaderUid] = unit.leaderUid
            it[sortOrder] = unit.sortOrder
            it[groupChatId] = if (enableGroup) unit.unitId else null
            it[status] = OrganizationUnit.STATUS_ACTIVE
            it[createdAt] = now
            it[updatedAt] = now
        }
        unit.leaderUid?.let { leader -> upsertMemberInternal(unit.unitId, leader, "负责人", false, now) }

        val created = unit.copy(
            groupChatId = if (enableGroup) unit.unitId else null,
            status = OrganizationUnit.STATUS_ACTIVE,
        )
        val after = before + (created.unitId to created)
        val affected = ancestors(created.unitId, after)
        commandResult(created, stateRevision, affected, after)
    }

    override fun updateUnit(
        transaction: PgTransactionContext,
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ): OrganizationCommandResult<OrganizationUnit> = inWriteTransaction(transaction) {
        require(sortOrder >= 0) { "sortOrder 不能为负数" }
        val stateRevision = lockStateAndProjectionRevision()
        leaderUid?.let(::lockRequiredUser)
        val before = lockActiveUnits()
        val old = before[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        if (old.groupChatId != null) {
            require(leaderUid != null) { "已启用部门群的组织节点必须保留负责人" }
        }
        if (parentId == null) {
            require(before.values.none { it.parentId == null && it.unitId != unitId }) { "单组织只能有一个根节点" }
        } else {
            require(before.containsKey(parentId)) { "父组织节点不存在: $parentId" }
            require(parentId != unitId && parentId !in descendants(unitId, before)) {
                "组织节点不能移动到自己或后代节点下"
            }
        }
        val oldAncestors = ancestors(unitId, before)
        val now = System.currentTimeMillis()
        check(OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
            it[OrganizationUnits.parentId] = parentId
            it[OrganizationUnits.name] = name
            it[OrganizationUnits.leaderUid] = leaderUid
            it[OrganizationUnits.sortOrder] = sortOrder
            it[updatedAt] = now
        } == 1) { "Locked organization unit disappeared" }
        leaderUid?.let { leader ->
            if (!membershipExists(unitId, leader, forUpdate = true)) {
                upsertMemberInternal(unitId, leader, "负责人", false, now)
            }
        }
        val updated = old.copy(parentId = parentId, name = name, leaderUid = leaderUid, sortOrder = sortOrder)
        val after = before + (unitId to updated)
        val affected = oldAncestors + ancestors(unitId, after) + unitId
        commandResult(updated, stateRevision, affected, after)
    }

    override fun archiveUnit(
        transaction: PgTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<Unit> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        val before = lockActiveUnits()
        val unit = before[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        require(before.values.none { it.parentId == unitId }) { "请先移动或删除下级部门" }
        val memberships = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.unitId eq unitId
        }.orderBy(OrganizationMemberships.uid, SortOrder.ASC).forUpdate().toList()
        require(memberships.isEmpty()) { "请先移出部门成员" }
        val oldAncestors = ancestors(unitId, before) - unitId
        val now = System.currentTimeMillis()
        check(OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
            it[status] = OrganizationUnit.STATUS_ARCHIVED
            it[groupChatId] = null
            it[updatedAt] = now
        } == 1) { "Locked organization unit disappeared" }
        val after = before - unitId
        commandResult(Unit, stateRevision, oldAncestors, after, negativeUnitIds = setOf(unitId))
    }

    override fun assignMember(
        transaction: PgTransactionContext,
        member: OrganizationMember,
    ): OrganizationCommandResult<OrganizationMember> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        lockRequiredUser(member.uid)
        val units = lockActiveUnits()
        require(units.containsKey(member.unitId)) { "组织节点不存在: ${member.unitId}" }
        OrganizationMemberships.selectAll().where {
            OrganizationMemberships.uid eq member.uid
        }.orderBy(OrganizationMemberships.unitId, SortOrder.ASC).forUpdate().toList()
        val now = System.currentTimeMillis()
        upsertMemberInternal(
            member.unitId,
            member.uid,
            member.title,
            member.primary,
            if (member.joinedAt > 0) member.joinedAt else now,
        )
        val committed = member.copy(joinedAt = if (member.joinedAt > 0) member.joinedAt else now)
        commandResult(committed, stateRevision, ancestors(member.unitId, units), units)
    }

    override fun removeMember(
        transaction: PgTransactionContext,
        unitId: String,
        uid: String,
    ): OrganizationCommandResult<Unit> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        lockRequiredUser(uid)
        val units = lockActiveUnits()
        val unit = units[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        require(unit.leaderUid != uid) { "请先变更部门负责人" }
        OrganizationMemberships.selectAll().where {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }.forUpdate().singleOrNull()
        OrganizationMemberships.deleteWhere {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }
        commandResult(Unit, stateRevision, ancestors(unitId, units), units)
    }

    override fun enableGroup(
        transaction: PgTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit> = inWriteTransaction(transaction) {
        // Resolve the leader without a lock, then revalidate after State/projection -> User locks.
        val leader = OrganizationUnits.selectAll().where {
            (OrganizationUnits.unitId eq unitId) and
                (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
        }.singleOrNull()?.get(OrganizationUnits.leaderUid)
            ?: throw IllegalArgumentException("启用部门群前必须设置负责人")
        val stateRevision = lockStateAndProjectionRevision()
        lockRequiredUser(leader)
        val before = lockActiveUnits()
        val unit = before[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        require(unit.leaderUid == leader) { "部门负责人在锁定前发生变化，请重试" }
        val now = System.currentTimeMillis()
        if (!membershipExists(unitId, leader, forUpdate = true)) {
            upsertMemberInternal(unitId, leader, "负责人", false, now)
        }
        check(OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
            it[groupChatId] = unitId
            it[updatedAt] = now
        } == 1) { "Locked organization unit disappeared" }
        val updated = unit.copy(groupChatId = unitId)
        val after = before + (unitId to updated)
        commandResult(updated, stateRevision, ancestors(unitId, after), after)
    }

    override fun disableGroup(
        transaction: PgTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        val before = lockActiveUnits()
        val unit = before[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        val now = System.currentTimeMillis()
        check(OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
            it[groupChatId] = null
            it[updatedAt] = now
        } == 1) { "Locked organization unit disappeared" }
        val updated = unit.copy(groupChatId = null)
        val after = before + (unitId to updated)
        commandResult(updated, stateRevision, emptySet(), after, negativeUnitIds = setOf(unitId))
    }

    override fun listMembers(unitIds: Set<String>): List<OrganizationMember> {
        if (unitIds.isEmpty()) return emptyList()
        return transaction {
            OrganizationMemberships.selectAll()
                .where { OrganizationMemberships.unitId inList unitIds }
                .orderBy(OrganizationMemberships.joinedAt to SortOrder.ASC)
                .map(ResultRow::toOrganizationMember)
        }
    }

    override fun countDirectMembers(unitIds: Set<String>): Map<String, Int> {
        if (unitIds.isEmpty()) return emptyMap()
        return transaction {
            val memberCount = OrganizationMemberships.id.count()
            OrganizationMemberships.select(OrganizationMemberships.unitId, memberCount)
                .where { OrganizationMemberships.unitId inList unitIds }
                .groupBy(OrganizationMemberships.unitId)
                .associate { it[OrganizationMemberships.unitId] to it[memberCount].toInt() }
        }
    }

    override fun listMemberships(uid: String): List<OrganizationMember> = transaction {
        OrganizationMemberships.selectAll().where { OrganizationMemberships.uid eq uid }
            .map(ResultRow::toOrganizationMember)
    }

    override fun authority(chatId: String): ManagedChatAuthority = transaction {
        val row = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.chatId eq chatId
        }.singleOrNull() ?: return@transaction ManagedChatAuthority(managed = false, ready = true)
        val owner = OrganizationUnits.selectAll().where {
            OrganizationUnits.unitId eq row[OrganizationManagedChatProjections.unitId]
        }.singleOrNull()?.get(OrganizationUnits.name)?.let { "$it 部门" }
        ManagedChatAuthority(managed = true, ready = row.projectionReady(), ownerLabel = owner)
    }

    override fun lockAuthority(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
    ): Map<String, ManagedChatAuthority> = inWriteTransaction(transaction) {
        val requested = chatIds.distinct().sorted()
        if (requested.isEmpty()) return@inWriteTransaction emptyMap()
        val projections = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.chatId inList requested
        }.orderBy(OrganizationManagedChatProjections.chatId, SortOrder.ASC).forUpdate().toList()
        val units = projections.map { it[OrganizationManagedChatProjections.unitId] }.distinct().sorted()
        val names = if (units.isEmpty()) emptyMap() else OrganizationUnits.selectAll().where {
            OrganizationUnits.unitId inList units
        }.associate { it[OrganizationUnits.unitId] to it[OrganizationUnits.name] }
        val byChat = projections.associate { row ->
            val unitId = row[OrganizationManagedChatProjections.unitId]
            row[OrganizationManagedChatProjections.chatId] to ManagedChatAuthority(
                managed = true,
                ready = row.projectionReady(),
                ownerLabel = names[unitId]?.let { "$it 部门" },
            )
        }
        requested.associateWith { chatId ->
            byChat[chatId] ?: ManagedChatAuthority(managed = false, ready = true)
        }
    }

    private fun <T> commandResult(
        value: T,
        stateRevision: Long,
        affectedUnitIds: Set<String>,
        activeUnits: Map<String, OrganizationUnit>,
        negativeUnitIds: Set<String> = emptySet(),
    ): OrganizationCommandResult<T> {
        val revision = stateRevision + 1L
        OrganizationState.update({ OrganizationState.id eq STATE_ID }) {
            it[OrganizationState.revision] = revision
            it[updatedAt] = System.currentTimeMillis()
        }
        val desired = linkedMapOf<String, Boolean>()
        affectedUnitIds.sorted().forEach { unitId ->
            if (activeUnits[unitId]?.groupChatId == unitId) desired[unitId] = true
        }
        negativeUnitIds.sorted().forEach { desired[it] = false }
        val tasks = desired.map { (unitId, active) -> scheduleProjection(unitId, revision, active) }
        return OrganizationCommandResult(value, tasks)
    }

    private fun scheduleProjection(unitId: String, revision: Long, desiredActive: Boolean): ManagedChatProjectionTask {
        val existing = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.unitId eq unitId
        }.forUpdate().singleOrNull()
        val now = System.currentTimeMillis()
        if (existing == null) {
            OrganizationManagedChatProjections.insert {
                it[OrganizationManagedChatProjections.unitId] = unitId
                it[chatId] = unitId
                it[desiredRevision] = revision
                it[appliedRevision] = 0L
                it[OrganizationManagedChatProjections.desiredActive] = desiredActive
                it[attemptCount] = 0
                it[nextAttemptAt] = 0L
                it[lastFailure] = null
                it[updatedAt] = now
            }
        } else {
            OrganizationManagedChatProjections.update({ OrganizationManagedChatProjections.unitId eq unitId }) {
                it[desiredRevision] = revision
                it[OrganizationManagedChatProjections.desiredActive] = desiredActive
                it[attemptCount] = 0
                it[nextAttemptAt] = 0L
                it[lastFailure] = null
                it[updatedAt] = now
            }
        }
        return ManagedChatProjectionTask(unitId, unitId, revision, desiredActive)
    }

    private fun lockRequiredUser(uid: String) {
        require(Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull() != null) {
            "用户不存在: $uid"
        }
    }

    /**
     * Organization facts are a low-frequency global aggregate. Lock every permanent managed-chat
     * authority row before any User row so the global order remains projection -> Chat -> User for
     * ordinary/Bot commands. The singleton state serializes projection-row discovery and creation,
     * so a concurrent organization command cannot introduce an unobserved existing row.
     */
    private fun lockStateAndProjectionRevision(): Long {
        lockHooks.beforeStateAndProjectionLocks()
        val revision = OrganizationState.selectAll()
            .where { OrganizationState.id eq STATE_ID }
            .forUpdate()
            .single()[OrganizationState.revision]
        OrganizationManagedChatProjections.selectAll()
            .orderBy(OrganizationManagedChatProjections.chatId, SortOrder.ASC)
            .forUpdate()
            .toList()
        return revision
    }

    private fun lockActiveUnits(): Map<String, OrganizationUnit> = OrganizationUnits.selectAll()
        .where { OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE }
        .orderBy(OrganizationUnits.unitId, SortOrder.ASC)
        .forUpdate()
        .associate { row -> row[OrganizationUnits.unitId] to row.toOrganizationUnit() }

    private fun membershipExists(unitId: String, uid: String, forUpdate: Boolean): Boolean {
        val query = OrganizationMemberships.selectAll().where {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull() != null
    }

    private fun upsertMemberInternal(
        unitId: String,
        uid: String,
        title: String?,
        primary: Boolean,
        joinedAt: Long,
    ) {
        val now = System.currentTimeMillis()
        if (primary) {
            OrganizationMemberships.update({ OrganizationMemberships.uid eq uid }) {
                it[OrganizationMemberships.primary] = false
                it[updatedAt] = now
            }
        }
        val existing = OrganizationMemberships.selectAll().where {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }.forUpdate().singleOrNull()
        if (existing == null) {
            OrganizationMemberships.insert {
                it[OrganizationMemberships.unitId] = unitId
                it[OrganizationMemberships.uid] = uid
                it[OrganizationMemberships.title] = title
                it[OrganizationMemberships.primary] = primary
                it[OrganizationMemberships.joinedAt] = joinedAt
                it[updatedAt] = now
            }
        } else {
            OrganizationMemberships.update({
                (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
            }) {
                it[OrganizationMemberships.title] = title
                it[OrganizationMemberships.primary] = primary
                it[updatedAt] = now
            }
        }
    }

    private fun ancestors(unitId: String, units: Map<String, OrganizationUnit>): Set<String> {
        val result = linkedSetOf<String>()
        var cursor: String? = unitId
        while (cursor != null) {
            check(result.add(cursor)) { "组织架构存在循环: $cursor" }
            cursor = units[cursor]?.parentId
        }
        return result
    }

    private fun descendants(unitId: String, units: Map<String, OrganizationUnit>): Set<String> {
        val children = units.values.groupBy { it.parentId }
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

    private companion object {
        const val STATE_ID = 1
    }
}

private fun ResultRow.toOrganizationUnit() = OrganizationUnit(
    unitId = this[OrganizationUnits.unitId],
    parentId = this[OrganizationUnits.parentId],
    name = this[OrganizationUnits.name],
    leaderUid = this[OrganizationUnits.leaderUid],
    sortOrder = this[OrganizationUnits.sortOrder],
    groupChatId = this[OrganizationUnits.groupChatId],
    status = this[OrganizationUnits.status],
)

private fun ResultRow.toOrganizationMember() = OrganizationMember(
    unitId = this[OrganizationMemberships.unitId],
    uid = this[OrganizationMemberships.uid],
    title = this[OrganizationMemberships.title],
    primary = this[OrganizationMemberships.primary],
    joinedAt = this[OrganizationMemberships.joinedAt],
)

private fun ResultRow.projectionReady(): Boolean =
    this[OrganizationManagedChatProjections.desiredRevision] ==
        this[OrganizationManagedChatProjections.appliedRevision] &&
        this[OrganizationManagedChatProjections.lastFailure] == null
