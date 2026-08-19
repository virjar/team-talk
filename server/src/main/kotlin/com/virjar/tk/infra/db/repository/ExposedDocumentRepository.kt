package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.document.DocumentRepository
import com.virjar.tk.infra.db.DocumentContentRevisions
import com.virjar.tk.infra.db.DocumentNodes
import com.virjar.tk.infra.db.DocumentSpaceGrants
import com.virjar.tk.infra.db.DocumentSpaces
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedDocumentRepository : DocumentRepository {
    override fun listSpaces(): List<DocumentSpace> = transaction {
        DocumentSpaces.selectAll().where { DocumentSpaces.status eq STATUS_ACTIVE }
            .orderBy(DocumentSpaces.updatedAt to SortOrder.DESC)
            .map(ResultRow::toDocumentSpace)
    }

    override fun findSpace(spaceId: String): DocumentSpace? = transaction {
        DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toDocumentSpace()
    }

    override fun createSpace(space: DocumentSpace): DocumentSpace = transaction {
        DocumentSpaces.insert {
            it[spaceId] = space.spaceId
            it[name] = space.name
            it[description] = space.description
            it[status] = STATUS_ACTIVE
            it[createdBy] = space.createdBy
            it[createdAt] = space.createdAt
            it[updatedAt] = space.updatedAt
        }
        space
    }

    override fun updateSpace(spaceId: String, name: String, description: String?, updatedAt: Long): DocumentSpace = transaction {
        val updated = DocumentSpaces.update({
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }) {
            it[DocumentSpaces.name] = name
            it[DocumentSpaces.description] = description
            it[DocumentSpaces.updatedAt] = updatedAt
        }
        require(updated == 1) { "文档空间不存在" }
        requireActiveSpace(spaceId)
    }

    override fun archiveSpace(spaceId: String, updatedAt: Long) {
        transaction {
            val updated = DocumentSpaces.update({
                (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
            }) {
                it[status] = STATUS_DELETED
                it[DocumentSpaces.updatedAt] = updatedAt
            }
            require(updated == 1) { "文档空间不存在" }
        }
    }

    override fun listGrants(spaceId: String): List<DocumentSpaceGrant> = transaction {
        DocumentSpaceGrants.selectAll().where { DocumentSpaceGrants.spaceId eq spaceId }
            .orderBy(DocumentSpaceGrants.principalType to SortOrder.ASC, DocumentSpaceGrants.principalId to SortOrder.ASC)
            .map(ResultRow::toDocumentSpaceGrant)
    }

    override fun upsertGrant(grant: DocumentSpaceGrant) {
        transaction {
            lockSpace(grant.spaceId)
            val existing = DocumentSpaceGrants.selectAll().where {
                (DocumentSpaceGrants.spaceId eq grant.spaceId) and
                    (DocumentSpaceGrants.principalType eq grant.principalType) and
                    (DocumentSpaceGrants.principalId eq grant.principalId)
            }.singleOrNull()
            if (existing == null) {
                DocumentSpaceGrants.insert {
                    it[spaceId] = grant.spaceId
                    it[principalType] = grant.principalType
                    it[principalId] = grant.principalId
                    it[role] = grant.role
                    it[includeDescendants] = grant.includeDescendants
                    it[updatedAt] = System.currentTimeMillis()
                }
            } else {
                DocumentSpaceGrants.update({
                    (DocumentSpaceGrants.spaceId eq grant.spaceId) and
                        (DocumentSpaceGrants.principalType eq grant.principalType) and
                        (DocumentSpaceGrants.principalId eq grant.principalId)
                }) {
                    it[role] = grant.role
                    it[includeDescendants] = grant.includeDescendants
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override fun removeGrant(spaceId: String, principalType: Int, principalId: String) {
        transaction {
            lockSpace(spaceId)
            DocumentSpaceGrants.deleteWhere {
                (DocumentSpaceGrants.spaceId eq spaceId) and
                    (DocumentSpaceGrants.principalType eq principalType) and
                    (DocumentSpaceGrants.principalId eq principalId)
            }
        }
    }

    override fun listNodes(spaceId: String, parentId: String?): List<DocumentNode> = transaction {
        DocumentNodes.selectAll().where {
            val parentMatches = if (parentId == null) DocumentNodes.parentId.isNull() else DocumentNodes.parentId eq parentId
            (DocumentNodes.spaceId eq spaceId) and parentMatches and (DocumentNodes.status eq STATUS_ACTIVE)
        }.orderBy(DocumentNodes.nodeType to SortOrder.ASC, DocumentNodes.name to SortOrder.ASC)
            .map(ResultRow::toDocumentNode)
    }

    override fun findNode(nodeId: String): DocumentNode? = transaction {
        findActiveNodeRow(nodeId)?.toDocumentNode()
    }

    override fun findDocument(documentId: String): Document? = transaction {
        findActiveNodeRow(documentId)
            ?.takeIf { it[DocumentNodes.nodeType] == DocumentNode.TYPE_DOCUMENT }
            ?.toDocument()
    }

    override fun createFolder(node: DocumentNode): DocumentNode = transaction {
        lockSpace(node.spaceId)
        insertNode(node, markdown = null)
        node
    }

    override fun createDocument(document: Document, initialRevision: DocumentRevision): Document = transaction {
        lockSpace(document.spaceId)
        insertNode(document.toNode(), document.markdown)
        insertRevision(initialRevision)
        document
    }

    override fun updateDocument(
        documentId: String,
        expectedRevision: Long,
        title: String,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
    ): Document = transaction {
        val current = requireActiveNodeRow(documentId, forUpdate = true)
        require(current[DocumentNodes.nodeType] == DocumentNode.TYPE_DOCUMENT) { "节点不是文档" }
        require(current[DocumentNodes.revision] == expectedRevision) { CONFLICT_MESSAGE }
        if (current[DocumentNodes.name] == title && current[DocumentNodes.markdown].orEmpty() == markdown) {
            return@transaction current.toDocument()
        }
        val nextRevision = expectedRevision + 1
        val updated = DocumentNodes.update({
            (DocumentNodes.nodeId eq documentId) and
                (DocumentNodes.status eq STATUS_ACTIVE) and
                (DocumentNodes.revision eq expectedRevision)
        }) {
            it[name] = title
            it[DocumentNodes.markdown] = markdown
            it[revision] = nextRevision
            it[updatedBy] = actorUid
            it[DocumentNodes.updatedAt] = updatedAt
        }
        require(updated == 1) { CONFLICT_MESSAGE }
        insertRevision(DocumentRevision(documentId, nextRevision, title, markdown, actorUid, updatedAt))
        requireActiveNodeRow(documentId).toDocument()
    }

    override fun moveNode(
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentNode = transaction {
        val current = requireActiveNodeRow(nodeId, forUpdate = true)
        require(current[DocumentNodes.revision] == expectedRevision) { CONFLICT_MESSAGE }
        val nextRevision = expectedRevision + 1
        val updated = DocumentNodes.update({
            (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.status eq STATUS_ACTIVE) and
                (DocumentNodes.revision eq expectedRevision)
        }) {
            it[DocumentNodes.parentId] = parentId
            it[DocumentNodes.name] = name
            it[revision] = nextRevision
            it[updatedBy] = actorUid
            it[DocumentNodes.updatedAt] = updatedAt
        }
        require(updated == 1) { CONFLICT_MESSAGE }
        if (current[DocumentNodes.nodeType] == DocumentNode.TYPE_DOCUMENT) {
            insertRevision(
                DocumentRevision(
                    documentId = nodeId,
                    revision = nextRevision,
                    title = name,
                    markdown = current[DocumentNodes.markdown].orEmpty(),
                    editedBy = actorUid,
                    editedAt = updatedAt,
                ),
            )
        }
        requireActiveNodeRow(nodeId).toDocumentNode()
    }

    override fun deleteNode(nodeId: String, expectedRevision: Long, actorUid: String, updatedAt: Long) {
        transaction {
            val current = requireActiveNodeRow(nodeId, forUpdate = true)
            require(current[DocumentNodes.revision] == expectedRevision) { CONFLICT_MESSAGE }
            val updated = DocumentNodes.update({
                (DocumentNodes.nodeId eq nodeId) and
                    (DocumentNodes.status eq STATUS_ACTIVE) and
                    (DocumentNodes.revision eq expectedRevision)
            }) {
                it[status] = STATUS_DELETED
                it[revision] = expectedRevision + 1
                it[updatedBy] = actorUid
                it[DocumentNodes.updatedAt] = updatedAt
            }
            require(updated == 1) { CONFLICT_MESSAGE }
        }
    }

    override fun listRevisions(documentId: String): List<DocumentRevisionSummary> = transaction {
        DocumentContentRevisions.selectAll().where { DocumentContentRevisions.documentId eq documentId }
            .orderBy(DocumentContentRevisions.revision to SortOrder.DESC)
            .map(ResultRow::toDocumentRevisionSummary)
    }

    override fun findRevision(documentId: String, revision: Long): DocumentRevision? = transaction {
        DocumentContentRevisions.selectAll().where {
            (DocumentContentRevisions.documentId eq documentId) and
                (DocumentContentRevisions.revision eq revision)
        }.singleOrNull()?.toDocumentRevision()
    }

    private fun lockSpace(spaceId: String) {
        require(DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }.forUpdate().singleOrNull() != null) { "文档空间不存在" }
    }

    private fun requireActiveSpace(spaceId: String): DocumentSpace =
        DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toDocumentSpace() ?: throw IllegalArgumentException("文档空间不存在")

    private fun findActiveNodeRow(nodeId: String): ResultRow? = DocumentNodes.selectAll().where {
        (DocumentNodes.nodeId eq nodeId) and (DocumentNodes.status eq STATUS_ACTIVE)
    }.singleOrNull()

    private fun requireActiveNodeRow(nodeId: String, forUpdate: Boolean = false): ResultRow {
        val query = DocumentNodes.selectAll().where {
            (DocumentNodes.nodeId eq nodeId) and (DocumentNodes.status eq STATUS_ACTIVE)
        }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
            ?: throw IllegalArgumentException("文档节点不存在")
    }

    private fun insertNode(node: DocumentNode, markdown: String?) {
        DocumentNodes.insert {
            it[nodeId] = node.nodeId
            it[spaceId] = node.spaceId
            it[parentId] = node.parentId
            it[nodeType] = node.nodeType
            it[name] = node.name
            it[DocumentNodes.markdown] = markdown
            it[revision] = node.revision
            it[status] = STATUS_ACTIVE
            it[createdBy] = node.createdBy
            it[createdAt] = node.createdAt
            it[updatedBy] = node.updatedBy
            it[updatedAt] = node.updatedAt
        }
    }

    private fun insertRevision(value: DocumentRevision) {
        DocumentContentRevisions.insert {
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

private fun ResultRow.toDocumentSpace() = DocumentSpace(
    spaceId = this[DocumentSpaces.spaceId],
    name = this[DocumentSpaces.name],
    description = this[DocumentSpaces.description],
    myRole = DocumentSpace.ROLE_NONE,
    createdBy = this[DocumentSpaces.createdBy],
    createdAt = this[DocumentSpaces.createdAt],
    updatedAt = this[DocumentSpaces.updatedAt],
)

private fun ResultRow.toDocumentSpaceGrant() = DocumentSpaceGrant(
    spaceId = this[DocumentSpaceGrants.spaceId],
    principalType = this[DocumentSpaceGrants.principalType],
    principalId = this[DocumentSpaceGrants.principalId],
    role = this[DocumentSpaceGrants.role],
    includeDescendants = this[DocumentSpaceGrants.includeDescendants],
)

private fun ResultRow.toDocumentNode() = DocumentNode(
    nodeId = this[DocumentNodes.nodeId],
    spaceId = this[DocumentNodes.spaceId],
    parentId = this[DocumentNodes.parentId],
    nodeType = this[DocumentNodes.nodeType],
    name = this[DocumentNodes.name],
    excerpt = if (this[DocumentNodes.nodeType] == DocumentNode.TYPE_DOCUMENT) {
        markdownExcerpt(this[DocumentNodes.markdown].orEmpty())
    } else "",
    revision = this[DocumentNodes.revision],
    createdBy = this[DocumentNodes.createdBy],
    createdAt = this[DocumentNodes.createdAt],
    updatedBy = this[DocumentNodes.updatedBy],
    updatedAt = this[DocumentNodes.updatedAt],
)

private fun ResultRow.toDocument() = Document(
    documentId = this[DocumentNodes.nodeId],
    spaceId = this[DocumentNodes.spaceId],
    parentId = this[DocumentNodes.parentId],
    title = this[DocumentNodes.name],
    markdown = this[DocumentNodes.markdown].orEmpty(),
    revision = this[DocumentNodes.revision],
    createdBy = this[DocumentNodes.createdBy],
    createdAt = this[DocumentNodes.createdAt],
    updatedBy = this[DocumentNodes.updatedBy],
    updatedAt = this[DocumentNodes.updatedAt],
)

private fun Document.toNode() = DocumentNode(
    nodeId = documentId,
    spaceId = spaceId,
    parentId = parentId,
    nodeType = DocumentNode.TYPE_DOCUMENT,
    name = title,
    excerpt = markdownExcerpt(markdown),
    revision = revision,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
)

private fun ResultRow.toDocumentRevision() = DocumentRevision(
    documentId = this[DocumentContentRevisions.documentId],
    revision = this[DocumentContentRevisions.revision],
    title = this[DocumentContentRevisions.title],
    markdown = this[DocumentContentRevisions.markdown],
    editedBy = this[DocumentContentRevisions.editedBy],
    editedAt = this[DocumentContentRevisions.editedAt],
)

private fun ResultRow.toDocumentRevisionSummary() = DocumentRevisionSummary(
    documentId = this[DocumentContentRevisions.documentId],
    revision = this[DocumentContentRevisions.revision],
    title = this[DocumentContentRevisions.title],
    contentLength = this[DocumentContentRevisions.markdown].length,
    editedBy = this[DocumentContentRevisions.editedBy],
    editedAt = this[DocumentContentRevisions.editedAt],
)

private fun markdownExcerpt(markdown: String): String = markdown.lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotEmpty)
    ?.trimStart('#', '>', '-', '*', '+', '`', ' ')
    ?.take(160)
    ?.ifBlank { "空白文档" }
    ?: "空白文档"
