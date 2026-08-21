package com.virjar.tk.integration

import com.virjar.tk.domain.document.DocumentService
import com.virjar.tk.infra.db.DocumentNodes
import com.virjar.tk.infra.db.OrganizationUnits
import com.virjar.tk.model.Document
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
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
        ctx.seedOrganizationUnit(root)
        ctx.seedOrganizationUnit(child)
        ctx.seedOrganizationMember(OrganizationMember(child.unitId, departmentMember))

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

        ctx.organizationService.removeMember(child.unitId, departmentMember)
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
        assertEquals(listOf(parent.nodeId, child.nodeId), created.ancestorIds)

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
        assertEquals(listOf(parent.nodeId), unchanged.ancestorIds)
        assertEquals(
            listOf(parent.nodeId),
            ctx.documentService.getDocument(owner, space.spaceId, created.documentId).ancestorIds,
        )
        val restored = ctx.documentService.updateDocument(
            owner,
            space.spaceId,
            created.documentId,
            unchanged.title,
            "# restored",
            unchanged.revision,
        )
        assertEquals(listOf(parent.nodeId), restored.ancestorIds)
        assertEquals(3, ctx.documentService.listRevisions(owner, space.spaceId, created.documentId).size)

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.moveNode(owner, space.spaceId, parent.nodeId, child.nodeId, parent.name, parent.revision)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.deleteNode(owner, space.spaceId, parent.nodeId, parent.revision)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.documentRepo.deleteNode(
                    transaction,
                    space.spaceId,
                    parent.nodeId,
                    parent.revision,
                    owner,
                    System.currentTimeMillis(),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.documentRepo.moveNode(
                    transaction,
                    space.spaceId,
                    child.nodeId,
                    child.revision,
                    created.documentId,
                    child.name,
                    owner,
                    System.currentTimeMillis(),
                )
            }
        }
        val otherSpace = ctx.documentService.createSpace(owner, "其他空间", null)
        val otherFolder = ctx.documentService.createFolder(owner, otherSpace.spaceId, null, "其他目录")
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.documentRepo.moveNode(
                    transaction,
                    space.spaceId,
                    child.nodeId,
                    child.revision,
                    otherFolder.nodeId,
                    child.name,
                    owner,
                    System.currentTimeMillis(),
                )
            }
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

        // 即使绕过领域服务，仓储事务也不允许制造环。
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.documentRepo.moveNode(
                    transaction,
                    space.spaceId,
                    parent.nodeId,
                    parent.revision,
                    child.nodeId,
                    parent.name,
                    owner,
                    System.currentTimeMillis(),
                )
            }
        }
        assertEquals(listOf(parent.nodeId), ctx.documentRepo.findDocument(created.documentId)!!.ancestorIds)
    }

    @Test
    fun `space candidates include only owner direct user direct unit and inherited unit grants`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("candidate-owner"))
        val member = ctx.registerUser(uniqueUsername("candidate-member"))
        val root = OrganizationUnit(UUID.randomUUID().toString(), name = "研发中心")
        val child = OrganizationUnit(UUID.randomUUID().toString(), parentId = root.unitId, name = "客户端组")
        val sibling = OrganizationUnit(UUID.randomUUID().toString(), parentId = root.unitId, name = "服务端组")
        ctx.seedOrganizationUnit(root)
        ctx.seedOrganizationUnit(child)
        ctx.seedOrganizationUnit(sibling)
        ctx.seedOrganizationMember(OrganizationMember(child.unitId, member))

        val inherited = ctx.documentService.createSpace(owner, "继承授权", null)
        val directUnit = ctx.documentService.createSpace(owner, "直属部门授权", null)
        val directUser = ctx.documentService.createSpace(owner, "用户授权", null)
        val ancestorWithoutInheritance = ctx.documentService.createSpace(owner, "祖先非继承授权", null)
        val unrelated = ctx.documentService.createSpace(owner, "无关部门授权", null)
        ctx.documentService.upsertGrant(
            owner,
            inherited.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            root.unitId,
            DocumentSpace.ROLE_VIEWER,
            true,
        )
        ctx.documentService.upsertGrant(
            owner,
            directUnit.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            child.unitId,
            DocumentSpace.ROLE_EDITOR,
            false,
        )
        ctx.documentService.upsertGrant(
            owner,
            directUser.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            member,
            DocumentSpace.ROLE_VIEWER,
            false,
        )
        ctx.documentService.upsertGrant(
            owner,
            ancestorWithoutInheritance.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            root.unitId,
            DocumentSpace.ROLE_VIEWER,
            false,
        )
        ctx.documentService.upsertGrant(
            owner,
            unrelated.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            sibling.unitId,
            DocumentSpace.ROLE_VIEWER,
            true,
        )

        val expectedIds = setOf(inherited.spaceId, directUnit.spaceId, directUser.spaceId)
        assertEquals(expectedIds, ctx.documentService.listSpaces(member).mapTo(mutableSetOf()) { it.spaceId })
        val candidates = ctx.documentRepo.listSpaceAccessCandidates(
            actorUid = member,
            directUnitIds = setOf(child.unitId),
            unitAndAncestorIds = setOf(child.unitId, root.unitId),
        )
        assertEquals(expectedIds, candidates.mapTo(mutableSetOf()) { it.space.spaceId })
        assertEquals(
            setOf(inherited.spaceId, directUnit.spaceId, directUser.spaceId, ancestorWithoutInheritance.spaceId, unrelated.spaceId),
            ctx.documentService.listSpaces(owner).mapTo(mutableSetOf()) { it.spaceId },
        )

        transaction {
            OrganizationUnits.update({ OrganizationUnits.unitId eq root.unitId }) {
                it[OrganizationUnits.status] = OrganizationUnit.STATUS_ARCHIVED
            }
        }
        assertEquals(
            setOf(directUnit.spaceId, directUser.spaceId),
            ctx.documentService.listSpaces(member).mapTo(mutableSetOf()) { it.spaceId },
        )

        // membership 仍在，但已归档的直属部门不再是授权事实。
        transaction {
            OrganizationUnits.update({ OrganizationUnits.unitId eq child.unitId }) {
                it[OrganizationUnits.status] = OrganizationUnit.STATUS_ARCHIVED
            }
        }
        assertEquals(listOf(child.unitId), ctx.organizationRepo.listMemberships(member).map { it.unitId })
        assertEquals(listOf(directUser.spaceId), ctx.documentService.listSpaces(member).map { it.spaceId })
    }

    @Test
    fun `cyclic active organization path keeps direct grant but drops inherited grants`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("cycle-owner"))
        val member = ctx.registerUser(uniqueUsername("cycle-member"))
        val ancestor = OrganizationUnit(UUID.randomUUID().toString(), name = "事业部")
        val direct = OrganizationUnit(UUID.randomUUID().toString(), parentId = ancestor.unitId, name = "小组")
        ctx.seedOrganizationUnit(ancestor)
        ctx.seedOrganizationUnit(direct)
        ctx.seedOrganizationMember(OrganizationMember(direct.unitId, member))

        val directSpace = ctx.documentService.createSpace(owner, "直属授权空间", null)
        val inheritedSpace = ctx.documentService.createSpace(owner, "继承授权空间", null)
        ctx.documentService.upsertGrant(
            owner,
            directSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            direct.unitId,
            DocumentSpace.ROLE_VIEWER,
            false,
        )
        ctx.documentService.upsertGrant(
            owner,
            inheritedSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            ancestor.unitId,
            DocumentSpace.ROLE_VIEWER,
            true,
        )
        assertEquals(
            setOf(directSpace.spaceId, inheritedSpace.spaceId),
            ctx.documentService.listSpaces(member).mapTo(mutableSetOf()) { it.spaceId },
        )

        // 模拟并发管理写或历史脏数据造成 direct → ancestor → direct。
        transaction {
            OrganizationUnits.update({ OrganizationUnits.unitId eq ancestor.unitId }) {
                it[OrganizationUnits.parentId] = direct.unitId
            }
        }
        assertEquals(listOf(directSpace.spaceId), ctx.documentService.listSpaces(member).map { it.spaceId })
    }

    @Test
    fun `document tree accepts depth 128 and rejects depth 129 including moved subtrees`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("depth-owner"))
        val space = ctx.documentService.createSpace(owner, "深层目录空间", null)
        val chain = seedFolderChain(space.spaceId, owner, Document.MAX_ANCESTOR_DEPTH)

        val boundaryDocument = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            chain.last(),
            "128 层文档",
            "# boundary",
        )
        assertEquals(Document.MAX_ANCESTOR_DEPTH, boundaryDocument.ancestorIds.size)

        assertFailsWith<IllegalArgumentException> {
            // chain 已有 128 个文件夹；文档可以挂在最深层，但不能再创建第 129 个文件夹。
            ctx.documentService.createFolder(owner, space.spaceId, chain.last(), "129 层文件夹")
        }
        assertEquals(
            Document.MAX_ANCESTOR_DEPTH,
            ctx.documentService.getDocument(owner, space.spaceId, boundaryDocument.documentId).ancestorIds.size,
        )

        val movingRoot = ctx.documentService.createFolder(owner, space.spaceId, null, "待移动子树")
        val movingChild = ctx.documentService.createFolder(owner, space.spaceId, movingRoot.nodeId, "子目录")
        val movingDocument = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            movingChild.nodeId,
            "子树文档",
            "# subtree",
        )
        val allowedParent = chain[Document.MAX_ANCESTOR_DEPTH - 3]
        val rejectedParent = chain[Document.MAX_ANCESTOR_DEPTH - 2]
        val moved = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            movingRoot.nodeId,
            allowedParent,
            movingRoot.name,
            movingRoot.revision,
        )
        assertEquals(
            Document.MAX_ANCESTOR_DEPTH,
            ctx.documentService.getDocument(owner, space.spaceId, movingDocument.documentId).ancestorIds.size,
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.moveNode(
                owner,
                space.spaceId,
                movingRoot.nodeId,
                rejectedParent,
                movingRoot.name,
                moved.revision,
            )
        }
        assertEquals(
            Document.MAX_ANCESTOR_DEPTH,
            ctx.documentService.getDocument(owner, space.spaceId, movingDocument.documentId).ancestorIds.size,
        )
    }

    @Test
    fun `repository serializes conflicting moves and delete versus create`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("tree-race-owner"))
        val space = ctx.documentService.createSpace(owner, "目录并发空间", null)
        val left = ctx.documentService.createFolder(owner, space.spaceId, null, "A")
        val right = ctx.documentService.createFolder(owner, space.spaceId, null, "B")

        val moveResults = coroutineScope {
            listOf(left to right, right to left).map { (moving, target) ->
                async(Dispatchers.Default) {
                    runCatching {
                        ctx.pgUnitOfWork.write {
                            ctx.documentRepo.moveNode(
                                transaction,
                                space.spaceId,
                                moving.nodeId,
                                moving.revision,
                                target.nodeId,
                                moving.name,
                                owner,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, moveResults.count { it.isSuccess })
        assertEquals(1, moveResults.count { it.isFailure })
        val currentLeft = ctx.documentRepo.findNode(left.nodeId)!!
        val currentRight = ctx.documentRepo.findNode(right.nodeId)!!
        assertTrue(
            (currentLeft.parentId == currentRight.nodeId) xor (currentRight.parentId == currentLeft.nodeId),
        )

        val emptyFolder = ctx.documentService.createFolder(owner, space.spaceId, null, "空目录")
        val now = System.currentTimeMillis()
        val child = DocumentNode(
            nodeId = UUID.randomUUID().toString(),
            spaceId = space.spaceId,
            parentId = emptyFolder.nodeId,
            nodeType = DocumentNode.TYPE_FOLDER,
            name = "并发子目录",
            revision = 1,
            createdBy = owner,
            createdAt = now,
            updatedBy = owner,
            updatedAt = now,
        )
        val (createResult, deleteResult) = coroutineScope {
            val create = async(Dispatchers.Default) {
                runCatching {
                    ctx.pgUnitOfWork.write { ctx.documentRepo.createFolder(transaction, child) }
                }
            }
            val delete = async(Dispatchers.Default) {
                runCatching {
                    ctx.pgUnitOfWork.write {
                        ctx.documentRepo.deleteNode(
                            transaction,
                            space.spaceId,
                            emptyFolder.nodeId,
                            emptyFolder.revision,
                            owner,
                            System.currentTimeMillis(),
                        )
                    }
                }
            }
            create.await() to delete.await()
        }
        assertEquals(1, listOf(createResult.isSuccess, deleteResult.isSuccess).count { it })
        if (createResult.isSuccess) {
            assertTrue(ctx.documentRepo.findNode(emptyFolder.nodeId) != null)
            assertTrue(ctx.documentRepo.findNode(child.nodeId) != null)
        } else {
            assertTrue(deleteResult.isSuccess)
            assertTrue(ctx.documentRepo.findNode(emptyFolder.nodeId) == null)
            assertTrue(ctx.documentRepo.findNode(child.nodeId) == null)
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

    @Test
    fun `document home orders visits and creations and hides spaces after access loss`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("home-owner"))
        val member = ctx.registerUser(uniqueUsername("home-member"))
        val firstSpace = ctx.documentService.createSpace(owner, "产品空间", null)
        val secondSpace = ctx.documentService.createSpace(owner, "研发空间", null)
        listOf(firstSpace, secondSpace).forEach { space ->
            ctx.documentService.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member,
                DocumentSpace.ROLE_VIEWER,
                false,
            )
        }

        val first = ctx.documentService.createDocument(owner, firstSpace.spaceId, null, "交互规范", "# 第一篇\n正文")
        Thread.sleep(5)
        val second = ctx.documentService.createDocument(owner, secondSpace.spaceId, null, "发布清单", "# 第二篇\n正文")
        assertEquals(
            listOf(second.documentId, first.documentId),
            ctx.documentService.listRecentDocuments(owner, 10).map { it.documentId },
        )

        val created = ctx.documentService.listRecentlyCreatedDocuments(member, 10)
        assertEquals(listOf(second.documentId, first.documentId), created.map { it.documentId })
        assertEquals(listOf("研发空间", "产品空间"), created.map { it.spaceName })
        assertEquals(listOf("第二篇", "第一篇"), created.map { it.excerpt })
        assertEquals(owner, created.first().createdBy)
        assertEquals(ctx.userRepo.findByUid(owner)!!.name, created.first().creatorName)
        assertTrue(created.all { it.accessedAt == 0L })

        ctx.documentService.getDocument(member, firstSpace.spaceId, first.documentId)
        ctx.documentService.getDocument(member, secondSpace.spaceId, second.documentId)
        assertEquals(
            listOf(second.documentId, first.documentId),
            ctx.documentService.listRecentDocuments(member, 10).map { it.documentId },
        )
        ctx.documentService.getDocument(member, firstSpace.spaceId, first.documentId)
        val revisited = ctx.documentService.listRecentDocuments(member, 1).single()
        assertEquals(first.documentId, revisited.documentId)
        assertTrue(revisited.accessedAt > 0)

        assertFailsWith<IllegalArgumentException> { ctx.documentService.listRecentDocuments(member, 0) }
        assertFailsWith<IllegalArgumentException> { ctx.documentService.listRecentlyCreatedDocuments(member, 51) }

        ctx.documentService.removeGrant(
            owner,
            firstSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            member,
        )
        assertEquals(
            listOf(second.documentId),
            ctx.documentService.listRecentDocuments(member, 10).map { it.documentId },
        )
        assertEquals(
            listOf(second.documentId),
            ctx.documentService.listRecentlyCreatedDocuments(member, 10).map { it.documentId },
        )
    }

    @Test
    fun `document create and update enforce markdown structure budgets`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("markdown-budget-owner"))
        val space = ctx.documentService.createSpace(owner, "结构预算空间", null)
        val validQuote = "> ".repeat(DocumentService.MAX_MARKDOWN_QUOTE_DEPTH) + "边界正文"
        val created = ctx.documentService.createDocument(owner, space.spaceId, null, "结构预算", validQuote)

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(
                owner,
                space.spaceId,
                null,
                "引用超限",
                "> ".repeat(DocumentService.MAX_MARKDOWN_QUOTE_DEPTH + 1) + "正文",
            )
        }

        val tooManyColumns = listOf(
            markdownTableRow(DocumentService.MAX_MARKDOWN_TABLE_COLUMNS + 1) { "h$it" },
            markdownTableRow(DocumentService.MAX_MARKDOWN_TABLE_COLUMNS + 1) { "---" },
        ).joinToString("\n")
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(owner, space.spaceId, null, "表格列超限", tooManyColumns)
        }

        val columns = 10
        val bodyRows = DocumentService.MAX_MARKDOWN_TABLE_CELLS / columns
        val tooManyCells = buildList {
            add(markdownTableRow(columns) { "h$it" })
            add(markdownTableRow(columns) { "---" })
            repeat(bodyRows) { row -> add(markdownTableRow(columns) { column -> "$row:$column" }) }
        }.joinToString("\n")
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.updateDocument(
                owner,
                space.spaceId,
                created.documentId,
                created.title,
                tooManyCells,
                created.revision,
            )
        }

        val unchanged = ctx.documentService.getDocument(owner, space.spaceId, created.documentId)
        assertEquals(1, unchanged.revision)
        assertEquals(validQuote, unchanged.markdown)
    }

    private fun markdownTableRow(columns: Int, cell: (Int) -> String): String =
        "| " + (0 until columns).joinToString(" | ", transform = cell) + " |"

    /** 仅用于构造深度边界；被测的最后一层及后续写入仍全部走正式服务/仓储路径。 */
    private fun seedFolderChain(spaceId: String, actorUid: String, count: Int): List<String> {
        val nodeIds = List(count) { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        transaction {
            nodeIds.forEachIndexed { index, id ->
                DocumentNodes.insert {
                    it[nodeId] = id
                    it[DocumentNodes.spaceId] = spaceId
                    it[parentId] = nodeIds.getOrNull(index - 1)
                    it[nodeType] = DocumentNode.TYPE_FOLDER
                    it[name] = "边界目录-$index"
                    it[excerpt] = ""
                    it[markdown] = null
                    it[revision] = 1
                    it[status] = 1
                    it[createdBy] = actorUid
                    it[createdAt] = now + index
                    it[updatedBy] = actorUid
                    it[updatedAt] = now + index
                }
            }
        }
        return nodeIds
    }
}
