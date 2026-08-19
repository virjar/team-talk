package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.document.DocumentRepository
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.DocumentRevisions
import com.virjar.tk.infra.db.Documents
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedDocumentRepository : DocumentRepository {
    override fun list(scopeType: Int, scopeId: String): List<DocumentSummary> = transaction {
        Documents.selectAll().where {
            (Documents.scopeType eq scopeType) and
                (Documents.scopeId eq scopeId) and
                (Documents.status eq STATUS_ACTIVE)
        }.orderBy(Documents.updatedAt to SortOrder.DESC)
            .map(ResultRow::toDocumentSummary)
    }

    override fun find(documentId: String): Document? = transaction {
        Documents.selectAll().where {
            (Documents.documentId eq documentId) and (Documents.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toDocument()
    }

    override fun create(document: Document, initialRevision: DocumentRevision): Document = transaction {
        lockScope(document.scopeType, document.scopeId)
        Documents.insert { row ->
            row[documentId] = document.documentId
            row[scopeType] = document.scopeType
            row[scopeId] = document.scopeId
            row[title] = document.title
            row[markdown] = document.markdown
            row[revision] = document.revision
            row[status] = STATUS_ACTIVE
            row[createdBy] = document.createdBy
            row[createdAt] = document.createdAt
            row[updatedBy] = document.updatedBy
            row[updatedAt] = document.updatedAt
        }
        insertRevision(initialRevision)
        document
    }

    override fun update(
        documentId: String,
        expectedRevision: Long,
        title: String,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
    ): Document = transaction {
        val current = requireActiveDocument(documentId, forUpdate = true)
        require(current.revision == expectedRevision) { CONFLICT_MESSAGE }
        val nextRevision = expectedRevision + 1
        val updated = Documents.update({
            (Documents.documentId eq documentId) and
                (Documents.status eq STATUS_ACTIVE) and
                (Documents.revision eq expectedRevision)
        }) {
            it[Documents.title] = title
            it[Documents.markdown] = markdown
            it[revision] = nextRevision
            it[updatedBy] = actorUid
            it[Documents.updatedAt] = updatedAt
        }
        require(updated == 1) { CONFLICT_MESSAGE }
        insertRevision(DocumentRevision(documentId, nextRevision, title, markdown, actorUid, updatedAt))
        requireActiveDocument(documentId)
    }

    override fun delete(documentId: String, expectedRevision: Long, actorUid: String, updatedAt: Long) {
        transaction {
            val current = requireActiveDocument(documentId, forUpdate = true)
            require(current.revision == expectedRevision) { CONFLICT_MESSAGE }
            val updated = Documents.update({
                (Documents.documentId eq documentId) and
                    (Documents.status eq STATUS_ACTIVE) and
                    (Documents.revision eq expectedRevision)
            }) {
                it[status] = STATUS_DELETED
                it[revision] = expectedRevision + 1
                it[updatedBy] = actorUid
                it[Documents.updatedAt] = updatedAt
            }
            require(updated == 1) { CONFLICT_MESSAGE }
        }
    }

    override fun listRevisions(documentId: String): List<DocumentRevisionSummary> = transaction {
        DocumentRevisions.selectAll().where { DocumentRevisions.documentId eq documentId }
            .orderBy(DocumentRevisions.revision to SortOrder.DESC)
            .map(ResultRow::toDocumentRevisionSummary)
    }

    override fun findRevision(documentId: String, revision: Long): DocumentRevision? = transaction {
        DocumentRevisions.selectAll().where {
            (DocumentRevisions.documentId eq documentId) and (DocumentRevisions.revision eq revision)
        }.singleOrNull()?.toDocumentRevision()
    }

    /** 群聊行是当前唯一 scope 锁；扩展组织/个人空间时需为相应 scope 增加锁锚点。 */
    private fun lockScope(scopeType: Int, scopeId: String) {
        require(scopeType == Document.SCOPE_GROUP_CHAT) { "暂不支持该文档空间" }
        require(Chats.selectAll().where { Chats.chatId eq scopeId }.forUpdate().singleOrNull() != null) { "群聊不存在" }
    }

    private fun requireActiveDocument(documentId: String, forUpdate: Boolean = false): Document {
        val query = Documents.selectAll().where {
            (Documents.documentId eq documentId) and (Documents.status eq STATUS_ACTIVE)
        }
        val row = (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw IllegalArgumentException("文档不存在")
        return row.toDocument()
    }

    private fun insertRevision(value: DocumentRevision) {
        DocumentRevisions.insert {
            it[documentId] = value.documentId
            it[revision] = value.revision
            it[title] = value.title
            it[markdown] = value.markdown
            it[editedBy] = value.editedBy
            it[editedAt] = value.editedAt
        }
    }

    companion object {
        private const val STATUS_DELETED = 0
        private const val STATUS_ACTIVE = 1
        private const val CONFLICT_MESSAGE = "文档已被其他成员修改，请刷新后重试"
    }
}

private fun ResultRow.toDocument() = Document(
    documentId = this[Documents.documentId],
    scopeType = this[Documents.scopeType],
    scopeId = this[Documents.scopeId],
    title = this[Documents.title],
    markdown = this[Documents.markdown],
    revision = this[Documents.revision],
    createdBy = this[Documents.createdBy],
    createdAt = this[Documents.createdAt],
    updatedBy = this[Documents.updatedBy],
    updatedAt = this[Documents.updatedAt],
)

private fun ResultRow.toDocumentSummary() = DocumentSummary(
    documentId = this[Documents.documentId],
    scopeType = this[Documents.scopeType],
    scopeId = this[Documents.scopeId],
    title = this[Documents.title],
    excerpt = markdownExcerpt(this[Documents.markdown]),
    revision = this[Documents.revision],
    createdBy = this[Documents.createdBy],
    createdAt = this[Documents.createdAt],
    updatedBy = this[Documents.updatedBy],
    updatedAt = this[Documents.updatedAt],
)

private fun ResultRow.toDocumentRevision() = DocumentRevision(
    documentId = this[DocumentRevisions.documentId],
    revision = this[DocumentRevisions.revision],
    title = this[DocumentRevisions.title],
    markdown = this[DocumentRevisions.markdown],
    editedBy = this[DocumentRevisions.editedBy],
    editedAt = this[DocumentRevisions.editedAt],
)

private fun ResultRow.toDocumentRevisionSummary() = DocumentRevisionSummary(
    documentId = this[DocumentRevisions.documentId],
    revision = this[DocumentRevisions.revision],
    title = this[DocumentRevisions.title],
    contentLength = this[DocumentRevisions.markdown].length,
    editedBy = this[DocumentRevisions.editedBy],
    editedAt = this[DocumentRevisions.editedAt],
)

private fun markdownExcerpt(markdown: String): String {
    val excerpt = markdown.lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
        ?.trimStart('#', '>', '-', '*', '+', '`', ' ')
        ?.take(160)
        .orEmpty()
    return excerpt.ifBlank { "空白文档" }
}
