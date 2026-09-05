package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentPolicyMutationReplayIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `old upsert replay cannot restore a removed grant before or after archive`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("policy-replay-owner"))
        val principal = ctx.registerUser(uniqueUsername("policy-replay-principal"))
        val space = ctx.documentService.createSpace(owner, "旧授权重放空间", null)
        val upsertOperationId = UUID.randomUUID().toString()
        val upsertIssuedAt = System.currentTimeMillis()

        val granted = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = upsertOperationId,
            issuedAt = upsertIssuedAt,
        )
        val removed = ctx.documentService.removeGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            expectedPolicyRevision = granted.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )

        val replayAfterRemove = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = upsertOperationId,
            issuedAt = upsertIssuedAt,
        )
        assertEquals(removed.policyRevision, replayAfterRemove.policyRevision)
        assertEquals(DocumentSpace.ROLE_OWNER, replayAfterRemove.effectiveRole)
        assertTrue(ctx.documentService.listGrants(owner, space.spaceId).isEmpty())

        ctx.documentService.archiveSpace(owner, space.spaceId, UUID.randomUUID().toString())
        val replayAfterArchive = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = upsertOperationId,
            issuedAt = upsertIssuedAt,
        )
        assertEquals(removed.policyRevision, replayAfterArchive.policyRevision)
        assertEquals(DocumentSpace.ROLE_NONE, replayAfterArchive.effectiveRole)
    }

    @Test
    fun `direct and organization self revoke return NONE and old remove cannot erase regrant`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("policy-self-owner"))
        val directMember = ctx.registerUser(uniqueUsername("policy-self-direct"))
        val organizationMember = ctx.registerUser(uniqueUsername("policy-self-org"))

        val directSpace = ctx.documentService.createSpace(owner, "个人自撤权空间", null)
        val directGrant = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = directSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = directMember,
            role = DocumentSpace.ROLE_ADMIN,
            includeDescendants = false,
            expectedPolicyRevision = directSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val directRemoveOperationId = UUID.randomUUID().toString()
        val directRemoveIssuedAt = System.currentTimeMillis()
        val directRemoved = ctx.documentService.removeGrant(
            actorUid = directMember,
            spaceId = directSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = directMember,
            expectedPolicyRevision = directGrant.policyRevision,
            operationId = directRemoveOperationId,
            issuedAt = directRemoveIssuedAt,
        )
        assertEquals(DocumentSpace.ROLE_NONE, directRemoved.effectiveRole)
        assertEquals(
            directRemoved,
            ctx.documentService.removeGrant(
                actorUid = directMember,
                spaceId = directSpace.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = directMember,
                expectedPolicyRevision = directGrant.policyRevision,
                operationId = directRemoveOperationId,
                issuedAt = directRemoveIssuedAt,
            ),
        )

        val unit = OrganizationUnit(UUID.randomUUID().toString(), name = "权限自撤部门")
        ctx.seedOrganizationUnit(unit)
        ctx.seedOrganizationMember(OrganizationMember(unit.unitId, organizationMember))
        val organizationSpace = ctx.documentService.createSpace(owner, "部门自撤权空间", null)
        val organizationGrant = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = organizationSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            principalId = unit.unitId,
            role = DocumentSpace.ROLE_ADMIN,
            includeDescendants = false,
            expectedPolicyRevision = organizationSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val organizationRemoveOperationId = UUID.randomUUID().toString()
        val organizationRemoveIssuedAt = System.currentTimeMillis()
        val organizationRemoved = ctx.documentService.removeGrant(
            actorUid = organizationMember,
            spaceId = organizationSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            principalId = unit.unitId,
            expectedPolicyRevision = organizationGrant.policyRevision,
            operationId = organizationRemoveOperationId,
            issuedAt = organizationRemoveIssuedAt,
        )
        assertEquals(DocumentSpace.ROLE_NONE, organizationRemoved.effectiveRole)

        val regranted = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = organizationSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            principalId = unit.unitId,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = organizationRemoved.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val oldRemoveReplay = ctx.documentService.removeGrant(
            actorUid = organizationMember,
            spaceId = organizationSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            principalId = unit.unitId,
            expectedPolicyRevision = organizationGrant.policyRevision,
            operationId = organizationRemoveOperationId,
            issuedAt = organizationRemoveIssuedAt,
        )
        assertEquals(regranted.policyRevision, oldRemoveReplay.policyRevision)
        assertEquals(DocumentSpace.ROLE_VIEWER, oldRemoveReplay.effectiveRole)
        assertEquals(DocumentSpace.ROLE_VIEWER, ctx.documentService.listGrants(owner, organizationSpace.spaceId).single().role)
    }

    @Test
    fun `exact replay after actor ban returns NONE without restoring its old target grant`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("policy-ban-owner"))
        val admin = ctx.registerUser(uniqueUsername("policy-ban-admin"))
        val target = ctx.registerUser(uniqueUsername("policy-ban-target"))
        val space = ctx.documentService.createSpace(owner, "封禁回放空间", null)
        val adminGrant = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = admin,
            role = DocumentSpace.ROLE_ADMIN,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val adminOperationId = UUID.randomUUID().toString()
        val adminIssuedAt = System.currentTimeMillis()
        val targetGrant = ctx.documentService.upsertGrant(
            actorUid = admin,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = target,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = adminGrant.policyRevision,
            operationId = adminOperationId,
            issuedAt = adminIssuedAt,
        )
        val removed = ctx.documentService.removeGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = target,
            expectedPolicyRevision = targetGrant.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.credentialAdministration.banUser(admin)

        val replay = ctx.documentService.upsertGrant(
            actorUid = admin,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = target,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = adminGrant.policyRevision,
            operationId = adminOperationId,
            issuedAt = adminIssuedAt,
        )
        assertEquals(removed.policyRevision, replay.policyRevision)
        assertEquals(DocumentSpace.ROLE_NONE, replay.effectiveRole)
        assertTrue(ctx.documentService.listGrants(owner, space.spaceId).none { it.principalId == target })
    }
}
