package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandExpiredException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.infra.db.DocumentSpacePolicyCommands
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentPolicyMutationReliabilityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `full actor window still replays exact receipt and rejects a fresh no-op`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-policy-capacity-owner"))
        val principal = ctx.registerUser(uniqueUsername("document-policy-capacity-principal"))
        val space = ctx.documentService.createSpace(owner, "权限可靠容量空间", null)
        val now = System.currentTimeMillis()
        val service = DocumentService(ctx.documentRepo, ctx.pgUnitOfWork) { now }
        val operationId = UUID.randomUUID().toString()
        val granted = service.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            principal,
            DocumentSpace.ROLE_VIEWER,
            false,
            space.policyRevision,
            operationId,
            now,
        )

        transaction(ctx.database) {
            repeat(DocumentCapacityPolicy.MAX_POLICY_MUTATION_RECEIPTS_PER_ACTOR - 1) {
                DocumentSpacePolicyCommands.insert {
                    it[actorUid] = owner
                    it[DocumentSpacePolicyCommands.operationId] = UUID.randomUUID().toString()
                    it[spaceId] = space.spaceId
                    it[mutationType] = 1
                    it[fingerprint] = "0".repeat(64)
                    it[fromPolicyRevision] = granted.policyRevision
                    it[resultingPolicyRevision] = granted.policyRevision
                    it[issuedAt] = now
                    it[expiresAt] = ReliableCommandPolicy.expiresAt(now)
                    it[createdAt] = now
                }
            }
        }

        assertEquals(
            granted,
            service.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                principal,
                DocumentSpace.ROLE_VIEWER,
                false,
                space.policyRevision,
                operationId,
                now,
            ),
            "an exact ACK-loss replay must bypass fresh-command capacity admission",
        )
        assertFailsWith<ReliableCommandCapacityException> {
            service.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                principal,
                DocumentSpace.ROLE_VIEWER,
                false,
                granted.policyRevision,
                UUID.randomUUID().toString(),
                now,
            )
        }
        assertEquals(
            DocumentCapacityPolicy.MAX_POLICY_MUTATION_RECEIPTS_PER_ACTOR.toLong(),
            policyReceiptCount(owner),
        )
        assertEquals(granted.policyRevision, persistedPolicyRevision(space.spaceId))
    }

    @Test
    fun `collected old unknown retry is terminal and cannot restore a later removed grant`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-policy-expiry-owner"))
        val principal = ctx.registerUser(uniqueUsername("document-policy-expiry-principal"))
        val space = ctx.documentService.createSpace(owner, "权限可靠过期空间", null)
        var now = System.currentTimeMillis()
        val service = DocumentService(ctx.documentRepo, ctx.pgUnitOfWork) { now }
        val oldOperationId = UUID.randomUUID().toString()
        val oldIssuedAt = now
        val granted = service.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            principal,
            DocumentSpace.ROLE_VIEWER,
            false,
            space.policyRevision,
            oldOperationId,
            oldIssuedAt,
        )

        now = ReliableCommandPolicy.expiresAt(oldIssuedAt) + 1L
        assertFailsWith<ReliableCommandExpiredException> {
            service.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                principal,
                DocumentSpace.ROLE_VIEWER,
                false,
                space.policyRevision,
                oldOperationId,
                oldIssuedAt,
            )
        }

        val noOp = service.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            principal,
            DocumentSpace.ROLE_VIEWER,
            false,
            granted.policyRevision,
            UUID.randomUUID().toString(),
            now,
        )
        assertEquals(granted.policyRevision, noOp.policyRevision)
        assertTrue(!policyReceiptExists(owner, oldOperationId), "a fresh command may collect expired identities")

        val removed = service.removeGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            principal,
            noOp.policyRevision,
            UUID.randomUUID().toString(),
            now,
        )
        assertFailsWith<ReliableCommandExpiredException> {
            service.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                principal,
                DocumentSpace.ROLE_VIEWER,
                false,
                space.policyRevision,
                oldOperationId,
                oldIssuedAt,
            )
        }
        assertTrue(ctx.documentService.listGrants(owner, space.spaceId).isEmpty())
        assertEquals(removed.policyRevision, persistedPolicyRevision(space.spaceId))
    }

    private fun policyReceiptCount(actorUid: String): Long = transaction(ctx.database) {
        DocumentSpacePolicyCommands.selectAll().where {
            DocumentSpacePolicyCommands.actorUid eq actorUid
        }.count()
    }

    private fun policyReceiptExists(actorUid: String, operationId: String): Boolean = transaction(ctx.database) {
        DocumentSpacePolicyCommands.selectAll().where {
            (DocumentSpacePolicyCommands.actorUid eq actorUid) and
                (DocumentSpacePolicyCommands.operationId eq operationId)
        }.count() == 1L
    }

    private fun persistedPolicyRevision(spaceId: String): Long = transaction(ctx.database) {
        DocumentSpaces.selectAll().where { DocumentSpaces.spaceId eq spaceId }
            .single()[DocumentSpaces.policyRevision]
    }
}
