package com.virjar.tk.domain.document

import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.transaction.PgWriteScope
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant

/**
 * Document-space ACL policy and command admission boundary.
 *
 * Reads resolve the latest committed organization projection without taking aggregate locks.
 * Writes lock any required user principals first, then the active space before grants,
 * memberships and organization units, and consume the transaction-scoped authority snapshot
 * returned by [DocumentRepository.lockWriteAuthority]. Keeping both paths here guarantees that
 * user and organization grants share one effective-role policy without weakening command fences.
 */
internal class DocumentAccessControl(
    private val repository: DocumentRepository,
    private val organizations: OrganizationRepository,
    private val unitOfWork: PgUnitOfWork,
) {
    fun requireRole(actorUid: String, spaceId: String, minimum: Int): DocumentSpace {
        val space = repository.findSpace(spaceId) ?: throw IllegalArgumentException("文档空间不存在")
        require(effectiveRole(actorUid, space) >= minimum) { "没有文档空间权限" }
        return space
    }

    fun accessibleSpaces(actorUid: String): Map<String, DocumentSpace> =
        resolveAccessibleSpaces(actorUid).associateBy(DocumentSpace::spaceId)

    fun resolveAccessibleSpaces(actorUid: String): List<DocumentSpace> {
        val access = actorAccess(actorUid)
        return repository.listSpaceAccessCandidates(
            actorUid = actorUid,
            directUnitIds = access.directUnitIds,
            unitAndAncestorIds = access.unitAndAncestorIds,
        ).mapNotNull { candidate ->
            effectiveRole(actorUid, candidate.space, candidate.grants, access)
                .takeIf { it >= DocumentSpace.ROLE_VIEWER }
                ?.let { candidate.space.copy(myRole = it) }
        }
    }

    suspend fun <T> writeAuthorized(
        actorUid: String,
        spaceId: String,
        minimum: Int,
        requiredOrganizationUnitIds: Set<String> = emptySet(),
        requiredUserIds: Set<String> = emptySet(),
        block: suspend PgWriteScope.(space: DocumentSpace, effectiveRole: Int) -> T,
    ): T = unitOfWork.write {
        // The adapter acquires the active-space row lock before reading grants or organization
        // membership. Archive and revocation therefore either precede this command (which is
        // rejected) or follow the command's committed result.
        val authority = repository.lockWriteAuthority(
            transaction,
            actorUid,
            spaceId,
            requiredOrganizationUnitIds,
            requiredUserIds,
        )
        val role = effectiveRole(actorUid, authority)
        require(role >= minimum) { "没有文档空间权限" }
        block(authority.space, role)
    }

    private fun effectiveRole(actorUid: String, space: DocumentSpace): Int {
        if (space.createdBy == actorUid) return DocumentSpace.ROLE_OWNER
        return effectiveRole(actorUid, space, repository.listGrants(space.spaceId), actorAccess(actorUid))
    }

    private fun effectiveRole(actorUid: String, authority: DocumentWriteAuthority): Int = effectiveRole(
        actorUid = actorUid,
        space = authority.space,
        grants = authority.grants,
        access = ActorAccess(authority.directUnitIds, authority.unitAndAncestorIds),
    )

    private fun effectiveRole(
        actorUid: String,
        space: DocumentSpace,
        grants: List<DocumentSpaceGrant>,
        access: ActorAccess,
    ): Int {
        if (space.createdBy == actorUid) return DocumentSpace.ROLE_OWNER
        return grants.asSequence().filter { grant ->
            when (grant.principalType) {
                DocumentSpaceGrant.PRINCIPAL_USER -> grant.principalId == actorUid
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> if (grant.includeDescendants) {
                    grant.principalId in access.unitAndAncestorIds
                } else {
                    grant.principalId in access.directUnitIds
                }
                else -> false
            }
        }.maxOfOrNull { it.role } ?: DocumentSpace.ROLE_NONE
    }

    private fun actorAccess(actorUid: String): ActorAccess {
        val activeUnits = organizations.listUnits()
        val activeUnitIds = activeUnits.mapTo(hashSetOf()) { it.unitId }
        val directUnitIds = organizations.listMemberships(actorUid)
            .mapNotNullTo(linkedSetOf()) { membership ->
                membership.unitId.takeIf(activeUnitIds::contains)
            }
        if (directUnitIds.isEmpty()) return ActorAccess(emptySet(), emptySet())
        val parentByUnitId = activeUnits.associate { it.unitId to it.parentId }
        // 直属关系是独立事实；继承祖先必须来自一条完整无环的活动路径。
        val unitAndAncestorIds = linkedSetOf<String>().apply { addAll(directUnitIds) }
        directUnitIds.forEach { directId ->
            val inheritedPath = arrayListOf<String>()
            var cursor: String? = directId
            val visited = hashSetOf<String>()
            var cycleDetected = false
            while (cursor != null && cursor in activeUnitIds) {
                if (!visited.add(cursor)) {
                    cycleDetected = true
                    break
                }
                inheritedPath += cursor
                cursor = parentByUnitId[cursor]
            }
            if (!cycleDetected) unitAndAncestorIds += inheritedPath
        }
        return ActorAccess(directUnitIds, unitAndAncestorIds)
    }

    private data class ActorAccess(
        val directUnitIds: Set<String>,
        val unitAndAncestorIds: Set<String>,
    )
}
