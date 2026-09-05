package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.infra.db.DocumentSpacePolicyCommands
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentPolicyMutationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `policy receipts preserve no-op replay and reject conflicting or stale commands`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-policy-owner"))
        val principal = ctx.registerUser(uniqueUsername("document-policy-principal"))
        val stalePrincipal = ctx.registerUser(uniqueUsername("document-policy-stale-principal"))
        val space = ctx.documentService.createSpace(owner, "权限回执空间", null)

        val initial = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(space.policyRevision + 1L, initial.policyRevision)

        val noOpOperationId = UUID.randomUUID().toString()
        val noOpIssuedAt = System.currentTimeMillis()
        val noOp = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = initial.policyRevision,
            operationId = noOpOperationId,
            issuedAt = noOpIssuedAt,
        )
        assertEquals(initial.policyRevision, noOp.policyRevision, "a persisted no-op receipt must not advance ACL CAS")
        assertEquals(initial.policyRevision, persistedPolicyRevision(space.spaceId))

        val later = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_EDITOR,
            includeDescendants = false,
            expectedPolicyRevision = noOp.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(noOp.policyRevision + 1L, later.policyRevision)

        val replayAfterLaterMutation = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = principal,
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = initial.policyRevision,
            operationId = noOpOperationId,
            issuedAt = noOpIssuedAt,
        )
        assertEquals(later.policyRevision, replayAfterLaterMutation.policyRevision)
        assertEquals(DocumentSpace.ROLE_OWNER, replayAfterLaterMutation.effectiveRole)
        assertEquals(
            DocumentSpace.ROLE_EDITOR,
            ctx.documentService.listGrants(owner, space.spaceId).single().role,
            "exact replay must not reapply its older no-op request over later policy state",
        )

        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentService.upsertGrant(
                actorUid = owner,
                spaceId = space.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = principal,
                role = DocumentSpace.ROLE_ADMIN,
                includeDescendants = false,
                expectedPolicyRevision = initial.policyRevision,
                operationId = noOpOperationId,
                issuedAt = noOpIssuedAt,
            )
        }
        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentService.upsertGrant(
                actorUid = owner,
                spaceId = space.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = stalePrincipal,
                role = DocumentSpace.ROLE_VIEWER,
                includeDescendants = false,
                expectedPolicyRevision = noOp.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertTrue(ctx.documentService.listGrants(owner, space.spaceId).none { it.principalId == stalePrincipal })
        assertEquals(later.policyRevision, persistedPolicyRevision(space.spaceId))
        assertEquals(
            3L,
            transaction(ctx.database) {
                DocumentSpacePolicyCommands.selectAll().where {
                    DocumentSpacePolicyCommands.spaceId eq space.spaceId
                }.count()
            },
            "initial change, durable no-op and later change are the only committed receipts",
        )
    }

    private fun persistedPolicyRevision(spaceId: String): Long = transaction(ctx.database) {
        DocumentSpaces.selectAll().where { DocumentSpaces.spaceId eq spaceId }
            .single()[DocumentSpaces.policyRevision]
    }
}
