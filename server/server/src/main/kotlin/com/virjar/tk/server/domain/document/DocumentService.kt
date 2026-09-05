package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentCreateResult
import com.virjar.tk.protocol.model.DocumentCustodyTransferResult
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveCommandResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionPage
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.protocol.model.DocumentSpacePageRequest
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 企业文档空间领域服务。
 *
 * 空间是独立权限根。用户授权与组织部门授权共同计算当前角色；文档节点只属于空间，
 * 群聊既不拥有文档，也不参与权限判断。所有读写都重新计算实时组织归属。
 */
class DocumentService(
    private val repository: DocumentRepository,
    private val unitOfWork: PgUnitOfWork,
    attachmentCatalog: AttachmentCatalog? = null,
    attachmentLifecycle: AttachmentLifecycleGate = AttachmentLifecycleGate(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)
    private val accessControl = DocumentAccessControl(repository, unitOfWork)
    private val nodeMoveCommands = DocumentNodeMoveCommandHandler(repository, accessControl, wallClockMillis)
    private val policyMutations = DocumentPolicyMutationService(repository, unitOfWork, wallClockMillis)
    private val embeddedAssets = DocumentEmbeddedAssetCoordinator(attachmentCatalog, attachmentLifecycle)

    suspend fun listSpaces(actorUid: String, request: DocumentSpacePageRequest): DocumentSpacePage {
        require(request.limit in 1..DocumentSpacePage.MAX_PAGE_SIZE) {
            "文档空间分页大小必须在 1..${DocumentSpacePage.MAX_PAGE_SIZE} 之间"
        }
        val page = accessControl.resolveAccessibleSpacePage(
            actorUid = actorUid,
            after = DocumentSpacePageCursorCodec.decode(request.cursor),
            pageSize = request.limit,
        )
        return DocumentSpacePage(
            snapshotVersion = page.snapshotVersion,
            items = page.items,
            nextCursor = page.nextAnchor?.let(DocumentSpacePageCursorCodec::encode),
            snapshotChanged = page.snapshotChanged,
        )
    }

    suspend fun createSpace(
        actorUid: String,
        spaceId: String,
        name: String,
        description: String?,
    ): DocumentSpace = createSpaceCommand(actorUid, spaceId, name, description).space
        ?: throw DocumentAccessDeniedException("文档空间创建已提交，但当前已无访问权")

    /**
     * 面向协议的可靠创建确认。
     *
     * 即使之后的归属交接或归档使当前的 [DocumentSpace] 投影返回变得不安全，重试仍能证明
     * 原始创建已经提交。
     */
    suspend fun createSpaceCommand(
        actorUid: String,
        spaceId: String,
        name: String,
        description: String?,
    ): DocumentSpaceCreateResult {
        val validatedSpaceId = validateResourceId(spaceId, "文档空间标识")
        val validatedName = validateSpaceName(name)
        val validatedDescription = validateDescription(description)
        val creationFingerprint = reliableCommandFingerprint(
            "document-space",
            actorUid,
            validatedName,
            validatedDescription,
        )
        val now = System.currentTimeMillis()
        return unitOfWork.write {
            val result = repository.createSpace(
                transaction,
                DocumentSpace(
                    spaceId = validatedSpaceId,
                    name = validatedName,
                    description = validatedDescription,
                    myRole = DocumentSpace.ROLE_NONE,
                    createdBy = actorUid,
                    createdAt = now,
                    updatedAt = now,
                    ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
                    ownerPrincipalId = actorUid,
                    stewardUid = actorUid,
                    custodyRevision = 1,
                ),
                creationFingerprint,
            )
            check(result.spaceId == validatedSpaceId) {
                "文档空间创建回执的资源标识不一致"
            }
            result.copy(space = result.space?.copy(myRole = DocumentSpace.ROLE_OWNER))
        }
    }

    suspend fun updateSpace(actorUid: String, spaceId: String, name: String, description: String?): DocumentSpace {
        val validatedName = validateSpaceName(name)
        val validatedDescription = validateDescription(description)
        return accessControl.writeAuthorized(actorUid, spaceId, DocumentCapability.MANAGE_SPACE) { space, role ->
            repository.updateSpace(
                transaction,
                space.spaceId,
                validatedName,
                validatedDescription,
                System.currentTimeMillis(),
            ).copy(myRole = role)
        }
    }

    suspend fun archiveSpace(actorUid: String, spaceId: String, operationId: String) {
        val validatedOperationId = validateResourceId(operationId, "归档操作标识")
        accessControl.writeAuthorizedOrCompleted(
            actorUid = actorUid,
            spaceId = spaceId,
            required = DocumentCapability.ARCHIVE_SPACE,
            alreadyCompleted = {
                repository.isSpaceArchivedByCommand(
                    transaction,
                    actorUid,
                    spaceId,
                    validatedOperationId,
                )
            },
        ) { _, _ ->
            repository.archiveSpace(
                transaction,
                actorUid,
                spaceId,
                validatedOperationId,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun transferSpaceCustody(
        actorUid: String,
        spaceId: String,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        expectedCustodyRevision: Long,
        operationId: String,
    ): DocumentCustodyTransferResult {
        require(
            ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER ||
                ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
        ) { "空间归属主体类型非法" }
        require(expectedCustodyRevision in 1 until Long.MAX_VALUE) { "空间归属版本非法" }
        val validatedOwnerPrincipalId = validatePrincipalId(ownerPrincipalId, "空间归属主体标识")
        val validatedStewardUid = validatePrincipalId(stewardUid, "空间责任人标识")
        val validatedOperationId = validateResourceId(operationId, "空间交接操作标识")
        if (ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER) {
            require(validatedOwnerPrincipalId == validatedStewardUid) { "个人持有空间必须由本人负责" }
        }
        val fingerprint = reliableCommandFingerprint(
            "document-space-custody",
            actorUid,
            spaceId,
            ownerPrincipalType.toString(),
            validatedOwnerPrincipalId,
            validatedStewardUid,
            expectedCustodyRevision.toString(),
        )
        val requiredOrganizationUnitIds = if (
            ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT
        ) {
            setOf(validatedOwnerPrincipalId)
        } else {
            emptySet()
        }
        val requiredUserIds = buildSet {
            add(validatedStewardUid)
            if (ownerPrincipalType == DocumentSpaceGrant.PRINCIPAL_USER) add(validatedOwnerPrincipalId)
        }
        unitOfWork.read {
            resolveCustodyReplay(
                repository.findCustodyTransferReceipt(transaction, validatedOperationId),
                actorUid,
                spaceId,
                fingerprint,
            )?.let { return@read it }
            // 把未授权/随机资源的流量挡在单例组织围栏之外。这只是快速拒绝：被准入的
            // 责任人会在写锁下被完整地重新授权，而一次与首次提交竞争的精确重试会在那里
            // 等待，并在 READ COMMITTED 写事务中观察到不可变回执。
            val current = repository.findSpace(transaction, spaceId)
                ?: throw DocumentNotFoundException("文档空间不存在")
            if (current.stewardUid != actorUid) {
                throw DocumentAccessDeniedException("没有文档空间权限")
            }
            null
        }?.let { return it }
        return accessControl.transferCustodyAuthorized(
            actorUid = actorUid,
            spaceId = spaceId,
            requiredOrganizationUnitIds = requiredOrganizationUnitIds,
            requiredUserIds = requiredUserIds,
            replay = {
                resolveCustodyReplay(
                    repository.findCustodyTransferReceipt(transaction, validatedOperationId),
                    actorUid,
                    spaceId,
                    fingerprint,
                )
            },
        ) { _ ->
            val steward = repository.findUser(transaction, validatedStewardUid)
                ?: throw IllegalArgumentException("用户不存在")
            require(steward.role == UserRole.HUMAN && steward.status == USER_STATUS_ACTIVE) {
                "空间责任人必须是活动普通用户"
            }
            when (ownerPrincipalType) {
                DocumentSpaceGrant.PRINCIPAL_USER -> Unit
                DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT -> requireNotNull(
                    repository.findActiveOrganizationUnitName(transaction, validatedOwnerPrincipalId),
                ) { "组织节点不存在" }
            }
            repository.transferSpaceCustody(
                transaction = transaction,
                actorUid = actorUid,
                spaceId = spaceId,
                ownerPrincipalType = ownerPrincipalType,
                ownerPrincipalId = validatedOwnerPrincipalId,
                stewardUid = validatedStewardUid,
                expectedCustodyRevision = expectedCustodyRevision,
                operationId = validatedOperationId,
                fingerprint = fingerprint,
                updatedAt = System.currentTimeMillis(),
            ).toCustodyTransferResult()
        }
    }

    suspend fun listGrants(actorUid: String, spaceId: String): List<DocumentSpaceGrant> =
        accessControl.readAuthorized(actorUid, spaceId, DocumentCapability.MANAGE_POLICY) { _, _ ->
            repository.listGrants(transaction, spaceId)
        }

    suspend fun upsertGrant(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentPolicyMutationResult = policyMutations.upsertGrant(
        actorUid,
        spaceId,
        principalType,
        principalId,
        role,
        includeDescendants,
        expectedPolicyRevision,
        operationId,
        issuedAt,
    )

    suspend fun removeGrant(
        actorUid: String,
        spaceId: String,
        principalType: Int,
        principalId: String,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentPolicyMutationResult = policyMutations.removeGrant(
        actorUid,
        spaceId,
        principalType,
        principalId,
        expectedPolicyRevision,
        operationId,
        issuedAt,
    )

    suspend fun listNodes(actorUid: String, spaceId: String, parentId: String?): List<DocumentNode> =
        accessControl.readAuthorized(actorUid, spaceId, DocumentCapability.READ) { _, _ ->
            requireParentDocument(transaction, spaceId, parentId)
            repository.listNodes(transaction, spaceId, parentId)
        }

    suspend fun getNodePathSpine(
        actorUid: String,
        spaceId: String,
        nodeId: String,
    ): DocumentPathSpine =
        accessControl.readAuthorized(actorUid, spaceId, DocumentCapability.READ) { _, _ ->
            repository.findPathSpine(transaction, spaceId, nodeId)
        }

    suspend fun createDocument(
        actorUid: String,
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        markdown: String,
    ): Document = createDocument(
        actorUid,
        documentId,
        spaceId,
        parentId,
        title,
        DocumentContent(markdown),
    )

    suspend fun createDocument(
        actorUid: String,
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        content: DocumentContent,
    ): Document = createDocumentCommand(
        actorUid,
        documentId,
        spaceId,
        parentId,
        title,
        content,
    ).document ?: getDocument(actorUid, spaceId, documentId)

    /** 面向协议的、针对一个稳定文档创建身份的可靠确认。 */
    suspend fun createDocumentCommand(
        actorUid: String,
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        markdown: String,
    ): DocumentCreateResult = createDocumentCommand(
        actorUid,
        documentId,
        spaceId,
        parentId,
        title,
        DocumentContent(markdown),
    )

    suspend fun createDocumentCommand(
        actorUid: String,
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        content: DocumentContent,
    ): DocumentCreateResult {
        val validatedDocumentId = validateResourceId(documentId, "文档标识")
        val validatedTitle = validateNodeName(title)
        val validatedContent = embeddedAssets.validateContent(content)
        val creationFingerprint = reliableCommandFingerprint(*(
            listOf(
                "document",
                actorUid,
                spaceId,
                parentId,
                validatedTitle,
                validatedContent.markdown,
            ) + embeddedAssets.fingerprintFields(validatedContent.assets)
            ).toTypedArray())
        val now = System.currentTimeMillis()
        val document = Document(
            documentId = validatedDocumentId,
            spaceId = spaceId,
            parentId = parentId,
            title = validatedTitle,
            markdown = validatedContent.markdown,
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
            assets = validatedContent.assets,
        )
        return embeddedAssets.withReferenceMutation(validatedContent.assets) {
            val result = accessControl.createDocumentAuthorizedOrCompleted(
                actorUid = actorUid,
                spaceId = spaceId,
                alreadyCompleted = {
                    if (
                        repository.hasExactDocumentCreateReceipt(
                            transaction,
                            actorUid,
                            spaceId,
                            validatedDocumentId,
                            creationFingerprint,
                        )
                    ) {
                        DocumentCreateResult(validatedDocumentId, null)
                    } else {
                        null
                    }
                },
            ) { _, _ ->
                val resolvedAssets = embeddedAssets.resolve(
                    actorUid = actorUid,
                    declared = validatedContent.assets,
                    knownById = emptyMap(),
                )
                // 发布被刻意放在 PostgreSQL 变更之前。如果后续事务失败或进程崩溃，该对象
                // 会变成默认拒绝（fail-closed），而不会重新获得上传者暂存权威。
                embeddedAssets.markBusinessBound(resolvedAssets)
                val resolvedDocument = document.copy(assets = resolvedAssets)
                val persisted = repository.createDocument(
                    transaction,
                    resolvedDocument,
                    DocumentRevision(
                        documentId = document.documentId,
                        revision = 1,
                        title = document.title,
                        markdown = document.markdown,
                        editedBy = actorUid,
                        editedAt = now,
                        assets = resolvedAssets,
                    ),
                    creationFingerprint,
                )
                // 创建与创建者最近可见性是一条持久化命令。任一写入失败都会回滚另一个，
                // 而不会留下一个已提交文档却缺少首页索引条目。
                repository.touchRecentDocument(transaction, actorUid, persisted.documentId, now)
                DocumentCreateResult(validatedDocumentId, persisted)
            }
            result
        }
    }

    suspend fun getDocument(actorUid: String, spaceId: String, documentId: String): Document {
        val document = accessControl.readAuthorized(
            actorUid,
            spaceId,
            DocumentCapability.READ,
        ) { _, _ ->
            requireDocument(transaction, spaceId, documentId)
        }
        touchRecentAfterReadBestEffort(actorUid, document.documentId, System.currentTimeMillis())
        return document
    }

    private suspend fun touchRecentAfterReadBestEffort(actorUid: String, documentId: String, accessedAt: Long) {
        try {
            unitOfWork.write {
                repository.touchRecentDocument(transaction, actorUid, documentId, accessedAt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 最近访问是个人索引，写入异常不得把已经授权且存在的正文伪装成读取失败。
            logger.warn("Failed to update document recent access uid={} documentId={}", actorUid, documentId, error)
        }
    }

    suspend fun updateDocument(
        actorUid: String,
        spaceId: String,
        documentId: String,
        markdown: String,
        expectedRevision: Long,
    ): Document = updateDocument(
        actorUid,
        spaceId,
        documentId,
        DocumentContent(markdown),
        expectedRevision,
    )

    suspend fun updateDocument(
        actorUid: String,
        spaceId: String,
        documentId: String,
        content: DocumentContent,
        expectedRevision: Long,
    ): Document {
        require(expectedRevision in 1 until Long.MAX_VALUE) { "文档版本非法" }
        val validatedContent = embeddedAssets.validateContent(content)
        return embeddedAssets.withReferenceMutation(validatedContent.assets) {
            val updated = accessControl.writeAuthorized(actorUid, spaceId, DocumentCapability.EDIT_CONTENT) { _, _ ->
                val knownById = repository.findKnownEmbeddedAssets(
                    transaction,
                    documentId,
                    validatedContent.assets.mapTo(linkedSetOf(), EmbeddedAsset::assetId),
                ).associateBy(EmbeddedAsset::assetId)
                val resolvedAssets = embeddedAssets.resolve(actorUid, validatedContent.assets, knownById)
                embeddedAssets.markBusinessBound(resolvedAssets)
                // 写适配器自己锁定并读取权威内容行。服务层的单独预读会复制一个可能达到
                // 最大尺寸的 Markdown 快照，且除了那个写入锁之外仍然提供不了任何并发保证。
                repository.updateDocument(
                    transaction = transaction,
                    spaceId = spaceId,
                    documentId = documentId,
                    expectedRevision = expectedRevision,
                    markdown = validatedContent.markdown,
                    actorUid = actorUid,
                    updatedAt = System.currentTimeMillis(),
                    assets = resolvedAssets,
                )
            }
            updated
        }
    }

    suspend fun moveNode(
        actorUid: String,
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentMoveCommandResult = nodeMoveCommands.execute(
        actorUid = actorUid,
        spaceId = spaceId,
        nodeId = nodeId,
        parentId = parentId,
        name = name,
        expectedRevision = expectedRevision,
        operationId = operationId,
        issuedAt = issuedAt,
    )

    suspend fun deleteNode(
        actorUid: String,
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    ) {
        require(expectedRevision in 1 until Long.MAX_VALUE) { "节点版本非法" }
        val validatedOperationId = validateResourceId(operationId, "删除操作标识")
        accessControl.writeAuthorizedOrCompleted(
            actorUid = actorUid,
            spaceId = spaceId,
            required = DocumentCapability.EDIT_CONTENT,
            alreadyCompleted = {
                repository.isNodeDeletedByCommand(
                    transaction,
                    actorUid,
                    spaceId,
                    nodeId,
                    expectedRevision,
                    validatedOperationId,
                )
            },
        ) { _, _ ->
            requireNode(transaction, spaceId, nodeId)
            require(repository.listNodes(transaction, spaceId, nodeId).isEmpty()) { "请先移动或删除子文档" }
            repository.deleteNode(
                transaction,
                spaceId,
                nodeId,
                expectedRevision,
                validatedOperationId,
                actorUid,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun listRevisions(
        actorUid: String,
        spaceId: String,
        documentId: String,
        beforeRevision: Long,
        limit: Int,
    ): DocumentRevisionPage {
        require(beforeRevision >= DocumentRevisionPage.FIRST_PAGE_CURSOR) { "文档版本游标非法" }
        require(limit in 1..DocumentRevisionPage.MAX_PAGE_SIZE) {
            "版本历史分页大小必须在 1..${DocumentRevisionPage.MAX_PAGE_SIZE} 之间"
        }
        return accessControl.readAuthorized(actorUid, spaceId, DocumentCapability.READ) { _, _ ->
            requireActiveDocumentIdentity(transaction, spaceId, documentId)
            val candidates = repository.listRevisions(transaction, documentId, beforeRevision, limit + 1)
            val items = candidates.take(limit)
            DocumentRevisionPage(
                items = items,
                nextBeforeRevision = if (candidates.size > limit) {
                    items.last().revision
                } else {
                    DocumentRevisionPage.END_CURSOR
                },
            )
        }
    }

    suspend fun getRevision(
        actorUid: String,
        spaceId: String,
        documentId: String,
        revision: Long,
    ): DocumentRevision {
        require(revision > 0) { "文档版本非法" }
        return accessControl.readAuthorized(actorUid, spaceId, DocumentCapability.READ) { _, _ ->
            requireActiveDocumentIdentity(transaction, spaceId, documentId)
            repository.findRevision(transaction, documentId, revision)
                ?: throw DocumentNotFoundException("文档版本不存在")
        }
    }

    suspend fun listRecentDocuments(actorUid: String, limit: Int): List<DocumentHomeItem> {
        validateHomeLimit(limit)
        return unitOfWork.read {
            accessControl.resolveReadableHomeRecords(
                actorUid,
                repository.findUser(transaction, actorUid),
                repository.listRecentDocuments(transaction, actorUid, limit),
            ).map(::toHomeItem)
        }
    }

    suspend fun listRecentlyCreatedDocuments(actorUid: String, limit: Int): List<DocumentHomeItem> {
        validateHomeLimit(limit)
        return unitOfWork.read {
            accessControl.resolveReadableHomeRecords(
                actorUid,
                repository.findUser(transaction, actorUid),
                repository.listRecentlyCreatedDocuments(transaction, actorUid, limit),
            ).map(::toHomeItem)
        }
    }

    private fun toHomeItem(record: DocumentHomeRecord): DocumentHomeItem = DocumentHomeItem(
        documentId = record.documentId,
        spaceId = record.spaceId,
        spaceName = record.spaceName,
        title = record.title,
        excerpt = record.excerpt,
        createdBy = record.createdBy,
        creatorName = record.creatorName,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
        accessedAt = record.accessedAt,
    )

    private fun validateHomeLimit(limit: Int) {
        require(limit in 1..MAX_HOME_DOCUMENTS) { "首页文档数量必须在 1..$MAX_HOME_DOCUMENTS 之间" }
    }

    private fun validateResourceId(value: String, label: String): String {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "$label 非法"
        }
        return value
    }

    private fun validatePrincipalId(value: String, label: String): String {
        require(value.isNotBlank() && value.length <= 36) { "$label 非法" }
        return value
    }

    private fun resolveCustodyReplay(
        receipt: DocumentCustodyTransferReceipt?,
        actorUid: String,
        spaceId: String,
        fingerprint: String,
    ): DocumentCustodyTransferResult? {
        if (receipt == null || receipt.actorUid != actorUid) return null
        if (receipt.spaceId != spaceId || receipt.fingerprint != fingerprint) {
            throw ReliableCommandConflictException("文档空间交接操作标识已用于不同请求")
        }
        return DocumentCustodyTransferResult(
            spaceId = receipt.spaceId,
            ownerPrincipalType = receipt.ownerPrincipalType,
            ownerPrincipalId = receipt.ownerPrincipalId,
            stewardUid = receipt.stewardUid,
            custodyRevision = receipt.custodyRevision,
        )
    }

    private fun DocumentSpace.toCustodyTransferResult() = DocumentCustodyTransferResult(
        spaceId = spaceId,
        ownerPrincipalType = ownerPrincipalType,
        ownerPrincipalId = ownerPrincipalId,
        stewardUid = stewardUid,
        custodyRevision = custodyRevision,
    )

    private fun requireParentDocument(
        transaction: PgReadTransactionContext,
        spaceId: String,
        parentId: String?,
    ) {
        if (parentId == null) return
        requireNode(transaction, spaceId, parentId)
    }

    private fun requireNode(
        transaction: PgReadTransactionContext,
        spaceId: String,
        nodeId: String,
    ): DocumentNode {
        return repository.findNode(transaction, spaceId, nodeId)
            ?: throw DocumentNotFoundException("文档节点不存在")
    }

    private fun requireDocument(
        transaction: PgReadTransactionContext,
        spaceId: String,
        documentId: String,
    ): Document {
        val document = repository.findDocument(transaction, spaceId, documentId)
            ?: throw DocumentNotFoundException("文档不存在")
        return document
    }

    private fun requireActiveDocumentIdentity(
        transaction: PgReadTransactionContext,
        spaceId: String,
        documentId: String,
    ): ActiveDocumentIdentity {
        val identity = repository.findActiveDocumentIdentity(transaction, spaceId, documentId)
            ?: throw DocumentNotFoundException("文档不存在")
        return identity
    }

    private fun validateSpaceName(value: String): String = DocumentPolicy.normalizeSpaceName(value)
    private fun validateNodeName(value: String): String = DocumentPolicy.normalizeNodeName(value)

    private fun validateDescription(value: String?): String? = DocumentPolicy.normalizeDescription(value)

    companion object {
        const val MAX_SPACE_NAME_LENGTH = DocumentPolicy.MAX_SPACE_NAME_LENGTH
        const val MAX_DESCRIPTION_LENGTH = DocumentPolicy.MAX_DESCRIPTION_LENGTH
        const val MAX_NODE_NAME_LENGTH = DocumentPolicy.MAX_NODE_NAME_LENGTH
        const val MAX_MARKDOWN_LENGTH = DocumentPolicy.MAX_MARKDOWN_LENGTH
        const val MAX_MARKDOWN_QUOTE_DEPTH = 64
        const val MAX_MARKDOWN_TABLE_COLUMNS = 32
        const val MAX_MARKDOWN_TABLE_CELLS = 1_000
        const val MAX_MARKDOWN_LINES = 20_000
        const val MAX_MARKDOWN_RENDERABLE_BLOCKS = 4_096
        const val MAX_HOME_DOCUMENTS = 50
        private const val USER_STATUS_ACTIVE = 1
    }
}
