package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.UserRole
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DocumentCustodyAdministrationPolicyTest {
    private val sourceUid = "00000000-0000-4000-8000-000000000001"
    private val targetUid = "00000000-0000-4000-8000-000000000002"
    private val target = DocumentCustodyTarget(
        ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
        ownerPrincipalId = targetUid,
        stewardUid = targetUid,
    )
    private val space = DocumentCustodyPlanEntry(
        spaceId = "00000000-0000-4000-8000-000000000003",
        name = "交接空间",
        ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
        ownerPrincipalId = sourceUid,
        stewardUid = sourceUid,
        custodyRevision = 7,
        policyRevision = 11,
    )

    @Test
    fun `banned source remains recoverable but destination must be an active human`() {
        val recoverable = snapshot(sourceStatus = 2, targetStatus = 1)
        DocumentCustodyAdministrationPolicy.requireValid(recoverable, target)

        assertFailsWith<IllegalArgumentException> {
            DocumentCustodyAdministrationPolicy.requireValid(
                snapshot(sourceStatus = 2, targetStatus = 2),
                target,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentCustodyAdministrationPolicy.requireValid(
                snapshot(sourceStatus = 2, targetStatus = 1, targetRole = UserRole.BOT),
                target,
            )
        }
    }

    @Test
    fun `plan fingerprint binds destination custody facts and every direct grant`() {
        val directGrant = DocumentCustodyGrantPlanEntry(space.spaceId, space.policyRevision)
        val baseline = fingerprint(target, space, listOf(directGrant))
        assertNotEquals(
            baseline,
            fingerprint(target.copy(stewardUid = "00000000-0000-4000-8000-000000000004"), space, listOf(directGrant)),
        )
        assertNotEquals(baseline, fingerprint(target, space.copy(custodyRevision = 8), listOf(directGrant)))
        assertNotEquals(baseline, fingerprint(target, space.copy(policyRevision = 12), listOf(directGrant)))
        assertNotEquals(
            baseline,
            fingerprint(target, space, listOf(directGrant.copy(policyRevision = 12))),
        )
        assertNotEquals(
            baseline,
            fingerprint(target, space.copy(ownerPrincipalId = targetUid), listOf(directGrant)),
        )
        assertNotEquals(
            baseline,
            fingerprint(
                target,
                space,
                listOf(
                    directGrant,
                    DocumentCustodyGrantPlanEntry("00000000-0000-4000-8000-000000000005", 1),
                ),
            ),
        )
    }

    private fun snapshot(
        sourceStatus: Int,
        targetStatus: Int,
        targetRole: Int = UserRole.HUMAN,
    ) = DocumentCustodySnapshot(
        source = DocumentCustodyUserFact(sourceUid, UserRole.HUMAN, sourceStatus),
        targetSteward = DocumentCustodyUserFact(targetUid, targetRole, targetStatus),
        targetOwnerUnitStatus = null,
        spaces = listOf(space),
        directGrants = listOf(DocumentCustodyGrantPlanEntry(space.spaceId, space.policyRevision)),
    )

    private fun fingerprint(
        destination: DocumentCustodyTarget,
        asset: DocumentCustodyPlanEntry,
        grants: List<DocumentCustodyGrantPlanEntry>,
    ) = DocumentCustodyAdministrationPolicy.fingerprint(
        sourceUid,
        destination,
        listOf(asset),
        grants,
    )
}
