package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentAuthorizationPolicyTest {
    @Test
    fun `document role capability matrix remains explicit`() {
        val expected = mapOf(
            DocumentRole.VIEWER to setOf(DocumentCapability.READ),
            DocumentRole.EDITOR to setOf(
                DocumentCapability.READ,
                DocumentCapability.EDIT_CONTENT,
            ),
            DocumentRole.ADMIN to setOf(
                DocumentCapability.READ,
                DocumentCapability.EDIT_CONTENT,
                DocumentCapability.MANAGE_SPACE,
                DocumentCapability.MANAGE_POLICY,
            ),
            DocumentRole.OWNER to DocumentCapability.entries.toSet(),
        )

        DocumentRole.entries.forEach { role ->
            assertEquals(
                expected.getValue(role),
                DocumentCapability.entries.filterTo(mutableSetOf(), role::allows),
            )
        }
    }

    @Test
    fun `only the explicit steward receives implicit owner authority`() {
        val space = space(
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            ownerPrincipalId = "unit-a",
            stewardUid = "steward",
        )

        val steward = resolve("steward", space, DocumentCapability.TRANSFER_CUSTODY)
        val ordinaryMember = DocumentAuthorizationPolicy.resolve(
            actorUid = "member",
            space = space,
            grants = emptyList(),
            directUnitIds = setOf("unit-a"),
            unitAndAncestorIds = setOf("unit-a"),
            required = DocumentCapability.READ,
        )

        assertTrue(steward.allowed)
        assertEquals(DocumentSpace.ROLE_OWNER, steward.effectiveRole)
        assertFalse(ordinaryMember.allowed)
        assertEquals(DocumentSpace.ROLE_NONE, ordinaryMember.effectiveRole)
    }

    @Test
    fun `document roles expose explicit capabilities instead of numeric threshold checks`() {
        val space = space()
        val grants = listOf(
            DocumentSpaceGrant(
                spaceId = space.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = "editor",
                role = DocumentSpace.ROLE_EDITOR,
            ),
        )

        val read = DocumentAuthorizationPolicy.resolve(
            "editor",
            space,
            grants,
            emptySet(),
            emptySet(),
            DocumentCapability.READ,
        )
        val policy = DocumentAuthorizationPolicy.resolve(
            "editor",
            space,
            grants,
            emptySet(),
            emptySet(),
            DocumentCapability.MANAGE_POLICY,
        )

        assertTrue(read.allowed)
        assertEquals(DocumentSpace.ROLE_EDITOR, read.effectiveRole)
        assertFalse(policy.allowed)
    }

    @Test
    fun `organization grants distinguish direct and inherited membership and fail closed`() {
        val space = space()
        val inheritedGrant = DocumentSpaceGrant(
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            principalId = "parent",
            role = DocumentSpace.ROLE_VIEWER,
            includeDescendants = true,
        )
        val directOnlyGrant = inheritedGrant.copy(includeDescendants = false)
        val invalidRole = inheritedGrant.copy(role = 999)
        val forgedOwnerGrant = inheritedGrant.copy(role = DocumentSpace.ROLE_OWNER)

        fun decide(grant: DocumentSpaceGrant) = DocumentAuthorizationPolicy.resolve(
            actorUid = "member",
            space = space,
            grants = listOf(grant),
            directUnitIds = setOf("child"),
            unitAndAncestorIds = setOf("child", "parent"),
            required = DocumentCapability.READ,
        ).allowed

        assertTrue(decide(inheritedGrant))
        assertFalse(decide(directOnlyGrant))
        assertFalse(decide(invalidRole))
        assertFalse(decide(forgedOwnerGrant))
    }

    @Test
    fun `grants for another space do not affect authorization or effective role`() {
        val space = space()
        val authorization = DocumentAuthorizationPolicy.resolve(
            actorUid = "member",
            space = space,
            grants = listOf(
                DocumentSpaceGrant(
                    spaceId = "another-space",
                    principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                    principalId = "member",
                    role = DocumentSpace.ROLE_ADMIN,
                ),
            ),
            directUnitIds = emptySet(),
            unitAndAncestorIds = emptySet(),
            required = DocumentCapability.READ,
        )

        assertFalse(authorization.allowed)
        assertEquals(DocumentSpace.ROLE_NONE, authorization.effectiveRole)
    }

    private fun resolve(
        actorUid: String,
        space: DocumentSpace,
        capability: DocumentCapability,
    ) = DocumentAuthorizationPolicy.resolve(
        actorUid,
        space,
        emptyList(),
        emptySet(),
        emptySet(),
        capability,
    )

    private fun space(
        ownerPrincipalType: Int = DocumentSpaceGrant.PRINCIPAL_USER,
        ownerPrincipalId: String = "creator",
        stewardUid: String = "creator",
    ) = DocumentSpace(
        spaceId = "space-a",
        name = "空间",
        myRole = DocumentSpace.ROLE_NONE,
        createdBy = "creator",
        createdAt = 1,
        updatedAt = 1,
        ownerPrincipalType = ownerPrincipalType,
        ownerPrincipalId = ownerPrincipalId,
        stewardUid = stewardUid,
        custodyRevision = 1,
    )
}
