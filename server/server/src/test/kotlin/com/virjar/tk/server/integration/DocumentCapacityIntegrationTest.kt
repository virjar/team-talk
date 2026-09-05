package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentCapacityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `steward row lock bounds cross-owner responsibility and exact replay consumes no slot`() = runTest {
        val targetSteward = ctx.registerUser(uniqueUsername("document-steward-cap-target"))
        val firstSource = ctx.registerUser(uniqueUsername("document-steward-cap-source-a"))
        val secondSource = ctx.registerUser(uniqueUsername("document-steward-cap-source-b"))
        seedActiveStewardships(
            targetSteward,
            DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER - 1,
        )
        val root = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, "责任人容量组织", null)
        val targetOwner = ctx.organizationService.createUnit(root.unitId, "责任人容量目标部门", null)
        val firstSpace = ctx.documentService.createSpace(firstSource, "责任人末槽", null)
        val secondSpace = ctx.documentService.createSpace(secondSource, "责任人越界", null)
        val operationId = UUID.randomUUID().toString()

        val transferred = ctx.documentService.transferSpaceCustody(
            firstSource,
            firstSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            targetOwner.unitId,
            targetSteward,
            firstSpace.custodyRevision,
            operationId,
        )
        assertEquals(
            transferred,
            ctx.documentService.transferSpaceCustody(
                firstSource,
                firstSpace.spaceId,
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                targetOwner.unitId,
                targetSteward,
                firstSpace.custodyRevision,
                operationId,
            ),
            "exact replay must return before a second capacity charge",
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.transferSpaceCustody(
                secondSource,
                secondSpace.spaceId,
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
                targetOwner.unitId,
                targetSteward,
                secondSpace.custodyRevision,
                UUID.randomUUID().toString(),
            )
        }
        assertEquals(
            DocumentCapacityPolicy.MAX_ACTIVE_STEWARDSHIPS_PER_USER.toLong(),
            transaction(ctx.database) {
                DocumentSpaces.selectAll().where {
                    (DocumentSpaces.stewardUid eq targetSteward) and (DocumentSpaces.status eq 1)
                }.count()
            },
        )
    }

    @Test
    fun `direct user grant cap admits an existing update but rejects a new cross-space slot`() = runTest {
        val principal = ctx.registerUser(uniqueUsername("document-grant-cap-principal"))
        val firstOwner = ctx.registerUser(uniqueUsername("document-grant-cap-owner-a"))
        val secondOwner = ctx.registerUser(uniqueUsername("document-grant-cap-owner-b"))
        val firstSpace = ctx.documentService.createSpace(firstOwner, "既有授权空间", null)
        val secondSpace = ctx.documentService.createSpace(secondOwner, "新增授权空间", null)
        val initialGrant = ctx.documentService.upsertGrant(
            firstOwner,
            firstSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            principal,
            com.virjar.tk.protocol.model.DocumentSpace.ROLE_VIEWER,
            false,
            expectedPolicyRevision = firstSpace.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        seedArchivedDirectUserGrants(
            ownerUid = firstOwner,
            principalUid = principal,
            count = DocumentCapacityPolicy.MAX_DIRECT_DOCUMENT_GRANTS_PER_USER - 1,
        )

        val updated = ctx.documentService.upsertGrant(
            firstOwner,
            firstSpace.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            principal,
            com.virjar.tk.protocol.model.DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = initialGrant.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        assertEquals(initialGrant.policyRevision + 1L, updated.policyRevision)
        assertEquals(
            com.virjar.tk.protocol.model.DocumentSpace.ROLE_EDITOR,
            ctx.documentService.listGrants(firstOwner, firstSpace.spaceId).single().role,
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.upsertGrant(
                secondOwner,
                secondSpace.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                principal,
                com.virjar.tk.protocol.model.DocumentSpace.ROLE_VIEWER,
                false,
                expectedPolicyRevision = secondSpace.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
    }

    @Test
    fun `target owner row lock admits exactly one concurrent final custody transfer`() = runTest {
        val targetOwner = ctx.registerUser(uniqueUsername("document-transfer-cap-target"))
        val sourceOwners = List(2) { index ->
            ctx.registerUser(uniqueUsername("document-transfer-cap-source-$index"))
        }
        seedActiveSpaces(
            targetOwner,
            List(DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER - 1) { UUID.randomUUID().toString() },
        )
        val sourceSpaces = sourceOwners.mapIndexed { index, owner ->
            ctx.documentService.createSpace(owner, "待转入空间-$index", null)
        }

        val results = coroutineScope {
            sourceOwners.zip(sourceSpaces).map { (owner, space) ->
                async(Dispatchers.Default) {
                    runCatching {
                        ctx.documentService.transferSpaceCustody(
                            actorUid = owner,
                            spaceId = space.spaceId,
                            ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                            ownerPrincipalId = targetOwner,
                            stewardUid = targetOwner,
                            expectedCustodyRevision = 1,
                            operationId = UUID.randomUUID().toString(),
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is IllegalArgumentException)
        val ownedCount = transaction(ctx.database) {
            DocumentSpaces.selectAll().where {
                (DocumentSpaces.ownerPrincipalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                    (DocumentSpaces.ownerPrincipalId eq targetOwner) and
                    (DocumentSpaces.status eq 1)
            }.count()
        }
        assertEquals(DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER.toLong(), ownedCount)
    }

    @Test
    fun `owner row lock admits exactly one concurrent final active space`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-space-cap-owner"))
        val seededSpaceIds = List(DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER - 1) {
            UUID.randomUUID().toString()
        }
        seedActiveSpaces(owner, seededSpaceIds)

        val candidates = List(2) { index ->
            SpaceCandidate(UUID.randomUUID().toString(), "并发末槽-$index")
        }
        val results = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.Default) {
                    candidate to runCatching {
                        ctx.documentService.createSpace(
                            owner,
                            candidate.spaceId,
                            candidate.name,
                            null,
                        )
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.second.isSuccess })
        assertEquals(1, results.count { it.second.isFailure })
        assertTrue(
            results.single { it.second.isFailure }.second.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(
            DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER,
            ctx.documentService.listSpaces(owner).size,
        )

        // 幂等投递在应用当前配额之前先检查既有回执。
        val winner = results.single { it.second.isSuccess }.first
        assertEquals(
            winner.spaceId,
            ctx.documentService.createSpace(owner, winner.spaceId, winner.name, null).spaceId,
        )

        val overflowId = UUID.randomUUID().toString()
        seedActiveSpaces(owner, listOf(overflowId))
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.listSpaces(owner)
        }
        transaction(ctx.database) {
            DocumentSpaces.update({ DocumentSpaces.spaceId eq overflowId }) {
                it[status] = 0
            }
        }

        ctx.documentService.archiveSpace(owner, seededSpaceIds.first(), UUID.randomUUID().toString())
        ctx.documentService.createSpace(
            owner,
            UUID.randomUUID().toString(),
            "归档后释放的空间槽",
            null,
        )
        assertEquals(
            DocumentCapacityPolicy.MAX_ACTIVE_SPACES_PER_OWNER,
            ctx.documentService.listSpaces(owner).size,
        )
    }

    @Test
    fun `space lock admits one final child and move charges only a different parent`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-child-cap-owner"))
        val space = ctx.documentService.createSpace(owner, "直接子文档容量", null)
        val parent = ctx.documentService.createDocument(owner, space.spaceId, null, "父文档", "")
        val moving = ctx.documentService.createDocument(owner, space.spaceId, null, "待移动文档", "")
        val seededChildIds = List(DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT - 1) {
            UUID.randomUUID().toString()
        }
        seedActiveDocuments(
            spaceId = space.spaceId,
            ownerUid = owner,
            rows = seededChildIds.mapIndexed { index, nodeId ->
                SeedDocument(nodeId, parent.documentId, "既有子文档-$index")
            },
        )

        val candidates = List(2) { index ->
            DocumentCandidate(UUID.randomUUID().toString(), "并发末槽子文档-$index")
        }
        val results = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.Default) {
                    candidate to runCatching {
                        ctx.documentService.createDocument(
                            owner,
                            candidate.documentId,
                            space.spaceId,
                            parent.documentId,
                            candidate.title,
                            "",
                        )
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, results.count { it.second.isSuccess })
        assertEquals(1, results.count { it.second.isFailure })
        assertTrue(
            results.single { it.second.isFailure }.second.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(
            DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT,
            ctx.documentService.listNodes(owner, space.spaceId, parent.documentId).size,
        )

        val winner = results.single { it.second.isSuccess }.first
        assertEquals(
            winner.documentId,
            ctx.documentService.createDocument(
                owner,
                winner.documentId,
                space.spaceId,
                parent.documentId,
                winner.title,
                "",
            ).documentId,
        )

        val renamed = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            seededChildIds.first(),
            parent.documentId,
            "同层满额仍可改名",
            expectedRevision = 1,
        )
        assertEquals(2, renamed.node.revision)
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.moveNode(
                owner,
                space.spaceId,
                moving.documentId,
                parent.documentId,
                moving.title,
                moving.revision,
            )
        }

        val overflowChildId = UUID.randomUUID().toString()
        seedActiveDocuments(
            space.spaceId,
            owner,
            listOf(SeedDocument(overflowChildId, parent.documentId, "越界历史行")),
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.listNodes(owner, space.spaceId, parent.documentId)
        }
        transaction(ctx.database) {
            DocumentNodes.update({ DocumentNodes.nodeId eq overflowChildId }) {
                it[status] = 0
            }
        }

        ctx.documentService.deleteNode(
            owner,
            space.spaceId,
            renamed.node.nodeId,
            renamed.node.revision,
            UUID.randomUUID().toString(),
        )
        val moved = ctx.documentService.moveNode(
            owner,
            space.spaceId,
            moving.documentId,
            parent.documentId,
            moving.title,
            moving.revision,
        )
        assertEquals(parent.documentId, moved.node.parentId)
        assertEquals(
            DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT,
            ctx.documentService.listNodes(owner, space.spaceId, parent.documentId).size,
        )

        val additionalRootIds = List(DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT - 1) {
            UUID.randomUUID().toString()
        }
        seedActiveDocuments(
            space.spaceId,
            owner,
            additionalRootIds.mapIndexed { index, nodeId ->
                SeedDocument(nodeId, null, "根级容量文档-$index")
            },
        )
        assertEquals(
            DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT,
            ctx.documentService.listNodes(owner, space.spaceId, null).size,
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(owner, space.spaceId, null, "根级越界文档", "")
        }
    }

    @Test
    fun `space page quota counts only active documents`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-page-cap-owner"))
        val space = ctx.documentService.createSpace(owner, "空间文档总量", null)
        val rootIds = List(20) { UUID.randomUUID().toString() }
        val childIds = List(DocumentCapacityPolicy.MAX_ACTIVE_DOCUMENTS_PER_SPACE - rootIds.size) {
            UUID.randomUUID().toString()
        }
        seedActiveDocuments(
            spaceId = space.spaceId,
            ownerUid = owner,
            rows = rootIds.mapIndexed { index, nodeId ->
                SeedDocument(nodeId, null, "容量根文档-$index")
            } + childIds.mapIndexed { index, nodeId ->
                SeedDocument(nodeId, rootIds[index % rootIds.size], "容量子文档-$index")
            },
        )

        assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createDocument(
                owner,
                space.spaceId,
                rootIds.first(),
                "总量越界文档",
                "",
            )
        }

        ctx.documentService.deleteNode(
            owner,
            space.spaceId,
            childIds.first(),
            expectedRevision = 1,
            operationId = UUID.randomUUID().toString(),
        )
        ctx.documentService.createDocument(
            owner,
            space.spaceId,
            rootIds.first(),
            "删除后复用活动槽",
            "",
        )

        val activeCount = transaction(ctx.database) {
            DocumentNodes.selectAll().where {
                (DocumentNodes.spaceId eq space.spaceId) and (DocumentNodes.status eq 1)
            }.count()
        }
        assertEquals(DocumentCapacityPolicy.MAX_ACTIVE_DOCUMENTS_PER_SPACE.toLong(), activeCount)
        assertTrue(
            ctx.documentService.listNodes(owner, space.spaceId, rootIds.first()).size <=
                DocumentCapacityPolicy.MAX_ACTIVE_CHILDREN_PER_PARENT,
        )
    }

    private fun seedActiveSpaces(ownerUid: String, spaceIds: List<String>) {
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            DocumentSpaces.batchInsert(spaceIds, shouldReturnGeneratedValues = false) { id ->
                this[DocumentSpaces.spaceId] = id
                this[DocumentSpaces.creationFingerprint] = "0".repeat(64)
                this[DocumentSpaces.name] = "容量空间-$id"
                this[DocumentSpaces.description] = null
                this[DocumentSpaces.status] = 1
                this[DocumentSpaces.createdBy] = ownerUid
                this[DocumentSpaces.ownerPrincipalType] = DocumentSpaceGrant.PRINCIPAL_USER
                this[DocumentSpaces.ownerPrincipalId] = ownerUid
                this[DocumentSpaces.stewardUid] = ownerUid
                this[DocumentSpaces.custodyRevision] = 1
                this[DocumentSpaces.createdAt] = now
                this[DocumentSpaces.updatedAt] = now
            }
        }
    }

    private fun seedActiveStewardships(stewardUid: String, count: Int) {
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            DocumentSpaces.batchInsert(List(count) { UUID.randomUUID().toString() }, false) { id ->
                this[DocumentSpaces.spaceId] = id
                this[DocumentSpaces.creationFingerprint] = "1".repeat(64)
                this[DocumentSpaces.name] = "跨归属责任空间-$id"
                this[DocumentSpaces.description] = null
                this[DocumentSpaces.status] = 1
                this[DocumentSpaces.createdBy] = stewardUid
                this[DocumentSpaces.ownerPrincipalType] = DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT
                this[DocumentSpaces.ownerPrincipalId] = UUID.randomUUID().toString()
                this[DocumentSpaces.stewardUid] = stewardUid
                this[DocumentSpaces.custodyRevision] = 1
                this[DocumentSpaces.createdAt] = now
                this[DocumentSpaces.updatedAt] = now
            }
        }
    }

    private fun seedArchivedDirectUserGrants(ownerUid: String, principalUid: String, count: Int) {
        val now = System.currentTimeMillis()
        val spaceIds = List(count) { UUID.randomUUID().toString() }
        transaction(ctx.database) {
            DocumentSpaces.batchInsert(spaceIds, shouldReturnGeneratedValues = false) { id ->
                this[DocumentSpaces.spaceId] = id
                this[DocumentSpaces.creationFingerprint] = "2".repeat(64)
                this[DocumentSpaces.name] = "归档授权空间-$id"
                this[DocumentSpaces.description] = null
                this[DocumentSpaces.status] = 0
                this[DocumentSpaces.createdBy] = ownerUid
                this[DocumentSpaces.ownerPrincipalType] = DocumentSpaceGrant.PRINCIPAL_USER
                this[DocumentSpaces.ownerPrincipalId] = ownerUid
                this[DocumentSpaces.stewardUid] = ownerUid
                this[DocumentSpaces.custodyRevision] = 1
                this[DocumentSpaces.createdAt] = now
                this[DocumentSpaces.updatedAt] = now
            }
            DocumentSpaceGrants.batchInsert(spaceIds, shouldReturnGeneratedValues = false) { spaceId ->
                this[DocumentSpaceGrants.spaceId] = spaceId
                this[DocumentSpaceGrants.principalType] = DocumentSpaceGrant.PRINCIPAL_USER
                this[DocumentSpaceGrants.principalId] = principalUid
                this[DocumentSpaceGrants.role] = com.virjar.tk.protocol.model.DocumentSpace.ROLE_VIEWER
                this[DocumentSpaceGrants.includeDescendants] = false
                this[DocumentSpaceGrants.updatedAt] = now
            }
        }
    }

    private fun seedActiveDocuments(
        spaceId: String,
        ownerUid: String,
        rows: List<SeedDocument>,
    ) {
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            DocumentNodes.batchInsert(rows, shouldReturnGeneratedValues = false) { row ->
                this[DocumentNodes.nodeId] = row.nodeId
                this[DocumentNodes.creationFingerprint] = "0".repeat(64)
                this[DocumentNodes.spaceId] = spaceId
                this[DocumentNodes.parentId] = row.parentId
                this[DocumentNodes.name] = row.name
                this[DocumentNodes.excerpt] = ""
                this[DocumentNodes.markdown] = ""
                this[DocumentNodes.revision] = 1
                this[DocumentNodes.status] = 1
                this[DocumentNodes.createdBy] = ownerUid
                this[DocumentNodes.createdAt] = now
                this[DocumentNodes.updatedBy] = ownerUid
                this[DocumentNodes.updatedAt] = now
            }
        }
    }

    private data class SpaceCandidate(val spaceId: String, val name: String)
    private data class DocumentCandidate(val documentId: String, val title: String)
    private data class SeedDocument(val nodeId: String, val parentId: String?, val name: String)
}
