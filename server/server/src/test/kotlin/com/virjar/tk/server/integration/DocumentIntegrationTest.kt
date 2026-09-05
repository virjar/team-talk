package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentCustodyConflictException
import com.virjar.tk.server.domain.document.DocumentHierarchyConflictException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentRevisionConflictException
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.organization.OrganizationChangePublisher
import com.virjar.tk.server.domain.organization.OrganizationUnitArchiveConflictException
import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentContentRevisions
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaceCustodyTransfers
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.server.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.server.infra.db.repository.OrganizationLockHooks
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `exact space access distinguishes missing resources permissions and validation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-status-owner"))
        val outsider = ctx.registerUser(uniqueUsername("document-status-outsider"))
        val existingPrincipal = ctx.registerUser(uniqueUsername("document-status-principal"))
        val existingUnit = OrganizationUnit(UUID.randomUUID().toString(), name = "状态契约部门")
        ctx.seedOrganizationUnit(existingUnit)
        val space = ctx.documentService.createSpace(owner, "稳定错误契约", null)
        val root = ctx.documentService.createDocument(owner, space.spaceId, null, "受限根文档", "# root")

        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.listNodes(outsider, space.spaceId, null)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.getNodePathSpine(outsider, space.spaceId, root.documentId)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.updateSpace(outsider, space.spaceId, "不应写入", null)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.archiveSpace(outsider, space.spaceId, UUID.randomUUID().toString())
        }
        listOf(existingPrincipal, UUID.randomUUID().toString()).forEach { principalId ->
            assertFailsWith<DocumentAccessDeniedException> {
                ctx.documentService.upsertGrant(
                    outsider,
                    space.spaceId,
                    DocumentSpaceGrant.PRINCIPAL_USER,
                    principalId,
                    DocumentSpace.ROLE_VIEWER,
                    false,
                    expectedPolicyRevision = space.policyRevision,
                    operationId = UUID.randomUUID().toString(),
                )
            }
        }
        listOf(existingUnit.unitId, UUID.randomUUID().toString()).forEach { principalId ->
            assertFailsWith<DocumentAccessDeniedException> {
                ctx.documentService.upsertGrant(
                    outsider,
                    space.spaceId,
                    DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                    principalId,
                    DocumentSpace.ROLE_VIEWER,
                    false,
                    expectedPolicyRevision = space.policyRevision,
                    operationId = UUID.randomUUID().toString(),
                )
            }
        }

        val missingPrincipalId = UUID.randomUUID().toString()
        val authorizedMissingPrincipal = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                missingPrincipalId,
                DocumentSpace.ROLE_VIEWER,
                false,
                expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertEquals(IllegalArgumentException::class, authorizedMissingPrincipal::class)
        val authorizedMissingUnit = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                UUID.randomUUID().toString(),
                DocumentSpace.ROLE_VIEWER,
                false,
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertEquals(IllegalArgumentException::class, authorizedMissingUnit::class)

        val missingSpaceId = UUID.randomUUID().toString()
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.listNodes(owner, missingSpaceId, null)
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.updateSpace(owner, missingSpaceId, "不存在", null)
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.archiveSpace(owner, missingSpaceId, UUID.randomUUID().toString())
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.upsertGrant(
                outsider,
                missingSpaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                missingPrincipalId,
                DocumentSpace.ROLE_VIEWER,
                false,
                expectedPolicyRevision = 1L,
                operationId = UUID.randomUUID().toString(),
            )
        }

        val archiveOperationId = UUID.randomUUID().toString()
        ctx.documentService.archiveSpace(owner, space.spaceId, archiveOperationId)
        ctx.documentService.archiveSpace(owner, space.spaceId, archiveOperationId)
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.listNodes(owner, space.spaceId, null)
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.updateSpace(owner, space.spaceId, "已归档", null)
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.createSpace(owner, space.spaceId, "稳定错误契约", null)
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.archiveSpace(owner, space.spaceId, UUID.randomUUID().toString())
        }

        val invalidParameter = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.archiveSpace(owner, space.spaceId, "not-a-uuid")
        }
        assertEquals(IllegalArgumentException::class, invalidParameter::class)
    }

    @Test
    fun `siblings keep creation order across rename move and timestamp ties`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-sibling-order-owner"))
        val space = ctx.documentService.createSpace(owner, "稳定同级顺序", null)
        val parent = ctx.documentService.createDocument(owner, space.spaceId, null, "父页面", "")
        val older = ctx.documentService.createDocument(
            owner, space.spaceId, parent.documentId, "Zulu", "# older",
        )
        val later = ctx.documentService.createDocument(
            owner, space.spaceId, parent.documentId, "Alpha", "# later",
        )
        val tieA = ctx.documentService.createDocument(
            owner, space.spaceId, parent.documentId, "Tie Zulu", "# tie-a",
        )
        val tieB = ctx.documentService.createDocument(
            owner, space.spaceId, parent.documentId, "Tie Alpha", "# tie-b",
        )
        val moving = ctx.documentService.createDocument(owner, space.spaceId, null, "000 moved", "")

        transaction(ctx.database) {
            mapOf(
                older.documentId to 100L,
                moving.documentId to 150L,
                later.documentId to 200L,
                tieA.documentId to 300L,
                tieB.documentId to 300L,
            ).forEach { (documentId, createdAt) ->
                DocumentNodes.update({ DocumentNodes.nodeId eq documentId }) {
                    it[DocumentNodes.createdAt] = createdAt
                }
            }
        }

        val tiedIds = listOf(tieA.documentId, tieB.documentId).sorted()
        assertEquals(
            listOf(older.documentId, later.documentId) + tiedIds,
            ctx.documentService.listNodes(owner, space.spaceId, parent.documentId)
                .map { it.nodeId },
        )

        val renamed = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            older.documentId,
            parent.documentId,
            "000 renamed",
            older.revision,
        )
        assertEquals(
            listOf(renamed.node.nodeId, later.documentId) + tiedIds,
            ctx.documentService.listNodes(owner, space.spaceId, parent.documentId)
                .map { it.nodeId },
        )

        ctx.documentService.moveNode(
            owner,
            space.spaceId,
            moving.documentId,
            parent.documentId,
            moving.title,
            moving.revision,
        )
        assertEquals(
            listOf(renamed.node.nodeId, moving.documentId, later.documentId) + tiedIds,
            ctx.documentService.listNodes(owner, space.spaceId, parent.documentId)
                .map { it.nodeId },
        )
    }

    @Test
    fun `exact node access hides missing and foreign identities behind the same not found terminal`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-node-status-owner"))
        val space = ctx.documentService.createSpace(owner, "节点状态契约", null)
        val foreignSpace = ctx.documentService.createSpace(owner, "其他节点状态契约", null)
        val foreignDocument = ctx.documentService.createDocument(
            owner,
            foreignSpace.spaceId,
            null,
            "其他空间文档",
            "# 不应由当前空间探测",
        )
        val localDocument = ctx.documentService.createDocument(owner, space.spaceId, null, "当前空间文档", "")

        suspend fun assertNotFoundForExactNode(nodeId: String) {
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.listNodes(owner, space.spaceId, nodeId)
            }
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.createDocument(owner, space.spaceId, nodeId, "非法父节点", "")
            }
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.getDocument(owner, space.spaceId, nodeId)
            }
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.updateDocument(owner, space.spaceId, nodeId, "", 1)
            }
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.moveNode(owner, space.spaceId, nodeId, null, "不存在", 1)
            }
            assertFailsWith<DocumentHierarchyConflictException> {
                ctx.documentService.moveNode(
                    owner,
                    space.spaceId,
                    localDocument.documentId,
                    nodeId,
                    localDocument.title,
                    localDocument.revision,
                )
            }
            assertEquals(
                localDocument,
                ctx.documentService.getDocument(owner, space.spaceId, localDocument.documentId),
            )
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.deleteNode(
                    owner,
                    space.spaceId,
                    nodeId,
                    1,
                    UUID.randomUUID().toString(),
                )
            }
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.listRevisions(owner, space.spaceId, nodeId, 0, 10)
            }
            assertFailsWith<DocumentNotFoundException> {
                ctx.documentService.getRevision(owner, space.spaceId, nodeId, 1)
            }
        }

        assertNotFoundForExactNode(UUID.randomUUID().toString())
        assertNotFoundForExactNode(foreignDocument.documentId)
    }

    @Test
    fun `client resource ids make space and document creation retry safe`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-idempotency-owner"))
        val spaceId = UUID.randomUUID().toString()

        val firstSpace = ctx.documentService.createSpace(owner, spaceId, "幂等空间", "同一创建意图")
        val retriedSpace = ctx.documentService.createSpace(owner, spaceId, "幂等空间", "同一创建意图")
        assertEquals(firstSpace, retriedSpace)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createSpace(owner, spaceId, "不同空间", "复用了同一标识")
        }

        val documentId = UUID.randomUUID().toString()
        val concurrent = coroutineScope {
            List(2) {
                async(Dispatchers.Default) {
                    ctx.documentService.createDocumentCommand(
                        owner,
                        documentId,
                        spaceId,
                        null,
                        "幂等文档",
                        "# 唯一初始正文",
                    )
                }
            }.awaitAll()
        }
        assertEquals(setOf(documentId), concurrent.mapTo(hashSetOf()) { it.documentId })
        assertEquals(1, concurrent.count { it.document != null })
        assertEquals(1, concurrent.count { it.document == null })
        assertEquals(1, ctx.documentService.listNodes(owner, spaceId, null).size)
        assertEquals(
            listOf(1L),
            ctx.documentService.listRevisions(owner, spaceId, documentId, 0, 10).items.map { it.revision },
        )
        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentService.createDocumentCommand(
                owner,
                documentId,
                spaceId,
                null,
                "幂等文档",
                "# 不同初始正文",
            )
        }

        val otherActor = ctx.registerUser(uniqueUsername("document-idempotency-other"))
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.createDocumentCommand(
                otherActor,
                documentId,
                spaceId,
                null,
                "幂等文档",
                "# 唯一初始正文",
            )
        }
        ctx.documentService.upsertGrant(
            owner,
            spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            otherActor,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = firstSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentService.createDocumentCommand(
                otherActor,
                documentId,
                spaceId,
                null,
                "幂等文档",
                "# 唯一初始正文",
            )
        }
    }

    @Test
    fun `space create replay keeps immutable identity without reviving stale owner authority`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("create-replay-creator"))
        val nextSteward = ctx.registerUser(uniqueUsername("create-replay-steward"))
        val root = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, "创建重试组织", null)
        val owningUnit = ctx.organizationService.createUnit(root.unitId, "创建重试资产部门", null)
        val spaceId = UUID.randomUUID().toString()
        val created = ctx.documentService.createSpace(creator, spaceId, "交接后重试空间", null)

        val organizationTransfer = ctx.documentService.transferSpaceCustody(
            actorUid = creator,
            spaceId = spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            ownerPrincipalId = owningUnit.unitId,
            stewardUid = creator,
            expectedCustodyRevision = 1,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(2L, organizationTransfer.custodyRevision)

        val replayWhileStillSteward = ctx.documentService.createSpaceCommand(
            creator,
            spaceId,
            "交接后重试空间",
            null,
        )
        val currentProjection = requireNotNull(replayWhileStillSteward.space)
        assertEquals(created.createdBy, currentProjection.createdBy)
        assertEquals(owningUnit.unitId, currentProjection.ownerPrincipalId)
        assertEquals(creator, currentProjection.stewardUid)
        assertEquals(DocumentSpace.ROLE_OWNER, currentProjection.myRole)

        ctx.documentService.transferSpaceCustody(
            actorUid = creator,
            spaceId = spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            ownerPrincipalId = nextSteward,
            stewardUid = nextSteward,
            expectedCustodyRevision = 2,
            operationId = UUID.randomUUID().toString(),
        )
        val replayAfterAuthorityLoss = ctx.documentService.createSpaceCommand(
            creator,
            spaceId,
            "交接后重试空间",
            null,
        )
        assertEquals(spaceId, replayAfterAuthorityLoss.spaceId)
        assertNull(replayAfterAuthorityLoss.space)
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.createSpace(creator, spaceId, "交接后重试空间", null)
        }
    }

    @Test
    fun `destructive command ids make completed archive and delete retries safe`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-destructive-owner"))
        val outsider = ctx.registerUser(uniqueUsername("document-destructive-outsider"))
        val archiveSpace = ctx.documentService.createSpace(owner, "归档幂等空间", null)
        val archiveOperationId = UUID.randomUUID().toString()

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.archiveSpace(owner, archiveSpace.spaceId, "not-a-uuid")
        }
        ctx.documentService.archiveSpace(owner, archiveSpace.spaceId, archiveOperationId)
        ctx.documentService.archiveSpace(owner, archiveSpace.spaceId, archiveOperationId)
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.archiveSpace(owner, archiveSpace.spaceId, UUID.randomUUID().toString())
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.archiveSpace(outsider, archiveSpace.spaceId, archiveOperationId)
        }
        transaction(ctx.database) {
            val row = DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId eq archiveSpace.spaceId
            }.single()
            assertEquals(0, row[DocumentSpaces.status])
            assertEquals(archiveOperationId, row[DocumentSpaces.archiveCommandId])
            assertEquals(owner, row[DocumentSpaces.createdBy])
        }

        val deleteSpace = ctx.documentService.createSpace(owner, "删除幂等空间", null)
        val documentId = UUID.randomUUID().toString()
        val documentTitle = "待删除文档"
        val documentMarkdown = "# 待删除"
        val document = requireNotNull(ctx.documentService.createDocumentCommand(
            owner,
            documentId,
            deleteSpace.spaceId,
            null,
            documentTitle,
            documentMarkdown,
        ).document)
        val deleteOperationId = UUID.randomUUID().toString()

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.deleteNode(
                owner,
                deleteSpace.spaceId,
                document.documentId,
                document.revision,
                "not-a-uuid",
            )
        }
        assertFailsWith<DocumentRevisionConflictException> {
            ctx.documentService.deleteNode(
                owner,
                deleteSpace.spaceId,
                document.documentId,
                document.revision + 1,
                deleteOperationId,
            )
        }
        transaction(ctx.database) {
            val row = DocumentNodes.selectAll().where {
                DocumentNodes.nodeId eq document.documentId
            }.single()
            assertEquals(1, row[DocumentNodes.status])
            assertEquals(null, row[DocumentNodes.deleteCommandId])
            assertEquals(document.revision, row[DocumentNodes.revision])
        }

        ctx.documentService.deleteNode(
            owner,
            deleteSpace.spaceId,
            document.documentId,
            document.revision,
            deleteOperationId,
        )
        ctx.documentService.deleteNode(
            owner,
            deleteSpace.spaceId,
            document.documentId,
            document.revision,
            deleteOperationId,
        )
        val createReplayAfterDelete = ctx.documentService.createDocumentCommand(
            owner,
            documentId,
            deleteSpace.spaceId,
            null,
            documentTitle,
            documentMarkdown,
        )
        assertEquals(documentId, createReplayAfterDelete.documentId)
        assertNull(createReplayAfterDelete.document)
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.deleteNode(
                owner,
                deleteSpace.spaceId,
                document.documentId,
                document.revision + 99,
                deleteOperationId,
            )
        }
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.deleteNode(
                owner,
                deleteSpace.spaceId,
                document.documentId,
                document.revision,
                UUID.randomUUID().toString(),
            )
        }
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.deleteNode(
                outsider,
                deleteSpace.spaceId,
                document.documentId,
                document.revision,
                deleteOperationId,
            )
        }
        transaction(ctx.database) {
            val row = DocumentNodes.selectAll().where {
                DocumentNodes.nodeId eq document.documentId
            }.single()
            assertEquals(0, row[DocumentNodes.status])
            assertEquals(deleteOperationId, row[DocumentNodes.deleteCommandId])
            assertEquals(owner, row[DocumentNodes.updatedBy])
            assertEquals(document.revision + 1, row[DocumentNodes.revision])
        }
    }

    @Test
    fun `authorization sensitive document reads retain one repeatable snapshot`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-read-snapshot-owner"))
        val space = ctx.documentService.createSpace(owner, "读取快照", null)
        val firstReadFinished = CountDownLatch(1)
        val allowSecondRead = CountDownLatch(1)

        val observed = async(Dispatchers.Default) {
            ctx.pgUnitOfWork.read {
                val before = requireNotNull(ctx.documentRepo.findSpace(transaction, space.spaceId)).name
                firstReadFinished.countDown()
                check(allowSecondRead.await(10, TimeUnit.SECONDS))
                val after = requireNotNull(ctx.documentRepo.findSpace(transaction, space.spaceId)).name
                before to after
            }
        }
        check(firstReadFinished.await(10, TimeUnit.SECONDS))
        ctx.documentService.updateSpace(owner, space.spaceId, "提交后的名称", null)
        allowSecondRead.countDown()

        assertEquals("读取快照" to "读取快照", observed.await())
        assertEquals("提交后的名称", ctx.documentService.listSpaces(owner).single().name)
    }

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

        val departmentGrant = ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            root.unitId,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants = true,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            editor,
            DocumentSpace.ROLE_EDITOR,
            includeDescendants = false,
            expectedPolicyRevision = departmentGrant.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(DocumentSpace.ROLE_VIEWER, ctx.documentService.listSpaces(departmentMember).single().myRole)
        assertEquals(DocumentSpace.ROLE_EDITOR, ctx.documentService.listSpaces(editor).single().myRole)
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.createDocument(departmentMember, space.spaceId, null, "不能创建", "")
        }

        val overview = ctx.documentService.createDocument(
            editor,
            space.spaceId,
            null,
            "产品规范",
            "# 产品规范\n本节点同时是目录入口和综述文档。",
        )
        val document = ctx.documentService.createDocument(
            editor,
            space.spaceId,
            overview.documentId,
            "交互原则",
            "# 交互原则\n第一版",
        )
        val rootNode = ctx.documentService.listNodes(departmentMember, space.spaceId, null).single()
        assertEquals(overview.documentId, rootNode.nodeId)
        assertTrue(rootNode.hasChildren)
        assertEquals(
            "# 产品规范\n本节点同时是目录入口和综述文档。",
            ctx.documentService.getDocument(departmentMember, space.spaceId, overview.documentId).markdown,
        )
        assertEquals("# 交互原则\n第一版", ctx.documentService.getDocument(departmentMember, space.spaceId, document.documentId).markdown)

        ctx.organizationService.removeMember(child.unitId, departmentMember)
        assertTrue(ctx.documentService.listSpaces(departmentMember).isEmpty())
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.getDocument(departmentMember, space.spaceId, document.documentId)
        }
    }

    @Test
    fun `custody transfer separates provenance ownership stewardship and organization visibility`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("custody-creator"))
        val nextSteward = ctx.registerUser(uniqueUsername("custody-steward"))
        val ordinaryMember = ctx.registerUser(uniqueUsername("custody-member"))
        val organizationRoot = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, "交接测试组织", null)
        val owningUnit = ctx.organizationService.createUnit(organizationRoot.unitId, "资产持有部门", null)
        ctx.organizationService.assignMember(owningUnit.unitId, ordinaryMember, "普通成员", false)
        val created = ctx.documentService.createSpace(creator, "归属交接空间", null)
        val createdDocumentId = UUID.randomUUID().toString()
        val createdDocumentTitle = "交接前文档"
        val createdDocumentMarkdown = "# 交接前文档"
        val createdDocument = requireNotNull(ctx.documentService.createDocumentCommand(
            creator,
            createdDocumentId,
            created.spaceId,
            null,
            createdDocumentTitle,
            createdDocumentMarkdown,
        ).document)
        assertEquals(createdDocument.documentId, ctx.documentService.listRecentDocuments(creator, 50).single().documentId)
        assertEquals(
            createdDocument.documentId,
            ctx.documentService.listRecentlyCreatedDocuments(creator, 50).single().documentId,
        )
        val firstOperationId = UUID.randomUUID().toString()

        val transferred = ctx.documentService.transferSpaceCustody(
            actorUid = creator,
            spaceId = created.spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            ownerPrincipalId = owningUnit.unitId,
            stewardUid = nextSteward,
            expectedCustodyRevision = 1,
            operationId = firstOperationId,
        )

        assertEquals(DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT, transferred.ownerPrincipalType)
        assertEquals(owningUnit.unitId, transferred.ownerPrincipalId)
        assertEquals(nextSteward, transferred.stewardUid)
        assertEquals(2L, transferred.custodyRevision)
        assertTrue(ctx.documentService.listSpaces(creator).isEmpty())
        assertTrue(ctx.documentService.listRecentDocuments(creator, 50).isEmpty())
        assertTrue(ctx.documentService.listRecentlyCreatedDocuments(creator, 50).isEmpty())
        val createReplayAfterAuthorityLoss = ctx.documentService.createDocumentCommand(
            creator,
            createdDocumentId,
            created.spaceId,
            null,
            createdDocumentTitle,
            createdDocumentMarkdown,
        )
        assertEquals(createdDocumentId, createReplayAfterAuthorityLoss.documentId)
        assertNull(createReplayAfterAuthorityLoss.document)
        val stewardProjection = ctx.documentService.listSpaces(nextSteward).single()
        assertEquals(creator, stewardProjection.createdBy, "创建来源必须在资产交接后保持不变")
        assertEquals(DocumentSpace.ROLE_OWNER, stewardProjection.myRole)
        assertEquals(
            createdDocument.documentId,
            ctx.documentService.listRecentlyCreatedDocuments(nextSteward, 50).single().documentId,
        )
        assertTrue(
            ctx.documentService.listSpaces(ordinaryMember).isEmpty(),
            "组织持有资产本身不能给普通组织成员隐式访问权",
        )

        val retry = ctx.documentService.transferSpaceCustody(
            creator,
            created.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            owningUnit.unitId,
            nextSteward,
            1,
            firstOperationId,
        )
        assertEquals(transferred, retry, "失去权限后的精确重试必须返回原始交接收据")
        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentService.transferSpaceCustody(
                creator,
                created.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                creator,
                creator,
                1,
                firstOperationId,
            )
        }
        assertFailsWith<ReliableCommandConflictException> {
            ctx.documentService.transferSpaceCustody(
                creator,
                UUID.randomUUID().toString(),
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                owningUnit.unitId,
                nextSteward,
                1,
                firstOperationId,
            )
        }
        assertFailsWith<DocumentCustodyConflictException> {
            ctx.documentService.transferSpaceCustody(
                nextSteward,
                created.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                nextSteward,
                nextSteward,
                1,
                UUID.randomUUID().toString(),
            )
        }

        ctx.documentService.upsertGrant(
            nextSteward,
            created.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            owningUnit.unitId,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(created.spaceId),
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(DocumentSpace.ROLE_VIEWER, ctx.documentService.listSpaces(ordinaryMember).single().myRole)
        ctx.organizationService.removeMember(owningUnit.unitId, ordinaryMember)
        assertFailsWith<OrganizationUnitArchiveConflictException> {
            ctx.organizationService.archiveUnit(owningUnit.unitId)
        }

        val returnedToUser = ctx.documentService.transferSpaceCustody(
            nextSteward,
            created.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            nextSteward,
            nextSteward,
            2,
            UUID.randomUUID().toString(),
        )
        assertEquals(3L, returnedToUser.custodyRevision)
        assertEquals(creator, ctx.documentService.listSpaces(nextSteward).single().createdBy)
        ctx.organizationService.archiveUnit(owningUnit.unitId)

        val archiveOperationId = UUID.randomUUID().toString()
        ctx.documentService.archiveSpace(nextSteward, created.spaceId, archiveOperationId)
        ctx.documentService.archiveSpace(nextSteward, created.spaceId, archiveOperationId)
        val createReplayAfterArchive = ctx.documentService.createDocumentCommand(
            creator,
            createdDocumentId,
            created.spaceId,
            null,
            createdDocumentTitle,
            createdDocumentMarkdown,
        )
        assertEquals(createdDocumentId, createReplayAfterArchive.documentId)
        assertNull(createReplayAfterArchive.document)
        val replayAfterLaterTransferAndArchive = ctx.documentService.transferSpaceCustody(
            creator,
            created.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            owningUnit.unitId,
            nextSteward,
            1,
            firstOperationId,
        )
        assertEquals(transferred, replayAfterLaterTransferAndArchive)

        val receipts = transaction(ctx.database) {
            DocumentSpaceCustodyTransfers.selectAll().where {
                DocumentSpaceCustodyTransfers.spaceId eq created.spaceId
            }.toList()
        }
        assertEquals(2, receipts.size)
    }

    @Test
    fun `no-op custody rejection leaves its operation id available for a real transfer`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("custody-no-op-creator"))
        val nextSteward = ctx.registerUser(uniqueUsername("custody-no-op-steward"))
        val space = ctx.documentService.createSpace(creator, "拒绝空交接空间", null)
        val operationId = UUID.randomUUID().toString()

        val rejection = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.transferSpaceCustody(
                actorUid = creator,
                spaceId = space.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = creator,
                stewardUid = creator,
                expectedCustodyRevision = space.custodyRevision,
                operationId = operationId,
            )
        }
        assertEquals("空间归属和责任人均未发生变化", rejection.message)
        assertEquals(0L, transaction(ctx.database) {
            DocumentSpaceCustodyTransfers.selectAll().where {
                DocumentSpaceCustodyTransfers.operationId eq operationId
            }.count()
        })

        val transferred = ctx.documentService.transferSpaceCustody(
            actorUid = creator,
            spaceId = space.spaceId,
            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
            ownerPrincipalId = nextSteward,
            stewardUid = nextSteward,
            expectedCustodyRevision = space.custodyRevision,
            operationId = operationId,
        )
        assertEquals(nextSteward, transferred.ownerPrincipalId)
        assertEquals(nextSteward, transferred.stewardUid)
        assertEquals(space.custodyRevision + 1, transferred.custodyRevision)
        assertEquals(1L, transaction(ctx.database) {
            DocumentSpaceCustodyTransfers.selectAll().where {
                DocumentSpaceCustodyTransfers.operationId eq operationId
            }.count()
        })

        assertEquals(
            transferred,
            ctx.documentService.transferSpaceCustody(
                actorUid = creator,
                spaceId = space.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = nextSteward,
                stewardUid = nextSteward,
                expectedCustodyRevision = space.custodyRevision,
                operationId = operationId,
            ),
            "已提交真实交接的相同 operationId 仍须精确重放不可变收据",
        )
    }

    @Test
    fun `organization archive observes custody transfer committed before its global fence`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("custody-archive-race-creator"))
        val steward = ctx.registerUser(uniqueUsername("custody-archive-race-steward"))
        val root = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, "交接并发测试组织", null)
        val targetUnit = ctx.organizationService.createUnit(root.unitId, "待归档资产部门", null)
        val space = ctx.documentService.createSpace(creator, "并发转入组织空间", null)

        val archiveReachedGlobalFence = CountDownLatch(1)
        val allowArchiveToLockOrganization = CountDownLatch(1)
        val racingRepository = ExposedOrganizationRepository(
            database = ctx.database,
            lockHooks = OrganizationLockHooks {
                archiveReachedGlobalFence.countDown()
                check(allowArchiveToLockOrganization.await(10, TimeUnit.SECONDS)) {
                    "test did not release organization archive after custody transfer"
                }
            },
        )
        val racingOrganizationService = OrganizationService(
            racingRepository,
            ctx.userRepo,
            ctx.pgUnitOfWork,
            ctx.organizationProjector,
            OrganizationChangePublisher { },
        )
        val archive = async(Dispatchers.IO) {
            runCatching { racingOrganizationService.archiveUnit(targetUnit.unitId) }
        }

        assertTrue(archiveReachedGlobalFence.await(10, TimeUnit.SECONDS))
        try {
            val transferred = ctx.documentService.transferSpaceCustody(
                actorUid = creator,
                spaceId = space.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                ownerPrincipalId = targetUnit.unitId,
                stewardUid = steward,
                expectedCustodyRevision = 1,
                operationId = UUID.randomUUID().toString(),
            )
            assertEquals(targetUnit.unitId, transferred.ownerPrincipalId)
        } finally {
            allowArchiveToLockOrganization.countDown()
        }

        val archiveFailure = withContext(Dispatchers.IO) {
            withTimeout(10_000) { archive.await() }
        }.exceptionOrNull()
        assertTrue(archiveFailure is OrganizationUnitArchiveConflictException)
        assertEquals(
            OrganizationUnit.STATUS_ACTIVE,
            ctx.organizationService.listUnits().single { it.unitId == targetUnit.unitId }.status,
        )
        assertEquals(
            targetUnit.unitId,
            ctx.documentService.listSpaces(steward).single { it.spaceId == space.spaceId }.ownerPrincipalId,
        )
    }

    @Test
    fun `unauthorized custody request is rejected before the global fence`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("custody-preflight-owner"))
        val stranger = ctx.registerUser(uniqueUsername("custody-preflight-stranger"))
        val space = ctx.documentService.createSpace(creator, "交接预检空间", null)
        var fenceLocks = 0
        val repository = object : com.virjar.tk.server.domain.document.DocumentRepository by ctx.documentRepo {
            override fun lockCustodyTransferFence(transaction: PgWriteTransactionContext) {
                fenceLocks += 1
                ctx.documentRepo.lockCustodyTransferFence(transaction)
            }
        }
        val service = DocumentService(repository, ctx.pgUnitOfWork)

        assertFailsWith<DocumentAccessDeniedException> {
            service.transferSpaceCustody(
                actorUid = stranger,
                spaceId = space.spaceId,
                ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                ownerPrincipalId = stranger,
                stewardUid = stranger,
                expectedCustodyRevision = 1,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertEquals(0, fenceLocks)
    }

    @Test
    fun `exact custody retry waits for the fence and replays the first immutable receipt`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("custody-fence-retry-creator"))
        val nextSteward = ctx.registerUser(uniqueUsername("custody-fence-retry-steward"))
        val space = ctx.documentService.createSpace(creator, "并发精确重试空间", null)
        val operationId = UUID.randomUUID().toString()
        val firstReadyToCommit = CountDownLatch(1)
        val releaseFirstCommit = CountDownLatch(1)
        val retryFinishedDomainBlock = CountDownLatch(1)

        val firstUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                    firstReadyToCommit.countDown()
                    check(releaseFirstCommit.await(10, TimeUnit.SECONDS)) {
                        "test did not release the first custody transaction"
                    }
                }
            },
        )
        val retryUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.BEFORE_EVENT_FLUSH) {
                    retryFinishedDomainBlock.countDown()
                }
            },
        )
        val firstService = DocumentService(ctx.documentRepo, firstUnitOfWork)
        val retryService = DocumentService(ctx.documentRepo, retryUnitOfWork)

        val first = async(Dispatchers.IO) {
            firstService.transferSpaceCustody(
                creator,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                nextSteward,
                nextSteward,
                1,
                operationId,
            )
        }
        assertTrue(firstReadyToCommit.await(10, TimeUnit.SECONDS))
        val retry = async(Dispatchers.IO) {
            retryService.transferSpaceCustody(
                creator,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                nextSteward,
                nextSteward,
                1,
                operationId,
            )
        }
        try {
            assertFalse(
                retryFinishedDomainBlock.await(250, TimeUnit.MILLISECONDS),
                "exact retry must wait for the first custody fence owner to commit",
            )
        } finally {
            releaseFirstCommit.countDown()
        }
        val (firstResult, retryResult) = withContext(Dispatchers.IO) {
            withTimeout(10_000) { first.await() to retry.await() }
        }
        assertEquals(firstResult, retryResult)
        assertTrue(retryFinishedDomainBlock.await(1, TimeUnit.SECONDS))
        assertEquals(1L, transaction(ctx.database) {
            DocumentSpaceCustodyTransfers.selectAll().where {
                DocumentSpaceCustodyTransfers.operationId eq operationId
            }.count()
        })
    }

    @Test
    fun `grant listing resolves principals in bounded queries and enforces the space ACL cap`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bounded-grant-owner"))
        val space = ctx.documentService.createSpace(owner, "有界授权空间", null)
        val userPrincipals = List(4) { index ->
            val username = uniqueUsername("bounded-grant-user-$index")
            ctx.registerUser(username) to username
        }
        val unitPrincipals = List(4) { index ->
            OrganizationUnit(UUID.randomUUID().toString(), name = "有界部门-$index").also {
                ctx.seedOrganizationUnit(it)
            }
        }
        userPrincipals.forEach { (uid, _) ->
            ctx.documentService.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                uid,
                DocumentSpace.ROLE_VIEWER,
                false,
                expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
                operationId = UUID.randomUUID().toString(),
            )
        }
        unitPrincipals.forEach { unit ->
            ctx.documentService.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                unit.unitId,
                DocumentSpace.ROLE_VIEWER,
                true,
                expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
                operationId = UUID.randomUUID().toString(),
            )
        }

        val countingUnitOfWork = StatementCountingUnitOfWork(ctx.pgUnitOfWork)
        val countingService = DocumentService(ctx.documentRepo, countingUnitOfWork)
        val resolved = countingService.listGrants(owner, space.spaceId)
        assertEquals(
            (userPrincipals.map { it.second } + unitPrincipals.map { it.name }).toSet(),
            resolved.mapNotNull(DocumentSpaceGrant::displayName).toSet(),
        )
        assertTrue(
            countingUnitOfWork.lastReadStatementCount <= 7,
            "grant list used ${countingUnitOfWork.lastReadStatementCount} SQL statements",
        )

        val existingGrantCount = resolved.size
        val fillerPrincipalIds = List(DocumentSpaceGrant.MAX_GRANTS_PER_SPACE - existingGrantCount) {
            UUID.randomUUID().toString()
        }
        transaction(ctx.database) {
            DocumentSpaceGrants.batchInsert(fillerPrincipalIds) { principalId ->
                this[DocumentSpaceGrants.spaceId] = space.spaceId
                this[DocumentSpaceGrants.principalType] = DocumentSpaceGrant.PRINCIPAL_USER
                this[DocumentSpaceGrants.principalId] = principalId
                this[DocumentSpaceGrants.role] = DocumentSpace.ROLE_VIEWER
                this[DocumentSpaceGrants.includeDescendants] = false
                this[DocumentSpaceGrants.updatedAt] = System.currentTimeMillis()
            }
        }
        assertEquals(
            DocumentSpaceGrant.MAX_GRANTS_PER_SPACE,
            ctx.readDocuments { listGrants(it, space.spaceId) }.size,
        )

        val overflowUser = ctx.registerUser(uniqueUsername("bounded-grant-overflow"))
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                overflowUser,
                DocumentSpace.ROLE_VIEWER,
                false,
                expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
                operationId = UUID.randomUUID().toString(),
            )
        }
        transaction(ctx.database) {
            DocumentSpaceGrants.insert {
                it[DocumentSpaceGrants.spaceId] = space.spaceId
                it[principalType] = DocumentSpaceGrant.PRINCIPAL_USER
                it[principalId] = overflowUser
                it[role] = DocumentSpace.ROLE_VIEWER
                it[includeDescendants] = false
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.readDocuments { listGrants(it, space.spaceId) }
        }
    }

    @Test
    fun `directory tree revisions restore inputs and destructive boundaries are enforced`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("tree-owner"))
        val space = ctx.documentService.createSpace(owner, "研发空间", null)
        val parent = ctx.documentService.createDocument(owner, space.spaceId, null, "架构", "# 架构综述")
        val child = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            parent.documentId,
            "客户端",
            "# 客户端综述",
        )
        val created = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            child.documentId,
            "状态模型",
            "# v1",
        )
        assertEquals(listOf(parent.documentId, child.documentId), created.ancestorIds)
        assertTrue(ctx.documentService.listNodes(owner, space.spaceId, null).single().hasChildren)
        assertTrue(ctx.documentService.listNodes(owner, space.spaceId, parent.documentId).single().hasChildren)
        assertTrue(!ctx.documentService.listNodes(owner, space.spaceId, child.documentId).single().hasChildren)

        val noOp = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            created.documentId,
            child.documentId,
            created.title,
            created.revision,
        )
        assertEquals(created.revision, noOp.node.revision)
        assertEquals(created.updatedAt, noOp.node.updatedAt)
        assertEquals(listOf(parent.documentId, child.documentId), noOp.ancestorIds)
        assertEquals(
            listOf(1L),
            ctx.documentService.listRevisions(owner, space.spaceId, created.documentId, 0, 100)
                .items.map { it.revision },
        )

        val moved = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            created.documentId,
            parent.documentId,
            created.title,
            noOp.node.revision,
        )
        assertEquals(2, moved.node.revision)
        assertEquals(listOf(parent.documentId), moved.ancestorIds)
        assertEquals(
            listOf(1L),
            ctx.documentService.listRevisions(owner, space.spaceId, created.documentId, 0, 100)
                .items.map { it.revision },
        )
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.getRevision(owner, space.spaceId, created.documentId, moved.node.revision)
        }
        assertEquals("# v1", ctx.documentService.getRevision(owner, space.spaceId, created.documentId, 1).markdown)

        val renamed = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            created.documentId,
            parent.documentId,
            "状态与同步",
            moved.node.revision,
        )
        assertEquals(3, renamed.node.revision)
        assertEquals(
            listOf(3L, 1L),
            ctx.documentService.listRevisions(owner, space.spaceId, created.documentId, 0, 100)
                .items.map { it.revision },
        )
        assertEquals(
            "状态与同步" to "# v1",
            ctx.documentService.getRevision(owner, space.spaceId, created.documentId, 3)
                .let { it.title to it.markdown },
        )
        assertFailsWith<DocumentRevisionConflictException> {
            ctx.documentService.moveNode(
                owner,
                space.spaceId,
                created.documentId,
                parent.documentId,
                renamed.node.name,
                moved.node.revision,
            )
        }

        val unchanged = ctx.documentService.updateDocument(
            owner,
            space.spaceId,
            created.documentId,
            "# v1",
            renamed.node.revision,
        )
        assertEquals(renamed.node.revision, unchanged.revision)
        assertEquals(listOf(parent.documentId), unchanged.ancestorIds)
        assertEquals(
            listOf(parent.documentId),
            ctx.documentService.getDocument(owner, space.spaceId, created.documentId).ancestorIds,
        )
        val restored = ctx.documentService.updateDocument(
            owner,
            space.spaceId,
            created.documentId,
            "# restored",
            unchanged.revision,
        )
        assertEquals(listOf(parent.documentId), restored.ancestorIds)
        val firstRevisionPage = ctx.documentService.listRevisions(
            owner,
            space.spaceId,
            created.documentId,
            beforeRevision = 0,
            limit = 2,
        )
        assertEquals(listOf(4L, 3L), firstRevisionPage.items.map { it.revision })
        assertEquals(listOf("# restored".length, "# v1".length), firstRevisionPage.items.map { it.contentLength })
        assertEquals(3L, firstRevisionPage.nextBeforeRevision)
        val finalRevisionPage = ctx.documentService.listRevisions(
            owner,
            space.spaceId,
            created.documentId,
            beforeRevision = firstRevisionPage.nextBeforeRevision,
            limit = 2,
        )
        assertEquals(listOf(1L), finalRevisionPage.items.map { it.revision })
        assertEquals(0L, finalRevisionPage.nextBeforeRevision)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.listRevisions(owner, space.spaceId, created.documentId, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.listRevisions(owner, space.spaceId, created.documentId, -1, 2)
        }

        val nested = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            created.documentId,
            "恢复策略",
            "# 子文档",
        )
        assertEquals(listOf(parent.documentId, created.documentId), nested.ancestorIds)
        val parentChildren = ctx.documentService.listNodes(owner, space.spaceId, parent.documentId)
        assertTrue(parentChildren.single { it.nodeId == created.documentId }.hasChildren)
        assertTrue(!parentChildren.single { it.nodeId == child.documentId }.hasChildren)

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.moveNode(
                owner,
                space.spaceId,
                parent.documentId,
                nested.documentId,
                parent.title,
                parent.revision,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.deleteNode(
                owner,
                space.spaceId,
                parent.documentId,
                parent.revision,
                UUID.randomUUID().toString(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                ctx.documentRepo.deleteNode(
                    transaction,
                    space.spaceId,
                    parent.documentId,
                    parent.revision,
                    UUID.randomUUID().toString(),
                    owner,
                    System.currentTimeMillis(),
                )
            }
        }
        val otherSpace = ctx.documentService.createSpace(owner, "其他空间", null)
        val otherParent = ctx.documentService.createDocument(owner, otherSpace.spaceId, null, "其他文档", "")
        assertFailsWith<DocumentHierarchyConflictException> {
            ctx.pgUnitOfWork.write {
                ctx.documentRepo.moveNode(
                    transaction,
                    space.spaceId,
                    child.documentId,
                    child.revision,
                    otherParent.documentId,
                    child.title,
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
                    parent.documentId,
                    parent.revision,
                    child.documentId,
                    parent.title,
                    owner,
                    System.currentTimeMillis(),
                )
            }
        }
        ctx.documentService.deleteNode(
            owner,
            space.spaceId,
            nested.documentId,
            nested.revision,
            UUID.randomUUID().toString(),
        )
        assertTrue(
            !ctx.documentService.listNodes(owner, space.spaceId, parent.documentId)
                .single { it.nodeId == created.documentId }
                .hasChildren,
        )
        assertEquals(
            listOf(parent.documentId),
            ctx.readDocuments { findDocument(it, space.spaceId, created.documentId) }!!.ancestorIds,
        )
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
            expectedPolicyRevision = inherited.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.upsertGrant(
            owner,
            directUnit.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            child.unitId,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = directUnit.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.upsertGrant(
            owner,
            directUser.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            member,
            DocumentSpace.ROLE_VIEWER,
            false,
            expectedPolicyRevision = directUser.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.upsertGrant(
            owner,
            ancestorWithoutInheritance.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            root.unitId,
            DocumentSpace.ROLE_VIEWER,
            false,
            expectedPolicyRevision = ancestorWithoutInheritance.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.upsertGrant(
            owner,
            unrelated.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            sibling.unitId,
            DocumentSpace.ROLE_VIEWER,
            true,
            expectedPolicyRevision = unrelated.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )

        val expectedIds = setOf(inherited.spaceId, directUnit.spaceId, directUser.spaceId)
        assertEquals(expectedIds, ctx.documentService.listSpaces(member).mapTo(mutableSetOf()) { it.spaceId })
        val candidates = ctx.readDocuments {
            readAccessibleSpacePage(
                transaction = it,
                actorUid = member,
                after = null,
                pageSize = DocumentSpacePage.MAX_PAGE_SIZE,
            ).snapshot.candidates
        }
        assertEquals(expectedIds, candidates.mapTo(mutableSetOf()) { it.space.spaceId })
        assertEquals(
            setOf(inherited.spaceId, directUnit.spaceId, directUser.spaceId, ancestorWithoutInheritance.spaceId, unrelated.spaceId),
            ctx.documentService.listSpaces(owner).mapTo(mutableSetOf()) { it.spaceId },
        )

        transaction(ctx.database) {
            OrganizationUnits.update({ OrganizationUnits.unitId eq root.unitId }) {
                it[OrganizationUnits.status] = OrganizationUnit.STATUS_ARCHIVED
            }
        }
        assertEquals(
            setOf(directUnit.spaceId, directUser.spaceId),
            ctx.documentService.listSpaces(member).mapTo(mutableSetOf()) { it.spaceId },
        )

        // membership 仍在，但已归档的直属部门不再是授权事实。
        transaction(ctx.database) {
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
            expectedPolicyRevision = directSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.upsertGrant(
            owner,
            inheritedSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            ancestor.unitId,
            DocumentSpace.ROLE_VIEWER,
            true,
            expectedPolicyRevision = inheritedSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(
            setOf(directSpace.spaceId, inheritedSpace.spaceId),
            ctx.documentService.listSpaces(member).mapTo(mutableSetOf()) { it.spaceId },
        )

        // 模拟并发管理写或历史脏数据造成 direct → ancestor → direct。这个测试类共享数据库，
        // 因此必须恢复故意注入的损坏，否则后续组织写入会正确地失败关闭。
        val originalParentId = transaction(ctx.database) {
            OrganizationUnits.selectAll()
                .where { OrganizationUnits.unitId eq ancestor.unitId }
                .single()[OrganizationUnits.parentId]
        }
        try {
            transaction(ctx.database) {
                OrganizationUnits.update({ OrganizationUnits.unitId eq ancestor.unitId }) {
                    it[OrganizationUnits.parentId] = direct.unitId
                }
            }
            assertEquals(listOf(directSpace.spaceId), ctx.documentService.listSpaces(member).map { it.spaceId })
        } finally {
            transaction(ctx.database) {
                OrganizationUnits.update({ OrganizationUnits.unitId eq ancestor.unitId }) {
                    it[OrganizationUnits.parentId] = originalParentId
                }
            }
        }
    }

    @Test
    fun `document tree accepts depth 128 and rejects depth 129 including moved subtrees`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("depth-owner"))
        val space = ctx.documentService.createSpace(owner, "深层文档空间", null)
        val chain = seedDocumentChain(space.spaceId, owner, Document.MAX_ANCESTOR_DEPTH)

        val boundaryDocument = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            chain.last(),
            "128 层文档",
            "# boundary",
        )
        assertEquals(Document.MAX_ANCESTOR_DEPTH, boundaryDocument.ancestorIds.size)

        assertFailsWith<IllegalArgumentException> {
            // boundaryDocument 已有 128 个祖先，仍可作为最深叶节点，但不能再拥有子文档。
            ctx.documentService.createDocument(
                owner,
                space.spaceId,
                boundaryDocument.documentId,
                "第 129 层后代",
                "# too deep",
            )
        }
        assertEquals(
            Document.MAX_ANCESTOR_DEPTH,
            ctx.documentService.getDocument(owner, space.spaceId, boundaryDocument.documentId).ancestorIds.size,
        )
        val (deepAncestorIds, deepReadStatementCount) = ctx.pgUnitOfWork.read {
            val exposed = transaction.requireExposedReadTransaction()
            val before = exposed.statementCount
            val document = requireNotNull(
                ctx.documentRepo.findDocument(transaction, space.spaceId, boundaryDocument.documentId),
            )
            document.ancestorIds to (exposed.statementCount - before)
        }
        assertEquals(Document.MAX_ANCESTOR_DEPTH, deepAncestorIds.size)
        assertEquals(
            3,
            deepReadStatementCount,
            "deep document read must remain one content SELECT, one ancestor CTE and one bounded asset manifest SELECT",
        )
        val (spine, spineStatementCount) = ctx.pgUnitOfWork.read {
            val exposed = transaction.requireExposedReadTransaction()
            val before = exposed.statementCount
            val result = ctx.documentRepo.findPathSpine(
                transaction,
                space.spaceId,
                boundaryDocument.documentId,
            )
            result to (exposed.statementCount - before)
        }
        assertEquals(Document.MAX_ANCESTOR_DEPTH + 1, spine.nodes.size)
        assertEquals(chain.first(), spine.nodes.first().nodeId)
        assertEquals(boundaryDocument.documentId, spine.targetNodeId)
        assertTrue(spine.nodes.dropLast(1).all { it.hasChildren })
        assertFalse(spine.nodes.last().hasChildren)
        assertEquals(1, spineStatementCount, "a 128-level spine must use one recursive SQL statement")
        assertEquals(
            spine,
            ctx.documentService.getNodePathSpine(owner, space.spaceId, boundaryDocument.documentId),
        )

        val otherSpace = ctx.documentService.createSpace(owner, "另一文档空间", null)
        assertFailsWith<DocumentNotFoundException> {
            ctx.documentService.getNodePathSpine(owner, otherSpace.spaceId, boundaryDocument.documentId)
        }

        val movingRoot = ctx.documentService.createDocument(owner, space.spaceId, null, "待移动子树", "# 根综述")
        val movingChild = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            movingRoot.documentId,
            "子文档",
            "# 子综述",
        )
        val movingDocument = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            movingChild.documentId,
            "子树文档",
            "# subtree",
        )
        val allowedParent = chain[Document.MAX_ANCESTOR_DEPTH - 3]
        val rejectedParent = chain[Document.MAX_ANCESTOR_DEPTH - 2]
        val moved = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            movingRoot.documentId,
            allowedParent,
            movingRoot.title,
            movingRoot.revision,
        )
        assertEquals(
            listOf(1L),
            ctx.documentService.listRevisions(owner, space.spaceId, movingRoot.documentId, 0, 100)
                .items.map { it.revision },
        )
        assertEquals(
            Document.MAX_ANCESTOR_DEPTH,
            ctx.documentService.getDocument(owner, space.spaceId, movingDocument.documentId).ancestorIds.size,
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.moveNode(
                owner,
                space.spaceId,
                movingRoot.documentId,
                rejectedParent,
                moved.node.name,
                moved.node.revision,
            )
        }
        assertEquals(
            Document.MAX_ANCESTOR_DEPTH,
            ctx.documentService.getDocument(owner, space.spaceId, movingDocument.documentId).ancestorIds.size,
        )
    }

    @Test
    fun `wide subtree move keeps SQL statement count constant`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("wide-move-owner"))
        val space = ctx.documentService.createSpace(owner, "宽子树空间", null)
        val targetParent = ctx.documentService.createDocument(owner, space.spaceId, null, "目标目录", "")
        val movingRoot = ctx.documentService.createDocument(owner, space.spaceId, null, "宽子树", "")
        val childIds = List(501) { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            DocumentNodes.batchInsert(childIds) { childId ->
                this[DocumentNodes.nodeId] = childId
                this[DocumentNodes.creationFingerprint] = "0".repeat(64)
                this[DocumentNodes.spaceId] = space.spaceId
                this[DocumentNodes.parentId] = movingRoot.documentId
                this[DocumentNodes.name] = "宽子节点-$childId"
                this[DocumentNodes.excerpt] = ""
                this[DocumentNodes.markdown] = ""
                this[DocumentNodes.revision] = 1
                this[DocumentNodes.status] = 1
                this[DocumentNodes.createdBy] = owner
                this[DocumentNodes.createdAt] = now
                this[DocumentNodes.updatedBy] = owner
                this[DocumentNodes.updatedAt] = now
            }
        }

        val countingUnitOfWork = StatementCountingUnitOfWork(ctx.pgUnitOfWork)
        val moved = DocumentService(ctx.documentRepo, countingUnitOfWork).moveNode(
            owner,
            space.spaceId,
            movingRoot.documentId,
            targetParent.documentId,
            movingRoot.title,
            movingRoot.revision,
        )

        assertEquals(listOf(targetParent.documentId), moved.ancestorIds)
        assertTrue(
            // 可靠准入会增加固定的 actor/space 门禁、回执查找/保留工作以及
            // 回执插入。这些语句都不依赖子树宽度。
            countingUnitOfWork.lastWriteStatementCount <= 18,
            "wide move used ${countingUnitOfWork.lastWriteStatementCount} SQL statements",
        )
        assertEquals(
            listOf(targetParent.documentId, movingRoot.documentId),
            ctx.documentService.getDocument(owner, space.spaceId, childIds.first()).ancestorIds,
        )
    }

    @Test
    fun `repository serializes conflicting moves and delete versus create`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("tree-race-owner"))
        val space = ctx.documentService.createSpace(owner, "文档树并发空间", null)
        val left = ctx.documentService.createDocument(owner, space.spaceId, null, "A", "# A")
        val right = ctx.documentService.createDocument(owner, space.spaceId, null, "B", "# B")

        val moveResults = coroutineScope {
            listOf(left to right, right to left).map { (moving, target) ->
                async(Dispatchers.Default) {
                    runCatching {
                        ctx.pgUnitOfWork.write {
                            ctx.documentRepo.moveNode(
                                transaction,
                                space.spaceId,
                                moving.documentId,
                                moving.revision,
                                target.documentId,
                                moving.title,
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
        val currentLeft = ctx.readDocuments { findNode(it, space.spaceId, left.documentId) }!!
        val currentRight = ctx.readDocuments { findNode(it, space.spaceId, right.documentId) }!!
        assertTrue(
            (currentLeft.parentId == currentRight.nodeId) xor (currentRight.parentId == currentLeft.nodeId),
        )
        val movedDocumentId = if (currentLeft.parentId != null) left.documentId else right.documentId
        assertEquals(
            listOf(1L),
            ctx.readDocuments { listRevisions(it, movedDocumentId, beforeRevision = 0, limit = 100) }
                .map { it.revision },
        )

        val emptyParent = ctx.documentService.createDocument(owner, space.spaceId, null, "空父文档", "")
        val (createResult, deleteResult) = coroutineScope {
            val create = async(Dispatchers.Default) {
                runCatching {
                    ctx.documentService.createDocument(
                        owner,
                        space.spaceId,
                        emptyParent.documentId,
                        "并发子文档",
                        "",
                    )
                }
            }
            val delete = async(Dispatchers.Default) {
                runCatching {
                    ctx.documentService.deleteNode(
                        owner,
                        space.spaceId,
                        emptyParent.documentId,
                        emptyParent.revision,
                        UUID.randomUUID().toString(),
                    )
                }
            }
            create.await() to delete.await()
        }
        assertEquals(1, listOf(createResult.isSuccess, deleteResult.isSuccess).count { it })
        if (createResult.isSuccess) {
            assertTrue(ctx.readDocuments { findNode(it, space.spaceId, emptyParent.documentId) } != null)
            assertTrue(ctx.readDocuments { findNode(it, space.spaceId, createResult.getOrThrow().documentId) } != null)
        } else {
            assertTrue(deleteResult.isSuccess)
            assertTrue(ctx.readDocuments { findNode(it, space.spaceId, emptyParent.documentId) } == null)
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
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
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
                            "并发版本 ${index + 1}",
                            created.revision,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is DocumentRevisionConflictException)
        val current = ctx.documentService.getDocument(owner, space.spaceId, created.documentId)
        assertEquals(2, current.revision)
        assertEquals(
            2,
            ctx.readDocuments {
                listRevisions(it, created.documentId, beforeRevision = 0, limit = 100)
            }.size,
        )
    }

    @Test
    fun `content save and parent-only move compete for the same aggregate revision`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-mutation-race-owner"))
        val space = ctx.documentService.createSpace(owner, "文档变更竞态空间", null)
        val parent = ctx.documentService.createDocument(owner, space.spaceId, null, "目标父文档", "")
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "竞态文档", "# v1")

        val (save, move) = coroutineScope {
            val saving = async(Dispatchers.Default) {
                runCatching {
                    ctx.documentService.updateDocument(
                        owner,
                        space.spaceId,
                        document.documentId,
                        "# v2",
                        document.revision,
                    )
                }
            }
            val moving = async(Dispatchers.Default) {
                runCatching {
                    ctx.documentService.moveNode(
                        owner,
                        space.spaceId,
                        document.documentId,
                        parent.documentId,
                        document.title,
                        document.revision,
                    )
                }
            }
            saving.await() to moving.await()
        }

        assertEquals(1, listOf(save.isSuccess, move.isSuccess).count { it })
        assertTrue(
            listOf(save.exceptionOrNull(), move.exceptionOrNull())
                .filterNotNull()
                .single() is DocumentRevisionConflictException,
        )
        val current = ctx.documentService.getDocument(owner, space.spaceId, document.documentId)
        assertEquals(2L, current.revision)
        val revisions = ctx.documentService.listRevisions(
            owner,
            space.spaceId,
            document.documentId,
            beforeRevision = 0,
            limit = 100,
        ).items.map { it.revision }
        if (save.isSuccess) {
            assertEquals("# v2", current.markdown)
            assertEquals(null, current.parentId)
            assertEquals(listOf(2L, 1L), revisions)
        } else {
            assertEquals("# v1", current.markdown)
            assertEquals(parent.documentId, current.parentId)
            assertEquals(listOf(1L), revisions)
        }
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
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
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
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(firstSpace.spaceId),
            operationId = UUID.randomUUID().toString(),
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
    private fun seedDocumentChain(spaceId: String, actorUid: String, count: Int): List<String> {
        val nodeIds = List(count) { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            nodeIds.forEachIndexed { index, id ->
                val markdown = "# 边界文档-$index"
                DocumentNodes.insert {
                    it[nodeId] = id
                    it[creationFingerprint] = "0".repeat(64)
                    it[DocumentNodes.spaceId] = spaceId
                    it[parentId] = nodeIds.getOrNull(index - 1)
                    it[name] = "边界文档-$index"
                    it[excerpt] = "边界文档-$index"
                    it[DocumentNodes.markdown] = markdown
                    it[revision] = 1
                    it[status] = 1
                    it[createdBy] = actorUid
                    it[createdAt] = now + index
                    it[updatedBy] = actorUid
                    it[updatedAt] = now + index
                }
                DocumentContentRevisions.insert {
                    it[DocumentContentRevisions.documentId] = id
                    it[DocumentContentRevisions.revision] = 1
                    it[DocumentContentRevisions.title] = "边界文档-$index"
                    it[DocumentContentRevisions.markdown] = markdown
                    it[DocumentContentRevisions.contentLength] = markdown.length
                    it[editedBy] = actorUid
                    it[editedAt] = now + index
                }
            }
        }
        return nodeIds
    }

    private class StatementCountingUnitOfWork(
        private val delegate: PgUnitOfWork,
    ) : PgUnitOfWork {
        var lastReadStatementCount: Int = 0
            private set
        var lastWriteStatementCount: Int = 0
            private set

        override suspend fun <T> read(block: PgReadScope.() -> T): T = delegate.read {
            val exposed = transaction.requireExposedReadTransaction()
            val before = exposed.statementCount
            try {
                block(this)
            } finally {
                lastReadStatementCount = exposed.statementCount - before
            }
        }

        override suspend fun <T> write(block: PgWriteScope.() -> T): T = delegate.write {
            val exposed = transaction.requireExposedTransaction()
            val before = exposed.statementCount
            try {
                block(this)
            } finally {
                lastWriteStatementCount = exposed.statementCount - before
            }
        }
    }
}
