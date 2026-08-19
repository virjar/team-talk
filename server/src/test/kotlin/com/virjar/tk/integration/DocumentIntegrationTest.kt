package com.virjar.tk.integration

import com.virjar.tk.domain.document.DocumentService
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `space grants combine users and live organization membership`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("space-owner"))
        val departmentMember = ctx.registerUser(uniqueUsername("space-department"))
        val editor = ctx.registerUser(uniqueUsername("space-editor"))
        val outsider = ctx.registerUser(uniqueUsername("space-outsider"))
        val root = OrganizationUnit(UUID.randomUUID().toString(), name = "产品中心")
        val child = OrganizationUnit(UUID.randomUUID().toString(), parentId = root.unitId, name = "体验设计组")
        ctx.organizationRepo.createUnit(root)
        ctx.organizationRepo.createUnit(child)
        ctx.organizationRepo.upsertMember(OrganizationMember(child.unitId, departmentMember))

        val space = ctx.documentService.createSpace(owner, "产品知识库", "跨小组共享的产品资产")
        assertEquals(DocumentSpace.ROLE_OWNER, space.myRole)
        assertTrue(ctx.documentService.listSpaces(outsider).isEmpty())

        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            root.unitId,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants = true,
        )
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            editor,
            DocumentSpace.ROLE_EDITOR,
            includeDescendants = false,
        )
        assertEquals(DocumentSpace.ROLE_VIEWER, ctx.documentService.listSpaces(departmentMember).single().myRole)
        assertEquals(DocumentSpace.ROLE_EDITOR, ctx.documentService.listSpaces(editor).single().myRole)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createFolder(departmentMember, space.spaceId, null, "不能创建")
        }

        val folder = ctx.documentService.createFolder(editor, space.spaceId, null, "产品规范")
        val document = ctx.documentService.createDocument(
            editor,
            space.spaceId,
            folder.nodeId,
            "交互原则",
            "# 交互原则\n第一版",
        )
        assertEquals(listOf(DocumentNode.TYPE_FOLDER), ctx.documentService.listNodes(departmentMember, space.spaceId, null).map { it.nodeType })
        assertEquals("# 交互原则\n第一版", ctx.documentService.getDocument(departmentMember, space.spaceId, document.documentId).markdown)

        ctx.organizationRepo.removeMember(child.unitId, departmentMember)
        assertTrue(ctx.documentService.listSpaces(departmentMember).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.getDocument(departmentMember, space.spaceId, document.documentId)
        }
    }

    @Test
    fun `directory tree revisions restore inputs and destructive boundaries are enforced`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("tree-owner"))
        val space = ctx.documentService.createSpace(owner, "研发空间", null)
        val parent = ctx.documentService.createFolder(owner, space.spaceId, null, "架构")
        val child = ctx.documentService.createFolder(owner, space.spaceId, parent.nodeId, "客户端")
        val created = ctx.documentService.createDocument(owner, space.spaceId, child.nodeId, "状态模型", "# v1")

        val renamed = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            created.documentId,
            parent.nodeId,
            "状态与同步",
            created.revision,
        )
        assertEquals(2, renamed.revision)
        assertEquals(listOf(2L, 1L), ctx.documentService.listRevisions(owner, space.spaceId, created.documentId).map { it.revision })
        assertEquals("# v1", ctx.documentService.getRevision(owner, space.spaceId, created.documentId, 1).markdown)

        val unchanged = ctx.documentService.updateDocument(
            owner,
            space.spaceId,
            created.documentId,
            renamed.name,
            "# v1",
            renamed.revision,
        )
        assertEquals(renamed.revision, unchanged.revision)
        assertEquals(2, ctx.documentService.listRevisions(owner, space.spaceId, created.documentId).size)

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.moveNode(owner, space.spaceId, parent.nodeId, child.nodeId, parent.name, parent.revision)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.deleteNode(owner, space.spaceId, parent.nodeId, parent.revision)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(owner, space.spaceId, null, "x".repeat(181), "")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(
                owner,
                space.spaceId,
                null,
                "超长正文",
                "x".repeat(DocumentService.MAX_MARKDOWN_LENGTH + 1),
            )
        }
    }

    @Test
    fun `only one editor can claim the same document revision`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("race-owner"))
        val editor = ctx.registerUser(uniqueUsername("race-editor"))
        val space = ctx.documentService.createSpace(owner, "会议空间", null)
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            editor,
            DocumentSpace.ROLE_EDITOR,
            false,
        )
        val created = ctx.documentService.createDocument(owner, space.spaceId, null, "会议纪要", "初稿")

        val results = coroutineScope {
            listOf(owner, editor).mapIndexed { index, actor ->
                async(Dispatchers.Default) {
                    runCatching {
                        ctx.documentService.updateDocument(
                            actor,
                            space.spaceId,
                            created.documentId,
                            "会议纪要-${index + 1}",
                            "并发版本 ${index + 1}",
                            created.revision,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        val current = ctx.documentService.getDocument(owner, space.spaceId, created.documentId)
        assertEquals(2, current.revision)
        assertEquals(2, ctx.documentRepo.listRevisions(created.documentId).size)
    }
}
