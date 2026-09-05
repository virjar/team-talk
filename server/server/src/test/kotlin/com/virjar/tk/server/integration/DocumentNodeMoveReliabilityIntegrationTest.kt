package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandExpiredException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.infra.db.DocumentNodeMoveCommands
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentContentRevisions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentNodeMoveReliabilityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val NOW = 1_800_000_000_000L
    }

    @Test
    fun `content update retains the authoritative node name`() = runTest {
        val ctx = ext.env
        val owner = ctx.registerUser(uniqueUsername("document-content-only"))
        val space = ctx.documentService.createSpace(owner, "正文保存", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "稳定标题", "# 初稿")

        val updated = ctx.documentService.updateDocument(
            owner,
            space.spaceId,
            document.documentId,
            "# 定稿",
            document.revision,
        )

        assertEquals("稳定标题", updated.title)
        assertEquals("稳定标题", ctx.documentService.getRevision(owner, space.spaceId, document.documentId, 2L).title)
    }

    @Test
    fun `move and rename replay durable receipts without repeating revisions`() = runTest {
        val ctx = ext.env
        val owner = ctx.registerUser(uniqueUsername("document-move-receipt"))
        val space = ctx.documentService.createSpace(owner, "可靠移动", null)
        val parent = ctx.documentService.createDocument(owner, space.spaceId, null, "目标目录", "")
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "原标题", "# 正文")
        val service = DocumentService(
            repository = ctx.documentRepo,
            unitOfWork = ctx.pgUnitOfWork,
            attachmentCatalog = ctx.fileStore,
            wallClockMillis = { NOW },
        )
        val moveOperationId = UUID.randomUUID().toString()

        val firstMove = service.moveNode(
            owner,
            space.spaceId,
            document.documentId,
            parent.documentId,
            document.title,
            document.revision,
            moveOperationId,
            NOW,
        )
        val firstProjection = assertNotNull(firstMove.result)
        assertEquals(moveOperationId, firstMove.operationId)
        assertEquals(2L, firstProjection.node.revision)
        assertEquals(listOf(parent.documentId), firstProjection.ancestorIds)

        val replayedMove = service.moveNode(
            owner,
            space.spaceId,
            document.documentId,
            parent.documentId,
            document.title,
            document.revision,
            moveOperationId,
            NOW,
        )
        assertEquals(moveOperationId, replayedMove.operationId)
        assertNull(replayedMove.result)
        assertEquals(1L, receiptCount(owner, moveOperationId))
        assertEquals(2L, nodeRevision(document.documentId))
        assertEquals(listOf(1L), contentRevisions(document.documentId))

        assertFailsWith<ReliableCommandConflictException> {
            service.moveNode(
                owner,
                space.spaceId,
                document.documentId,
                parent.documentId,
                "冲突标题",
                document.revision,
                moveOperationId,
                NOW,
            )
        }

        val renameOperationId = UUID.randomUUID().toString()
        val renamed = service.moveNode(
            owner,
            space.spaceId,
            document.documentId,
            parent.documentId,
            "新标题",
            2L,
            renameOperationId,
            NOW,
        )
        assertEquals(3L, renamed.result?.node?.revision)
        assertEquals(listOf(3L, 1L), contentRevisions(document.documentId))

        val replayedRename = service.moveNode(
            owner,
            space.spaceId,
            document.documentId,
            parent.documentId,
            "新标题",
            2L,
            renameOperationId,
            NOW,
        )
        assertNull(replayedRename.result)
        assertEquals(3L, nodeRevision(document.documentId))
        assertEquals(listOf(3L, 1L), contentRevisions(document.documentId))
    }

    @Test
    fun `concurrent duplicate deliveries commit one rename and one content revision`() = runTest {
        val ctx = ext.env
        val owner = ctx.registerUser(uniqueUsername("document-move-concurrent-replay"))
        val space = ctx.documentService.createSpace(owner, "并发可靠改名", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "旧标题", "# 正文")
        val operationId = UUID.randomUUID().toString()
        val service = DocumentService(
            repository = ctx.documentRepo,
            unitOfWork = ctx.pgUnitOfWork,
            attachmentCatalog = ctx.fileStore,
            wallClockMillis = { NOW },
        )

        val results = coroutineScope {
            List(8) {
                async(Dispatchers.Default) {
                    service.moveNode(
                        owner,
                        space.spaceId,
                        document.documentId,
                        null,
                        "并发新标题",
                        document.revision,
                        operationId,
                        NOW,
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it.result != null })
        assertEquals(7, results.count { it.result == null })
        assertEquals(1L, receiptCount(owner, operationId))
        assertEquals(2L, nodeRevision(document.documentId))
        assertEquals(listOf(2L, 1L), contentRevisions(document.documentId))
    }

    @Test
    fun `expired exact move identity is rejected instead of executing anew`() = runTest {
        val ctx = ext.env
        val owner = ctx.registerUser(uniqueUsername("document-move-expiry"))
        val space = ctx.documentService.createSpace(owner, "移动期限", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "期限文档", "")
        val operationId = UUID.randomUUID().toString()
        val initial = DocumentService(
            repository = ctx.documentRepo,
            unitOfWork = ctx.pgUnitOfWork,
            attachmentCatalog = ctx.fileStore,
            wallClockMillis = { NOW },
        )
        initial.moveNode(
            owner,
            space.spaceId,
            document.documentId,
            null,
            "期限标题",
            document.revision,
            operationId,
            NOW,
        )
        val expired = DocumentService(
            repository = ctx.documentRepo,
            unitOfWork = ctx.pgUnitOfWork,
            attachmentCatalog = ctx.fileStore,
            wallClockMillis = { ReliableCommandPolicy.expiresAt(NOW) + 1L },
        )

        assertFailsWith<ReliableCommandExpiredException> {
            expired.moveNode(
                owner,
                space.spaceId,
                document.documentId,
                null,
                "期限标题",
                document.revision,
                operationId,
                NOW,
            )
        }
        assertEquals(2L, nodeRevision(document.documentId))
        assertEquals(1L, receiptCount(owner, operationId))
    }

    private fun receiptCount(actorUid: String, operationId: String): Long = transaction(ext.env.database) {
        DocumentNodeMoveCommands.selectAll().where {
            (DocumentNodeMoveCommands.actorUid eq actorUid) and
                (DocumentNodeMoveCommands.operationId eq operationId)
        }.count()
    }

    private fun nodeRevision(nodeId: String): Long = transaction(ext.env.database) {
        DocumentNodes.select(DocumentNodes.revision).where { DocumentNodes.nodeId eq nodeId }
            .single()[DocumentNodes.revision]
    }

    private fun contentRevisions(documentId: String): List<Long> = transaction(ext.env.database) {
        DocumentContentRevisions.select(DocumentContentRevisions.revision).where {
            DocumentContentRevisions.documentId eq documentId
        }.map { it[DocumentContentRevisions.revision] }.sortedDescending()
    }
}
