package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.ActiveDocumentIdentity
import com.virjar.tk.server.domain.document.DocumentHomeRecord
import com.virjar.tk.server.domain.document.DocumentHomeAccessSnapshot
import com.virjar.tk.server.domain.document.DocumentCustodyTransferReceipt
import com.virjar.tk.server.domain.document.DocumentPolicyMutationCommit
import com.virjar.tk.server.domain.document.DocumentPolicyMutationFence
import com.virjar.tk.server.domain.document.DocumentPolicyMutationKind
import com.virjar.tk.server.domain.document.DocumentPolicyMutationReceipt
import com.virjar.tk.server.domain.document.DocumentNodeMoveReceipt
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentReadAccessSnapshot
import com.virjar.tk.server.domain.document.DocumentSpaceAccessPage
import com.virjar.tk.server.domain.document.DocumentSpacePageAnchor
import com.virjar.tk.server.domain.document.DocumentWriteAuthority
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.EmbeddedAsset

/**
 * 文档聚合的 PostgreSQL 适配器。
 *
 * 此类刻意保持为一个轻薄的 [DocumentRepository] 外观。查询投影、
 * 聚合变更与层级校验有各自的包内拥有者，使
 * 公共领域端口不必把所有持久化关注点塞进一个实现文件。
 */
class ExposedDocumentRepository : DocumentRepository {
    private val nodeRows = ExposedDocumentNodeRows()
    private val hierarchy = ExposedDocumentHierarchy()
    private val reads = ExposedDocumentReadStore(nodeRows, hierarchy)
    private val writes = ExposedDocumentWriteStore(nodeRows, hierarchy)
    private val policyMutations = ExposedDocumentPolicyMutationStore()
    private val nodeMoves = ExposedDocumentNodeMoveStore()
    private val recentWrites = ExposedDocumentRecentWriteStore()

    override fun findSpace(transaction: PgReadTransactionContext, spaceId: String): DocumentSpace? =
        reads.findSpace(transaction, spaceId)

    override fun readAccessSnapshot(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
    ): DocumentReadAccessSnapshot = reads.readAccessSnapshot(transaction, actorUid, spaceId)

    override fun readAccessibleSpacePage(
        transaction: PgReadTransactionContext,
        actorUid: String,
        after: DocumentSpacePageAnchor?,
        pageSize: Int,
    ): DocumentSpaceAccessPage = reads.readAccessibleSpacePage(transaction, actorUid, after, pageSize)

    override fun lockWriteAuthority(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredOrganizationUnitIds: Set<String>,
        requiredUserIds: Set<String>,
    ): DocumentWriteAuthority = ExposedDocumentWriteAuthority.lock(
        transaction = transaction,
        actorUid = actorUid,
        spaceId = spaceId,
        requiredOrganizationUnitIds = requiredOrganizationUnitIds,
        requiredUserIds = requiredUserIds,
    )

    override fun lockDestructiveCommandSpace(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    ) {
        writes.lockDestructiveCommandSpace(transaction, actorUid, spaceId)
    }

    override fun lockDocumentCreateCommandFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    ) {
        writes.lockDocumentCreateCommandFence(transaction, actorUid, spaceId)
    }

    override fun lockNodeMoveCommandFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    ) {
        nodeMoves.lockFence(transaction, actorUid, spaceId)
    }

    override fun lockCustodyTransferFence(transaction: PgWriteTransactionContext) {
        writes.lockCustodyTransferFence(transaction)
    }

    override fun findUser(transaction: PgReadTransactionContext, uid: String): User? =
        reads.findUser(transaction, uid)

    override fun findActiveOrganizationUnitName(
        transaction: PgReadTransactionContext,
        unitId: String,
    ): String? = reads.findActiveOrganizationUnitName(transaction, unitId)

    override fun createSpace(
        transaction: PgWriteTransactionContext,
        space: DocumentSpace,
        creationFingerprint: String,
    ): DocumentSpaceCreateResult = writes.createSpace(transaction, space, creationFingerprint)

    override fun updateSpace(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        name: String,
        description: String?,
        updatedAt: Long,
    ): DocumentSpace = writes.updateSpace(transaction, spaceId, name, description, updatedAt)

    override fun isSpaceArchivedByCommand(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
    ): Boolean = reads.isSpaceArchivedByCommand(transaction, actorUid, spaceId, operationId)

    override fun archiveSpace(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
        updatedAt: Long,
    ) {
        writes.archiveSpace(transaction, actorUid, spaceId, operationId, updatedAt)
    }

    override fun findCustodyTransferReceipt(
        transaction: PgReadTransactionContext,
        operationId: String,
    ): DocumentCustodyTransferReceipt? = reads.findCustodyTransferReceipt(transaction, operationId)

    override fun transferSpaceCustody(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        expectedCustodyRevision: Long,
        operationId: String,
        fingerprint: String,
        updatedAt: Long,
    ): DocumentSpace = writes.transferSpaceCustody(
        transaction = transaction,
        actorUid = actorUid,
        spaceId = spaceId,
        ownerPrincipalType = ownerPrincipalType,
        ownerPrincipalId = ownerPrincipalId,
        stewardUid = stewardUid,
        expectedCustodyRevision = expectedCustodyRevision,
        operationId = operationId,
        fingerprint = fingerprint,
        updatedAt = updatedAt,
    )

    override fun listGrants(
        transaction: PgReadTransactionContext,
        spaceId: String,
    ): List<DocumentSpaceGrant> = reads.listGrants(transaction, spaceId)

    override fun lockPolicyMutationFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredUserIds: Set<String>,
    ): DocumentPolicyMutationFence = policyMutations.lockFence(
        transaction = transaction,
        actorUid = actorUid,
        spaceId = spaceId,
        requiredUserIds = requiredUserIds,
    )

    override fun findPolicyMutationReceipt(
        transaction: PgReadTransactionContext,
        actorUid: String,
        operationId: String,
    ): DocumentPolicyMutationReceipt? = policyMutations.findReceipt(
        transaction,
        actorUid,
        operationId,
    )

    override fun pruneExpiredPolicyMutationReceiptsAndRequireCapacity(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        nowMillis: Long,
    ) = policyMutations.pruneExpiredPolicyMutationReceiptsAndRequireCapacity(
        transaction,
        actorUid,
        nowMillis,
    )

    override fun commitPolicyMutation(
        transaction: PgWriteTransactionContext,
        command: DocumentPolicyMutationCommit,
    ) {
        if (command.changed) {
            when (command.kind) {
                DocumentPolicyMutationKind.UPSERT -> writes.upsertGrant(
                    transaction,
                    DocumentSpaceGrant(
                        spaceId = command.spaceId,
                        principalType = command.principalType,
                        principalId = command.principalId,
                        role = requireNotNull(command.role),
                        includeDescendants = command.includeDescendants,
                    ),
                )
                DocumentPolicyMutationKind.REMOVE -> writes.removeGrant(
                    transaction,
                    command.spaceId,
                    command.principalType,
                    command.principalId,
                )
            }
        }
        policyMutations.commitRevisionAndReceipt(transaction, command)
    }

    override fun listNodes(
        transaction: PgReadTransactionContext,
        spaceId: String,
        parentId: String?,
    ): List<DocumentNode> = reads.listNodes(transaction, spaceId, parentId)

    override fun findNode(
        transaction: PgReadTransactionContext,
        spaceId: String,
        nodeId: String,
    ): DocumentNode? = reads.findNode(transaction, spaceId, nodeId)

    override fun findPathSpine(
        transaction: PgReadTransactionContext,
        spaceId: String,
        nodeId: String,
    ): DocumentPathSpine = reads.findPathSpine(transaction, spaceId, nodeId)

    override fun findDocument(
        transaction: PgReadTransactionContext,
        spaceId: String,
        documentId: String,
    ): Document? = reads.findDocument(transaction, spaceId, documentId)

    override fun findActiveDocumentIdentity(
        transaction: PgReadTransactionContext,
        spaceId: String,
        documentId: String,
    ): ActiveDocumentIdentity? = reads.findActiveDocumentIdentity(transaction, spaceId, documentId)

    override fun findActiveEmbeddedAssetSpaceIds(
        transaction: PgReadTransactionContext,
        path: String,
        limit: Int,
    ): List<String> = reads.findActiveEmbeddedAssetSpaceIds(transaction, path, limit)

    override fun findKnownEmbeddedAssets(
        transaction: PgReadTransactionContext,
        documentId: String,
        assetIds: Set<String>,
    ): List<EmbeddedAsset> = reads.findKnownEmbeddedAssets(transaction, documentId, assetIds)

    override fun createDocument(
        transaction: PgWriteTransactionContext,
        document: Document,
        initialRevision: DocumentRevision,
        creationFingerprint: String,
    ): Document = writes.createDocument(transaction, document, initialRevision, creationFingerprint)

    override fun hasExactDocumentCreateReceipt(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        documentId: String,
        creationFingerprint: String,
    ): Boolean = writes.hasExactDocumentCreateReceipt(
        transaction,
        actorUid,
        spaceId,
        documentId,
        creationFingerprint,
    )

    override fun updateDocument(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        documentId: String,
        expectedRevision: Long,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
        assets: List<EmbeddedAsset>,
    ): Document = writes.updateDocument(
        transaction = transaction,
        spaceId = spaceId,
        documentId = documentId,
        expectedRevision = expectedRevision,
        markdown = markdown,
        actorUid = actorUid,
        updatedAt = updatedAt,
        assets = assets,
    )

    override fun moveNode(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentMoveResult = writes.moveNode(
        transaction = transaction,
        spaceId = spaceId,
        nodeId = nodeId,
        expectedRevision = expectedRevision,
        parentId = parentId,
        name = name,
        actorUid = actorUid,
        updatedAt = updatedAt,
    )

    override fun findNodeMoveReceipt(
        transaction: PgReadTransactionContext,
        actorUid: String,
        operationId: String,
    ): DocumentNodeMoveReceipt? = nodeMoves.findReceipt(transaction, actorUid, operationId)

    override fun pruneExpiredNodeMoveReceiptsAndRequireCapacity(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        nowMillis: Long,
    ) = nodeMoves.pruneExpiredAndRequireCapacity(transaction, actorUid, nowMillis)

    override fun appendNodeMoveReceipt(
        transaction: PgWriteTransactionContext,
        receipt: DocumentNodeMoveReceipt,
        createdAt: Long,
    ) = nodeMoves.append(transaction, receipt, createdAt)

    override fun isNodeDeletedByCommand(
        transaction: PgReadTransactionContext,
        actorUid: String,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    ): Boolean = reads.isNodeDeletedByCommand(
        transaction,
        actorUid,
        spaceId,
        nodeId,
        expectedRevision,
        operationId,
    )

    override fun deleteNode(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
        actorUid: String,
        updatedAt: Long,
    ) {
        writes.deleteNode(
            transaction,
            spaceId,
            nodeId,
            expectedRevision,
            operationId,
            actorUid,
            updatedAt,
        )
    }

    override fun listRevisions(
        transaction: PgReadTransactionContext,
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ): List<DocumentRevisionSummary> = reads.listRevisions(transaction, documentId, beforeRevision, limit)

    override fun findRevision(
        transaction: PgReadTransactionContext,
        documentId: String,
        revision: Long,
    ): DocumentRevision? = reads.findRevision(transaction, documentId, revision)

    override fun touchRecentDocument(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        documentId: String,
        accessedAt: Long,
    ) {
        recentWrites.touch(transaction, actorUid, documentId, accessedAt)
    }

    override fun listRecentDocuments(
        transaction: PgReadTransactionContext,
        actorUid: String,
        limit: Int,
    ): DocumentHomeAccessSnapshot = reads.listRecentDocuments(
        transaction,
        actorUid,
        limit,
    )

    override fun listRecentlyCreatedDocuments(
        transaction: PgReadTransactionContext,
        actorUid: String,
        limit: Int,
    ): DocumentHomeAccessSnapshot = reads.listRecentlyCreatedDocuments(
        transaction,
        actorUid,
        limit,
    )
}
