package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.organization.ManagedChatProjectionTask
import com.virjar.tk.server.domain.organization.OrganizationMemberPageAnchor
import com.virjar.tk.server.domain.organization.OrganizationMemberPageSlice
import com.virjar.tk.server.domain.organization.OrganizationCommandResult
import com.virjar.tk.server.domain.organization.OrganizationHierarchy
import com.virjar.tk.server.domain.organization.OrganizationHierarchyNode
import com.virjar.tk.server.domain.organization.OrganizationMemberRemovalConflictException
import com.virjar.tk.server.domain.organization.OrganizationRepository
import com.virjar.tk.server.domain.organization.OrganizationUnitPageAnchor
import com.virjar.tk.server.domain.organization.OrganizationUnitPageSlice
import com.virjar.tk.server.domain.organization.OrganizationUnitArchiveConflictException
import com.virjar.tk.server.domain.chat.ManagedChatAuthority
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Database
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

internal data class OrganizationCapacityLimits(
    val activeUnits: Int = OrganizationCapacityPolicy.MAX_ACTIVE_UNITS,
    val unitRecords: Int = OrganizationCapacityPolicy.MAX_UNIT_RECORDS,
    val managedChatProjections: Int = OrganizationCapacityPolicy.MAX_MANAGED_CHAT_PROJECTIONS,
    val membershipRelations: Int = OrganizationCapacityPolicy.MAX_MEMBERSHIP_RELATIONS,
    val membersPerUnit: Int = OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT,
    val membershipsPerUser: Int = OrganizationCapacityPolicy.MAX_MEMBERSHIPS_PER_USER,
) {
    init {
        require(activeUnits in 1..OrganizationCapacityPolicy.MAX_ACTIVE_UNITS)
        require(unitRecords in activeUnits..OrganizationCapacityPolicy.MAX_UNIT_RECORDS)
        require(managedChatProjections in 1..OrganizationCapacityPolicy.MAX_MANAGED_CHAT_PROJECTIONS)
        require(membershipRelations in 1..OrganizationCapacityPolicy.MAX_MEMBERSHIP_RELATIONS)
        require(membersPerUnit in 1..OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT)
        require(membershipsPerUser in 1..OrganizationCapacityPolicy.MAX_MEMBERSHIPS_PER_USER)
    }
}

internal class ExposedOrganizationRepository(
    private val database: Database,
    private val lockHooks: OrganizationLockHooks = OrganizationLockHooks.None,
    private val capacityLimits: OrganizationCapacityLimits = OrganizationCapacityLimits(),
) : OrganizationRepository {
    private val readProjection = ExposedOrganizationReadProjection(database)

    override fun listUnitPage(
        expectedRevision: Long?,
        after: OrganizationUnitPageAnchor?,
        pageSize: Int,
    ): OrganizationUnitPageSlice = readProjection.listUnitPage(expectedRevision, after, pageSize)

    override fun listMemberPage(
        rootUnitId: String,
        recursive: Boolean,
        expectedRevision: Long?,
        after: OrganizationMemberPageAnchor?,
        pageSize: Int,
    ): OrganizationMemberPageSlice =
        readProjection.listMemberPage(rootUnitId, recursive, expectedRevision, after, pageSize)

    override fun listUnits(): List<OrganizationUnit> = readProjection.listUnits()

    override fun findUnit(unitId: String): OrganizationUnit? = readProjection.findUnit(unitId)

    override fun createUnit(
        transaction: PgWriteTransactionContext,
        unit: OrganizationUnit,
        enableGroup: Boolean,
    ): OrganizationCommandResult<OrganizationUnit> = inWriteTransaction(transaction) {
        require(unit.sortOrder >= 0) { "sortOrder 不能为负数" }
        val stateRevision = lockStateAndProjectionRevision()
        unit.leaderUid?.let(::lockRequiredUser)
        val before = lockActiveUnits()
        require(before.size < capacityLimits.activeUnits) {
            OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
        }
        requireCurrentUnitRecordCount(hasRoomForInsert = true)
        if (unit.parentId == null) {
            require(before.values.none { it.parentId == null }) { "单组织只能有一个根节点" }
        } else {
            require(before.containsKey(unit.parentId)) { "父组织节点不存在: ${unit.parentId}" }
        }
        if (enableGroup) require(unit.leaderUid != null) { "启用部门群前必须设置负责人" }

        val created = unit.copy(
            groupChatId = if (enableGroup) unit.unitId else null,
            status = OrganizationUnit.STATUS_ACTIVE,
        )
        val after = before + (created.unitId to created)
        val affected = hierarchy(after).ancestors(created.unitId)
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
        unit.leaderUid?.let { leader ->
            requireNewMembershipCapacity(unit.unitId, leader)
            upsertMemberInternal(unit.unitId, leader, "负责人", false, now)
        }
        commandResult(created, stateRevision, affected, after)
    }

    override fun updateUnit(
        transaction: PgWriteTransactionContext,
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
        val oldAncestors = hierarchy(before).ancestors(unitId)
        val updated = old.copy(parentId = parentId, name = name, leaderUid = leaderUid, sortOrder = sortOrder)
        val after = before + (unitId to updated)
        val affected = oldAncestors + hierarchy(after).ancestors(unitId) + unitId
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
                requireNewMembershipCapacity(unitId, leader)
                upsertMemberInternal(unitId, leader, "负责人", false, now)
            }
        }
        commandResult(updated, stateRevision, affected, after)
    }

    override fun archiveUnit(
        transaction: PgWriteTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<Unit> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        val before = lockActiveUnits()
        val unit = before[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        // 资产交接在 User/Space/Unit 之前获取 OrganizationState。持有同一
        // 单例使这个无锁谓词成为可线性化的归属守卫，
        // 同时避免普通文档 ACL 写使用的反向 Unit -> Space 边。
        requireNoOwnedDocumentSpaces(unitId)
        require(before.values.none { it.parentId == unitId }) { "请先移动或删除下级部门" }
        val memberships = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.unitId eq unitId
        }.orderBy(OrganizationMemberships.uid, SortOrder.ASC).forUpdate().toList()
        require(memberships.isEmpty()) { "请先移出部门成员" }
        val oldAncestors = hierarchy(before).ancestors(unitId) - unitId
        val after = before - unitId
        hierarchy(after)
        val now = System.currentTimeMillis()
        check(OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
            it[status] = OrganizationUnit.STATUS_ARCHIVED
            it[groupChatId] = null
            it[updatedAt] = now
        } == 1) { "Locked organization unit disappeared" }
        commandResult(Unit, stateRevision, oldAncestors, after, negativeUnitIds = setOf(unitId))
    }

    override fun assignMember(
        transaction: PgWriteTransactionContext,
        member: OrganizationMember,
    ): OrganizationCommandResult<OrganizationMember> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        lockRequiredUser(member.uid)
        val units = lockActiveUnits()
        require(units.containsKey(member.unitId)) { "组织节点不存在: ${member.unitId}" }
        OrganizationMemberships.selectAll().where {
            OrganizationMemberships.uid eq member.uid
        }.orderBy(OrganizationMemberships.unitId, SortOrder.ASC).forUpdate().toList()
        val membershipAlreadyExists = membershipExists(member.unitId, member.uid, forUpdate = true)
        if (!membershipAlreadyExists) requireNewMembershipCapacity(member.unitId, member.uid)
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
        transaction: PgWriteTransactionContext,
        unitId: String,
        uid: String,
    ): OrganizationCommandResult<Unit> = inWriteTransaction(transaction) {
        val stateRevision = lockStateAndProjectionRevision()
        lockRequiredUser(uid)
        val units = lockActiveUnits()
        val unit = units[unitId] ?: throw IllegalArgumentException("组织节点不存在: $unitId")
        if (unit.leaderUid == uid) {
            throw OrganizationMemberRemovalConflictException("请先变更部门负责人")
        }
        OrganizationMemberships.selectAll().where {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }.forUpdate().singleOrNull()
        OrganizationMemberships.deleteWhere {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }
        commandResult(Unit, stateRevision, ancestors(unitId, units), units)
    }

    override fun enableGroup(
        transaction: PgWriteTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit> = inWriteTransaction(transaction) {
        // 先不加锁解析负责人，然后在 State/projection -> User 锁之后重新校验。
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
            requireNewMembershipCapacity(unitId, leader)
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
        transaction: PgWriteTransactionContext,
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

    override fun listMembers(unitIds: Set<String>): List<OrganizationMember> =
        readProjection.listMembers(unitIds)

    override fun countDirectMembers(unitIds: Set<String>): Map<String, Int> =
        readProjection.countDirectMembers(unitIds)

    override fun listMemberships(uid: String): List<OrganizationMember> =
        readProjection.listMemberships(uid)

    override fun authority(chatId: String): ManagedChatAuthority = transaction(database) {
        val row = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.chatId eq chatId
        }.singleOrNull() ?: return@transaction ManagedChatAuthority(managed = false, ready = true)
        val owner = OrganizationUnits.selectAll().where {
            OrganizationUnits.unitId eq row[OrganizationManagedChatProjections.unitId]
        }.singleOrNull()?.get(OrganizationUnits.name)?.let { "$it 部门" }
        ManagedChatAuthority(managed = true, ready = row.projectionReady(), ownerLabel = owner)
    }

    override fun lockAuthority(
        transaction: PgWriteTransactionContext,
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
        return OrganizationCommandResult(
            value = value,
            revision = revision,
            projections = tasks,
        )
    }

    private fun scheduleProjection(unitId: String, revision: Long, desiredActive: Boolean): ManagedChatProjectionTask {
        val existing = OrganizationManagedChatProjections.selectAll().where {
            OrganizationManagedChatProjections.unitId eq unitId
        }.forUpdate().singleOrNull()
        val now = System.currentTimeMillis()
        if (existing == null) {
            val projectionCount = OrganizationManagedChatProjections.selectAll().count()
            require(projectionCount < capacityLimits.managedChatProjections.toLong()) {
                OrganizationCapacityPolicy.MANAGED_CHAT_PROJECTION_CAPACITY_REASON
            }
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

    private fun requireNoOwnedDocumentSpaces(unitId: String) {
        val ownedActiveSpace = DocumentSpaces.select(DocumentSpaces.spaceId).where {
            (DocumentSpaces.ownerPrincipalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                (DocumentSpaces.ownerPrincipalId eq unitId) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }.limit(1).singleOrNull()
        if (ownedActiveSpace != null) {
            throw OrganizationUnitArchiveConflictException("请先转移该组织节点持有的文档空间")
        }
    }

    /**
     * 组织事实是一个低频全局聚合。在任何 User 行之前先锁定每个永久受管聊天
     * 权威行，使普通/Bot 命令的全局顺序保持 projection -> Chat -> User。
     * 单例状态序列化投影行的发现与创建，
     * 因此并发的组织命令不可能引入一条未被观察到的现有行。
     */
    private fun lockStateAndProjectionRevision(): Long {
        lockHooks.beforeStateAndProjectionLocks()
        val revision = OrganizationState.selectAll()
            .where { OrganizationState.id eq STATE_ID }
            .forUpdate()
            .single()[OrganizationState.revision]
        val projections = OrganizationManagedChatProjections.selectAll()
            .orderBy(OrganizationManagedChatProjections.chatId, SortOrder.ASC)
            .limit(capacityLimits.managedChatProjections + 1)
            .forUpdate()
            .toList()
        require(projections.size <= capacityLimits.managedChatProjections) {
            OrganizationCapacityPolicy.MANAGED_CHAT_PROJECTION_CAPACITY_REASON
        }
        return revision
    }

    private fun lockActiveUnits(): Map<String, OrganizationUnit> {
        requireCurrentUnitRecordCount(hasRoomForInsert = false)
        val rows = OrganizationUnits.selectAll()
            .where { OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE }
            .orderBy(OrganizationUnits.unitId, SortOrder.ASC)
            .limit(capacityLimits.activeUnits + 1)
            .forUpdate()
            .toList()
        require(rows.size <= capacityLimits.activeUnits) {
            OrganizationCapacityPolicy.UNIT_CAPACITY_REASON
        }
        val units = rows.associate { row -> row[OrganizationUnits.unitId] to row.toOrganizationUnit() }
        hierarchy(units)
        return units
    }

    private fun requireCurrentUnitRecordCount(hasRoomForInsert: Boolean) {
        val recordCount = OrganizationUnits.selectAll().count()
        val accepted = if (hasRoomForInsert) {
            recordCount < capacityLimits.unitRecords.toLong()
        } else {
            recordCount <= capacityLimits.unitRecords.toLong()
        }
        require(accepted) { OrganizationCapacityPolicy.UNIT_RECORD_CAPACITY_REASON }
    }

    private fun membershipExists(unitId: String, uid: String, forUpdate: Boolean): Boolean {
        val query = OrganizationMemberships.selectAll().where {
            (OrganizationMemberships.unitId eq unitId) and (OrganizationMemberships.uid eq uid)
        }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull() != null
    }

    /**
     * 阶段 A 准入 fence。[OrganizationState] 已经锁定，因此每个组织写入器
     * 都观察同一个序列化的计数快照，落败的写入器不可能并发通过检查。
     * 之后的锁局部性重构可以把这些有界索引计数替换为 O(1) 台账；
     * 对外可见的限制与拒绝语义保持不变。
     */
    private fun requireNewMembershipCapacity(unitId: String, uid: String) {
        val total = OrganizationMemberships.selectAll().count()
        require(total < capacityLimits.membershipRelations.toLong()) {
            OrganizationCapacityPolicy.MEMBERSHIP_CAPACITY_REASON
        }
        val direct = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.unitId eq unitId
        }.count()
        require(direct < capacityLimits.membersPerUnit.toLong()) {
            OrganizationCapacityPolicy.UNIT_MEMBER_CAPACITY_REASON
        }
        val membershipsForUser = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.uid eq uid
        }.count()
        require(membershipsForUser < capacityLimits.membershipsPerUser.toLong()) {
            OrganizationCapacityPolicy.USER_MEMBERSHIP_CAPACITY_REASON
        }
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

    private fun ancestors(unitId: String, units: Map<String, OrganizationUnit>): Set<String> =
        hierarchy(units).ancestors(unitId)

    private fun descendants(unitId: String, units: Map<String, OrganizationUnit>): Set<String> =
        hierarchy(units).descendants(unitId)

    private fun hierarchy(units: Map<String, OrganizationUnit>): OrganizationHierarchy =
        OrganizationHierarchy.validate(
            units.values.map { unit -> OrganizationHierarchyNode(unit.unitId, unit.parentId) },
        )

    private inline fun <T> inWriteTransaction(context: PgWriteTransactionContext, block: () -> T): T {
        context.requireExposedTransaction()
        return block()
    }

    private companion object {
        const val STATE_ID = 1
    }
}

private fun ResultRow.projectionReady(): Boolean =
    this[OrganizationManagedChatProjections.desiredRevision] ==
        this[OrganizationManagedChatProjections.appliedRevision] &&
        this[OrganizationManagedChatProjections.lastFailure] == null
