package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.DocumentSpacePageRequest
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentSpacePaginationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `keyset page deduplicates overlapping owner user and department access`() = runTest {
        val firstOwner = ctx.registerUser(uniqueUsername("document-page-owner-a"))
        val secondOwner = ctx.registerUser(uniqueUsername("document-page-owner-b"))
        val actor = ctx.registerUser(uniqueUsername("document-page-actor"))
        val root = OrganizationUnit(uuid(801), name = "文档分页根部门")
        val direct = OrganizationUnit(uuid(802), parentId = root.unitId, name = "文档分页直属部门")
        ctx.seedOrganizationUnit(root)
        ctx.seedOrganizationUnit(direct)
        ctx.seedOrganizationMember(OrganizationMember(direct.unitId, actor))

        val owned = ctx.documentService.createSpace(actor, uuid(1), "本人空间", null)
        val overlapping = ctx.documentService.createSpace(firstOwner, uuid(2), "重复授权空间", null)
        val directGrant = ctx.documentService.createSpace(secondOwner, uuid(3), "直属部门空间", null)
        val userGrant = ctx.documentService.createSpace(firstOwner, uuid(4), "个人授权空间", null)
        val inheritedGrant = ctx.documentService.createSpace(secondOwner, uuid(5), "继承部门空间", null)
        ctx.documentService.createSpace(firstOwner, uuid(6), "不可访问空间", null)

        grantUser(firstOwner, overlapping.spaceId, actor, DocumentSpace.ROLE_ADMIN)
        grantUnit(firstOwner, overlapping.spaceId, direct.unitId, DocumentSpace.ROLE_EDITOR, false)
        grantUnit(firstOwner, overlapping.spaceId, root.unitId, DocumentSpace.ROLE_VIEWER, true)
        grantUnit(secondOwner, directGrant.spaceId, direct.unitId, DocumentSpace.ROLE_EDITOR, false)
        grantUser(firstOwner, userGrant.spaceId, actor, DocumentSpace.ROLE_VIEWER)
        grantUnit(secondOwner, inheritedGrant.spaceId, root.unitId, DocumentSpace.ROLE_VIEWER, true)

        val firstPage = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(limit = 2))
        val secondPage = ctx.documentService.listSpaces(
            actor,
            DocumentSpacePageRequest(checkNotNull(firstPage.nextCursor), 2),
        )
        val thirdPage = ctx.documentService.listSpaces(
            actor,
            DocumentSpacePageRequest(checkNotNull(secondPage.nextCursor), 2),
        )
        val all = firstPage.items + secondPage.items + thirdPage.items

        assertEquals(firstPage.snapshotVersion, secondPage.snapshotVersion)
        assertEquals(firstPage.snapshotVersion, thirdPage.snapshotVersion)
        assertTrue(!firstPage.snapshotChanged && !secondPage.snapshotChanged && !thirdPage.snapshotChanged)
        assertEquals(listOf(owned, overlapping, directGrant, userGrant, inheritedGrant).map { it.spaceId }, all.map { it.spaceId })
        assertEquals(all.size, all.map { it.spaceId }.distinct().size)
        assertEquals(DocumentSpace.ROLE_OWNER, all[0].myRole)
        assertEquals(DocumentSpace.ROLE_ADMIN, all[1].myRole)
        assertEquals(DocumentSpace.ROLE_EDITOR, all[2].myRole)
        assertNull(thirdPage.nextCursor)

        val document = ctx.documentService.createDocument(
            firstOwner,
            uuid(101),
            overlapping.spaceId,
            null,
            "重复授权下的正文",
            "# 分页 ACL",
        )
        ctx.documentService.getDocument(actor, overlapping.spaceId, document.documentId)
        assertEquals(
            listOf(document.documentId),
            ctx.documentService.listRecentDocuments(actor, 10).map { it.documentId },
        )
        assertEquals(
            listOf(document.documentId),
            ctx.documentService.listRecentlyCreatedDocuments(actor, 10).map { it.documentId },
        )
    }

    @Test
    fun `archive and revocation force explicit restart instead of proving omissions across snapshots`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-page-change-owner"))
        val actor = ctx.registerUser(uniqueUsername("document-page-change-actor"))
        val spaces = (201..205).map { index ->
            ctx.documentService.createSpace(owner, uuid(index), "变化空间-$index", null).also { space ->
                grantUser(owner, space.spaceId, actor, DocumentSpace.ROLE_VIEWER)
            }
        }

        val first = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(limit = 2))
        assertEquals(spaces.take(2).map { it.spaceId }, first.items.map { it.spaceId })
        val cursor = checkNotNull(first.nextCursor)

        // 排他锚点完全消失，而另一个靠后的候选失去访问权。
        // 续页绝不能把这两个变更中的任何一个与第一页的权威快照混合。
        ctx.documentService.archiveSpace(owner, spaces[1].spaceId, uuid(900))
        ctx.documentService.removeGrant(
            owner,
            spaces[2].spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            actor,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(spaces[2].spaceId),
            operationId = UUID.randomUUID().toString(),
        )

        val changed = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(cursor, 2))
        assertTrue(changed.snapshotChanged)
        assertTrue(changed.items.isEmpty())
        assertNull(changed.nextCursor)
        assertTrue(changed.snapshotVersion.documentDirectoryRevision > first.snapshotVersion.documentDirectoryRevision)

        val restartedFirst = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(limit = 2))
        val restartedSecond = ctx.documentService.listSpaces(
            actor,
            DocumentSpacePageRequest(checkNotNull(restartedFirst.nextCursor), 2),
        )
        assertEquals(
            listOf(spaces[0].spaceId, spaces[3].spaceId, spaces[4].spaceId),
            (restartedFirst.items + restartedSecond.items).map { it.spaceId },
        )
        assertEquals(restartedFirst.snapshotVersion, restartedSecond.snapshotVersion)
        assertTrue(!restartedFirst.snapshotChanged && !restartedSecond.snapshotChanged)
        assertNull(restartedSecond.nextCursor)
    }

    @Test
    fun `organization revision drift invalidates a document continuation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-page-org-owner"))
        val actor = ctx.registerUser(uniqueUsername("document-page-org-actor"))
        repeat(3) { index ->
            ctx.documentService.createSpace(owner, uuid(301 + index), "组织漂移空间-$index", null).also { space ->
                grantUser(owner, space.spaceId, actor, DocumentSpace.ROLE_VIEWER)
            }
        }
        val first = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(limit = 1))
        val cursor = checkNotNull(first.nextCursor)

        ctx.seedOrganizationUnit(OrganizationUnit(uuid(399), name = "分页期间新增组织节点"))

        val changed = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(cursor, 1))
        assertTrue(changed.snapshotChanged)
        assertTrue(changed.items.isEmpty())
        assertNull(changed.nextCursor)
        assertTrue(changed.snapshotVersion.organizationRevision > first.snapshotVersion.organizationRevision)
        assertEquals(
            first.snapshotVersion.documentDirectoryRevision,
            changed.snapshotVersion.documentDirectoryRevision,
        )
    }

    @Test
    fun `actor credential epoch drift invalidates a continuation after reactivation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-page-credential-owner"))
        val actor = ctx.registerUser(uniqueUsername("document-page-credential-actor"))
        repeat(3) { index ->
            ctx.documentService.createSpace(owner, uuid(401 + index), "凭据漂移空间-$index", null).also { space ->
                grantUser(owner, space.spaceId, actor, DocumentSpace.ROLE_VIEWER)
            }
        }
        val first = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(limit = 1))
        val cursor = checkNotNull(first.nextCursor)

        ctx.adminService.banUser(actor)
        ctx.adminService.unbanUser(actor)

        val changed = ctx.documentService.listSpaces(actor, DocumentSpacePageRequest(cursor, 1))
        assertTrue(changed.snapshotChanged)
        assertTrue(changed.items.isEmpty())
        assertNull(changed.nextCursor)
        assertTrue(changed.snapshotVersion.actorCredentialEpoch > first.snapshotVersion.actorCredentialEpoch)
        assertEquals(
            first.snapshotVersion.documentDirectoryRevision,
            changed.snapshotVersion.documentDirectoryRevision,
        )
    }

    @Test
    fun `malformed opaque cursor is rejected before querying`() = runTest {
        val actor = ctx.registerUser(uniqueUsername("document-page-invalid-cursor"))

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.listSpaces(
                actor,
                DocumentSpacePageRequest(cursor = "bm90LWEtdGVhbXRhbGstY3Vyc29y", limit = 2),
            )
        }
    }

    private suspend fun grantUser(owner: String, spaceId: String, actor: String, role: Int) {
        ctx.documentService.upsertGrant(
            owner,
            spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            actor,
            role,
            includeDescendants = false,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(spaceId),
            operationId = UUID.randomUUID().toString(),
        )
    }

    private suspend fun grantUnit(
        owner: String,
        spaceId: String,
        unitId: String,
        role: Int,
        includeDescendants: Boolean,
    ) {
        ctx.documentService.upsertGrant(
            owner,
            spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            unitId,
            role,
            includeDescendants,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(spaceId),
            operationId = UUID.randomUUID().toString(),
        )
    }

    private fun uuid(index: Int): String =
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
}
