package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 会话拥有的组织目录投影。
 *
 * 紧凑单元树随账号缓存加载一次。直属成员刻意不同：只有当该精确单元被活跃观察时，单元才获得
 * 常驻 StateFlow。递归 ACL 回退查询持久行，而不把整个大型组织提升为常驻内存。
 */
internal class LocalOrganizationProjectionStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val mergeUserLocked: (User) -> UserProjectionMerge,
    private val publishUserMergeLocked: (UserProjectionMerge) -> Unit,
) {
    private val unitsFlow = RetirableProjectionState(loadUnitProjectionFromSql())
    private val memberResidents = mutableMapOf<String, OrganizationMemberResident>()
    private val unitSnapshots = KeyedProjectionSnapshotGate("organization unit snapshot")
    private val memberSnapshots = KeyedProjectionSnapshotGate("organization member snapshot")
    private val activeMemberSnapshots = mutableMapOf<String, ProjectionSnapshotLease>()
    private var unitMemberFence: UnitMemberFence? = null

    fun getUnitProjection(): OrganizationUnitProjection = cacheUseGate.use {
        synchronized(stateLock) { unitsFlow.value }
    }

    fun observeUnitProjection(): Flow<OrganizationUnitProjection> =
        cacheUseGate.use { unitsFlow.observe() }

    /** 调用方持有 [stateLock] 与外层 SQL 事务。 */
    internal fun persistUserLocked(user: User) {
        val avatar = user.avatar
        queries.updateOrganizationMemberUser(
            user.username,
            user.name,
            avatar?.path,
            avatar?.name,
            avatar?.contentType,
            avatar?.size,
            user.phone,
            user.sex.toLong(),
            user.role.toLong(),
            user.status.toLong(),
            user.revision,
            user.uid,
            user.revision,
        )
    }

    /** 调用方持有 [stateLock]；对应的 SQL 事务已提交。 */
    internal fun publishUserLocked(user: User) {
        memberResidents.values.forEach { resident ->
            val projection = resident.flow.value
            var changed = false
            val members = projection.members.map { member ->
                val embedded = member.user
                if (
                    member.uid == user.uid &&
                    (embedded == null || user.revision > embedded.revision)
                ) {
                    changed = true
                    member.copy(user = user)
                } else {
                    member
                }
            }
            if (changed) resident.flow.value = projection.copy(members = members)
        }
    }

    fun advanceRequiredRevision(revision: Long): Long = cacheUseGate.use {
        requireOrganizationRevision(revision)
        synchronized(stateLock) {
            val current = loadProjectionStateFromSql()
            if (revision <= current.requiredRevision) return@synchronized current.requiredRevision
            val advanced = current.copy(requiredRevision = revision)
            queries.transaction { persistProjectionState(advanced) }
            publishProjectionStateLocked(advanced)
            advanced.requiredRevision
        }
    }

    fun upsertUnit(unit: OrganizationUnit) = cacheUseGate.use {
        validateUnit(unit)
        synchronized(stateLock) {
            invalidateUnitSnapshotLocked()
            persistUnit(unit)
            unitsFlow.value = unitsFlow.value.copy(
                units = normalizeUnits(
                    unitsFlow.value.units.filterNot { it.unitId == unit.unitId } + unit,
                    requireCompleteTree = false,
                ),
            )
        }
    }

    fun deleteUnit(unitId: String) = cacheUseGate.use {
        requireOrganizationKey(unitId, "unitId")
        synchronized(stateLock) {
            invalidateUnitSnapshotLocked()
            memberSnapshots.invalidate(unitId)
            activeMemberSnapshots.remove(unitId)
            queries.transaction {
                queries.deleteOrganizationMembersByUnit(unitId)
                queries.deleteOrganizationMemberSnapshot(unitId)
                queries.deleteOrganizationUnit(unitId)
            }
            unitsFlow.value = unitsFlow.value.copy(
                units = unitsFlow.value.units.filterNot { it.unitId == unitId },
            )
            memberResidents[unitId]?.flow?.value = OrganizationMemberProjection.Unfetched
        }
    }

    fun beginUnitSnapshot(): ProjectionSnapshotLease = cacheUseGate.use {
        synchronized(stateLock) {
            unitSnapshots.begin(ALL_UNITS_KEY).also { lease ->
                unitMemberFence = UnitMemberFence(lease, activeMemberSnapshots.toMap())
            }
        }
    }

    fun applyUnitSnapshot(
        lease: ProjectionSnapshotLease,
        units: List<OrganizationUnit>,
        revision: Long,
    ): Boolean = cacheUseGate.runIfOpen {
        requireOrganizationRevision(revision)
        val snapshot = normalizeUnits(units, requireCompleteTree = true)
        synchronized(stateLock) {
            if (!unitSnapshots.consumeIfCurrent(lease, ALL_UNITS_KEY)) return@synchronized false
            val state = loadProjectionStateFromSql()
            if (revision < state.requiredRevision) {
                if (unitMemberFence?.unitLease === lease) unitMemberFence = null
                return@synchronized false
            }
            val membersPredatingSnapshot = unitMemberFence
                ?.takeIf { it.unitLease === lease }
                ?.memberLeases
                .orEmpty()
            unitMemberFence = null
            val snapshotUnitIds = snapshot.asSequence().map(OrganizationUnit::unitId).toSet()
            val removedUnitIds = buildSet {
                unitsFlow.value.units.mapTo(this, OrganizationUnit::unitId)
                addAll(memberResidents.keys)
                addAll(activeMemberSnapshots.keys)
            } - snapshotUnitIds
            val memberSnapshotUnitsToFence = buildSet {
                addAll(removedUnitIds)
                membersPredatingSnapshot.forEach { (unitId, capturedLease) ->
                    if (activeMemberSnapshots[unitId] === capturedLease) add(unitId)
                }
            }
            queries.transaction {
                queries.deleteAllOrganizationUnits()
                snapshot.forEach(::persistUnit)
                queries.deleteOrganizationMembersForUnknownUnits()
                queries.deleteOrganizationMemberSnapshotsForUnknownUnits()
                persistProjectionState(
                    OrganizationProjectionState(
                        requiredRevision = revision,
                        unitSnapshotRevision = revision,
                        unitSnapshotKnown = true,
                    ),
                )
            }
            memberSnapshotUnitsToFence.forEach { unitId ->
                memberSnapshots.invalidate(unitId)
                activeMemberSnapshots.remove(unitId)
            }
            removedUnitIds.forEach { removedUnitId ->
                memberResidents[removedUnitId]?.flow?.value = OrganizationMemberProjection.Unfetched
            }
            unitsFlow.value = OrganizationUnitProjection(
                snapshotKnown = true,
                revision = revision,
                units = snapshot,
            )
            memberResidents.forEach { (unitId, resident) ->
                if (unitId !in removedUnitIds) {
                    resident.flow.value = loadMemberProjectionFromSql(unitId)
                }
            }
            true
        }
    }

    fun getMemberProjection(unitId: String): OrganizationMemberProjection = cacheUseGate.use {
        requireOrganizationKey(unitId, "unitId")
        synchronized(stateLock) {
            memberResidents[unitId]?.flow?.value ?: loadMemberProjectionFromSql(unitId)
        }
    }

    fun observeMemberProjection(unitId: String): Flow<OrganizationMemberProjection> = cacheUseGate.use {
        requireOrganizationKey(unitId, "unitId")
        flow {
            val resident = acquireMemberResident(unitId)
            try {
                emitAll(resident.flow.observe())
            } finally {
                releaseMemberResident(unitId, resident)
            }
        }
    }

    fun getMembersForUnits(unitIds: Set<String>): List<OrganizationMember> = cacheUseGate.use {
        unitIds.forEach { requireOrganizationKey(it, "unitId") }
        require(unitIds.size <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS) {
            "organization recursive cache scope exceeds the unit capacity"
        }
        if (unitIds.isEmpty()) return@use emptyList()
        synchronized(stateLock) {
            // 刻意绕过 memberResidents：ACL 对话框绝不能仅仅因为请求了一次递归回退就固定
            // 每个单元的成员列表。
            buildList {
                unitIds.sorted().chunked(ORGANIZATION_MEMBER_QUERY_UNIT_CHUNK_SIZE).forEach { chunk ->
                    val remaining = OrganizationCapacityPolicy.MAX_MEMBERSHIP_RELATIONS - size
                    val members = queries.selectOrganizationMembersByUnits(
                        chunk,
                        remaining.toLong() + 1L,
                    ).executeAsList().map { it.toLocalModel() }
                    require(members.size <= remaining) {
                        "organization recursive cache exceeds the relation capacity"
                    }
                    members.groupingBy(OrganizationMember::unitId).eachCount().forEach { (_, count) ->
                        require(count <= OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT) {
                            "organization member cache exceeds the per-unit capacity"
                        }
                    }
                    addAll(members)
                }
            }
        }
    }

    fun upsertMember(member: OrganizationMember) {
        cacheUseGate.use {
            validateMember(member)
            synchronized(stateLock) {
                memberSnapshots.invalidate(member.unitId)
                activeMemberSnapshots.remove(member.unitId)
                invalidateUnitSnapshotLocked()
                var directMemberCount = 0
                var canonicalMember = member
                var userMerge: UserProjectionMerge? = null
                queries.transaction {
                    userMerge = member.user?.let(mergeUserLocked)
                    canonicalMember = member.copy(
                        user = userMerge?.canonical ?: normalizedUserLocked(member.uid),
                    )
                    persistMember(canonicalMember)
                    directMemberCount = queries.countOrganizationMembersByUnit(member.unitId)
                        .executeAsOne().toInt()
                    queries.updateOrganizationUnitDirectMemberCount(
                        directMemberCount.toLong(),
                        member.unitId,
                    )
                }
                userMerge?.let(publishUserMergeLocked)
                publishDirectMemberCountLocked(member.unitId, directMemberCount)
                memberResidents[member.unitId]?.flow?.let { flow ->
                    flow.value = flow.value.copy(
                        members = normalizeMembers(
                            member.unitId,
                            flow.value.members.filterNot { it.uid == member.uid } + canonicalMember,
                        ),
                    )
                }
            }
        }
    }

    fun removeMember(unitId: String, uid: String) {
        cacheUseGate.use {
            requireOrganizationKey(unitId, "unitId")
            requireOrganizationKey(uid, "uid")
            synchronized(stateLock) {
                memberSnapshots.invalidate(unitId)
                activeMemberSnapshots.remove(unitId)
                invalidateUnitSnapshotLocked()
                var directMemberCount = 0
                queries.transaction {
                    queries.removeOrganizationMember(unitId, uid)
                    directMemberCount = queries.countOrganizationMembersByUnit(unitId)
                        .executeAsOne().toInt()
                    queries.updateOrganizationUnitDirectMemberCount(directMemberCount.toLong(), unitId)
                }
                publishDirectMemberCountLocked(unitId, directMemberCount)
                memberResidents[unitId]?.flow?.let { flow ->
                    flow.value = flow.value.copy(
                        members = flow.value.members.filterNot { it.uid == uid },
                    )
                }
            }
        }
    }

    fun beginMemberSnapshot(unitId: String): ProjectionSnapshotLease = cacheUseGate.use {
        requireOrganizationKey(unitId, "unitId")
        synchronized(stateLock) {
            memberSnapshots.begin(unitId).also { lease ->
                activeMemberSnapshots[unitId] = lease
            }
        }
    }

    fun applyMemberSnapshot(
        lease: ProjectionSnapshotLease,
        members: List<OrganizationMember>,
        revision: Long,
    ): Boolean = cacheUseGate.runIfOpen {
        requireOrganizationRevision(revision)
        val snapshot = normalizeMembers(lease.key, members)
        synchronized(stateLock) {
            if (!memberSnapshots.consumeIfCurrent(lease, lease.key)) return@synchronized false
            activeMemberSnapshots.remove(lease.key)
            val state = loadProjectionStateFromSql()
            if (revision < state.requiredRevision) return@synchronized false
            val markerAlreadyKnown =
                queries.isOrganizationMemberSnapshotCached(lease.key).executeAsOne() > 0L
            require(
                markerAlreadyKnown ||
                    queries.countOrganizationMemberSnapshots().executeAsOne() <
                    OrganizationCapacityPolicy.MAX_ACTIVE_UNITS.toLong(),
            ) {
                "organization member snapshot markers exceed the active-unit capacity"
            }
            // 两条组织通道携带同一个全局修订号。不要仅仅因为这个成员响应先完成就隔断并发的
            // 单元响应：单元应用路径会把自己的修订号与 requiredRevision 比较，并准入真正更新的树。
            val userMerges = mutableListOf<UserProjectionMerge>()
            var canonicalSnapshot = snapshot
            queries.transaction {
                canonicalSnapshot = snapshot.map { member ->
                    val merge = member.user?.let(mergeUserLocked)
                    merge?.let(userMerges::add)
                    member.copy(user = merge?.canonical ?: normalizedUserLocked(member.uid))
                }
                queries.deleteOrganizationMembersByUnit(lease.key)
                canonicalSnapshot.forEach(::persistMember)
                queries.markOrganizationMemberSnapshotCached(lease.key, revision)
                queries.updateOrganizationUnitDirectMemberCount(canonicalSnapshot.size.toLong(), lease.key)
                persistProjectionState(state.copy(requiredRevision = revision))
            }
            userMerges.forEach(publishUserMergeLocked)
            publishDirectMemberCountLocked(lease.key, canonicalSnapshot.size)
            publishProjectionStateLocked(state.copy(requiredRevision = revision))
            true
        }
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = cacheUseGate.runIfOpen {
        synchronized(stateLock) {
            val unitAbandoned = unitSnapshots.abandon(lease)
            if (unitAbandoned) {
                if (unitMemberFence?.unitLease === lease) unitMemberFence = null
                return@synchronized true
            }
            memberSnapshots.abandon(lease).also { memberAbandoned ->
                if (memberAbandoned && activeMemberSnapshots[lease.key] === lease) {
                    activeMemberSnapshots.remove(lease.key)
                }
            }
        }
    }

    /** 调用方持有 [stateLock]。 */
    fun resetSnapshotGatesLocked() {
        unitSnapshots.reset()
        memberSnapshots.reset()
        activeMemberSnapshots.clear()
        unitMemberFence = null
    }

    /** 调用方持有 [stateLock]；SQL 删除由 [LocalCacheImpl] 拥有。 */
    fun clearProjectionLocked() {
        unitsFlow.value = OrganizationUnitProjection.Unfetched
        memberResidents.values.forEach { it.flow.value = OrganizationMemberProjection.Unfetched }
    }

    /** 调用方持有 [stateLock]；终态完成从该缓存捕获的每个 flow。 */
    fun closeResidentsLocked() {
        unitsFlow.retire(OrganizationUnitProjection.Unfetched)
        memberResidents.values.forEach { resident ->
            resident.flow.retire(OrganizationMemberProjection.Unfetched)
        }
        memberResidents.clear()
    }

    internal fun residentMemberProjectionCountForTest(): Int = cacheUseGate.use {
        synchronized(stateLock) { memberResidents.size }
    }

    private fun acquireMemberResident(unitId: String): OrganizationMemberResident = cacheUseGate.use {
        synchronized(stateLock) {
            memberResidents.getOrPut(unitId) {
                OrganizationMemberResident(RetirableProjectionState(loadMemberProjectionFromSql(unitId)))
            }.also { resident -> resident.observers += 1 }
        }
    }

    private fun releaseMemberResident(unitId: String, resident: OrganizationMemberResident) {
        cacheUseGate.runIfOpen {
            synchronized(stateLock) {
                val current = memberResidents[unitId]
                if (current !== resident) return@synchronized false
                check(current.observers > 0) { "organization member observer count underflow" }
                current.observers -= 1
                if (current.observers == 0) memberResidents.remove(unitId)
                true
            }
        }
    }

    private fun loadUnitsFromSql(): List<OrganizationUnit> =
        queries.selectAllOrganizationUnits().executeAsList().map { it.toLocalModel() }

    private fun loadUnitProjectionFromSql(): OrganizationUnitProjection {
        val state = loadProjectionStateFromSql()
        return OrganizationUnitProjection(
            snapshotKnown = state.unitSnapshotKnown &&
                state.unitSnapshotRevision >= state.requiredRevision,
            revision = state.unitSnapshotRevision,
            units = loadUnitsFromSql(),
        )
    }

    private fun loadMembersFromSql(unitId: String): List<OrganizationMember> =
        queries.selectOrganizationMembersByUnit(unitId).executeAsList().map { it.toLocalModel() }

    private fun loadMemberProjectionFromSql(unitId: String): OrganizationMemberProjection {
        val storedRevision = queries.selectOrganizationMemberSnapshotRevision(unitId)
            .executeAsOneOrNull()
        val revision = storedRevision ?: 0L
        val requiredRevision = loadProjectionStateFromSql().requiredRevision
        return OrganizationMemberProjection(
            snapshotKnown = storedRevision != null && revision >= requiredRevision,
            members = loadMembersFromSql(unitId),
            revision = revision,
        )
    }

    private fun persistUnit(unit: OrganizationUnit) {
        queries.upsertOrganizationUnit(
            unit.unitId,
            unit.parentId,
            unit.name,
            unit.leaderUid,
            unit.sortOrder.toLong(),
            unit.groupChatId,
            unit.status.toLong(),
            unit.directMemberCount.toLong(),
        )
    }

    private fun persistMember(member: OrganizationMember) {
        val user = member.user
        val avatar = user?.avatar
        queries.upsertOrganizationMember(
            member.unitId,
            member.uid,
            member.title,
            if (member.primary) 1L else 0L,
            member.joinedAt,
            user?.username,
            user?.name,
            avatar?.path,
            avatar?.name,
            avatar?.contentType,
            avatar?.size,
            user?.phone,
            user?.sex?.toLong(),
            user?.role?.toLong(),
            user?.status?.toLong(),
            user?.revision,
        )
    }

    /** 调用方持有 [stateLock]，通常还持有外层 SQL 事务。 */
    private fun normalizedUserLocked(uid: String): User? =
        queries.selectUserByUid(uid).executeAsOneOrNull()?.toLocalModel()

    /** 调用方持有 [stateLock]；SQL 计数已经提交。 */
    private fun publishDirectMemberCountLocked(unitId: String, directMemberCount: Int) {
        val projection = unitsFlow.value
        val index = projection.units.indexOfFirst { it.unitId == unitId }
        if (index < 0 || projection.units[index].directMemberCount == directMemberCount) return
        unitsFlow.value = projection.copy(
            units = projection.units.toMutableList().apply {
                this[index] = this[index].copy(directMemberCount = directMemberCount)
            },
        )
    }

    /** 调用方持有 [stateLock]。 */
    private fun publishProjectionStateLocked(state: OrganizationProjectionState) {
        unitsFlow.value = unitsFlow.value.copy(
            snapshotKnown = state.unitSnapshotKnown &&
                state.unitSnapshotRevision >= state.requiredRevision,
            revision = state.unitSnapshotRevision,
        )
        memberResidents.forEach { (unitId, resident) ->
            resident.flow.value = loadMemberProjectionFromSql(unitId)
        }
    }

    private fun loadProjectionStateFromSql(): OrganizationProjectionState =
        OrganizationProjectionState(
            requiredRevision = queries.selectOrganizationRequiredRevision()
                .executeAsOneOrNull() ?: 0L,
            unitSnapshotRevision = queries.selectOrganizationUnitSnapshotRevision()
                .executeAsOneOrNull() ?: 0L,
            unitSnapshotKnown = queries.selectOrganizationUnitSnapshotKnown()
                .executeAsOneOrNull() == 1L,
        )

    private fun persistProjectionState(state: OrganizationProjectionState) {
        queries.setOrganizationProjectionState(
            required_revision = state.requiredRevision,
            unit_snapshot_revision = state.unitSnapshotRevision,
            unit_snapshot_known = if (state.unitSnapshotKnown) 1L else 0L,
        )
    }

    /** 调用方持有 [stateLock]。 */
    private fun invalidateUnitSnapshotLocked() {
        unitSnapshots.invalidate(ALL_UNITS_KEY)
        unitMemberFence = null
    }

    private fun normalizeUnits(
        units: List<OrganizationUnit>,
        requireCompleteTree: Boolean,
    ): List<OrganizationUnit> {
        require(units.size <= OrganizationCapacityPolicy.MAX_ACTIVE_UNITS) {
            "organization unit snapshot exceeds the active-unit capacity"
        }
        units.forEach(::validateUnit)
        require(units.map(OrganizationUnit::unitId).toSet().size == units.size) {
            "organization unit snapshot contains duplicate unitId"
        }
        if (requireCompleteTree) validateCompleteTree(units)
        return units.sortedWith(
            compareBy<OrganizationUnit> { it.sortOrder }
                .thenBy { it.name }
                .thenBy { it.unitId },
        )
    }

    private fun normalizeMembers(
        unitId: String,
        members: List<OrganizationMember>,
    ): List<OrganizationMember> {
        requireOrganizationKey(unitId, "unitId")
        require(members.size <= OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT) {
            "organization member snapshot exceeds the per-unit capacity"
        }
        members.forEach { member ->
            validateMember(member)
            require(member.unitId == unitId) { "organization member snapshot identity mismatch" }
        }
        require(members.map(OrganizationMember::uid).toSet().size == members.size) {
            "organization member snapshot contains duplicate uid"
        }
        return members.sortedWith(compareBy<OrganizationMember> { it.joinedAt }.thenBy { it.uid })
    }

    private fun validateUnit(unit: OrganizationUnit) {
        requireOrganizationKey(unit.unitId, "unitId")
        require(unit.parentId != unit.unitId) { "organization unit cannot parent itself" }
        require(unit.name.isNotBlank()) { "organization unit name must not be blank" }
        require(unit.sortOrder >= 0) { "organization unit sortOrder must not be negative" }
        require(unit.directMemberCount >= 0) { "organization directMemberCount must not be negative" }
    }

    private fun validateMember(member: OrganizationMember) {
        requireOrganizationKey(member.unitId, "unitId")
        requireOrganizationKey(member.uid, "uid")
        require(member.joinedAt >= 0L) { "organization member joinedAt must not be negative" }
        member.user?.let { user ->
            require(user.uid == member.uid) { "organization member user identity mismatch" }
        }
    }

    private fun validateCompleteTree(units: List<OrganizationUnit>) {
        if (units.isEmpty()) return
        val byId = units.associateBy(OrganizationUnit::unitId)
        units.forEach { unit ->
            require(unit.parentId == null || unit.parentId in byId) {
                "organization unit ${unit.unitId} references unknown parent ${unit.parentId}"
            }
        }
        require(units.count { it.parentId == null } == 1) {
            "organization unit snapshot must contain exactly one root"
        }

        // 每个节点只加入 [resolved] 一次。因此长共享祖先链保持 O(U) 而不是 O(U²)，
        // 而请求本地路径仍会在发布任何行之前检测到环。
        val resolved = hashSetOf<String>()
        units.forEach { unit ->
            if (unit.unitId in resolved) return@forEach
            val path = linkedSetOf<String>()
            var cursor: OrganizationUnit? = unit
            while (cursor != null && cursor.unitId !in resolved) {
                require(path.add(cursor.unitId)) { "organization unit snapshot contains a cycle" }
                cursor = cursor.parentId?.let(byId::get)
            }
            resolved += path
        }
    }

    private fun requireOrganizationKey(value: String, label: String) {
        require(value.isNotBlank()) { "$label must not be blank" }
    }

    private fun requireOrganizationRevision(revision: Long) {
        require(revision >= 0L) { "organization revision must not be negative" }
    }

    private companion object {
        const val ALL_UNITS_KEY = "all-units"
        // Android API 26 的 SQLite 保证至少 999 个绑定变量。为查询 limit 与未来的固定谓词
        // 留出余量，而不是依赖某个设备特定的构建。
        const val ORGANIZATION_MEMBER_QUERY_UNIT_CHUNK_SIZE = 500
    }

    private class OrganizationMemberResident(
        val flow: RetirableProjectionState<OrganizationMemberProjection>,
        var observers: Int = 0,
    )

    private data class UnitMemberFence(
        val unitLease: ProjectionSnapshotLease,
        val memberLeases: Map<String, ProjectionSnapshotLease>,
    )

    private data class OrganizationProjectionState(
        val requiredRevision: Long,
        val unitSnapshotRevision: Long,
        val unitSnapshotKnown: Boolean,
    )
}
