package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentCustodyPlanConflictException
import com.virjar.tk.server.infra.db.DocumentCustodyBatchTransferItems
import com.virjar.tk.server.infra.db.DocumentCustodyBatchTransfers
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentCustodyAdministrationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `verified admin atomically recovers banned steward assets and replays immutable receipt`() = runTest {
        val source = ctx.registerUser(uniqueUsername("custody-admin-source"))
        val target = ctx.registerUser(uniqueUsername("custody-admin-target"))
        val otherTarget = ctx.registerUser(uniqueUsername("custody-admin-other-target"))
        val grantOwner = ctx.registerUser(uniqueUsername("custody-admin-grant-owner"))
        val sharedCustodyOwner = ctx.registerUser(uniqueUsername("custody-admin-shared-owner"))
        val noGrantOwner = ctx.registerUser(uniqueUsername("custody-admin-no-grant-owner"))
        val custodyOnlySpaces = listOf(
            ctx.documentService.createSpace(source, "离职交接一", null),
            ctx.documentService.createSpace(source, "离职交接二", null),
        )
        val grantOnlySpace = ctx.documentService.createSpace(grantOwner, "仅显式授权清理", null)
        ctx.documentService.upsertGrant(
            grantOwner,
            grantOnlySpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = grantOnlySpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val sharedCustodySpace = ctx.documentService.createSpace(
            sharedCustodyOwner,
            "责任人与显式授权重叠",
            null,
        )
        ctx.documentService.upsertGrant(
            sharedCustodyOwner,
            sharedCustodySpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = sharedCustodySpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.transferSpaceCustody(
            actorUid = sharedCustodyOwner,
            spaceId = sharedCustodySpace.spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            ownerPrincipalId = source,
            stewardUid = source,
            expectedCustodyRevision = sharedCustodySpace.custodyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val noGrantSpace = ctx.documentService.createSpace(noGrantOwner, "无授权对照空间", null)
        val sourceSpaces = custodyOnlySpaces + sharedCustodySpace
        val policyRevisionBefore = policyRevisions(
            sourceSpaces + grantOnlySpace + noGrantSpace,
        )

        ctx.adminService.banUser(source)
        assertEquals(
            sourceSpaces.map { it.spaceId }.toSet(),
            transaction(ctx.database) {
                DocumentSpaces.selectAll().where {
                    (DocumentSpaces.stewardUid eq source) and (DocumentSpaces.status eq 1)
                }.map { it[DocumentSpaces.spaceId] }.toSet()
            },
            "ban must remain a credential fence and must not perform implicit custody transfer",
        )

        val plan = ctx.documentCustodyAdministration.plan(
            sourceUid = source,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        assertEquals(sourceSpaces.map { it.spaceId }.sorted(), plan.spaces.map { it.spaceId })
        assertEquals(
            listOf(grantOnlySpace.spaceId, sharedCustodySpace.spaceId).sorted(),
            plan.directGrants.map { it.spaceId },
        )
        assertTrue(plan.spaces.all { it.policyRevision > 0L })
        assertTrue(plan.directGrants.all { it.policyRevision > 0L })

        val operationId = UUID.randomUUID().toString()
        val receipt = ctx.documentCustodyAdministration.transfer(
            adminPrincipal = "verified-admin",
            sourceUid = source,
            operationId = operationId,
            expectedPlanFingerprint = plan.planFingerprint,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        assertEquals("verified-admin", receipt.adminPrincipal)
        assertEquals(2, receipt.revokedGrantCount)
        assertEquals(sourceSpaces.map { it.spaceId }.sorted(), receipt.items.map { it.spaceId })
        val receiptItems = receipt.items.associateBy { it.spaceId }
        custodyOnlySpaces.forEach { custodyOnly ->
            val item = receiptItems.getValue(custodyOnly.spaceId)
            assertEquals(1L, item.fromCustodyRevision)
            assertEquals(2L, item.resultingCustodyRevision)
        }
        assertEquals(2L, receiptItems.getValue(sharedCustodySpace.spaceId).fromCustodyRevision)
        assertEquals(3L, receiptItems.getValue(sharedCustodySpace.spaceId).resultingCustodyRevision)
        assertTrue(receipt.items.all { it.toOwnerPrincipalId == target && it.toStewardUid == target })

        transaction(ctx.database) {
            val transferred = DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId inList sourceSpaces.map { it.spaceId }
            }.toList()
            assertTrue(transferred.all { it[DocumentSpaces.ownerPrincipalId] == target })
            assertTrue(transferred.all { it[DocumentSpaces.stewardUid] == target })
            assertTrue(transferred.all { row ->
                row[DocumentSpaces.custodyRevision] ==
                    receiptItems.getValue(row[DocumentSpaces.spaceId]).resultingCustodyRevision
            })
            assertEquals(0L, DocumentSpaceGrants.selectAll().where {
                (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                    (DocumentSpaceGrants.principalId eq source)
            }.count())
            assertEquals(1L, DocumentCustodyBatchTransfers.selectAll().where {
                DocumentCustodyBatchTransfers.operationId eq operationId
            }.count())
            assertEquals(sourceSpaces.size.toLong(), DocumentCustodyBatchTransferItems.selectAll().where {
                DocumentCustodyBatchTransferItems.operationId eq operationId
            }.count())
        }
        val policyRevisionAfterTransfer = policyRevisions(sourceSpaces + grantOnlySpace + noGrantSpace)
        assertEquals(
            policyRevisionBefore.getValue(grantOnlySpace.spaceId) + 1L,
            policyRevisionAfterTransfer.getValue(grantOnlySpace.spaceId),
            "grant-only space must advance its ACL CAS exactly once",
        )
        assertEquals(
            policyRevisionBefore.getValue(sharedCustodySpace.spaceId) + 1L,
            policyRevisionAfterTransfer.getValue(sharedCustodySpace.spaceId),
            "custody and direct-grant overlap must advance each independent revision once",
        )
        custodyOnlySpaces.forEach { custodyOnly ->
            assertEquals(
                policyRevisionBefore.getValue(custodyOnly.spaceId),
                policyRevisionAfterTransfer.getValue(custodyOnly.spaceId),
                "custody without a removed grant must not advance policyRevision",
            )
        }
        assertEquals(
            policyRevisionBefore.getValue(noGrantSpace.spaceId),
            policyRevisionAfterTransfer.getValue(noGrantSpace.spaceId),
            "an unrelated no-grant space must remain unchanged",
        )

        val replay = ctx.documentCustodyAdministration.transfer(
            adminPrincipal = "verified-admin",
            sourceUid = source,
            operationId = operationId,
            expectedPlanFingerprint = plan.planFingerprint,
            targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            targetOwnerPrincipalId = target,
            targetStewardUid = target,
        )
        assertEquals(receipt, replay)
        assertEquals(
            policyRevisionAfterTransfer,
            policyRevisions(sourceSpaces + grantOnlySpace + noGrantSpace),
            "exact batch replay must not advance policyRevision again",
        )
        assertEquals(
            receipt.items.associate { it.spaceId to it.resultingCustodyRevision },
            transaction(ctx.database) {
                DocumentSpaces.selectAll().where {
                    DocumentSpaces.spaceId inList sourceSpaces.map { it.spaceId }
                }.associate { row ->
                    row[DocumentSpaces.spaceId] to row[DocumentSpaces.custodyRevision]
                }
            },
            "exact replay must not consume another stewardship slot or revision",
        )

        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentCustodyAdministration.transfer(
                adminPrincipal = "verified-admin",
                sourceUid = source,
                operationId = operationId,
                expectedPlanFingerprint = plan.planFingerprint,
                targetOwnerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                targetOwnerPrincipalId = otherTarget,
                targetStewardUid = otherTarget,
            )
        }
    }

    @Test
    fun `direct grant changes invalidate CAS and empty custody plan commits explicit zero item receipt`() = runTest {
        val source = ctx.registerUser(uniqueUsername("custody-empty-source"))
        val target = ctx.registerUser(uniqueUsername("custody-empty-target"))
        val firstOwner = ctx.registerUser(uniqueUsername("custody-empty-owner-a"))
        val secondOwner = ctx.registerUser(uniqueUsername("custody-empty-owner-b"))
        val first = ctx.documentService.createSpace(firstOwner, "待撤销授权一", null)
        val second = ctx.documentService.createSpace(secondOwner, "待撤销授权二", null)
        ctx.documentService.upsertGrant(
            firstOwner,
            first.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            DocumentSpace.ROLE_VIEWER,
            false,
            expectedPolicyRevision = first.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )

        val stalePlan = ctx.documentCustodyAdministration.plan(
            source,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )
        assertTrue(stalePlan.spaces.isEmpty())
        assertEquals(listOf(first.spaceId), stalePlan.directGrants.map { it.spaceId })

        val changedRole = ctx.documentService.upsertGrant(
            firstOwner,
            first.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = stalePlan.directGrants.single().policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val operationId = UUID.randomUUID().toString()
        assertFailsWith<DocumentCustodyPlanConflictException> {
            ctx.documentCustodyAdministration.transfer(
                "verified-admin",
                source,
                operationId,
                stalePlan.planFingerprint,
                DocumentSpaceGrant.PRINCIPAL_USER,
                target,
                target,
            )
        }
        assertEquals(1L, directGrantCount(source))
        assertEquals(changedRole.policyRevision, policyRevisions(listOf(first)).getValue(first.spaceId))

        val roleChangedPlan = ctx.documentCustodyAdministration.plan(
            source,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )

        ctx.documentService.upsertGrant(
            secondOwner,
            second.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = second.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertFailsWith<DocumentCustodyPlanConflictException> {
            ctx.documentCustodyAdministration.transfer(
                "verified-admin",
                source,
                operationId,
                roleChangedPlan.planFingerprint,
                DocumentSpaceGrant.PRINCIPAL_USER,
                target,
                target,
            )
        }
        assertEquals(2L, directGrantCount(source))
        assertEquals(0L, transaction(ctx.database) {
            DocumentCustodyBatchTransfers.selectAll().where {
                DocumentCustodyBatchTransfers.operationId eq operationId
            }.count()
        })

        val currentPlan = ctx.documentCustodyAdministration.plan(
            source,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )
        assertEquals(
            listOf(first.spaceId, second.spaceId).sorted(),
            currentPlan.directGrants.map { it.spaceId },
        )
        val removedSecond = ctx.documentService.removeGrant(
            secondOwner,
            second.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            expectedPolicyRevision = currentPlan.directGrants.single { it.spaceId == second.spaceId }.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertFailsWith<DocumentCustodyPlanConflictException> {
            ctx.documentCustodyAdministration.transfer(
                "verified-admin",
                source,
                operationId,
                currentPlan.planFingerprint,
                DocumentSpaceGrant.PRINCIPAL_USER,
                target,
                target,
            )
        }
        ctx.documentService.upsertGrant(
            secondOwner,
            second.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            source,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = removedSecond.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val finalPlan = ctx.documentCustodyAdministration.plan(
            source,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )
        val receipt = ctx.documentCustodyAdministration.transfer(
            "verified-admin",
            source,
            operationId,
            finalPlan.planFingerprint,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )
        assertTrue(receipt.items.isEmpty())
        assertEquals(2, receipt.revokedGrantCount)
        assertEquals(0L, directGrantCount(source))
        assertEquals(
            receipt,
            ctx.documentCustodyAdministration.transfer(
                "verified-admin",
                source,
                operationId,
                finalPlan.planFingerprint,
                DocumentSpaceGrant.PRINCIPAL_USER,
                target,
                target,
            ),
        )

        val zeroPlan = ctx.documentCustodyAdministration.plan(
            source,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )
        assertTrue(zeroPlan.spaces.isEmpty())
        assertTrue(zeroPlan.directGrants.isEmpty())
        val zeroReceipt = ctx.documentCustodyAdministration.transfer(
            "verified-admin",
            source,
            UUID.randomUUID().toString(),
            zeroPlan.planFingerprint,
            DocumentSpaceGrant.PRINCIPAL_USER,
            target,
            target,
        )
        assertTrue(zeroReceipt.items.isEmpty())
        assertEquals(0, zeroReceipt.revokedGrantCount)
    }

    private fun directGrantCount(uid: String): Long = transaction(ctx.database) {
        DocumentSpaceGrants.selectAll().where {
            (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                (DocumentSpaceGrants.principalId eq uid)
        }.count()
    }

    private fun policyRevisions(spaces: List<com.virjar.tk.protocol.model.DocumentSpace>): Map<String, Long> =
        transaction(ctx.database) {
            DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId inList spaces.map { it.spaceId }
            }.associate { row ->
                row[DocumentSpaces.spaceId] to row[DocumentSpaces.policyRevision]
            }
        }
}
