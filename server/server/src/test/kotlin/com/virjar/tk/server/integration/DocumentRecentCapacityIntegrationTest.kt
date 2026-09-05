package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.infra.db.DocumentUserRecents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentRecentCapacityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val SAME_ACCESS_TIME = 100L
        private const val CONCURRENT_ACCESS_TIME = 10_000L
        private const val OVERFLOW_REBASE_ACCESS_TIME = 1_700_000_000_000L
        private const val NEW_DOCUMENT_ID = "recent-new"
    }

    private val ctx get() = ext.env

    @Test
    fun `touch heals oversized history and deterministically evicts its oldest rows`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-recent-capacity-owner"))
        val seeded = List(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER + 2) { index ->
            RecentRow(recentId(index), SAME_ACCESS_TIME)
        }
        seedRows(owner, seeded)

        touch(owner, NEW_DOCUMENT_ID, SAME_ACCESS_TIME)

        val retained = loadRows(owner)
        assertEquals(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER, retained.size)
        assertEquals(NEW_DOCUMENT_ID, retained.first().documentId)
        assertEquals(recentId(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER - 2), retained.last().documentId)
        assertFalse(
            retained.any {
                it.documentId in setOf(
                    recentId(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER - 1),
                    recentId(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER),
                    recentId(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER + 1),
                )
            },
        )
    }

    @Test
    fun `user fence keeps concurrent final recent touches within capacity`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-recent-concurrency-owner"))
        seedRows(
            owner,
            List(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER - 1) { index ->
                RecentRow(recentId(index), index.toLong() + 1)
            },
        )
        val candidates = setOf("concurrent-recent-a", "concurrent-recent-b")

        coroutineScope {
            candidates.map { documentId ->
                async(Dispatchers.Default) {
                    touch(owner, documentId, CONCURRENT_ACCESS_TIME)
                }
            }.awaitAll()
        }

        val retained = loadRows(owner)
        assertEquals(DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER, retained.size)
        assertEquals(candidates, retained.take(2).mapTo(linkedSetOf()) { it.documentId })
        assertTrue(retained[0].accessedAt > retained[1].accessedAt)
        assertFalse(retained.any { it.documentId == recentId(0) })
    }

    @Test
    fun `maximum timestamp resequence preserves visible order and leaves a safe next value`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-recent-overflow-owner"))
        val space = ctx.documentService.createSpace(owner, "最近访问溢出测试", null)
        val documents = List(4) { index ->
            ctx.documentService.createDocument(owner, space.spaceId, null, "溢出文档-$index", "")
        }
        val tiedNewest = documents.take(2).sortedBy { it.documentId }
        val older = documents[2]
        val target = documents[3]
        transaction(ctx.database) {
            tiedNewest.forEach { document ->
                DocumentUserRecents.update({
                    (DocumentUserRecents.uid eq owner) and
                        (DocumentUserRecents.documentId eq document.documentId)
                }) {
                    it[accessedAt] = Long.MAX_VALUE
                }
            }
            DocumentUserRecents.update({
                (DocumentUserRecents.uid eq owner) and
                    (DocumentUserRecents.documentId eq older.documentId)
            }) {
                it[accessedAt] = 7L
            }
            DocumentUserRecents.update({
                (DocumentUserRecents.uid eq owner) and
                    (DocumentUserRecents.documentId eq target.documentId)
            }) {
                it[accessedAt] = 6L
            }
        }

        touch(owner, target.documentId, OVERFLOW_REBASE_ACCESS_TIME)

        val visible = ctx.documentService.listRecentDocuments(owner, 10)
        assertEquals(
            listOf(target.documentId, tiedNewest[0].documentId, tiedNewest[1].documentId, older.documentId),
            visible.map { it.documentId },
        )
        assertEquals(
            listOf(
                OVERFLOW_REBASE_ACCESS_TIME,
                OVERFLOW_REBASE_ACCESS_TIME - 1,
                OVERFLOW_REBASE_ACCESS_TIME - 2,
                OVERFLOW_REBASE_ACCESS_TIME - 3,
            ),
            visible.map { it.accessedAt },
        )
    }

    private suspend fun touch(owner: String, documentId: String, accessedAt: Long) {
        ctx.pgUnitOfWork.write {
            ctx.documentRepo.touchRecentDocument(transaction, owner, documentId, accessedAt)
        }
    }

    private fun seedRows(owner: String, rows: List<RecentRow>) {
        transaction(ctx.database) {
            DocumentUserRecents.batchInsert(rows) { row ->
                this[DocumentUserRecents.uid] = owner
                this[DocumentUserRecents.documentId] = row.documentId
                this[DocumentUserRecents.accessedAt] = row.accessedAt
            }
        }
    }

    private fun loadRows(owner: String): List<RecentRow> = transaction(ctx.database) {
        DocumentUserRecents.selectAll()
            .where { DocumentUserRecents.uid eq owner }
            .orderBy(
                DocumentUserRecents.accessedAt to SortOrder.DESC,
                DocumentUserRecents.documentId to SortOrder.ASC,
            )
            .map { row ->
                RecentRow(
                    documentId = row[DocumentUserRecents.documentId],
                    accessedAt = row[DocumentUserRecents.accessedAt],
                )
            }
    }

    private fun recentId(index: Int): String = "recent-${index.toString().padStart(4, '0')}"

    private data class RecentRow(
        val documentId: String,
        val accessedAt: Long,
    )

}
