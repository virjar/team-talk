package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentReadAccessSnapshot
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentReadAccessIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `deep actor path is resolved by one recursive statement`() = runTest {
        val actorUid = ctx.registerUser(uniqueUsername("document-deep-access"))
        val unitIds = List(128) { UUID.randomUUID().toString() }
        seedOrganizationUnits(
            unitIds.mapIndexed { index, unitId ->
                OrganizationUnit(
                    unitId = unitId,
                    parentId = unitIds.getOrNull(index - 1),
                    name = "深层组织-$index",
                )
            },
        )
        seedMemberships(actorUid, listOf(unitIds.last()))

        val (snapshot, statements) = readAccessSnapshotWithStatements(actorUid)

        assertEquals(setOf(unitIds.last()), snapshot.directUnitIds)
        assertEquals(unitIds.toSet(), snapshot.unitAndAncestorIds)
        assertEquals(unitIds.sorted(), snapshot.unitAndAncestorIds.toList())
        assertBoundedAccessStatements(statements)
    }

    @Test
    fun `wide unrelated organization branches never enter actor access result`() = runTest {
        val actorUid = ctx.registerUser(uniqueUsername("document-wide-access"))
        val relevantRootId = UUID.randomUUID().toString()
        val relevantLeafId = UUID.randomUUID().toString()
        val unrelatedRootId = UUID.randomUUID().toString()
        val unrelatedUnitIds = List(1_000) { UUID.randomUUID().toString() }
        seedOrganizationUnits(
            listOf(
                OrganizationUnit(relevantRootId, name = "相关根节点"),
                OrganizationUnit(relevantLeafId, relevantRootId, "相关直属节点"),
                OrganizationUnit(unrelatedRootId, name = "无关根节点"),
            ) + unrelatedUnitIds.mapIndexed { index, unitId ->
                OrganizationUnit(unitId, unrelatedRootId, "无关宽节点-$index")
            },
        )
        seedMemberships(actorUid, listOf(relevantLeafId))

        val (snapshot, statements) = readAccessSnapshotWithStatements(actorUid)

        assertEquals(setOf(relevantLeafId), snapshot.directUnitIds)
        assertEquals(setOf(relevantLeafId, relevantRootId), snapshot.unitAndAncestorIds)
        assertTrue(unrelatedUnitIds.none(snapshot.unitAndAncestorIds::contains))
        assertBoundedAccessStatements(statements)
    }

    @Test
    fun `multiple direct memberships deduplicate ancestors and preserve every ACL form`() = runTest {
        val ownerUid = ctx.registerUser(uniqueUsername("document-multi-owner"))
        val actorUid = ctx.registerUser(uniqueUsername("document-multi-actor"))
        val sharedRootId = UUID.randomUUID().toString()
        val firstDirectId = UUID.randomUUID().toString()
        val secondDirectId = UUID.randomUUID().toString()
        val unrelatedId = UUID.randomUUID().toString()
        seedOrganizationUnits(
            listOf(
                OrganizationUnit(sharedRootId, name = "共享祖先"),
                OrganizationUnit(firstDirectId, sharedRootId, "第一直属部门"),
                OrganizationUnit(secondDirectId, sharedRootId, "第二直属部门"),
                OrganizationUnit(unrelatedId, name = "无关部门"),
            ),
        )
        // 反向插入顺序，以证明结果排序不依赖成员关系写入。
        seedMemberships(actorUid, listOf(secondDirectId, firstDirectId))

        val inheritedSpace = ctx.documentService.createSpace(ownerUid, "继承部门授权", null)
        val firstDirectSpace = ctx.documentService.createSpace(ownerUid, "第一直属授权", null)
        val secondDirectSpace = ctx.documentService.createSpace(ownerUid, "第二直属授权", null)
        val userSpace = ctx.documentService.createSpace(ownerUid, "用户授权", null)
        val nonInheritedAncestorSpace = ctx.documentService.createSpace(ownerUid, "祖先非继承授权", null)
        val unrelatedSpace = ctx.documentService.createSpace(ownerUid, "无关部门授权", null)
        val archivedSpace = ctx.documentService.createSpace(ownerUid, "已归档用户授权", null)
        grantUnit(ownerUid, inheritedSpace.spaceId, sharedRootId, includeDescendants = true)
        grantUnit(ownerUid, firstDirectSpace.spaceId, firstDirectId, includeDescendants = false)
        grantUnit(ownerUid, secondDirectSpace.spaceId, secondDirectId, includeDescendants = false)
        grantUser(ownerUid, userSpace.spaceId, actorUid)
        grantUnit(
            ownerUid,
            nonInheritedAncestorSpace.spaceId,
            sharedRootId,
            includeDescendants = false,
        )
        grantUnit(ownerUid, unrelatedSpace.spaceId, unrelatedId, includeDescendants = true)
        grantUser(ownerUid, archivedSpace.spaceId, actorUid)
        ctx.documentService.archiveSpace(ownerUid, archivedSpace.spaceId, UUID.randomUUID().toString())

        val snapshot = readAccessSnapshot(actorUid)
        val expectedDirectIds = setOf(firstDirectId, secondDirectId)
        val expectedAllIds = expectedDirectIds + sharedRootId
        assertEquals(expectedDirectIds, snapshot.directUnitIds)
        assertEquals(expectedDirectIds.sorted(), snapshot.directUnitIds.toList())
        assertEquals(expectedAllIds, snapshot.unitAndAncestorIds)
        assertEquals(expectedAllIds.sorted(), snapshot.unitAndAncestorIds.toList())

        assertEquals(
            setOf(
                inheritedSpace.spaceId,
                firstDirectSpace.spaceId,
                secondDirectSpace.spaceId,
                userSpace.spaceId,
            ),
            ctx.documentService.listSpaces(actorUid).mapTo(mutableSetOf()) { it.spaceId },
        )
    }

    @Test
    fun `archived ancestor breaks inherited ACL while direct and user grants remain`() = runTest {
        val ownerUid = ctx.registerUser(uniqueUsername("document-archived-owner"))
        val actorUid = ctx.registerUser(uniqueUsername("document-archived-actor"))
        val rootId = UUID.randomUUID().toString()
        val archivedAncestorId = UUID.randomUUID().toString()
        val directId = UUID.randomUUID().toString()
        seedOrganizationUnits(
            listOf(
                OrganizationUnit(rootId, name = "活动顶层"),
                OrganizationUnit(archivedAncestorId, rootId, "稍后归档的祖先"),
                OrganizationUnit(directId, archivedAncestorId, "活动直属节点"),
            ),
        )
        seedMemberships(actorUid, listOf(directId))

        val directSpace = ctx.documentService.createSpace(ownerUid, "归档链直属授权", null)
        val archivedAncestorSpace = ctx.documentService.createSpace(ownerUid, "归档祖先授权", null)
        val rootSpace = ctx.documentService.createSpace(ownerUid, "归档链顶层授权", null)
        val userSpace = ctx.documentService.createSpace(ownerUid, "归档链用户授权", null)
        grantUnit(ownerUid, directSpace.spaceId, directId, includeDescendants = false)
        grantUnit(ownerUid, archivedAncestorSpace.spaceId, archivedAncestorId, includeDescendants = true)
        grantUnit(ownerUid, rootSpace.spaceId, rootId, includeDescendants = true)
        grantUser(ownerUid, userSpace.spaceId, actorUid)

        transaction(ctx.database) {
            OrganizationUnits.update({ OrganizationUnits.unitId eq archivedAncestorId }) {
                it[OrganizationUnits.status] = OrganizationUnit.STATUS_ARCHIVED
            }
        }

        val snapshot = readAccessSnapshot(actorUid)
        assertEquals(setOf(directId), snapshot.directUnitIds)
        assertEquals(setOf(directId), snapshot.unitAndAncestorIds)
        assertEquals(
            setOf(directSpace.spaceId, userSpace.spaceId),
            ctx.documentService.listSpaces(actorUid).mapTo(mutableSetOf()) { it.spaceId },
        )
    }

    private suspend fun readAccessSnapshot(actorUid: String): DocumentReadAccessSnapshot =
        ctx.pgUnitOfWork.read {
            ctx.documentRepo.readAccessibleSpacePage(
                transaction,
                actorUid,
                after = null,
                pageSize = DocumentSpacePage.MAX_PAGE_SIZE,
            ).snapshot
        }

    private suspend fun readAccessSnapshotWithStatements(
        actorUid: String,
    ): Pair<DocumentReadAccessSnapshot, List<String>> = ctx.pgUnitOfWork.read {
        val exposed = transaction.requireExposedReadTransaction()
        val statements = mutableListOf<String>()
        exposed.addLogger(object : SqlLogger {
            override fun log(context: StatementContext, transaction: Transaction) {
                statements += context.sql(transaction)
            }
        })
        val snapshot = ctx.documentRepo.readAccessibleSpacePage(
            transaction,
            actorUid,
            after = null,
            pageSize = DocumentSpacePage.MAX_PAGE_SIZE,
        ).snapshot
        snapshot to statements.toList()
    }

    private fun assertBoundedAccessStatements(statements: List<String>) {
        val normalized = statements.map { sql ->
            sql.lowercase().replace(Regex("\\s+"), " ").trim()
        }
        // 一次联表目录版本门禁 + owner 容量探测 + actor 作用域路径 CTE + 一页去重候选。
        // 空候选页不需要 grant 事实查询。单独断言 CTE 形态，
        // 以免另一个固定成本守卫意外削弱"一条递归语句"这一不变量。
        assertEquals(4, normalized.size)
        assertEquals(
            1,
            normalized.count { sql -> "with recursive" in sql && "actor_direct" in sql },
            "document ACL actor paths must be resolved by exactly one recursive statement",
        )
    }

    private fun seedOrganizationUnits(units: List<OrganizationUnit>) {
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            OrganizationUnits.batchInsert(units) { unit ->
                this[OrganizationUnits.unitId] = unit.unitId
                this[OrganizationUnits.parentId] = unit.parentId
                this[OrganizationUnits.name] = unit.name
                this[OrganizationUnits.leaderUid] = unit.leaderUid
                this[OrganizationUnits.sortOrder] = unit.sortOrder
                this[OrganizationUnits.groupChatId] = unit.groupChatId
                this[OrganizationUnits.status] = unit.status
                this[OrganizationUnits.createdAt] = now
                this[OrganizationUnits.updatedAt] = now
            }
        }
    }

    private fun seedMemberships(actorUid: String, unitIds: List<String>) {
        val now = System.currentTimeMillis()
        transaction(ctx.database) {
            OrganizationMemberships.batchInsert(unitIds) { unitId ->
                this[OrganizationMemberships.unitId] = unitId
                this[OrganizationMemberships.uid] = actorUid
                this[OrganizationMemberships.title] = null
                this[OrganizationMemberships.primary] = false
                this[OrganizationMemberships.joinedAt] = now
                this[OrganizationMemberships.updatedAt] = now
            }
        }
    }

    private suspend fun grantUnit(
        ownerUid: String,
        spaceId: String,
        unitId: String,
        includeDescendants: Boolean,
    ) {
        ctx.documentService.upsertGrant(
            ownerUid,
            spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            unitId,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(spaceId),
            operationId = UUID.randomUUID().toString(),
        )
    }

    private suspend fun grantUser(ownerUid: String, spaceId: String, actorUid: String) {
        ctx.documentService.upsertGrant(
            ownerUid,
            spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            actorUid,
            DocumentSpace.ROLE_VIEWER,
            includeDescendants = false,
            expectedPolicyRevision = ctx.currentDocumentPolicyRevision(spaceId),
            operationId = UUID.randomUUID().toString(),
        )
    }
}
