package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.DocumentDirectoryState
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DocumentDirectoryRevisionIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `space mutations advance once while exact receipts and node content stay invisible`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-directory-space-owner"))
        val spaceId = UUID.randomUUID().toString()
        val beforeCreate = directoryRevision()

        val created = ctx.documentService.createSpaceCommand(owner, spaceId, "目录版本空间", null)
        assertEquals(beforeCreate + 1L, directoryRevision())

        val createReplay = ctx.documentService.createSpaceCommand(owner, spaceId, "目录版本空间", null)
        assertEquals(created, createReplay)
        assertEquals(beforeCreate + 1L, directoryRevision(), "exact create replay must not advance")

        val document = ctx.documentService.createDocument(
            owner,
            spaceId,
            null,
            "仅正文聚合",
            "# 初始正文",
        )
        ctx.documentService.updateDocument(
            owner,
            spaceId,
            document.documentId,
            "# 更新正文",
            document.revision,
        )
        assertEquals(
            beforeCreate + 1L,
            directoryRevision(),
            "node and content changes cannot invalidate the space-directory scan",
        )

        ctx.documentService.updateSpace(owner, spaceId, "目录版本空间（二）", "描述")
        assertEquals(beforeCreate + 2L, directoryRevision())

        val archiveOperation = UUID.randomUUID().toString()
        ctx.documentService.archiveSpace(owner, spaceId, archiveOperation)
        assertEquals(beforeCreate + 3L, directoryRevision())
        ctx.documentService.archiveSpace(owner, spaceId, archiveOperation)
        assertEquals(beforeCreate + 3L, directoryRevision(), "exact archive replay must not advance")
    }

    @Test
    fun `policy and custody advance only for the first actual projection change`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-directory-policy-owner"))
        val grantee = ctx.registerUser(uniqueUsername("document-directory-policy-grantee"))
        val nextSteward = ctx.registerUser(uniqueUsername("document-directory-next-steward"))
        val space = ctx.documentService.createSpace(owner, "权限及归属版本空间", null)
        val baseline = directoryRevision()

        val grantOperation = UUID.randomUUID().toString()
        val grantIssuedAt = System.currentTimeMillis()
        val granted = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = grantee,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = grantOperation,
            issuedAt = grantIssuedAt,
        )
        assertEquals(baseline + 1L, directoryRevision())

        val replay = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = grantee,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = grantOperation,
            issuedAt = grantIssuedAt,
        )
        assertEquals(granted, replay)
        assertEquals(baseline + 1L, directoryRevision(), "exact policy replay must not advance")

        val noOp = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = grantee,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = granted.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(granted.policyRevision, noOp.policyRevision)
        assertEquals(baseline + 1L, directoryRevision(), "persisted policy no-op must not advance")

        val custodyOperation = UUID.randomUUID().toString()
        val transferred = ctx.documentService.transferSpaceCustody(
            actorUid = owner,
            spaceId = space.spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            ownerPrincipalId = nextSteward,
            stewardUid = nextSteward,
            expectedCustodyRevision = space.custodyRevision,
            operationId = custodyOperation,
        )
        assertEquals(baseline + 2L, directoryRevision())

        val custodyReplay = ctx.documentService.transferSpaceCustody(
            actorUid = owner,
            spaceId = space.spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            ownerPrincipalId = nextSteward,
            stewardUid = nextSteward,
            expectedCustodyRevision = space.custodyRevision,
            operationId = custodyOperation,
        )
        assertEquals(transferred, custodyReplay)
        assertEquals(baseline + 2L, directoryRevision(), "exact custody replay must not advance")
    }

    @Test
    fun `offboarding batch advances once for all changed spaces and zero work receipt does not`() = runTest {
        val source = ctx.registerUser(uniqueUsername("document-directory-offboard-source"))
        val target = ctx.registerUser(uniqueUsername("document-directory-offboard-target"))
        val grantOwner = ctx.registerUser(uniqueUsername("document-directory-offboard-grant-owner"))
        ctx.documentService.createSpace(source, "待批量交接一", null)
        ctx.documentService.createSpace(source, "待批量交接二", null)
        val grantedSpace = ctx.documentService.createSpace(grantOwner, "待批量撤销授权", null)
        ctx.documentService.upsertGrant(
            actorUid = grantOwner,
            spaceId = grantedSpace.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = source,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = grantedSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.adminService.banUser(source)
        val plan = ctx.documentCustodyAdministration.plan(
            sourceUid = source,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        val operationId = UUID.randomUUID().toString()
        val baseline = directoryRevision()

        val receipt = ctx.documentCustodyAdministration.transfer(
            adminPrincipal = "verified-admin",
            sourceUid = source,
            operationId = operationId,
            expectedPlanFingerprint = plan.planFingerprint,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        assertEquals(baseline + 1L, directoryRevision(), "one batch must advance globally only once")

        assertEquals(
            receipt,
            ctx.documentCustodyAdministration.transfer(
                adminPrincipal = "verified-admin",
                sourceUid = source,
                operationId = operationId,
                expectedPlanFingerprint = plan.planFingerprint,
                targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                targetOwnerPrincipalId = target,
                targetStewardUid = target,
            ),
        )
        assertEquals(baseline + 1L, directoryRevision(), "exact batch replay must not advance")

        val emptySource = ctx.registerUser(uniqueUsername("document-directory-empty-source"))
        ctx.adminService.banUser(emptySource)
        val emptyPlan = ctx.documentCustodyAdministration.plan(
            sourceUid = emptySource,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        ctx.documentCustodyAdministration.transfer(
            adminPrincipal = "verified-admin",
            sourceUid = emptySource,
            operationId = UUID.randomUUID().toString(),
            expectedPlanFingerprint = emptyPlan.planFingerprint,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        assertEquals(baseline + 1L, directoryRevision(), "zero-work receipt must not advance")
    }

    private fun directoryRevision(): Long = transaction(ctx.database) {
        DocumentDirectoryState.selectAll().where { DocumentDirectoryState.id eq 1 }
            .single()[DocumentDirectoryState.revision]
    }
}

class DocumentDirectoryRevisionOverflowIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `exhausted directory revision fails closed and rolls back the aggregate mutation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-directory-overflow-owner"))
        val spaceId = UUID.randomUUID().toString()
        transaction(ctx.database) {
            DocumentDirectoryState.update({ DocumentDirectoryState.id eq 1 }) {
                it[revision] = Long.MAX_VALUE
                it[updatedAt] = System.currentTimeMillis()
            }
        }

        assertFailsWith<IllegalStateException> {
            ctx.documentService.createSpace(owner, spaceId, "不能半提交的空间", null)
        }
        assertNull(transaction(ctx.database) {
            DocumentSpaces.selectAll().where { DocumentSpaces.spaceId eq spaceId }.singleOrNull()
        })
    }
}
