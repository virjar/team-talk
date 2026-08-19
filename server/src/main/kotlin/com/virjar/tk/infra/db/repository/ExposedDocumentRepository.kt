package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.document.DocumentRepository
import com.virjar.tk.domain.document.DocumentHomeRecord
import com.virjar.tk.domain.document.DocumentSpaceAccessCandidate
import com.virjar.tk.infra.db.DocumentContentRevisions
import com.virjar.tk.infra.db.DocumentNodes
import com.virjar.tk.infra.db.DocumentSpaceGrants
import com.virjar.tk.infra.db.DocumentSpaces
import com.virjar.tk.infra.db.DocumentUserRecents
import com.virjar.tk.infra.db.Users
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.util.ArrayDeque

class ExposedDocumentRepository : DocumentRepository {
    override fun findSpace(spaceId: String): DocumentSpace? = transaction {
        DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toDocumentSpace()
    }

    override fun listSpaceAccessCandidates(
        actorUid: String,
        directUnitIds: Set<String>,
        unitAndAncestorIds: Set<String>,
    ): List<DocumentSpaceAccessCandidate> = transaction {
        val candidates = linkedMapOf<String, DocumentSpaceAccessCandidate>()

        DocumentSpaces.selectAll().where {
            (DocumentSpaces.status eq STATUS_ACTIVE) and (DocumentSpaces.createdBy eq actorUid)
        }.forEach { row ->
            val space = row.toDocumentSpace()
            candidates[space.spaceId] = DocumentSpaceAccessCandidate(space, emptyList())
        }

        var relevantGrant: Op<Boolean> =
            (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                (DocumentSpaceGrants.principalId eq actorUid)
        if (directUnitIds.isNotEmpty()) {
            relevantGrant = relevantGrant or (
                (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                    (DocumentSpaceGrants.includeDescendants eq false) and
                    (DocumentSpaceGrants.principalId inList directUnitIds)
                )
        }
        if (unitAndAncestorIds.isNotEmpty()) {
            relevantGrant = relevantGrant or (
                (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                    (DocumentSpaceGrants.includeDescendants eq true) and
                    (DocumentSpaceGrants.principalId inList unitAndAncestorIds)
                )
        }

        DocumentSpaceGrants.join(
            otherTable = DocumentSpaces,
            joinType = JoinType.INNER,
            onColumn = DocumentSpaceGrants.spaceId,
            otherColumn = DocumentSpaces.spaceId,
        ).selectAll().where {
            (DocumentSpaces.status eq STATUS_ACTIVE) and relevantGrant
        }.forEach { row ->
            val space = row.toDocumentSpace()
            val grant = row.toDocumentSpaceGrant()
            val current = candidates[space.spaceId]
            candidates[space.spaceId] = if (current == null) {
                DocumentSpaceAccessCandidate(space, listOf(grant))
            } else {
                current.copy(grants = current.grants + grant)
            }
        }

        candidates.values.sortedWith(
            compareByDescending<DocumentSpaceAccessCandidate> { it.space.updatedAt }
                .thenBy { it.space.spaceId },
        )
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
        DocumentNodes.select(
            DocumentNodes.nodeId,
            DocumentNodes.spaceId,
            DocumentNodes.parentId,
            DocumentNodes.nodeType,
            DocumentNodes.name,
            DocumentNodes.excerpt,
            DocumentNodes.revision,
            DocumentNodes.createdBy,
            DocumentNodes.createdAt,
            DocumentNodes.updatedBy,
            DocumentNodes.updatedAt,
        ).where {
            val parentMatches = if (parentId == null) DocumentNodes.parentId.isNull() else DocumentNodes.parentId eq parentId
            (DocumentNodes.spaceId eq spaceId) and parentMatches and (DocumentNodes.status eq STATUS_ACTIVE)
        }.orderBy(DocumentNodes.nodeType to SortOrder.ASC, DocumentNodes.name to SortOrder.ASC)
            .map(ResultRow::toDocumentNode)
    }

    override fun findNode(nodeId: String): DocumentNode? = transaction {
        findActiveNodeRow(nodeId)?.toDocumentNode()
    }

    override fun findDocument(documentId: String): Document? = transaction {
        val row = findActiveNodeRow(documentId)
            ?.takeIf { it[DocumentNodes.nodeType] == DocumentNode.TYPE_DOCUMENT }
            ?: return@transaction null
        row.toDocument(resolveAncestorIds(row[DocumentNodes.spaceId], row[DocumentNodes.parentId]))
    }

    override fun createFolder(node: DocumentNode): DocumentNode = transaction {
        lockSpace(node.spaceId)
        val ancestorIds = resolveAncestorIds(node.spaceId, node.parentId)
        require(ancestorIds.size < Document.MAX_ANCESTOR_DEPTH) { "文档目录层级超过限制" }
        insertNode(node, markdown = null)
        node
    }

    override fun createDocument(document: Document, initialRevision: DocumentRevision): Document = transaction {
        lockSpace(document.spaceId)
        val ancestorIds = resolveAncestorIds(document.spaceId, document.parentId)
        insertNode(document.toNode(), document.markdown)
        insertRevision(initialRevision)
        // 创建与“创建者最近访问”属于同一提交，避免辅助索引失败却留下重复业务文档。
        touchRecentDocumentInternal(document.createdBy, document.documentId, document.createdAt)
        document.copy(ancestorIds = ancestorIds)
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
            return@transaction current.toDocument(
                resolveAncestorIds(current[DocumentNodes.spaceId], current[DocumentNodes.parentId]),
            )
        }
        val nextRevision = expectedRevision + 1
        val updated = DocumentNodes.update({
            (DocumentNodes.nodeId eq documentId) and
                (DocumentNodes.status eq STATUS_ACTIVE) and
                (DocumentNodes.revision eq expectedRevision)
        }) {
            it[name] = title
            it[excerpt] = markdownExcerpt(markdown)
            it[DocumentNodes.markdown] = markdown
            it[revision] = nextRevision
            it[updatedBy] = actorUid
            it[DocumentNodes.updatedAt] = updatedAt
        }
        require(updated == 1) { CONFLICT_MESSAGE }
        insertRevision(DocumentRevision(documentId, nextRevision, title, markdown, actorUid, updatedAt))
        val result = requireActiveNodeRow(documentId)
        result.toDocument(resolveAncestorIds(result[DocumentNodes.spaceId], result[DocumentNodes.parentId]))
    }

    override fun moveNode(
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentNode = transaction {
        val spaceId = requireActiveNodeSpaceId(nodeId)
        lockSpace(spaceId)
        val current = requireActiveNodeRow(nodeId, forUpdate = true)
        require(current[DocumentNodes.revision] == expectedRevision) { CONFLICT_MESSAGE }
        val targetAncestorIds = resolveAncestorIds(spaceId, parentId)
        require(nodeId !in targetAncestorIds) { "目录不能移动到自己的下级目录" }
        val subtreeAncestorContribution = if (current[DocumentNodes.nodeType] == DocumentNode.TYPE_FOLDER) {
            maxActiveSubtreeAncestorContribution(spaceId, nodeId)
        } else {
            0
        }
        require(
            targetAncestorIds.size.toLong() + subtreeAncestorContribution <= Document.MAX_ANCESTOR_DEPTH.toLong(),
        ) { "移动后文档目录层级超过限制" }
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
            val spaceId = requireActiveNodeSpaceId(nodeId)
            lockSpace(spaceId)
            val current = requireActiveNodeRow(nodeId, forUpdate = true)
            require(current[DocumentNodes.revision] == expectedRevision) { CONFLICT_MESSAGE }
            if (current[DocumentNodes.nodeType] == DocumentNode.TYPE_FOLDER) {
                require(!hasActiveChildren(nodeId)) { "请先清空文件夹" }
            }
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

    override fun touchRecentDocument(actorUid: String, documentId: String, accessedAt: Long) {
        transaction {
            touchRecentDocumentInternal(actorUid, documentId, accessedAt)
        }
    }

    override fun listRecentDocuments(
        actorUid: String,
        accessibleSpaceIds: Set<String>,
        limit: Int,
    ): List<DocumentHomeRecord> = transaction {
        if (accessibleSpaceIds.isEmpty()) return@transaction emptyList()
        recentDocumentJoin().select(
            DocumentNodes.nodeId,
            DocumentNodes.spaceId,
            DocumentSpaces.name,
            DocumentNodes.name,
            DocumentNodes.excerpt,
            DocumentNodes.createdBy,
            Users.name,
            DocumentNodes.createdAt,
            DocumentNodes.updatedAt,
            DocumentUserRecents.accessedAt,
        ).where {
            (DocumentUserRecents.uid eq actorUid) and
                (DocumentNodes.spaceId inList accessibleSpaceIds) and
                (DocumentNodes.nodeType eq DocumentNode.TYPE_DOCUMENT) and
                (DocumentNodes.status eq STATUS_ACTIVE) and
                (DocumentSpaces.status eq STATUS_ACTIVE)
        }.orderBy(
            DocumentUserRecents.accessedAt to SortOrder.DESC,
            DocumentNodes.nodeId to SortOrder.ASC,
        ).limit(limit).map { row ->
            row.toDocumentHomeRecord(row[DocumentUserRecents.accessedAt])
        }
    }

    override fun listRecentlyCreatedDocuments(
        accessibleSpaceIds: Set<String>,
        limit: Int,
    ): List<DocumentHomeRecord> = transaction {
        if (accessibleSpaceIds.isEmpty()) return@transaction emptyList()
        documentSpaceJoin().select(
            DocumentNodes.nodeId,
            DocumentNodes.spaceId,
            DocumentSpaces.name,
            DocumentNodes.name,
            DocumentNodes.excerpt,
            DocumentNodes.createdBy,
            Users.name,
            DocumentNodes.createdAt,
            DocumentNodes.updatedAt,
        ).where {
            (DocumentNodes.spaceId inList accessibleSpaceIds) and
                (DocumentNodes.nodeType eq DocumentNode.TYPE_DOCUMENT) and
                (DocumentNodes.status eq STATUS_ACTIVE) and
                (DocumentSpaces.status eq STATUS_ACTIVE)
        }.orderBy(
            DocumentNodes.createdAt to SortOrder.DESC,
            DocumentNodes.nodeId to SortOrder.ASC,
        ).limit(limit).map { row ->
            row.toDocumentHomeRecord(accessedAt = 0)
        }
    }

    private fun documentSpaceJoin() = DocumentNodes.join(
        otherTable = DocumentSpaces,
        joinType = JoinType.INNER,
        onColumn = DocumentNodes.spaceId,
        otherColumn = DocumentSpaces.spaceId,
    ).join(
        otherTable = Users,
        joinType = JoinType.INNER,
        onColumn = DocumentNodes.createdBy,
        otherColumn = Users.uid,
    )

    private fun recentDocumentJoin() = DocumentUserRecents.join(
        otherTable = DocumentNodes,
        joinType = JoinType.INNER,
        onColumn = DocumentUserRecents.documentId,
        otherColumn = DocumentNodes.nodeId,
    ).join(
        otherTable = DocumentSpaces,
        joinType = JoinType.INNER,
        onColumn = DocumentNodes.spaceId,
        otherColumn = DocumentSpaces.spaceId,
    ).join(
        otherTable = Users,
        joinType = JoinType.INNER,
        onColumn = DocumentNodes.createdBy,
        otherColumn = Users.uid,
    )

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

    private fun requireActiveNodeSpaceId(nodeId: String): String = DocumentNodes
        .select(DocumentNodes.spaceId)
        .where {
            (DocumentNodes.nodeId eq nodeId) and (DocumentNodes.status eq STATUS_ACTIVE)
        }
        .singleOrNull()
        ?.get(DocumentNodes.spaceId)
        ?: throw IllegalArgumentException("文档节点不存在")

    private fun resolveAncestorIds(spaceId: String, parentId: String?): List<String> {
        if (parentId == null) return emptyList()
        val leafToRoot = ArrayList<String>()
        val visited = hashSetOf<String>()
        var cursor: String? = parentId
        while (cursor != null) {
            require(leafToRoot.size < Document.MAX_ANCESTOR_DEPTH) { "文档目录层级超过限制" }
            require(visited.add(cursor)) { "文档目录存在循环" }
            val row = requireActiveNodeRow(cursor)
            require(row[DocumentNodes.spaceId] == spaceId) { "文档目录不属于当前空间" }
            require(row[DocumentNodes.nodeType] == DocumentNode.TYPE_FOLDER) { "文档父节点不是文件夹" }
            leafToRoot += cursor
            cursor = row[DocumentNodes.parentId]
        }
        leafToRoot.reverse()
        return leafToRoot
    }

    /**
     * 空间结构写已持有 DocumentSpaces 行锁；在同一事务中读取活动子树，
     * 返回子树移动后相对于目标目录新增的最大祖先层数：文件夹节点需要计入自身，文档节点只计算
     * 其文件夹祖先。这样既允许文档拥有 128 个祖先，也不会产生无法继续容纳文档的第 129 层文件夹。
     * 同时防御历史脏数据中的环。
     */
    private fun maxActiveSubtreeAncestorContribution(spaceId: String, rootNodeId: String): Int {
        val rows = DocumentNodes
            .select(DocumentNodes.nodeId, DocumentNodes.parentId, DocumentNodes.nodeType)
            .where {
                (DocumentNodes.spaceId eq spaceId) and (DocumentNodes.status eq STATUS_ACTIVE)
            }
            .toList()
        val childrenByParent = rows.groupBy(
            keySelector = { it[DocumentNodes.parentId] },
            valueTransform = { it[DocumentNodes.nodeId] },
        )
        val nodeTypeById = rows.associate { it[DocumentNodes.nodeId] to it[DocumentNodes.nodeType] }
        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = hashSetOf<String>()
        queue.add(rootNodeId to 0)
        var maxContribution = 1
        while (queue.isNotEmpty()) {
            val (nodeId, depth) = queue.removeFirst()
            require(visited.add(nodeId)) { "文档目录存在循环" }
            require(depth <= Document.MAX_ANCESTOR_DEPTH) { "文档目录层级超过限制" }
            val contribution = if (nodeTypeById[nodeId] == DocumentNode.TYPE_FOLDER) depth + 1 else depth
            maxContribution = maxOf(maxContribution, contribution)
            childrenByParent[nodeId].orEmpty().forEach { childId ->
                queue.add(childId to depth + 1)
            }
        }
        return maxContribution
    }

    private fun hasActiveChildren(nodeId: String): Boolean = DocumentNodes
        .select(DocumentNodes.nodeId)
        .where {
            (DocumentNodes.parentId eq nodeId) and (DocumentNodes.status eq STATUS_ACTIVE)
        }
        .limit(1)
        .any()

    private fun insertNode(node: DocumentNode, markdown: String?) {
        DocumentNodes.insert {
            it[nodeId] = node.nodeId
            it[spaceId] = node.spaceId
            it[parentId] = node.parentId
            it[nodeType] = node.nodeType
            it[name] = node.name
            it[excerpt] = if (node.nodeType == DocumentNode.TYPE_DOCUMENT) markdownExcerpt(markdown.orEmpty()) else ""
            it[DocumentNodes.markdown] = markdown
            it[revision] = node.revision
            it[status] = STATUS_ACTIVE
            it[createdBy] = node.createdBy
            it[createdAt] = node.createdAt
            it[updatedBy] = node.updatedBy
            it[updatedAt] = node.updatedAt
        }
    }

    private fun touchRecentDocumentInternal(actorUid: String, documentId: String, accessedAt: Long) {
        require(Users.selectAll().where { Users.uid eq actorUid }.forUpdate().singleOrNull() != null) {
            "用户不存在"
        }
        val latestAccess = DocumentUserRecents.selectAll()
            .where { DocumentUserRecents.uid eq actorUid }
            .orderBy(DocumentUserRecents.accessedAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DocumentUserRecents.accessedAt)
            ?: 0L
        val orderedAccessAt = maxOf(accessedAt, latestAccess + 1)
        DocumentUserRecents.upsert(DocumentUserRecents.uid, DocumentUserRecents.documentId) {
            it[uid] = actorUid
            it[DocumentUserRecents.documentId] = documentId
            it[DocumentUserRecents.accessedAt] = orderedAccessAt
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
    excerpt = this[DocumentNodes.excerpt],
    revision = this[DocumentNodes.revision],
    createdBy = this[DocumentNodes.createdBy],
    createdAt = this[DocumentNodes.createdAt],
    updatedBy = this[DocumentNodes.updatedBy],
    updatedAt = this[DocumentNodes.updatedAt],
)

private fun ResultRow.toDocument(ancestorIds: List<String> = emptyList()) = Document(
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
    ancestorIds = ancestorIds,
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

private fun ResultRow.toDocumentHomeRecord(accessedAt: Long) = DocumentHomeRecord(
    documentId = this[DocumentNodes.nodeId],
    spaceId = this[DocumentNodes.spaceId],
    spaceName = this[DocumentSpaces.name],
    title = this[DocumentNodes.name],
    excerpt = this[DocumentNodes.excerpt],
    createdBy = this[DocumentNodes.createdBy],
    creatorName = this[Users.name],
    createdAt = this[DocumentNodes.createdAt],
    updatedAt = this[DocumentNodes.updatedAt],
    accessedAt = accessedAt,
)

private fun markdownExcerpt(markdown: String): String = markdown.lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotEmpty)
    ?.trimStart('#', '>', '-', '*', '+', '`', ' ')
    ?.take(160)
    ?.ifBlank { "空白文档" }
    ?: "空白文档"
