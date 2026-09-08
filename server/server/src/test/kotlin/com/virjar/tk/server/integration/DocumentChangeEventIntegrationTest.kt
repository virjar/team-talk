package com.virjar.tk.server.integration

import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.OrganizationUnits
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 真实 PostgreSQL 上固定文档命令、可靠收据与用户持久事件的同事务关系。 */
class DocumentChangeEventIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }
    private val ctx get() = ext.env
    private val documents get() = ctx.documentService
    private fun id() = UUID.randomUUID().toString()

    @Test
    fun `node lifecycle emits ordered invalidations once and access changes include revoked readers`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("dce-owner"))
        val reader = ctx.registerUser(uniqueUsername("dce-reader"))
        val outsider = ctx.registerUser(uniqueUsername("dce-outsider"))
        val spaceId = id()
        val space = documents.createSpace(owner, spaceId, "空间", null)
        assertEquals(DocumentSpace.ROLE_OWNER, documents.getSpace(owner, spaceId).myRole)
        assertFailsWith<DocumentAccessDeniedException> { documents.getSpace(outsider, spaceId) }
        documents.createSpace(owner, spaceId, "空间", null)
        assertEquals(1, events(owner, spaceId).size)
        val grantId = id()
        val issuedAt = System.currentTimeMillis()
        val grant = documents.upsertGrant(owner, spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, 1, grantId, issuedAt)
        assertEquals(DocumentSpace.ROLE_VIEWER, documents.getSpace(reader, spaceId).myRole)
        assertEquals(grant.policyRevision, documents.getSpace(reader, spaceId).policyRevision)
        documents.upsertGrant(owner, spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, 1, grantId, issuedAt)
        assertEquals(listOf(DocumentChangedPayload.SPACE_CHANGED), events(reader, spaceId).map { it.kind })

        val nodeId = id()
        val created = documents.createDocument(owner, nodeId, spaceId, null, "文档", "v1")
        documents.createDocumentCommand(owner, nodeId, spaceId, null, "文档", "v1")
        documents.updateDocument(owner, spaceId, nodeId, "v1", created.revision)
        val updated = documents.updateDocument(owner, spaceId, nodeId, "v2", created.revision)
        val moveId = id()
        documents.moveNode(owner, spaceId, nodeId, null, "改名", updated.revision, moveId, issuedAt)
        documents.moveNode(owner, spaceId, nodeId, null, "改名", updated.revision, moveId, issuedAt)
        val deleteId = id()
        documents.deleteNode(owner, spaceId, nodeId, 3, deleteId)
        documents.deleteNode(owner, spaceId, nodeId, 3, deleteId)
        val nodes = events(reader, spaceId).filter { it.nodeId == nodeId }
        assertEquals(listOf(1L, 2L, 3L, 4L), nodes.map { it.revision })
        assertEquals(DocumentChangedPayload.NODE_DELETED, nodes.last().kind)
        assertTrue(nodes.all { it.policyRevision == grant.policyRevision })
        assertTrue(events(outsider, spaceId).isEmpty())

        documents.updateSpace(owner, spaceId, "新空间", null)
        assertEquals("新空间", documents.getSpace(reader, spaceId).name)
        val removed = documents.removeGrant(owner, spaceId, 1, reader, grant.policyRevision, id(), issuedAt)
        assertEquals(DocumentChangedPayload.SPACE_REVOKED, events(reader, spaceId).last().kind)
        assertEquals(removed.policyRevision, events(reader, spaceId).last().policyRevision)
        assertFailsWith<DocumentAccessDeniedException> { documents.getSpace(reader, spaceId) }
        val regrant = documents.upsertGrant(owner, spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, removed.policyRevision, id(), issuedAt)
        assertEquals(DocumentChangedPayload.SPACE_CHANGED, events(reader, spaceId).last().kind)
        val archiveId = id()
        documents.archiveSpace(owner, spaceId, archiveId)
        assertFailsWith<DocumentNotFoundException> { documents.getSpace(owner, spaceId) }
        val archivedEvents = events(reader, spaceId)
        assertEquals(DocumentChangedPayload.SPACE_REVOKED, archivedEvents.last().kind)
        assertEquals(regrant.policyRevision + 1, archivedEvents.last().policyRevision)
        documents.archiveSpace(owner, spaceId, archiveId)
        assertEquals(archivedEvents, events(reader, spaceId))
        assertEquals(space.spaceId, archivedEvents.last().spaceId)
    }

    @Test
    fun `cyclic organization paths keep direct department readers in the event audience`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("dce-cycle-owner"))
        val directReader = ctx.registerUser(uniqueUsername("dce-cycle-direct"))
        val inheritedReader = ctx.registerUser(uniqueUsername("dce-cycle-inherited"))
        val parent = OrganizationUnit(id(), name = "直属部门")
        val child = OrganizationUnit(id(), parentId = parent.unitId, name = "继承部门")
        ctx.seedOrganizationUnit(parent)
        ctx.seedOrganizationUnit(child)
        ctx.seedOrganizationMember(OrganizationMember(parent.unitId, directReader))
        ctx.seedOrganizationMember(OrganizationMember(child.unitId, inheritedReader))
        val space = documents.createSpace(owner, "环路径空间", null)
        documents.upsertGrant(owner, space.spaceId, 2, parent.unitId, DocumentSpace.ROLE_VIEWER, true, 1, id())
        val originalParent = transaction(ctx.database) {
            OrganizationUnits.selectAll().where { OrganizationUnits.unitId eq parent.unitId }
                .single()[OrganizationUnits.parentId]
        }
        try {
            transaction(ctx.database) { OrganizationUnits.update({ OrganizationUnits.unitId eq parent.unitId }) {
                it[OrganizationUnits.parentId] = child.unitId
            } }
            assertEquals(DocumentSpace.ROLE_VIEWER, documents.getSpace(directReader, space.spaceId).myRole)
            assertFailsWith<DocumentAccessDeniedException> { documents.getSpace(inheritedReader, space.spaceId) }
            val document = documents.createDocument(owner, space.spaceId, null, "直接授权仍有效", "正文")
            assertEquals(1, events(directReader, space.spaceId).count { it.nodeId == document.documentId })
            assertTrue(events(inheritedReader, space.spaceId).none { it.nodeId == document.documentId })
        } finally {
            transaction(ctx.database) { OrganizationUnits.update({ OrganizationUnits.unitId eq parent.unitId }) {
                it[OrganizationUnits.parentId] = originalParent
            } }
        }
    }

    @Test
    fun `transaction rollback leaves no event while lost post commit wake remains replayable`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("dce-rollback"))
        val space = documents.createSpace(owner, "原空间", null)
        val before = events(owner, space.spaceId)
        val failing = DocumentService(ctx.documentRepo, ExposedPgUnitOfWork(
            database = ctx.database, onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) error("rollback") },
        ))
        assertFailsWith<IllegalStateException> { failing.updateSpace(owner, space.spaceId, "不应提交", null) }
        assertEquals("原空间", ctx.readDocuments { findSpace(it, space.spaceId) }?.name)
        assertEquals(before, events(owner, space.spaceId))

        val lostWake = DocumentService(ctx.documentRepo, ExposedPgUnitOfWork(
            database = ctx.database, onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS) error("lost wake") },
        ))
        val documentId = id()
        assertFailsWith<IllegalStateException> {
            lostWake.createDocumentCommand(owner, documentId, space.spaceId, null, "已提交", "正文")
        }
        val committed = events(owner, space.spaceId)
        assertEquals(before.size + 1, committed.size)
        val restarted = DocumentService(ctx.documentRepo, ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        restarted.createDocumentCommand(owner, documentId, space.spaceId, null, "已提交", "正文")
        assertEquals(committed, events(owner, space.spaceId))
        val replayed = ctx.syncEventReader.getEventsAfter(owner, 0, 100)
            .filter { it.notifyType == NotifyType.DOCUMENT_CHANGED.code }
            .map { ProtoCodec.decode(DocumentChangedPayload, requireNotNull(it.payload)) }
            .filter { it.spaceId == space.spaceId }
        assertEquals(committed, replayed, "丢失进程内唤醒后，普通同步读取仍重放持久事件")
        assertEquals("正文", restarted.getDocument(owner, space.spaceId, documentId).markdown)
    }

    @Test
    fun `organization and direct grants share one deduplicated audience and only final loss revokes`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("dce-org-owner"))
        val reader = ctx.registerUser(uniqueUsername("dce-org-reader"))
        val root = OrganizationUnit(id(), name = "根")
        val child = OrganizationUnit(id(), parentId = root.unitId, name = "子部门")
        ctx.seedOrganizationUnit(root)
        ctx.seedOrganizationUnit(child)
        ctx.seedOrganizationMember(OrganizationMember(child.unitId, reader))
        val space = documents.createSpace(owner, "组织空间", null)
        val direct = documents.upsertGrant(owner, space.spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, 1, id())
        val inherited = documents.upsertGrant(owner, space.spaceId, 2, root.unitId, DocumentSpace.ROLE_EDITOR, true, direct.policyRevision, id())
        val document = documents.createDocument(owner, space.spaceId, null, "共享文档", "正文")
        assertEquals(1, events(reader, space.spaceId).count { it.nodeId == document.documentId })
        val removedDirect = documents.removeGrant(owner, space.spaceId, 1, reader, inherited.policyRevision, id())
        assertEquals(DocumentChangedPayload.SPACE_CHANGED, events(reader, space.spaceId).last().kind)
        documents.removeGrant(owner, space.spaceId, 2, root.unitId, removedDirect.policyRevision, id())
        assertEquals(DocumentChangedPayload.SPACE_REVOKED, events(reader, space.spaceId).last().kind)
    }

    @Test
    fun `custody and admin recovery publish access changes only for their first commit`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("dce-custody-owner"))
        val steward = ctx.registerUser(uniqueUsername("dce-custody-next"))
        val recovery = ctx.registerUser(uniqueUsername("dce-custody-recover"))
        val space = documents.createSpace(owner, "交接空间", null)
        val transferId = id()
        documents.transferSpaceCustody(owner, space.spaceId, 1, steward, steward, 1, transferId)
        documents.transferSpaceCustody(owner, space.spaceId, 1, steward, steward, 1, transferId)
        assertEquals(DocumentChangedPayload.SPACE_REVOKED, events(owner, space.spaceId).last().kind)
        assertEquals(2L, events(owner, space.spaceId).last().policyRevision)
        assertEquals(1, events(steward, space.spaceId).size)
        ctx.adminService.banUser(steward)
        val plan = ctx.documentCustodyAdministration.plan(steward, 1, recovery, recovery)
        val batchId = id()
        val receipt = ctx.documentCustodyAdministration.transfer("admin", steward, batchId, plan.planFingerprint, 1, recovery, recovery)
        assertEquals(DocumentChangedPayload.SPACE_CHANGED, events(recovery, space.spaceId).single().kind)
        assertEquals(3L, events(recovery, space.spaceId).single().policyRevision)
        assertEquals(receipt, ctx.documentCustodyAdministration.transfer("admin", steward, batchId, plan.planFingerprint, 1, recovery, recovery))
        assertEquals(1, events(recovery, space.spaceId).size)
    }

    private fun events(uid: String, spaceId: String): List<DocumentChangedPayload> = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.DOCUMENT_CHANGED.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { ProtoCodec.decode(DocumentChangedPayload, it[SyncEvents.payload]) }
            .filter { it.spaceId == spaceId }
    }
}
