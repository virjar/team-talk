package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.DocumentSpacePageRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentActiveUserAuthorizationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `revoked steward is denied by space exact and home read boundaries and cannot write`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-revoked-owner"))
        val space = ctx.documentService.createSpace(owner, "封禁读取空间", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "封禁读取文档", "正文")
        ctx.documentService.getDocument(owner, space.spaceId, document.documentId)
        ctx.credentialAdministration.banUser(owner)

        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.listSpaces(owner, DocumentSpacePageRequest())
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.listNodes(owner, space.spaceId, null)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.getDocument(owner, space.spaceId, document.documentId)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.listGrants(owner, space.spaceId)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.listRecentDocuments(owner, 10)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.listRecentlyCreatedDocuments(owner, 10)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.updateSpace(owner, space.spaceId, "不应写入", null)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.archiveSpace(owner, space.spaceId, UUID.randomUUID().toString())
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.createDocumentCommand(
                owner,
                UUID.randomUUID().toString(),
                space.spaceId,
                null,
                "封禁后不应创建",
                "不应写入",
            )
        }
    }

    @Test
    fun `revoked grantee cannot read or write and revoked user cannot become a grant target`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-active-grant-owner"))
        val grantee = ctx.registerUser(uniqueUsername("document-revoked-grantee"))
        val rejectedTarget = ctx.registerUser(uniqueUsername("document-revoked-grant-target"))
        val space = ctx.documentService.createSpace(owner, "用户授权活动围栏", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "授权文档", "正文")

        val granted = ctx.documentService.upsertGrant(
            actorUid = owner,
            spaceId = space.spaceId,
            principalType = DocumentSpaceGrant.PRINCIPAL_USER,
            principalId = grantee,
            role = DocumentSpace.ROLE_EDITOR,
            includeDescendants = false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.getDocument(grantee, space.spaceId, document.documentId)
        ctx.credentialAdministration.banUser(grantee)

        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.getDocument(grantee, space.spaceId, document.documentId)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.updateDocument(
                grantee,
                space.spaceId,
                document.documentId,
                "不应写入",
                document.revision,
            )
        }

        ctx.credentialAdministration.banUser(rejectedTarget)
        val rejected = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.upsertGrant(
                actorUid = owner,
                spaceId = space.spaceId,
                principalType = DocumentSpaceGrant.PRINCIPAL_USER,
                principalId = rejectedTarget,
                role = DocumentSpace.ROLE_VIEWER,
                includeDescendants = false,
                expectedPolicyRevision = granted.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertEquals("只能向活动普通用户授予文档空间", rejected.message)

        val grants = ctx.documentService.listGrants(owner, space.spaceId)
        assertTrue(grants.any { it.principalId == grantee })
        assertTrue(grants.none { it.principalId == rejectedTarget })
    }
}
