package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.KeyedProjectionSnapshotGate
import com.virjar.tk.shared.client.OrganizationMemberProjection
import com.virjar.tk.shared.client.OrganizationUnitProjection
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * [FakeLocalCache] 的组织目录状态机。
 *
 * 它镜像生产实现的分拆方式：单位是一个急切加载的完整投影，直属成员是按键控的快照，
 * 只有被主动收集的成员流才会常驻。递归读取直接使用持久的测试替身 map，
 * 因此不会改变内存常驻性断言。
 */
internal class FakeOrganizationProjectionStore(
    private val cacheUseGate: FakeCacheUseGate,
) {
    private val lock = Any()
    private val unitsFlow = MutableStateFlow(OrganizationUnitProjection.Unfetched)
    private var requiredRevision = 0L
    private val unitSnapshots = KeyedProjectionSnapshotGate("fake organization unit snapshot")
    private var unitSnapshotLease: ProjectionSnapshotLease? = null
    private var unitMemberFence: UnitMemberFence? = null
    private val memberProjections = mutableMapOf<String, OrganizationMemberProjection>()
    private val memberSnapshotMarkers = mutableSetOf<String>()
    private val memberFlows = mutableMapOf<String, MutableStateFlow<OrganizationMemberProjection>>()
    private val memberObserverCounts = mutableMapOf<String, Int>()
    private val memberSnapshots = KeyedProjectionSnapshotGate("fake organization member snapshot")
    private val memberSnapshotLeases = mutableMapOf<String, ProjectionSnapshotLease>()

    fun getUnitProjection(): OrganizationUnitProjection = synchronized(lock) { unitsFlow.value }

    fun observeUnitProjection(): Flow<OrganizationUnitProjection> = unitsFlow

    fun advanceRequiredRevision(revision: Long): Long = synchronized(lock) {
        require(revision >= 0L) { "organization revision must not be negative" }
        if (revision > requiredRevision) {
            requiredRevision = revision
            invalidateAuthorityLocked()
        }
        requiredRevision
    }

    fun upsertUnit(unit: OrganizationUnit) {
        require(unit.unitId.isNotBlank()) { "unitId must not be blank" }
        synchronized(lock) {
            invalidateUnitSnapshotLocked()
            unitsFlow.value = unitsFlow.value.copy(
                units = sortUnits(
                    unitsFlow.value.units.filterNot { it.unitId == unit.unitId } + unit,
                ),
            )
        }
    }

    fun deleteUnit(unitId: String) {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        synchronized(lock) {
            invalidateUnitSnapshotLocked()
            memberSnapshots.invalidate(unitId)
            memberSnapshotLeases.remove(unitId)
            unitsFlow.value = unitsFlow.value.copy(
                units = unitsFlow.value.units.filterNot { it.unitId == unitId },
            )
            memberProjections.remove(unitId)
            memberSnapshotMarkers.remove(unitId)
            memberFlows[unitId]?.value = OrganizationMemberProjection.Unfetched
        }
    }

    fun beginUnitSnapshot(): ProjectionSnapshotLease = synchronized(lock) {
        unitSnapshots.begin(ALL_UNITS_KEY).also { lease ->
            unitSnapshotLease = lease
            unitMemberFence = UnitMemberFence(lease, memberSnapshotLeases.toMap())
        }
    }

    fun applyUnitSnapshot(
        lease: ProjectionSnapshotLease,
        units: List<OrganizationUnit>,
        revision: Long,
    ): Boolean {
        require(revision >= 0L) { "organization revision must not be negative" }
        val snapshot = normalizeUnits(units)
        return synchronized(lock) {
            if (unitSnapshotLease !== lease) return@synchronized false
            if (!unitSnapshots.consumeIfCurrent(lease, ALL_UNITS_KEY)) return@synchronized false
            unitSnapshotLease = null
            if (revision < requiredRevision) {
                if (unitMemberFence?.unitLease === lease) unitMemberFence = null
                return@synchronized false
            }
            val membersPredatingSnapshot = unitMemberFence
                ?.takeIf { it.unitLease === lease }
                ?.memberLeases
                .orEmpty()
            unitMemberFence = null
            val snapshotUnitIds = snapshot.mapTo(mutableSetOf(), OrganizationUnit::unitId)
            val removedUnitIds = buildSet {
                unitsFlow.value.units.mapTo(this, OrganizationUnit::unitId)
                addAll(memberProjections.keys)
                addAll(memberFlows.keys)
                addAll(memberSnapshotLeases.keys)
            } - snapshotUnitIds
            val memberSnapshotUnitsToFence = buildSet {
                addAll(removedUnitIds)
                membersPredatingSnapshot.forEach { (unitId, capturedLease) ->
                    if (memberSnapshotLeases[unitId] === capturedLease) add(unitId)
                }
            }
            memberSnapshotUnitsToFence.forEach { unitId ->
                memberSnapshots.invalidate(unitId)
                memberSnapshotLeases.remove(unitId)
            }
            removedUnitIds.forEach { removedUnitId ->
                memberProjections.remove(removedUnitId)
                memberSnapshotMarkers.remove(removedUnitId)
                memberFlows[removedUnitId]?.value = OrganizationMemberProjection.Unfetched
            }
            requiredRevision = revision
            invalidateAuthorityLocked()
            unitsFlow.value = OrganizationUnitProjection(
                snapshotKnown = true,
                revision = revision,
                units = snapshot,
            )
            true
        }
    }

    fun getMemberProjection(unitId: String): OrganizationMemberProjection {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        return synchronized(lock) {
            memberFlows[unitId]?.value
                ?: memberProjections[unitId]
                ?: OrganizationMemberProjection.Unfetched
        }
    }

    fun observeMemberProjection(unitId: String): Flow<OrganizationMemberProjection> {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        return flow {
            val resident = acquireMemberResident(unitId)
            try {
                emitAll(resident)
            } finally {
                releaseMemberResident(unitId, resident)
            }
        }
    }

    fun getMembersForUnits(unitIds: Set<String>): List<OrganizationMember> {
        require(unitIds.none { it.isBlank() }) { "unitId must not be blank" }
        return synchronized(lock) {
            unitIds.sorted().flatMap { memberProjections[it]?.members.orEmpty() }
        }
    }

    fun upsertMember(member: OrganizationMember) {
        requireMember(member)
        synchronized(lock) {
            memberSnapshots.invalidate(member.unitId)
            memberSnapshotLeases.remove(member.unitId)
            invalidateUnitSnapshotLocked()
            val current = memberProjections[member.unitId] ?: OrganizationMemberProjection.Unfetched
            val updated = normalizeMembers(
                member.unitId,
                current.members.filterNot { it.uid == member.uid } + member,
            )
            val projection = current.copy(members = updated)
            memberProjections[member.unitId] = projection
            publishDirectMemberCountLocked(member.unitId, updated.size)
            memberFlows[member.unitId]?.value = projection
        }
    }

    fun removeMember(unitId: String, uid: String) {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        require(uid.isNotBlank()) { "uid must not be blank" }
        synchronized(lock) {
            memberSnapshots.invalidate(unitId)
            memberSnapshotLeases.remove(unitId)
            invalidateUnitSnapshotLocked()
            val current = memberProjections[unitId] ?: OrganizationMemberProjection.Unfetched
            val updated = current.members.filterNot { it.uid == uid }
            val projection = current.copy(members = updated)
            memberProjections[unitId] = projection
            publishDirectMemberCountLocked(unitId, updated.size)
            memberFlows[unitId]?.value = projection
        }
    }

    fun beginMemberSnapshot(unitId: String): ProjectionSnapshotLease {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        return synchronized(lock) {
            memberSnapshots.begin(unitId).also { memberSnapshotLeases[unitId] = it }
        }
    }

    fun applyMemberSnapshot(
        lease: ProjectionSnapshotLease,
        snapshotMembers: List<OrganizationMember>,
        revision: Long,
    ): Boolean = synchronized(lock) {
        require(revision >= 0L) { "organization revision must not be negative" }
        val unitId = memberSnapshotLeases.entries
            .firstOrNull { (_, currentLease) -> currentLease === lease }
            ?.key ?: return@synchronized false
        val snapshot = normalizeMembers(unitId, snapshotMembers)
        if (!memberSnapshots.consumeIfCurrent(lease, unitId)) return@synchronized false
        memberSnapshotLeases.remove(unitId)
        if (revision < requiredRevision) return@synchronized false
        val markerAlreadyKnown = unitId in memberSnapshotMarkers
        require(
            markerAlreadyKnown ||
                memberSnapshotMarkers.size < OrganizationCapacityPolicy.MAX_ACTIVE_UNITS,
        ) {
            "organization member snapshot markers exceed the active-unit capacity"
        }
        requiredRevision = revision
        invalidateAuthorityLocked()
        val projection = OrganizationMemberProjection(
            snapshotKnown = true,
            members = snapshot,
            revision = revision,
        )
        memberSnapshotMarkers += unitId
        memberProjections[unitId] = projection
        publishDirectMemberCountLocked(unitId, snapshot.size)
        memberFlows[unitId]?.value = projection
        true
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = synchronized(lock) {
        val unitAbandoned = unitSnapshots.abandon(lease)
        if (unitAbandoned && unitSnapshotLease === lease) {
            unitSnapshotLease = null
            if (unitMemberFence?.unitLease === lease) unitMemberFence = null
        }
        val memberAbandoned = !unitAbandoned && memberSnapshots.abandon(lease)
        if (memberAbandoned) removeCurrentFakeLease(memberSnapshotLeases, lease)
        unitAbandoned || memberAbandoned
    }

    fun activeSnapshotCountForTest(): Int = synchronized(lock) {
        (if (unitSnapshotLease == null) 0 else 1) + memberSnapshotLeases.size
    }

    fun residentMemberProjectionCountForTest(): Int = synchronized(lock) { memberFlows.size }

    fun resetServerProjection() = synchronized(lock) {
        unitSnapshots.reset()
        memberSnapshots.reset()
        unitSnapshotLease = null
        unitMemberFence = null
        memberSnapshotLeases.clear()
        requiredRevision = 0L
        unitsFlow.value = OrganizationUnitProjection.Unfetched
        memberSnapshotMarkers.clear()
        memberProjections.clear()
        memberFlows.values.forEach { it.value = OrganizationMemberProjection.Unfetched }
    }

    fun close() = synchronized(lock) {
        unitSnapshots.reset()
        memberSnapshots.reset()
        unitSnapshotLease = null
        unitMemberFence = null
        memberSnapshotLeases.clear()
    }

    private fun acquireMemberResident(unitId: String): MutableStateFlow<OrganizationMemberProjection> =
        cacheUseGate.use {
            synchronized(lock) {
                memberObserverCounts[unitId] = (memberObserverCounts[unitId] ?: 0) + 1
                memberFlows.getOrPut(unitId) {
                    MutableStateFlow(
                        memberProjections[unitId] ?: OrganizationMemberProjection.Unfetched,
                    )
                }
            }
        }

    private fun releaseMemberResident(
        unitId: String,
        resident: MutableStateFlow<OrganizationMemberProjection>,
    ) {
        cacheUseGate.runIfOpen {
            synchronized(lock) {
                if (memberFlows[unitId] !== resident) return@synchronized false
                val observers = checkNotNull(memberObserverCounts[unitId])
                check(observers > 0) { "organization member observer count underflow" }
                if (observers == 1) {
                    memberObserverCounts.remove(unitId)
                    memberFlows.remove(unitId)
                } else {
                    memberObserverCounts[unitId] = observers - 1
                }
                true
            }
        }
    }

    /** 调用方持有 [lock]。 */
    private fun invalidateUnitSnapshotLocked() {
        unitSnapshots.invalidate(ALL_UNITS_KEY)
        unitSnapshotLease = null
        unitMemberFence = null
    }

    /** 调用方持有 [lock]。 */
    private fun publishDirectMemberCountLocked(unitId: String, count: Int) {
        val projection = unitsFlow.value
        val index = projection.units.indexOfFirst { it.unitId == unitId }
        if (index < 0 || projection.units[index].directMemberCount == count) return
        unitsFlow.value = projection.copy(
            units = projection.units.toMutableList().apply {
                this[index] = this[index].copy(directMemberCount = count)
            },
        )
    }

    /** 调用方持有 [lock]。 */
    private fun invalidateAuthorityLocked() {
        unitsFlow.value = unitsFlow.value.copy(
            snapshotKnown = unitsFlow.value.snapshotKnown &&
                unitsFlow.value.revision >= requiredRevision,
        )
        memberProjections.keys.toList().forEach { unitId ->
            val current = checkNotNull(memberProjections[unitId])
            val invalidated = current.copy(
                snapshotKnown = current.snapshotKnown && current.revision >= requiredRevision,
            )
            memberProjections[unitId] = invalidated
            memberFlows[unitId]?.value = invalidated
        }
    }

    private fun normalizeUnits(units: List<OrganizationUnit>): List<OrganizationUnit> {
        units.forEach { unit ->
            require(unit.unitId.isNotBlank()) { "unitId must not be blank" }
            require(unit.name.isNotBlank()) { "organization unit name must not be blank" }
            require(unit.parentId != unit.unitId) { "organization unit cannot parent itself" }
        }
        require(units.map(OrganizationUnit::unitId).toSet().size == units.size) {
            "organization unit snapshot contains duplicate unitId"
        }
        val byId = units.associateBy(OrganizationUnit::unitId)
        units.forEach { unit ->
            require(unit.parentId == null || unit.parentId in byId) {
                "organization unit references an unknown parent"
            }
            val visited = mutableSetOf<String>()
            var cursor: OrganizationUnit? = unit
            while (cursor != null) {
                require(visited.add(cursor.unitId)) { "organization unit snapshot contains a cycle" }
                cursor = cursor.parentId?.let(byId::get)
            }
        }
        return sortUnits(units)
    }

    private fun sortUnits(units: List<OrganizationUnit>): List<OrganizationUnit> =
        units.sortedWith(
            compareBy<OrganizationUnit> { it.sortOrder }
                .thenBy { it.name }
                .thenBy { it.unitId },
        )

    private fun normalizeMembers(
        unitId: String,
        members: List<OrganizationMember>,
    ): List<OrganizationMember> {
        require(unitId.isNotBlank()) { "unitId must not be blank" }
        members.forEach { member ->
            requireMember(member)
            require(member.unitId == unitId) { "organization member snapshot identity mismatch" }
        }
        require(members.map(OrganizationMember::uid).toSet().size == members.size) {
            "organization member snapshot contains duplicate uid"
        }
        return members.sortedWith(compareBy<OrganizationMember> { it.joinedAt }.thenBy { it.uid })
    }

    private fun requireMember(member: OrganizationMember) {
        require(member.unitId.isNotBlank()) { "unitId must not be blank" }
        require(member.uid.isNotBlank()) { "uid must not be blank" }
        member.user?.let { user ->
            require(user.uid == member.uid) {
                "organization member user identity mismatch"
            }
        }
    }

    private companion object {
        const val ALL_UNITS_KEY = "all-units"
    }

    private data class UnitMemberFence(
        val unitLease: ProjectionSnapshotLease,
        val memberLeases: Map<String, ProjectionSnapshotLease>,
    )
}
