package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.ActiveDocumentIdentity
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.protocol.model.Document
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.expandArgs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentRevisionReadProjectionIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `history service entries query active identity without preloading current content or path`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("doc-history-owner"))
        val space = ctx.documentService.createSpace(owner, "历史投影空间", null)
        val parent = ctx.documentService.createDocument(owner, space.spaceId, null, "父文档", "# 父文档")
        val document = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            parent.documentId,
            "会议记录",
            "# 初稿\n当前节点正文不应为历史查询预读。",
        )
        ctx.documentService.updateDocument(
            owner,
            space.spaceId,
            document.documentId,
            "# 定稿\n只有指定修订查询可以读取不可变历史正文。",
            document.revision,
        )

        val guardedRepository = DocumentReadGuardRepository(ctx.documentRepo)
        val capturingUnitOfWork = SqlCapturingUnitOfWork(ctx.pgUnitOfWork)
        val service = DocumentService(guardedRepository, capturingUnitOfWork)

        val page = service.listRevisions(owner, space.spaceId, document.documentId, 0, 10)

        assertEquals(listOf(2L, 1L), page.items.map { it.revision })
        assertEquals(1, guardedRepository.identityReadCount)
        assertEquals(0, guardedRepository.contentReadCount)
        assertCurrentIdentityQuery(capturingUnitOfWork.readStatements)
        val summaryQuery = singleQueryFrom(capturingUnitOfWork.readStatements, "document_content_revisions")
        assertTrue("document_content_revisions.content_length" in selectClause(summaryQuery))
        assertFalse("document_content_revisions.markdown" in selectClause(summaryQuery))

        capturingUnitOfWork.clearReadStatements()
        val firstRevision = service.getRevision(owner, space.spaceId, document.documentId, 1)

        assertEquals("# 初稿\n当前节点正文不应为历史查询预读。", firstRevision.markdown)
        assertEquals(2, guardedRepository.identityReadCount)
        assertEquals(0, guardedRepository.contentReadCount)
        assertCurrentIdentityQuery(capturingUnitOfWork.readStatements)
        val revisionQuery = singleQueryFrom(capturingUnitOfWork.readStatements, "document_content_revisions")
        assertTrue("document_content_revisions.markdown" in selectClause(revisionQuery))
    }

    @Test
    fun `update service delegates directly to the locked writer snapshot`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("doc-update-owner"))
        val space = ctx.documentService.createSpace(owner, "更新投影空间", null)
        val document = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            null,
            "周报",
            "# 周报\n初稿",
        )
        val guardedRepository = DocumentReadGuardRepository(ctx.documentRepo)
        val service = DocumentService(guardedRepository, ctx.pgUnitOfWork)

        val updated = service.updateDocument(
            owner,
            space.spaceId,
            document.documentId,
            "# 周报\n定稿",
            document.revision,
        )

        assertEquals(document.revision + 1L, updated.revision)
        assertEquals("# 周报\n定稿", updated.markdown)
        assertEquals(
            0,
            guardedRepository.contentReadCount,
            "the writer already owns the locked content read; the service must not pre-load it",
        )
    }

    private fun assertCurrentIdentityQuery(statements: List<String>) {
        val identityQuery = singleQueryFrom(statements, "document_nodes")
        assertEquals(
            "document_nodes.node_id, document_nodes.space_id",
            selectClause(identityQuery),
        )
        assertFalse("document_nodes.markdown" in identityQuery)
        assertFalse("document_nodes.parent_id" in identityQuery)
        assertFalse("with recursive" in identityQuery)
        assertTrue("document_nodes.space_id =" in identityQuery)
        assertTrue("document_nodes.status =" in identityQuery)
    }

    private fun singleQueryFrom(statements: List<String>, table: String): String {
        val normalized = statements.map(::normalizeSql)
        return normalized.single { " from $table " in it }
    }

    private fun normalizeSql(sql: String): String = sql
        .lowercase()
        .replace("\"", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun selectClause(sql: String): String = sql
        .substringAfter("select ")
        .substringBefore(" from ")

    private class DocumentReadGuardRepository(
        private val delegate: DocumentRepository,
    ) : DocumentRepository by delegate {
        var identityReadCount: Int = 0
            private set
        var contentReadCount: Int = 0
            private set

        override fun findActiveDocumentIdentity(
            transaction: PgReadTransactionContext,
            spaceId: String,
            documentId: String,
        ): ActiveDocumentIdentity? {
            identityReadCount += 1
            return delegate.findActiveDocumentIdentity(transaction, spaceId, documentId)
        }

        override fun findDocument(
            transaction: PgReadTransactionContext,
            spaceId: String,
            documentId: String,
        ): Document? {
            contentReadCount += 1
            error("Service entry must not issue a redundant current-content pre-read")
        }
    }

    private class SqlCapturingUnitOfWork(
        private val delegate: PgUnitOfWork,
    ) : PgUnitOfWork {
        val readStatements = mutableListOf<String>()

        override suspend fun <T> read(block: PgReadScope.() -> T): T = delegate.read {
            val exposed = transaction.requireExposedReadTransaction()
            exposed.addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    readStatements += context.expandArgs(transaction)
                }
            })
            block(this)
        }

        override suspend fun <T> write(block: PgWriteScope.() -> T): T = delegate.write(block)

        fun clearReadStatements() {
            readStatements.clear()
        }
    }
}
