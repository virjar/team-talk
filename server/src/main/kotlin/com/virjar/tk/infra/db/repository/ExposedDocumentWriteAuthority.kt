package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.document.DocumentWriteAuthority
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.DocumentSpaceGrants
import com.virjar.tk.infra.db.DocumentSpaces
import com.virjar.tk.infra.db.OrganizationMemberships
import com.virjar.tk.infra.db.OrganizationUnits
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.model.User
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/** PostgreSQL lock protocol for one document-space write authorization snapshot. */
internal object ExposedDocumentWriteAuthority {
    fun lock(
        transaction: PgTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredOrganizationUnitIds: Set<String>,
        requiredUserIds: Set<String>,
    ): DocumentWriteAuthority {
        transaction.requireExposedTransaction()

        // Organization primary assignment acquires Users(uid) before mutating that uid's
        // memberships. Document commands that will later address a user row must use the same
        // order; otherwise an organization-granted admin updating their own direct grant can form
        // Membership -> User / User -> Membership deadlock.
        if (requiredUserIds.isNotEmpty()) {
            val lockedUserIds = Users.selectAll().where {
                Users.uid inList requiredUserIds.sorted()
            }.orderBy(Users.uid to SortOrder.ASC).forUpdate()
                .mapTo(hashSetOf()) { it[Users.uid] }
            require(lockedUserIds.containsAll(requiredUserIds)) { "用户不存在" }
        }

        val space = DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }.forUpdate().singleOrNull()?.toDocumentSpace()
            ?: throw IllegalArgumentException("文档空间不存在")
        if (space.createdBy == actorUid && requiredOrganizationUnitIds.isEmpty()) {
            return DocumentWriteAuthority(
                space = space,
                grants = emptyList(),
                directUnitIds = emptySet(),
                unitAndAncestorIds = emptySet(),
            )
        }

        // Grant mutations acquire the same space row first, so these rows form one stable ACL
        // snapshot. Lock them as a defence against any legacy writer that still addresses the
        // table directly instead of entering through this repository port.
        val grants = DocumentSpaceGrants.selectAll().where {
            DocumentSpaceGrants.spaceId eq spaceId
        }.orderBy(
            DocumentSpaceGrants.principalType to SortOrder.ASC,
            DocumentSpaceGrants.principalId to SortOrder.ASC,
        ).forUpdate().map(ResultRow::toDocumentSpaceGrant)

        // Existing membership removal must wait for an admitted document command, or complete
        // before this SELECT and disappear from its authorization snapshot. Concurrent membership
        // insertion only grants future authority and therefore need not block this command.
        val directMembershipUnitIds = OrganizationMemberships.selectAll().where {
            OrganizationMemberships.uid eq actorUid
        }.orderBy(
            OrganizationMemberships.unitId to SortOrder.ASC,
            OrganizationMemberships.id to SortOrder.ASC,
        ).forUpdate().map { it[OrganizationMemberships.unitId] }.distinct()

        // Discover only the actor's current paths without locks, then acquire every referenced unit
        // row in one lexical order. Locking child->parent while following a historical cycle can
        // deadlock two writers whose direct memberships start at opposite sides of that cycle.
        // After the ordered lock, compare the locked facts with discovery and fail closed if an
        // organization mutation raced between the two stages.
        val observedUnits = hashMapOf<String, OrganizationAuthorityRow?>()
        fun observeUnit(unitId: String): OrganizationAuthorityRow? {
            if (observedUnits.containsKey(unitId)) return observedUnits[unitId]
            val row = OrganizationUnits.selectAll().where {
                OrganizationUnits.unitId eq unitId
            }.singleOrNull()?.toOrganizationAuthorityRow()
            observedUnits[unitId] = row
            return row
        }

        val authorityUnitIds = linkedSetOf<String>()
        requiredOrganizationUnitIds.sorted().forEach { unitId ->
            authorityUnitIds += unitId
            observeUnit(unitId)
        }
        directMembershipUnitIds.sorted().forEach { directId ->
            var cursor: String? = directId
            val visited = hashSetOf<String>()
            while (cursor != null && visited.add(cursor)) {
                authorityUnitIds += cursor
                val unit = observeUnit(cursor) ?: break
                if (unit.status != OrganizationUnit.STATUS_ACTIVE) break
                cursor = unit.parentId
            }
        }

        val lockedUnits = if (authorityUnitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationUnits.selectAll().where {
                OrganizationUnits.unitId inList authorityUnitIds.sorted()
            }.orderBy(OrganizationUnits.unitId to SortOrder.ASC).forUpdate()
                .associate { row -> row[OrganizationUnits.unitId] to row.toOrganizationAuthorityRow() }
        }
        require(lockedUnits.keys.containsAll(requiredOrganizationUnitIds)) { "组织节点不存在" }
        authorityUnitIds.forEach { unitId ->
            require(lockedUnits[unitId] == observedUnits[unitId]) {
                "组织权限正在变更，请重试"
            }
        }

        val directUnitIds = linkedSetOf<String>()
        val unitAndAncestorIds = linkedSetOf<String>()
        directMembershipUnitIds.sorted().forEach { directId ->
            val direct = lockedUnits[directId]
            if (direct == null || direct.status != OrganizationUnit.STATUS_ACTIVE) return@forEach
            directUnitIds += directId

            val inheritedPath = arrayListOf<String>()
            val visited = hashSetOf<String>()
            var cursor: String? = directId
            var cycleDetected = false
            while (cursor != null) {
                if (!visited.add(cursor)) {
                    cycleDetected = true
                    break
                }
                val unit = lockedUnits[cursor] ?: break
                if (unit.status != OrganizationUnit.STATUS_ACTIVE) break
                inheritedPath += cursor
                cursor = unit.parentId
            }
            // A direct active membership remains authoritative even if historical organization
            // data contains a cycle. Inherited authority requires one complete acyclic path.
            if (!cycleDetected) unitAndAncestorIds += inheritedPath
        }
        unitAndAncestorIds += directUnitIds

        return DocumentWriteAuthority(
            space = space,
            grants = grants,
            directUnitIds = directUnitIds,
            unitAndAncestorIds = unitAndAncestorIds,
        )
    }

    private const val STATUS_ACTIVE = 1
}

private data class OrganizationAuthorityRow(
    val parentId: String?,
    val status: Int,
)

private fun ResultRow.toOrganizationAuthorityRow() = OrganizationAuthorityRow(
    parentId = this[OrganizationUnits.parentId],
    status = this[OrganizationUnits.status],
)

internal fun ResultRow.toDocumentAclUser() = User(
    uid = this[Users.uid],
    username = this[Users.username],
    name = this[Users.name],
    avatar = this[Users.avatar],
    phone = this[Users.phone],
    sex = this[Users.sex],
    role = this[Users.role],
    status = this[Users.status],
)
