package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentCustodyConflictException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentContentRevisions
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaceGrants
import com.virjar.tk.server.infra.db.DocumentSpaceCustodyTransfers
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * 文档聚合的命令侧持久化。
 *
 * 每个入口都要求调用方持有的 PostgreSQL 工作单元。空间结构写
 * 在检查或变更文档节点之前，先取活跃空间行作为其聚合 fence；
 * 破坏性命令重试还会跨其已归档状态对行加 fence。
 */
internal class ExposedDocumentWriteStore(
    private val nodeRows: ExposedDocumentNodeRows,
    private val hierarchy: ExposedDocumentHierarchy,
) {
    /**
     * 组织命令先取同一单例。让它位于 User 与 Space
     * 锁之前，可防止资产交接/归档竞态，而不会引入 Space -> OrganizationState 边。
     */
    fun lockCustodyTransferFence(transaction: PgWriteTransactionContext) {
        transaction.inExposedTransaction {
            check(
                OrganizationState.select(OrganizationState.id).where {
                    OrganizationState.id eq ORGANIZATION_STATE_SINGLETON_ID
                }.forUpdate().single()[OrganizationState.id] == ORGANIZATION_STATE_SINGLETON_ID,
            ) { "Organization state singleton is missing" }
        }
    }

    fun lockDestructiveCommandSpace(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    ) {
        transaction.inExposedTransaction {
            Users.selectAll().where { Users.uid eq actorUid }
                .forUpdate().singleOrNull() ?: throw DocumentAccessDeniedException("没有文档空间权限")
            DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId eq spaceId
            }.forUpdate().singleOrNull()
                ?: throw DocumentNotFoundException("文档空间不存在")
        }
    }

    /** User -> 任意状态的 Space，与最近索引及授权/资产交接写入器共享。 */
    fun lockDocumentCreateCommandFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    ) {
        transaction.inExposedTransaction {
            Users.selectAll().where { Users.uid eq actorUid }
                .forUpdate().singleOrNull() ?: throw IllegalArgumentException("用户不存在")
            DocumentSpaces.selectAll().where { DocumentSpaces.spaceId eq spaceId }
                .forUpdate().singleOrNull() ?: throw DocumentNotFoundException("文档空间不存在")
        }
    }

    fun createSpace(
        transaction: PgWriteTransactionContext,
        space: DocumentSpace,
        creationFingerprint: String,
    ): DocumentSpaceCreateResult =
        transaction.inExposedTransaction {
            requireCreationFingerprint(creationFingerprint)
            require(
                space.ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER &&
                    space.ownerPrincipalId == space.createdBy &&
                    space.stewardUid == space.createdBy &&
                    space.custodyRevision == 1L,
            ) { "新建文档空间必须由创建者本人持有并负责" }
            // 新空间还没有聚合行可锁。其创建者 User 行就是
            // 创建 fence，因此并发请求不可能同时观察到最后的活跃名额。
            val creator = Users.selectAll().where { Users.uid eq space.createdBy }
                .forUpdate().singleOrNull() ?: throw IllegalArgumentException("用户不存在")

            DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId eq space.spaceId
            }.singleOrNull()?.let { existing ->
                require(
                    existing[DocumentSpaces.creationFingerprint] == creationFingerprint &&
                        existing[DocumentSpaces.createdBy] == space.createdBy,
                ) { "创建请求标识已用于不同的文档空间" }
                return@inExposedTransaction existingCreateResult(
                    existing,
                    space.createdBy,
                    creator[Users.status] == USER_STATUS_ACTIVE &&
                        creator[Users.role] == UserRole.HUMAN,
                )
            }

            require(
                creator[Users.status] == USER_STATUS_ACTIVE && creator[Users.role] == UserRole.HUMAN,
            ) { "只有活动普通用户可以创建文档空间" }

            ExposedDocumentCapacity.requireOwnerSpaceSlot(
                transaction,
                space.ownerPrincipalType,
                space.ownerPrincipalId,
            )
            ExposedDocumentCapacity.requireStewardshipSlot(transaction, space.stewardUid)
            val inserted = DocumentSpaces.insertIgnore {
                it[spaceId] = space.spaceId
                it[DocumentSpaces.creationFingerprint] = creationFingerprint
                it[name] = space.name
                it[description] = space.description
                it[status] = DOCUMENT_STATUS_ACTIVE
                it[createdBy] = space.createdBy
                it[ownerPrincipalType] = space.ownerPrincipalType
                it[ownerPrincipalId] = space.ownerPrincipalId
                it[stewardUid] = space.stewardUid
                it[custodyRevision] = space.custodyRevision
                it[createdAt] = space.createdAt
                it[updatedAt] = space.updatedAt
            }.insertedCount == 1
            if (inserted) {
                // 最后的 Document 域锁：新行现在是每个后续目录
                // 快照的一部分，而下方的精确创建重放刻意不碰此版本。
                ExposedDocumentDirectoryRevision.advance(transaction, space.updatedAt)
                return@inExposedTransaction DocumentSpaceCreateResult(space.spaceId, space)
            }

            val existing = DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId eq space.spaceId
            }.singleOrNull() ?: error("Document space idempotency conflict was not materialized")
            require(
                existing[DocumentSpaces.creationFingerprint] == creationFingerprint &&
                    existing[DocumentSpaces.createdBy] == space.createdBy,
            ) { "创建请求标识已用于不同的文档空间" }
            existingCreateResult(existing, space.createdBy, creatorIsActive = true)
        }

    /**
     * 创建身份不可变，但当前的权威投影不是。精确重试
     * 必须在之后的资产交接/归档之后确认原始命令，而不为原始创建者
     * 重新引入过时的 OWNER 投影。
     */
    private fun existingCreateResult(
        existing: ResultRow,
        creatorUid: String,
        creatorIsActive: Boolean,
    ): DocumentSpaceCreateResult = DocumentSpaceCreateResult(
        spaceId = existing[DocumentSpaces.spaceId],
        space = existing.toDocumentSpace().takeIf { space ->
            creatorIsActive && existing[DocumentSpaces.status] == DOCUMENT_STATUS_ACTIVE &&
                space.stewardUid == creatorUid
        },
    )

    fun updateSpace(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        name: String,
        description: String?,
        updatedAt: Long,
    ): DocumentSpace = transaction.inExposedTransaction {
        lockSpace(spaceId)
        val updated = DocumentSpaces.update({
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }) {
            it[DocumentSpaces.name] = name
            it[DocumentSpaces.description] = description
            it[DocumentSpaces.updatedAt] = updatedAt
        }
        if (updated != 1) throw DocumentNotFoundException("文档空间不存在")
        val result = requireActiveSpace(spaceId)
        // State 始终是此命令中最后的 Document 域锁。
        ExposedDocumentDirectoryRevision.advance(transaction, updatedAt)
        result
    }

    fun archiveSpace(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        operationId: String,
        updatedAt: Long,
    ) {
        transaction.inExposedTransaction {
            requireCanonicalOperationId(operationId)
            val space = lockSpace(spaceId)
            if (space.stewardUid != actorUid) {
                throw DocumentAccessDeniedException("没有文档空间权限")
            }
            val updated = DocumentSpaces.update({
                (DocumentSpaces.spaceId eq spaceId) and
                    (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                    (DocumentSpaces.stewardUid eq actorUid) and
                    DocumentSpaces.archiveCommandId.isNull()
            }) {
                it[status] = DOCUMENT_STATUS_DELETED
                it[archiveCommandId] = operationId
                it[archiveActorUid] = actorUid
                it[DocumentSpaces.updatedAt] = updatedAt
            }
            if (updated != 1) throw DocumentNotFoundException("文档空间不存在")
            // 精确的归档重放在此存储被调用之前就返回了，因此只有第一次提交
            // 会到达全局目录 fence。
            ExposedDocumentDirectoryRevision.advance(transaction, updatedAt)
        }
    }

    fun transferSpaceCustody(
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
    ): DocumentSpace = transaction.inExposedTransaction {
        requireCanonicalOperationId(operationId)
        requireSha256Fingerprint(fingerprint, "文档空间交接请求指纹")
        require(
            ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "空间归属主体类型非法" }
        require(ownerPrincipalId.isNotBlank() && ownerPrincipalId.length <= 36) { "空间归属主体标识非法" }
        require(stewardUid.isNotBlank() && stewardUid.length <= 36) { "空间责任人标识非法" }
        require(expectedCustodyRevision in 1 until Long.MAX_VALUE) { "空间归属版本非法" }
        if (ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            require(ownerPrincipalId == stewardUid) { "个人持有空间必须由本人负责" }
        }
        val current = lockSpace(spaceId)
        if (current.stewardUid != actorUid) {
            throw DocumentAccessDeniedException("没有文档空间权限")
        }
        if (current.custodyRevision != expectedCustodyRevision) {
            throw DocumentCustodyConflictException()
        }

        val changesCustody = current.ownerPrincipalType != ownerPrincipalType ||
            current.ownerPrincipalId != ownerPrincipalId ||
            current.stewardUid != stewardUid
        require(changesCustody) { "空间归属和责任人均未发生变化" }
        if (
            current.ownerPrincipalType != ownerPrincipalType ||
            current.ownerPrincipalId != ownerPrincipalId
        ) {
            ExposedDocumentCapacity.requireOwnerSpaceSlot(
                transaction,
                ownerPrincipalType,
                ownerPrincipalId,
            )
        }
        if (current.stewardUid != stewardUid) {
            ExposedDocumentCapacity.requireStewardshipSlot(transaction, stewardUid)
        }
        val resultingRevision = expectedCustodyRevision + 1
        val receiptInserted = DocumentSpaceCustodyTransfers.insertIgnore {
            it[DocumentSpaceCustodyTransfers.operationId] = operationId
            it[DocumentSpaceCustodyTransfers.spaceId] = spaceId
            it[DocumentSpaceCustodyTransfers.actorUid] = actorUid
            it[DocumentSpaceCustodyTransfers.fingerprint] = fingerprint
            it[fromPrincipalType] = current.ownerPrincipalType
            it[fromPrincipalId] = current.ownerPrincipalId
            it[fromStewardUid] = current.stewardUid
            it[fromRevision] = current.custodyRevision
            it[toPrincipalType] = ownerPrincipalType
            it[toPrincipalId] = ownerPrincipalId
            it[toStewardUid] = stewardUid
            it[DocumentSpaceCustodyTransfers.resultingRevision] = resultingRevision
            it[createdAt] = updatedAt
        }.insertedCount == 1
        if (!receiptInserted) {
            throw ReliableCommandConflictException("文档空间交接操作标识已用于不同请求")
        }
        val updated = DocumentSpaces.update({
            (DocumentSpaces.spaceId eq spaceId) and
                (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentSpaces.stewardUid eq actorUid) and
                (DocumentSpaces.custodyRevision eq expectedCustodyRevision)
        }) {
            it[DocumentSpaces.ownerPrincipalType] = ownerPrincipalType
            it[DocumentSpaces.ownerPrincipalId] = ownerPrincipalId
            it[DocumentSpaces.stewardUid] = stewardUid
            it[DocumentSpaces.custodyRevision] = resultingRevision
            it[DocumentSpaces.updatedAt] = updatedAt
        }
        if (updated != 1) throw DocumentCustodyConflictException()
        val result = requireActiveSpace(spaceId)
        ExposedDocumentDirectoryRevision.advance(transaction, updatedAt)
        result
    }

    fun upsertGrant(transaction: PgWriteTransactionContext, grant: DocumentSpaceGrant) {
        transaction.inExposedTransaction {
            lockSpace(grant.spaceId)
            val existing = DocumentSpaceGrants.selectAll().where {
                (DocumentSpaceGrants.spaceId eq grant.spaceId) and
                    (DocumentSpaceGrants.principalType eq grant.principalType) and
                    (DocumentSpaceGrants.principalId eq grant.principalId)
            }.singleOrNull()
            if (existing == null) {
                if (grant.principalType == DocumentSpaceGrant.PRINCIPAL_USER) {
                    ExposedDocumentCapacity.requireDirectUserGrantSlot(transaction, grant.principalId)
                }
                require(
                    DocumentSpaceGrants.selectAll().where {
                        DocumentSpaceGrants.spaceId eq grant.spaceId
                    }.count() < DocumentSpaceGrant.MAX_GRANTS_PER_SPACE.toLong(),
                ) { "文档空间授权数量不能超过 ${DocumentSpaceGrant.MAX_GRANTS_PER_SPACE}" }
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

    fun removeGrant(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        principalType: Int,
        principalId: String,
    ) {
        transaction.inExposedTransaction {
            lockSpace(spaceId)
            DocumentSpaceGrants.deleteWhere {
                (DocumentSpaceGrants.spaceId eq spaceId) and
                    (DocumentSpaceGrants.principalType eq principalType) and
                    (DocumentSpaceGrants.principalId eq principalId)
            }
        }
    }

    fun createDocument(
        transaction: PgWriteTransactionContext,
        document: Document,
        initialRevision: DocumentRevision,
        creationFingerprint: String,
    ): Document = transaction.inExposedTransaction {
        requireCreationFingerprint(creationFingerprint)
        val exposedTransaction = transaction.requireExposedTransaction()
        lockSpace(document.spaceId)
        DocumentNodes.selectAll().where {
            DocumentNodes.nodeId eq document.documentId
        }.forUpdate().singleOrNull()?.let { existing ->
            return@inExposedTransaction requireIdempotentExistingDocument(
                exposedTransaction,
                existing,
                document,
                creationFingerprint,
            )
        }
        val ancestorIds = hierarchy.resolveAncestorIds(
            exposedTransaction,
            document.spaceId,
            document.parentId,
        )
        ExposedDocumentCapacity.requireSpaceDocumentSlot(transaction, document.spaceId)
        ExposedDocumentCapacity.requireParentChildSlot(transaction, document.spaceId, document.parentId)
        val inserted = insertNode(document.toNode(), document.markdown, creationFingerprint)
        if (!inserted) {
            val existing = DocumentNodes.selectAll().where {
                DocumentNodes.nodeId eq document.documentId
            }.singleOrNull() ?: error("Document idempotency conflict was not materialized")
            return@inExposedTransaction requireIdempotentExistingDocument(
                exposedTransaction,
                existing,
                document,
                creationFingerprint,
            )
        }
        require(initialRevision.assets == document.assets) {
            "文档初始版本与当前内嵌资产清单不一致"
        }
        insertRevision(initialRevision)
        insertInitialAssetManifest(document.documentId, initialRevision.revision, document.assets)
        document.copy(ancestorIds = ancestorIds)
    }

    fun hasExactDocumentCreateReceipt(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        documentId: String,
        creationFingerprint: String,
    ): Boolean = transaction.inExposedTransaction {
        requireCreationFingerprint(creationFingerprint)
        DocumentNodes.selectAll().where { DocumentNodes.nodeId eq documentId }
            .singleOrNull()?.let { existing ->
                existing[DocumentNodes.spaceId] == spaceId &&
                    existing[DocumentNodes.createdBy] == actorUid &&
                    existing[DocumentNodes.creationFingerprint] == creationFingerprint
            } == true
    }

    fun updateDocument(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        documentId: String,
        expectedRevision: Long,
        markdown: String,
        actorUid: String,
        updatedAt: Long,
        assets: List<EmbeddedAsset>,
    ): Document = transaction.inExposedTransaction {
        // 归档要么先提交并让此处 fail closed，要么等到当前
        // 快照与不可变修订追加都已提交。
        lockSpace(spaceId)
        val current = nodeRows.requireActiveContent(spaceId, documentId, forUpdate = true)
        if (current[DocumentNodes.revision] != expectedRevision) throwDocumentRevisionConflict()
        val currentAssets = loadDocumentAssetsAtRevision(documentId, expectedRevision)
        if (
            current[DocumentNodes.markdown] == markdown &&
            currentAssets == assets
        ) {
            return@inExposedTransaction current.toDocument(
                hierarchy.resolveAncestorIds(
                    transaction.requireExposedTransaction(),
                    current[DocumentNodes.spaceId],
                    current[DocumentNodes.parentId],
                ),
                currentAssets,
            )
        }

        val nextRevision = expectedRevision + 1
        val updated = DocumentNodes.update({
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq documentId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentNodes.revision eq expectedRevision)
        }) {
            it[excerpt] = DocumentPolicy.markdownExcerpt(markdown)
            it[DocumentNodes.markdown] = markdown
            it[revision] = nextRevision
            it[updatedBy] = actorUid
            it[DocumentNodes.updatedAt] = updatedAt
        }
        if (updated != 1) throwDocumentRevisionConflict()
        insertRevision(
            DocumentRevision(
                documentId = documentId,
                revision = nextRevision,
                title = current[DocumentNodes.name],
                markdown = markdown,
                editedBy = actorUid,
                editedAt = updatedAt,
                assets = assets,
            ),
        )
        replaceAssetManifest(documentId, expectedRevision, nextRevision, currentAssets, assets)
        val result = nodeRows.requireActiveContent(spaceId, documentId)
        result.toDocument(
            hierarchy.resolveAncestorIds(
                transaction.requireExposedTransaction(),
                result[DocumentNodes.spaceId],
                result[DocumentNodes.parentId],
            ),
            assets,
        )
    }

    fun moveNode(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        parentId: String?,
        name: String,
        actorUid: String,
        updatedAt: Long,
    ): DocumentMoveResult = transaction.inExposedTransaction {
        lockSpace(spaceId)
        val current = nodeRows.requireActiveContent(spaceId, nodeId, forUpdate = true)
        if (current[DocumentNodes.revision] != expectedRevision) throwDocumentRevisionConflict()
        val parentChanged = current[DocumentNodes.parentId] != parentId
        val titleChanged = current[DocumentNodes.name] != name
        val targetAncestorIds = if (parentChanged) {
            val hierarchySnapshot = hierarchy.moveSnapshot(transaction, spaceId, nodeId, parentId)
            hierarchySnapshot.targetAncestorIds.also { ancestorIds ->
                require(nodeId !in ancestorIds) { "文档不能移动到自己的后代" }
                require(
                    ancestorIds.size.toLong() + hierarchySnapshot.maxDescendantDepth <=
                        Document.MAX_ANCESTOR_DEPTH.toLong(),
                ) { "移动后文档层级超过限制" }
            }
        } else {
            hierarchy.resolveAncestorIds(
                transaction.requireExposedTransaction(),
                spaceId,
                current[DocumentNodes.parentId],
            )
        }
        if (!parentChanged && !titleChanged) {
            return@inExposedTransaction DocumentMoveResult(
                node = nodeRows.requireActiveSummary(spaceId, nodeId)
                    .toDocumentNode(hierarchy.hasActiveChildren(spaceId, nodeId)),
                ancestorIds = targetAncestorIds,
            )
        }
        if (parentChanged) {
            // 移动绝不改变空间范围的页面计数。只有真正不同的目标
            // 父节点才消耗一个兄弟名额；满层级内的重命名/空移动仍然有效。
            ExposedDocumentCapacity.requireParentChildSlot(transaction, spaceId, parentId)
        }

        val nextRevision = expectedRevision + 1
        val updated = DocumentNodes.update({
            (DocumentNodes.spaceId eq spaceId) and
                (DocumentNodes.nodeId eq nodeId) and
                (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                (DocumentNodes.revision eq expectedRevision)
        }) {
            it[DocumentNodes.parentId] = parentId
            it[DocumentNodes.name] = name
            it[revision] = nextRevision
            it[updatedBy] = actorUid
            it[DocumentNodes.updatedAt] = updatedAt
        }
        if (updated != 1) throwDocumentRevisionConflict()
        if (titleChanged) {
            val assets = loadDocumentAssetsAtRevision(nodeId, expectedRevision)
            insertRevision(
                DocumentRevision(
                    documentId = nodeId,
                    revision = nextRevision,
                    title = name,
                    markdown = current[DocumentNodes.markdown],
                    editedBy = actorUid,
                    editedAt = updatedAt,
                    assets = assets,
                ),
            )
        }
        DocumentMoveResult(
            node = nodeRows.requireActiveSummary(spaceId, nodeId)
                .toDocumentNode(hierarchy.hasActiveChildren(spaceId, nodeId)),
            ancestorIds = targetAncestorIds,
        )
    }

    fun deleteNode(
        transaction: PgWriteTransactionContext,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
        actorUid: String,
        updatedAt: Long,
    ) {
        transaction.inExposedTransaction {
            requireCanonicalOperationId(operationId)
            lockSpace(spaceId)
            val current = nodeRows.requireActiveDeleteProjection(spaceId, nodeId, forUpdate = true)
            if (current[DocumentNodes.revision] != expectedRevision) throwDocumentRevisionConflict()
            require(!hierarchy.hasActiveChildren(spaceId, nodeId)) { "请先移动或删除子文档" }
            val updated = DocumentNodes.update({
                (DocumentNodes.spaceId eq spaceId) and
                    (DocumentNodes.nodeId eq nodeId) and
                    (DocumentNodes.status eq DOCUMENT_STATUS_ACTIVE) and
                    (DocumentNodes.revision eq expectedRevision) and
                    DocumentNodes.deleteCommandId.isNull()
            }) {
                it[status] = DOCUMENT_STATUS_DELETED
                it[deleteCommandId] = operationId
                it[revision] = expectedRevision + 1
                it[updatedBy] = actorUid
                it[DocumentNodes.updatedAt] = updatedAt
            }
            if (updated != 1) throwDocumentRevisionConflict()
        }
    }

    private fun lockSpace(spaceId: String): DocumentSpace {
        val row = DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }.forUpdate().singleOrNull() ?: throw DocumentNotFoundException("文档空间不存在")
        return row.toDocumentSpace()
    }

    private fun requireActiveSpace(spaceId: String): DocumentSpace =
        DocumentSpaces.selectAll().where {
            (DocumentSpaces.spaceId eq spaceId) and (DocumentSpaces.status eq DOCUMENT_STATUS_ACTIVE)
        }.singleOrNull()?.toDocumentSpace() ?: throw DocumentNotFoundException("文档空间不存在")

    private fun insertNode(
        node: DocumentNode,
        markdown: String,
        creationFingerprint: String,
    ): Boolean = DocumentNodes.insertIgnore {
            it[nodeId] = node.nodeId
            it[DocumentNodes.creationFingerprint] = creationFingerprint
            it[spaceId] = node.spaceId
            it[parentId] = node.parentId
            it[name] = node.name
            it[excerpt] = DocumentPolicy.markdownExcerpt(markdown)
            it[DocumentNodes.markdown] = markdown
            it[revision] = node.revision
            it[status] = DOCUMENT_STATUS_ACTIVE
            it[createdBy] = node.createdBy
            it[createdAt] = node.createdAt
            it[updatedBy] = node.updatedBy
            it[updatedAt] = node.updatedAt
        }.insertedCount == 1

    private fun requireIdempotentExistingDocument(
        transaction: Transaction,
        existing: ResultRow,
        requested: Document,
        creationFingerprint: String,
    ): Document {
        if (
            existing[DocumentNodes.creationFingerprint] != creationFingerprint ||
            existing[DocumentNodes.createdBy] != requested.createdBy ||
            existing[DocumentNodes.spaceId] != requested.spaceId
        ) {
            throw ReliableCommandConflictException("文档创建标识已用于不同请求")
        }
        require(existing[DocumentNodes.status] == DOCUMENT_STATUS_ACTIVE) { "文档已删除" }
        return existing.toDocument(
            hierarchy.resolveAncestorIds(
                transaction,
                existing[DocumentNodes.spaceId],
                existing[DocumentNodes.parentId],
            ),
            loadDocumentAssetsAtRevision(
                requested.documentId,
                existing[DocumentNodes.revision],
            ),
        )
    }

    private fun requireCreationFingerprint(value: String) {
        requireSha256Fingerprint(value, "创建请求指纹")
    }

    private fun requireSha256Fingerprint(value: String, label: String) {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "$label 非法" }
    }

    private fun requireCanonicalOperationId(value: String) {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "文档破坏性操作标识非法"
        }
    }

    private fun insertRevision(value: DocumentRevision) {
        DocumentContentRevisions.insert {
            it[documentId] = value.documentId
            it[revision] = value.revision
            it[title] = value.title
            it[markdown] = value.markdown
            it[contentLength] = value.markdown.length
            it[editedBy] = value.editedBy
            it[editedAt] = value.editedAt
        }
    }

    private companion object {
        const val ORGANIZATION_STATE_SINGLETON_ID = 1
    }
}

private const val USER_STATUS_ACTIVE = 1

internal inline fun <T> PgWriteTransactionContext.inExposedTransaction(block: () -> T): T {
    requireExposedTransaction()
    return block()
}
