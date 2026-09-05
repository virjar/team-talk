package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.ActiveDocumentIdentity
import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.document.DocumentCustodyTransferReceipt
import com.virjar.tk.server.domain.document.DocumentHomeAccessCandidate
import com.virjar.tk.server.domain.document.DocumentHomeAccessSnapshot
import com.virjar.tk.server.domain.document.DocumentHomeRecord
import com.virjar.tk.server.domain.document.DocumentReadAccessSnapshot
import com.virjar.tk.server.domain.document.DocumentSpaceAccessCandidate
import com.virjar.tk.server.domain.document.DocumentSpaceAccessPage
import com.virjar.tk.server.domain.document.DocumentSpacePageAnchor
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.infra.db.DocumentContentRevisions
import com.virjar.tk.server.infra.db.DocumentEmbeddedAssets
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaceCustodyTransfers
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.DocumentUserRecents
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedReadTransaction
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.EmbeddedAsset
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

/**
 * 空间、惰性文档树、修订与首页索引的读侧投影。
 * 每个入口都消费调用方持有的 repeatable-read 事务；此适配器绝不
 * 在领域服务背后打开第二个快照。
 */
internal class ExposedDocumentReadStore(
    private val nodeRows: ExposedDocumentNodeRows,
    private val hierarchy: ExposedDocumentHierarchy,
) {
    fun findSpace(transaction: PgReadTransactionContext, spaceId: String): DocumentSpace? =
        transaction.inExposedReadTransaction { findSpaceInternal(spaceId) }

    fun isSpaceArchivedByCommand(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
    ): Boolean = transaction.inExposedReadTransaction {
        DocumentSpaces.select(DocumentSpaces.spaceId).where {
            (DocumentSpaces.spaceId eq spaceId) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_DELETED) and
                (DocumentSpaces.archiveActorUid eq actorUid) and
                (DocumentSpaces.archiveCommandId eq operationId)
        }.limit(1).any()
    }

    fun readAccessSnapshot(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
    ): DocumentReadAccessSnapshot = transaction.inExposedReadTransaction {
        val actorAccess = ExposedDocumentActorAccess.read(transaction, actorUid)
        val space = findSpaceInternal(spaceId)
        DocumentReadAccessSnapshot(
            candidates = space?.let {
                listOf(DocumentSpaceAccessCandidate(it, listGrantFactsInternal(spaceId)))
            }.orEmpty(),
            directUnitIds = actorAccess.directUnitIds,
            unitAndAncestorIds = actorAccess.unitAndAncestorIds,
        )
    }

    fun readAccessibleSpacePage(
        transaction: PgReadTransactionContext,
        actorUid: String,
        after: DocumentSpacePageAnchor?,
        pageSize: Int,
    ): DocumentSpaceAccessPage = transaction.inExposedReadTransaction {
        require(pageSize in 1..com.virjar.tk.protocol.model.DocumentSpacePage.MAX_PAGE_SIZE) {
            "Document space page size is out of range"
        }
        val snapshotVersion = ExposedDocumentDirectoryRevision.read(transaction, actorUid)
        if (after != null && after.snapshotVersion != snapshotVersion) {
            return@inExposedReadTransaction DocumentSpaceAccessPage(
                snapshot = DocumentReadAccessSnapshot(
                    candidates = emptyList(),
                    directUnitIds = emptySet(),
                    unitAndAncestorIds = emptySet(),
                ),
                nextAnchor = null,
                snapshotVersion = snapshotVersion,
                snapshotChanged = true,
            )
        }
        validateOwnedSpaceCapacity(actorUid)
        val actorAccess = ExposedDocumentActorAccess.read(transaction, actorUid)
        val relevantGrant = relevantGrantCondition(actorUid, actorAccess)
        val rows = documentSpaceGrantJoin()
            .select(DocumentSpaces.columns)
            .where {
                var predicate =
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                        (
                            (DocumentSpaces.stewardUid eq actorUid) or relevantGrant
                            )
                after?.let { cursor ->
                    predicate = predicate and (DocumentSpaces.spaceId greater cursor.spaceId)
                }
                predicate
            }
            // 一个用户可能同时匹配直接用户授权和同一空间的多个部门授权。
            // DISTINCT 必须在 keyset limit 之前于 PostgreSQL 中执行，绝不能在物化之后。
            .withDistinct()
            .orderBy(DocumentSpaces.spaceId to SortOrder.ASC)
            .limit(pageSize + 1)
            .map(ResultRow::toDocumentSpace)

        val hasMore = rows.size > pageSize
        val spaces = if (hasMore) rows.subList(0, pageSize) else rows
        val grantsBySpaceId = listRelevantPageGrants(
            spaceIds = spaces.map(DocumentSpace::spaceId),
            relevantGrant = relevantGrant,
            pageSize = pageSize,
        )
        DocumentSpaceAccessPage(
            snapshot = DocumentReadAccessSnapshot(
                candidates = spaces.map { space ->
                    DocumentSpaceAccessCandidate(space, grantsBySpaceId[space.spaceId].orEmpty())
                },
                directUnitIds = actorAccess.directUnitIds,
                unitAndAncestorIds = actorAccess.unitAndAncestorIds,
            ),
            nextAnchor = if (hasMore) {
                DocumentSpacePageAnchor(
                    spaceId = checkNotNull(spaces.lastOrNull()).spaceId,
                    snapshotVersion = snapshotVersion,
                )
            } else {
                null
            },
            snapshotVersion = snapshotVersion,
        )
    }

    /**
     * Keyset 分页绝不能把一个不可能的超容量拥有者投影变成多个
     * 各自有效的页面。在同一 repeatable-read 快照中
     * 探测永久写入边界之后的一行，并在发布第一页之前失败。
     */
    private fun validateOwnedSpaceCapacity(actorUid: String) {
        val ownedProbe = DocumentSpaces.select(DocumentSpaces.spaceId).where {
            (DocumentSpaces.ownerPrincipalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                (DocumentSpaces.ownerPrincipalId eq actorUid) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }.orderBy(DocumentSpaces.spaceId to SortOrder.ASC)
            .limit(DocumentCapacityPolicy.ACTIVE_SPACE_OVERFLOW_PROBE_LIMIT)
            .toList()
        DocumentCapacityPolicy.requireOwnedSpaceProjection(ownedProbe.size)
    }

    fun findUser(transaction: PgReadTransactionContext, uid: String): User? = transaction.inExposedReadTransaction {
        Users.selectAll().where { Users.uid eq uid }.singleOrNull()?.toDocumentAclUser()
    }

    private fun relevantGrantCondition(
        actorUid: String,
        actorAccess: DocumentActorOrganizationAccess,
    ): Op<Boolean> {
        var principalMatches: Op<Boolean> =
            (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_USER) and
                (DocumentSpaceGrants.principalId eq actorUid)
        if (actorAccess.directUnitIds.isNotEmpty()) {
            principalMatches = principalMatches or (
                (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                    (DocumentSpaceGrants.includeDescendants eq false) and
                    (DocumentSpaceGrants.principalId inList actorAccess.directUnitIds)
                )
        }
        if (actorAccess.unitAndAncestorIds.isNotEmpty()) {
            principalMatches = principalMatches or (
                (DocumentSpaceGrants.principalType eq DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT) and
                    (DocumentSpaceGrants.includeDescendants eq true) and
                    (DocumentSpaceGrants.principalId inList actorAccess.unitAndAncestorIds)
                )
        }
        return (DocumentSpaceGrants.role greaterEq DocumentSpace.ROLE_VIEWER) and
            (DocumentSpaceGrants.role lessEq DocumentSpace.ROLE_ADMIN) and principalMatches
    }

    private fun listRelevantPageGrants(
        spaceIds: List<String>,
        relevantGrant: Op<Boolean>,
        pageSize: Int,
    ): Map<String, List<DocumentSpaceGrant>> {
        if (spaceIds.isEmpty()) return emptyMap()
        val aggregateLimit = pageSize * DocumentSpaceGrant.MAX_GRANTS_PER_SPACE
        val grants = DocumentSpaceGrants.selectAll().where {
            (DocumentSpaceGrants.spaceId inList spaceIds) and relevantGrant
        }.orderBy(
            DocumentSpaceGrants.spaceId to SortOrder.ASC,
            DocumentSpaceGrants.principalType to SortOrder.ASC,
            DocumentSpaceGrants.principalId to SortOrder.ASC,
        ).limit(aggregateLimit + 1).map(ResultRow::toDocumentSpaceGrant)
        require(grants.size <= aggregateLimit) { "文档空间分页授权投影超过限制" }
        return grants.groupBy(DocumentSpaceGrant::spaceId).also { bySpace ->
            require(bySpace.values.all { it.size <= DocumentSpaceGrant.MAX_GRANTS_PER_SPACE }) {
                "文档空间授权数量超过限制"
            }
        }
    }

    fun findActiveOrganizationUnitName(
        transaction: PgReadTransactionContext,
        unitId: String,
    ): String? = transaction.inExposedReadTransaction {
        OrganizationUnits.selectAll().where {
            OrganizationUnits.unitId eq unitId
        }.singleOrNull()
            ?.takeIf { it[OrganizationUnits.status] == OrganizationUnit.STATUS_ACTIVE }
            ?.get(OrganizationUnits.name)
    }

    fun listGrants(transaction: PgReadTransactionContext, spaceId: String): List<DocumentSpaceGrant> =
        transaction.inExposedReadTransaction { listResolvedGrantsInternal(spaceId) }

    fun findCustodyTransferReceipt(
        transaction: PgReadTransactionContext,
        operationId: String,
    ): DocumentCustodyTransferReceipt? = transaction.inExposedReadTransaction {
        DocumentSpaceCustodyTransfers.selectAll().where {
            DocumentSpaceCustodyTransfers.operationId eq operationId
        }.singleOrNull()?.let { row ->
            DocumentCustodyTransferReceipt(
                operationId = row[DocumentSpaceCustodyTransfers.operationId],
                spaceId = row[DocumentSpaceCustodyTransfers.spaceId],
                actorUid = row[DocumentSpaceCustodyTransfers.actorUid],
                fingerprint = row[DocumentSpaceCustodyTransfers.fingerprint],
                ownerPrincipalType = row[DocumentSpaceCustodyTransfers.toPrincipalType],
                ownerPrincipalId = row[DocumentSpaceCustodyTransfers.toPrincipalId],
                stewardUid = row[DocumentSpaceCustodyTransfers.toStewardUid],
                custodyRevision = row[DocumentSpaceCustodyTransfers.resultingRevision],
            )
        }
    }

    fun listNodes(
        transaction: PgReadTransactionContext,
        spaceId: String,
        parentId: String?,
    ): List<DocumentNode> = transaction.inExposedReadTransaction {
        listNodesInternal(spaceId, parentId)
    }

    fun findNode(transaction: PgReadTransactionContext, spaceId: String, nodeId: String): DocumentNode? =
        transaction.inExposedReadTransaction { findNodeInternal(spaceId, nodeId) }

    fun findPathSpine(
        transaction: PgReadTransactionContext,
        spaceId: String,
        nodeId: String,
    ): DocumentPathSpine = transaction.inExposedReadTransaction {
        hierarchy.resolvePathSpine(transaction.requireExposedReadTransaction(), spaceId, nodeId)
    }

    fun isNodeDeletedByCommand(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    ): Boolean = transaction.inExposedReadTransaction {
        DocumentNodes.select(DocumentNodes.nodeId).where {
            (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_DELETED) and
                (DocumentNodes.updatedBy eq actorUid) and
                (DocumentNodes.revision eq expectedRevision + 1) and
                (DocumentNodes.deleteCommandId eq operationId)
        }.limit(1).any()
    }

    fun findDocument(transaction: PgReadTransactionContext, spaceId: String, documentId: String): Document? =
        transaction.inExposedReadTransaction {
            findDocumentInternal(transaction.requireExposedReadTransaction(), spaceId, documentId)
        }

    fun findActiveDocumentIdentity(
        transaction: PgReadTransactionContext,
        spaceId: String,
        documentId: String,
    ): ActiveDocumentIdentity? = transaction.inExposedReadTransaction {
        DocumentNodes.select(DOCUMENT_NODE_IDENTITY_PROJECTION).where {
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq documentId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }.singleOrNull()?.let { row ->
            ActiveDocumentIdentity(
                documentId = row[DocumentNodes.nodeId],
                spaceId = row[DocumentNodes.spaceId],
            )
        }
    }

    fun findActiveEmbeddedAssetSpaceIds(
        transaction: PgReadTransactionContext,
        path: String,
        limit: Int,
    ): List<String> = transaction.inExposedReadTransaction {
        require(limit > 0) { "document attachment access limit must be positive" }
        documentAssetSpaceJoin().select(DocumentNodes.spaceId).where {
            ((DocumentEmbeddedAssets.attachmentPath eq path) or
                (DocumentEmbeddedAssets.thumbnailPath eq path)) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }.withDistinct()
            .orderBy(DocumentNodes.spaceId to SortOrder.ASC)
            .limit(limit)
            .map { row -> row[DocumentNodes.spaceId] }
    }

    fun findKnownEmbeddedAssets(
        transaction: PgReadTransactionContext,
        documentId: String,
        assetIds: Set<String>,
    ): List<EmbeddedAsset> = transaction.inExposedReadTransaction {
        loadKnownDocumentAssets(documentId, assetIds)
    }

    fun listRevisions(
        transaction: PgReadTransactionContext,
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ): List<DocumentRevisionSummary> = transaction.inExposedReadTransaction {
        listRevisionsInternal(documentId, beforeRevision, limit)
    }

    private fun listRevisionsInternal(
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ): List<DocumentRevisionSummary> =
        DocumentContentRevisions.select(DOCUMENT_REVISION_SUMMARY_PROJECTION).where {
            if (beforeRevision == 0L) {
                DocumentContentRevisions.documentId eq documentId
            } else {
                (DocumentContentRevisions.documentId eq documentId) and
                    (DocumentContentRevisions.revision less beforeRevision)
            }
        }
            .orderBy(DocumentContentRevisions.revision to SortOrder.DESC)
            .limit(limit)
            .map(ResultRow::toDocumentRevisionSummary)

    fun findRevision(
        transaction: PgReadTransactionContext,
        documentId: String,
        revision: Long,
    ): DocumentRevision? = transaction.inExposedReadTransaction {
        findRevisionInternal(documentId, revision)
    }

    private fun findRevisionInternal(documentId: String, revision: Long): DocumentRevision? =
        DocumentContentRevisions.selectAll().where {
            (DocumentContentRevisions.documentId eq documentId) and
                (DocumentContentRevisions.revision eq revision)
        }.singleOrNull()?.toDocumentRevision(loadDocumentAssetsAtRevision(documentId, revision))

    fun listRecentDocuments(
        transaction: PgReadTransactionContext,
        actorUid: String,
        limit: Int,
    ): DocumentHomeAccessSnapshot = transaction.inExposedReadTransaction {
        val actorAccess = ExposedDocumentActorAccess.read(transaction, actorUid)
        val relevantGrant = relevantGrantCondition(actorUid, actorAccess)
        homeAccessSnapshot(
            records = listRecentDocumentsInternal(actorUid, relevantGrant, limit),
            actorAccess = actorAccess,
            relevantGrant = relevantGrant,
        )
    }

    private fun listRecentDocumentsInternal(
        actorUid: String,
        relevantGrant: Op<Boolean>,
        limit: Int,
    ): List<DocumentHomeRecord> {
        return recentDocumentJoin().select(
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
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                (
                    (DocumentSpaces.stewardUid eq actorUid) or relevantGrant
                    )
        }.withDistinct().orderBy(
            DocumentUserRecents.accessedAt to SortOrder.DESC,
            DocumentNodes.nodeId to SortOrder.ASC,
        ).limit(limit).map { row ->
            row.toDocumentHomeRecord(row[DocumentUserRecents.accessedAt])
        }
    }

    fun listRecentlyCreatedDocuments(
        transaction: PgReadTransactionContext,
        actorUid: String,
        limit: Int,
    ): DocumentHomeAccessSnapshot = transaction.inExposedReadTransaction {
        val actorAccess = ExposedDocumentActorAccess.read(transaction, actorUid)
        val relevantGrant = relevantGrantCondition(actorUid, actorAccess)
        homeAccessSnapshot(
            records = listRecentlyCreatedDocumentsInternal(actorUid, relevantGrant, limit),
            actorAccess = actorAccess,
            relevantGrant = relevantGrant,
        )
    }

    private fun listRecentlyCreatedDocumentsInternal(
        actorUid: String,
        relevantGrant: Op<Boolean>,
        limit: Int,
    ): List<DocumentHomeRecord> {
        return documentSpaceJoin().select(
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
            (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                (
                    (DocumentSpaces.stewardUid eq actorUid) or relevantGrant
                    )
        }.withDistinct().orderBy(
            DocumentNodes.createdAt to SortOrder.DESC,
            DocumentNodes.nodeId to SortOrder.ASC,
        ).limit(limit).map { row ->
            row.toDocumentHomeRecord(accessedAt = 0)
        }
    }

    private fun homeAccessSnapshot(
        records: List<DocumentHomeRecord>,
        actorAccess: DocumentActorOrganizationAccess,
        relevantGrant: Op<Boolean>,
    ): DocumentHomeAccessSnapshot {
        val spaceIds = records.mapTo(linkedSetOf(), DocumentHomeRecord::spaceId)
        val spacesById = if (spaceIds.isEmpty()) {
            emptyMap()
        } else {
            DocumentSpaces.selectAll().where {
                (DocumentSpaces.spaceId inList spaceIds) and
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
            }.associate { row -> row[DocumentSpaces.spaceId] to row.toDocumentSpace() }
        }
        val grantsBySpaceId = listRelevantPageGrants(
            spaceIds = spaceIds.toList(),
            relevantGrant = relevantGrant,
            pageSize = spaceIds.size.coerceAtLeast(1),
        )
        return DocumentHomeAccessSnapshot(
            candidates = records.map { record ->
                val space = checkNotNull(spacesById[record.spaceId]) {
                    "document-home candidate escaped its active space snapshot"
                }
                check(space.name == record.spaceName) {
                    "document-home candidate space projection is inconsistent"
                }
                DocumentHomeAccessCandidate(
                    record = record,
                    space = space,
                    grants = grantsBySpaceId[record.spaceId].orEmpty(),
                )
            },
            directUnitIds = actorAccess.directUnitIds,
            unitAndAncestorIds = actorAccess.unitAndAncestorIds,
        )
    }

    private fun findSpaceInternal(spaceId: String): DocumentSpace? =
        DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }.singleOrNull()?.toDocumentSpace()

    private fun listGrantFactsInternal(spaceId: String): List<DocumentSpaceGrant> {
        val grants = DocumentSpaceGrants.selectAll().where { DocumentSpaceGrants.spaceId eq spaceId }
            .orderBy(
                DocumentSpaceGrants.principalType to SortOrder.ASC,
                DocumentSpaceGrants.principalId to SortOrder.ASC,
            )
            .limit(DocumentSpaceGrant.MAX_GRANTS_PER_SPACE + 1)
            .map(ResultRow::toDocumentSpaceGrant)
        require(grants.size <= DocumentSpaceGrant.MAX_GRANTS_PER_SPACE) {
            "文档空间授权数量超过限制"
        }
        return grants
    }

    private fun listResolvedGrantsInternal(spaceId: String): List<DocumentSpaceGrant> {
        val grants = listGrantFactsInternal(spaceId)
        val userIds = grants.asSequence()
            .filter { it.principalType == DocumentSpaceGrant.PRINCIPAL_USER }
            .mapTo(linkedSetOf(), DocumentSpaceGrant::principalId)
        val activeUnitIds = grants.asSequence()
            .filter { it.principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT }
            .mapTo(linkedSetOf(), DocumentSpaceGrant::principalId)
        val userNames = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            Users.select(Users.uid, Users.name).where { Users.uid inList userIds }
                .associate { row -> row[Users.uid] to row[Users.name] }
        }
        val activeUnitNames = if (activeUnitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationUnits.select(OrganizationUnits.unitId, OrganizationUnits.name).where {
                (OrganizationUnits.unitId inList activeUnitIds) and
                    (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
            }.associate { row -> row[OrganizationUnits.unitId] to row[OrganizationUnits.name] }
        }
        return resolveDocumentGrantDisplayNames(grants, userNames, activeUnitNames)
    }

    private fun listNodesInternal(spaceId: String, parentId: String?): List<DocumentNode> {
        // 协议 DOCUMENT_NODE_SIBLING_ORDER 的 SQL 等价实现。
        val rows = DocumentNodes.select(DOCUMENT_NODE_SUMMARY_PROJECTION).where {
            val parentMatches = if (parentId == null) {
                DocumentNodes.parentId.isNull()
            } else {
                DocumentNodes.parentId eq parentId
            }
            (DocumentNodes.spaceId eq spaceId) and
                parentMatches and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE)
        }.orderBy(
            DocumentNodes.createdAt to SortOrder.ASC,
            DocumentNodes.nodeId to SortOrder.ASC,
        ).limit(DocumentCapacityPolicy.ACTIVE_CHILD_OVERFLOW_PROBE_LIMIT).toList()
        DocumentCapacityPolicy.requireChildProjection(rows.size)
        val parentsWithChildren = hierarchy.parentsWithActiveChildren(
            spaceId = spaceId,
            candidateNodeIds = rows.map { it[DocumentNodes.nodeId] },
        )
        return rows.map { row ->
            row.toDocumentNode(hasChildren = row[DocumentNodes.nodeId] in parentsWithChildren)
        }
    }

    private fun findNodeInternal(spaceId: String, nodeId: String): DocumentNode? =
        nodeRows.findActiveSummary(spaceId, nodeId)
            ?.toDocumentNode(hierarchy.hasActiveChildren(spaceId, nodeId))

    private fun findDocumentInternal(transaction: Transaction, spaceId: String, documentId: String): Document? {
        val row = nodeRows.findActiveContent(spaceId, documentId) ?: return null
        return row.toDocument(
            hierarchy.resolveAncestorIds(
                transaction,
                row[DocumentNodes.spaceId],
                row[DocumentNodes.parentId],
            ),
            loadDocumentAssetsAtRevision(documentId, row[DocumentNodes.revision]),
        )
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
    ).join(
        otherTable = DocumentSpaceGrants,
        joinType = JoinType.LEFT,
        onColumn = DocumentSpaces.spaceId,
        otherColumn = DocumentSpaceGrants.spaceId,
    )

    private fun documentAssetSpaceJoin() = DocumentEmbeddedAssets.join(
        otherTable = DocumentNodes,
        joinType = JoinType.INNER,
        onColumn = DocumentEmbeddedAssets.documentId,
        otherColumn = DocumentNodes.nodeId,
    ).join(
        otherTable = DocumentSpaces,
        joinType = JoinType.INNER,
        onColumn = DocumentNodes.spaceId,
        otherColumn = DocumentSpaces.spaceId,
    )

    private fun documentSpaceGrantJoin() = DocumentSpaces.join(
        otherTable = DocumentSpaceGrants,
        joinType = JoinType.LEFT,
        onColumn = DocumentSpaces.spaceId,
        otherColumn = DocumentSpaceGrants.spaceId,
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
    ).join(
        otherTable = DocumentSpaceGrants,
        joinType = JoinType.LEFT,
        onColumn = DocumentSpaces.spaceId,
        otherColumn = DocumentSpaceGrants.spaceId,
    )
}

internal inline fun <T> PgReadTransactionContext.inExposedReadTransaction(block: () -> T): T {
    requireExposedReadTransaction()
    return block()
}

internal fun resolveDocumentGrantDisplayNames(
    grants: List<DocumentSpaceGrant>,
    userNames: Map<String, String>,
    activeUnitNames: Map<String, String>,
): List<DocumentSpaceGrant> = grants.map { grant ->
    grant.copy(
        displayName = when (grant.principalType) {
            DocumentSpaceGrant.PRINCIPAL_USER -> userNames[grant.principalId]
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> activeUnitNames[grant.principalId]
            else -> null
        },
    )
}
