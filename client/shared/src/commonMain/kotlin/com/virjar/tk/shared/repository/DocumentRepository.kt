package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.shared.client.DocumentHomeCollection
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentCreateResult
import com.virjar.tk.protocol.model.DocumentCustodyTransferResult
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionPage
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.protocol.model.DocumentSpacePageRequest
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.RpcStatusException
import com.virjar.tk.protocol.rpc.gen.DocumentRpcProxy
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 企业文档 SDK 边界。
 *
 * 本地投影访问被刻意设计为同步的：UI 调用方使用自己的本地数据边界。
 * 这个 repository 是唯一的远程请求持有者，并把 Document RPC 的完成与其
 * 缓存发布或清除串行化。缓存租约只表达普通的按投影请求顺序；
 * 客户端不会复现服务端的权限状态机。网络、超时和
 * 服务端 5xx 失败永远不会丢弃已缓存的事实。
 */
class DocumentRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
    newMoveOperationId: () -> String = { UUID.randomUUID().toString() },
    nowMillis: () -> Long = System::currentTimeMillis,
    onPendingReliableCommandCommitted: () -> Unit = {},
    onPendingMoveCommandCompleted: (DocumentMoveCommandCompletion) -> Unit = {},
) {
    private val rpc = DocumentRpcProxy(rpcClient)
    private val projectionMaintenance = DocumentProjectionMaintenance(localCache)
    private val requestMutex = Mutex()
    private val moveProjectionConverger = DocumentMoveProjectionConverger(
        rpc = rpc,
        localCache = localCache,
        projectionMaintenance = projectionMaintenance,
    )
    private val moveCommands = DocumentMoveCommandCoordinator(
        rpc = rpc,
        localCache = localCache,
        projectionConverger = moveProjectionConverger,
        requestMutex = requestMutex,
        newOperationId = newMoveOperationId,
        nowMillis = nowMillis,
        onPendingCommandCommitted = onPendingReliableCommandCommitted,
        onCommandCompleted = onPendingMoveCommandCompleted,
    )
    private val spaceRefreshOwner = Any()

    fun isSpaceSnapshotCached(): Boolean = localCache.isDocumentSpaceSnapshotCached()

    fun cachedSpaces(): List<DocumentSpace> = localCache.getDocumentSpaces()

    fun isHomeSnapshotCached(collection: DocumentHomeCollection): Boolean =
        localCache.isDocumentHomeSnapshotCached(collection)

    fun cachedHome(collection: DocumentHomeCollection): List<DocumentHomeItem> =
        localCache.getDocumentHome(collection)

    fun isBranchCached(spaceId: String, parentId: String?): Boolean =
        localCache.isDocumentBranchCached(spaceId, parentId)

    fun cachedNodes(spaceId: String, parentId: String?): List<DocumentNode> =
        localCache.getDocumentNodes(spaceId, parentId)

    fun cachedNodePathSpine(spaceId: String, nodeId: String): DocumentPathSpine? =
        localCache.getDocumentPathSpine(spaceId, nodeId)

    fun cachedDocument(spaceId: String, documentId: String): Document? =
        localCache.getDocumentBody(spaceId, documentId)

    fun pendingMoveCommands(): List<PendingDocumentMoveCommand> =
        moveCommands.pendingCommands()

    fun beginSpaceRefreshCycle(): DocumentSpaceRefreshCycle = DocumentSpaceRefreshCycle(
        owner = spaceRefreshOwner,
        abandonProjection = localCache::abandonProjectionSnapshot,
    )

    suspend fun refreshSpacesPage(
        cycle: DocumentSpaceRefreshCycle,
        cursor: String? = null,
        limit: Int = DocumentSpacePage.DEFAULT_PAGE_SIZE,
    ): Outcome<DocumentSpaceRefreshPageResult> {
        synchronized(cycle.lock) {
            check(cycle.owner === spaceRefreshOwner) {
                "document space refresh cycle belongs to another repository"
            }
            check(!cycle.cancelled) { "document space refresh cycle was cancelled" }
            check(!cycle.completed) { "document space refresh cycle already completed" }
            check(!cycle.inFlight) { "document space refresh cycle already has an in-flight page" }
            check(cycle.expectedCursor == cursor) {
                "document space refresh cursor escaped its cycle"
            }
            cycle.inFlight = true
        }
        return try {
            remoteOutcome {
                val projectionLease = synchronized(cycle.lock) {
                    check(!cycle.cancelled) { "document space refresh cycle was cancelled" }
                    if (cursor == null) {
                        // 首页租约与它的 RPC 和缓存发布属于同一个串行化的 repository 操作。
                        // 失败的第一页可以在同一轮 cycle 上重试，
                        // 因此在开始新租约前先退掉未使用的旧租约。
                        cycle.projectionLease?.let(localCache::abandonProjectionSnapshot)
                        localCache.beginDocumentSpaceSnapshot().also {
                            cycle.projectionLease = it
                        }
                    } else {
                        checkNotNull(cycle.projectionLease) {
                            "document space continuation has no projection lease"
                        }
                    }
                }
                val page = rpc.listSpaces(DocumentSpacePageRequest(cursor, limit))
                require(page.nextCursor == null || page.nextCursor != cursor) {
                    "document space refresh cursor did not advance"
                }
                synchronized(cycle.lock) {
                    check(!cycle.cancelled) { "document space refresh cycle was cancelled" }
                    check(cycle.expectedCursor == cursor) {
                        "document space refresh cursor was superseded"
                    }
                    if (page.snapshotChanged) {
                        cycle.cancelled = true
                        localCache.abandonProjectionSnapshot(projectionLease)
                        cycle.projectionLease = null
                        return@synchronized DocumentSpaceRefreshPageResult.RestartRequired
                    }
                    val expectedVersion = cycle.snapshotVersion
                    if (expectedVersion != null && expectedVersion != page.snapshotVersion) {
                        cycle.cancelled = true
                        localCache.abandonProjectionSnapshot(projectionLease)
                        cycle.projectionLease = null
                        error("document space refresh version changed without a restart signal")
                    }
                    if (!localCache.applyDocumentSpaceRefreshPage(
                            lease = projectionLease,
                            spaces = page.items,
                            isFirstPage = cursor == null,
                            isTerminal = page.nextCursor == null,
                        )
                    ) {
                        cycle.cancelled = true
                        localCache.abandonProjectionSnapshot(projectionLease)
                        cycle.projectionLease = null
                        return@synchronized DocumentSpaceRefreshPageResult.RestartRequired
                    }
                    cycle.snapshotVersion = page.snapshotVersion
                    cycle.expectedCursor = page.nextCursor
                    cycle.completed = page.nextCursor == null
                    null
                }?.let { return@remoteOutcome it }
                val convergedById = localCache.getDocumentSpaces().associateBy(DocumentSpace::spaceId)
                DocumentSpaceRefreshPageResult.Page(
                    page.copy(
                        items = page.items.map { remote ->
                            checkNotNull(convergedById[remote.spaceId]) {
                                "document space page entity was not retained by the bounded projection"
                            }
                        },
                    ),
                )
            }
        } finally {
            synchronized(cycle.lock) {
                cycle.inFlight = false
            }
        }
    }

    suspend fun createSpace(
        spaceId: String,
        name: String,
        description: String?,
    ): Outcome<DocumentSpaceCreateResult> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        val remote = rpc.createSpace(spaceId, name, description)
        check(remote.spaceId == spaceId) { "createSpace response escaped its requested identity" }
        val projection = remote.space
        if (projection == null) {
            // 精确重试可能发生在托管权转移/归档之后。命令已完成，
            // 但客户端绝不能从旧的确认中伪造出当前投影。
            projectionMaintenance.runPostCommit("Failed to purge an unavailable created document space") {
                localCache.purgeDocumentSpace(spaceId)
            }
            remote
        } else {
            check(projection.spaceId == spaceId) {
                "createSpace response projection escaped its requested identity"
            }
            val accepted = projectionMaintenance.runPostCommit(
                "Failed to stage a created document space projection",
                fallback = false,
            ) {
                val lease = localCache.beginDocumentSpaceMutationSnapshot(spaceId)
                try {
                    localCache.applyDocumentSpaceMutation(lease, projection) &&
                        projectionMaintenance.requireConvergedSpace(projection).let { true }
                } finally {
                    localCache.abandonProjectionSnapshot(lease)
                }
            }
            remote.copy(space = projection.takeIf { accepted })
        }
    }

    suspend fun updateSpace(
        spaceId: String,
        name: String,
        description: String?,
    ): Outcome<DocumentMutationResult<DocumentSpace>> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        val remote = rpc.updateSpace(spaceId, name, description)
        check(remote.spaceId == spaceId) { "updateSpace response escaped its requested identity" }
        val projection = projectionMaintenance.runPostCommit(
            "Failed to stage an updated document space projection",
            fallback = null,
        ) {
            val lease = localCache.beginDocumentSpaceMutationSnapshot(spaceId)
            try {
                if (localCache.applyDocumentSpaceMutation(lease, remote)) {
                    projectionMaintenance.requireConvergedSpace(remote)
                } else {
                    null
                }
            } finally {
                localCache.abandonProjectionSnapshot(lease)
            }
        }
        DocumentMutationResult(projection)
    }

    suspend fun archiveSpace(spaceId: String, operationId: String): Outcome<Unit> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        rpc.archiveSpace(spaceId, operationId)
        projectionMaintenance.runPostCommit("Failed to purge an archived document space") {
            localCache.purgeDocumentSpace(spaceId)
        }
    }

    suspend fun transferSpaceCustody(
        spaceId: String,
        ownerPrincipalType: Int,
        ownerPrincipalId: String,
        stewardUid: String,
        expectedCustodyRevision: Long,
        operationId: String,
    ): Outcome<DocumentCustodyTransferResult> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        val remote = rpc.transferSpaceCustody(
            spaceId,
            ownerPrincipalType,
            ownerPrincipalId,
            stewardUid,
            expectedCustodyRevision,
            operationId,
        )
        check(
            remote.spaceId == spaceId &&
                remote.ownerPrincipalType == ownerPrincipalType &&
                remote.ownerPrincipalId == ownerPrincipalId &&
                remote.stewardUid == stewardUid,
        ) { "transferSpaceCustody response escaped its requested target" }
        check(remote.custodyRevision in expectedCustodyRevision..(expectedCustodyRevision + 1L)) {
            "transferSpaceCustody response escaped its requested revision"
        }
        // 命令确认刻意不携带当前的访问投影。草稿保留在它们各自独立的 owner 中，
        // 清除干净的服务端投影，并让下一次 list 确定调用方是否
        // 通过显式授权仍然保有访问权限。
        projectionMaintenance.runPostCommit("Failed to purge a transferred document space") {
            localCache.purgeDocumentSpace(spaceId)
        }
        remote
    }

    suspend fun listGrants(spaceId: String): Outcome<List<DocumentSpaceGrant>> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        rpc.listGrants(spaceId).items.also { grants ->
            check(grants.all { it.spaceId == spaceId }) {
                "listGrants response escaped its requested space"
            }
        }
    }

    suspend fun upsertGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): Outcome<DocumentPolicyMutationResult> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        rpc.upsertGrant(
            spaceId,
            principalType,
            principalId,
            role,
            includeDescendants,
            expectedPolicyRevision,
            operationId,
            issuedAt,
        ).also { result ->
            projectionMaintenance.validatePolicyMutationResult(
                requestedSpaceId = spaceId,
                expectedPolicyRevision = expectedPolicyRevision,
                result = result,
            )
            if (result.effectiveRole == DocumentSpace.ROLE_NONE) {
                projectionMaintenance.runPostCommit("Failed to purge an unavailable document space") {
                    localCache.purgeDocumentSpace(spaceId)
                }
            }
        }
    }

    suspend fun removeGrant(
        spaceId: String,
        principalType: Int,
        principalId: String,
        expectedPolicyRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): Outcome<DocumentPolicyMutationResult> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocumentSpace(spaceId) },
    ) {
        rpc.removeGrant(
            spaceId,
            principalType,
            principalId,
            expectedPolicyRevision,
            operationId,
            issuedAt,
        ).also { result ->
            projectionMaintenance.validatePolicyMutationResult(
                requestedSpaceId = spaceId,
                expectedPolicyRevision = expectedPolicyRevision,
                result = result,
            )
            if (result.effectiveRole == DocumentSpace.ROLE_NONE) {
                projectionMaintenance.runPostCommit("Failed to purge an unavailable document space") {
                    localCache.purgeDocumentSpace(spaceId)
                }
            }
        }
    }

    suspend fun listNodes(spaceId: String, parentId: String?): Outcome<List<DocumentNode>> =
        spaceOutcome(
            spaceId = spaceId,
            onNotFound = {
                if (parentId == null) {
                    localCache.purgeDocumentSpace(spaceId)
                } else {
                    localCache.purgeDocument(spaceId, parentId)
                }
            },
        ) {
            val lease = localCache.beginDocumentBranchSnapshot(spaceId, parentId)
            try {
                val remote = rpc.listNodes(spaceId, parentId)
                check(localCache.applyDocumentBranchSnapshot(lease, spaceId, parentId, remote)) {
                    "document branch response was fenced by a newer projection"
                }
                localCache.getDocumentNodes(spaceId, parentId)
            } finally {
                localCache.abandonProjectionSnapshot(lease)
            }
        }

    suspend fun getNodePathSpine(
        spaceId: String,
        nodeId: String,
    ): Outcome<DocumentPathSpine> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocument(spaceId, nodeId) },
    ) {
        val lease = localCache.beginDocumentPathSpineSnapshot(spaceId, nodeId)
        try {
            val remote = rpc.getNodePathSpine(spaceId, nodeId)
            check(remote.spaceId == spaceId && remote.targetNodeId == nodeId) {
                "document path spine response escaped its requested identity"
            }
            check(localCache.applyDocumentPathSpineSnapshot(lease, spaceId, nodeId, remote)) {
                "document path spine response was fenced by a newer projection"
            }
            checkNotNull(localCache.getDocumentPathSpine(spaceId, nodeId)) {
                "document path spine was not retained by the local projection"
            }
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    suspend fun createDocument(
        documentId: String,
        spaceId: String,
        parentId: String?,
        title: String,
        markdown: String,
        assets: List<EmbeddedAsset> = emptyList(),
    ): Outcome<DocumentMutationResult<Document>> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = {
            if (parentId == null) {
                localCache.purgeDocumentSpace(spaceId)
            } else {
                localCache.purgeDocument(spaceId, parentId)
            }
        },
    ) {
        val result: DocumentCreateResult =
            rpc.createDocument(documentId, spaceId, parentId, title, canonicalDocumentContent(markdown, assets))
        check(result.documentId == documentId) {
            "createDocument acknowledgement escaped its requested identity"
        }
        val remote = result.document
        if (remote == null) {
            // 精确可靠命令的重放证明最初的创建已提交，但它
            // 并不能证明当前投影仍然存在或仍然可见。
            DocumentMutationResult(null)
        } else {
            check(
                remote.documentId == documentId &&
                    remote.spaceId == spaceId &&
                    remote.parentId == parentId,
            ) { "createDocument response escaped its requested identity" }
            val projection = projectionMaintenance.runPostCommit(
                "Failed to stage a created document projection",
                fallback = null,
            ) {
                val lease = localCache.beginDocumentBodyMutationSnapshot(spaceId, documentId)
                try {
                    if (localCache.applyDocumentBodyMutation(lease, remote)) {
                        projectionMaintenance.requireConvergedDocument(remote)
                    } else {
                        null
                    }
                } finally {
                    localCache.abandonProjectionSnapshot(lease)
                }
            }
            DocumentMutationResult(projection)
        }
    }

    suspend fun getDocument(spaceId: String, documentId: String): Outcome<Document> =
        spaceOutcome(
            spaceId = spaceId,
            onNotFound = { localCache.purgeDocument(spaceId, documentId) },
        ) {
            val lease = localCache.beginDocumentBodySnapshot(spaceId, documentId)
            try {
                val remote = rpc.getDocument(spaceId, documentId)
                check(remote.spaceId == spaceId && remote.documentId == documentId) {
                    "getDocument response escaped its requested identity"
                }
                check(localCache.applyDocumentBodySnapshot(lease, remote)) {
                    "document body response was fenced by a newer projection"
                }
                projectionMaintenance.requireConvergedDocument(remote)
            } finally {
                localCache.abandonProjectionSnapshot(lease)
            }
        }

    suspend fun updateDocument(
        spaceId: String,
        documentId: String,
        markdown: String,
        expectedRevision: Long,
        assets: List<EmbeddedAsset> = emptyList(),
    ): Outcome<DocumentMutationResult<Document>> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocument(spaceId, documentId) },
    ) {
        val remote = rpc.updateDocument(
            spaceId,
            documentId,
            canonicalDocumentContent(markdown, assets),
            expectedRevision,
        )
        check(remote.spaceId == spaceId && remote.documentId == documentId) {
            "updateDocument response escaped its requested identity"
        }
        val projection = projectionMaintenance.runPostCommit(
            "Failed to stage an updated document projection",
            fallback = null,
        ) {
            val lease = localCache.beginDocumentBodyMutationSnapshot(spaceId, documentId)
            try {
                if (localCache.applyDocumentBodyMutation(lease, remote)) {
                    projectionMaintenance.requireConvergedDocument(remote)
                } else {
                    null
                }
            } finally {
                localCache.abandonProjectionSnapshot(lease)
            }
        }
        DocumentMutationResult(projection)
    }

    suspend fun moveNodeRecoverable(
        spaceId: String,
        nodeId: String,
        oldParentId: String?,
        targetParentId: String?,
        name: String,
        expectedRevision: Long,
    ): Outcome<DocumentMoveCommandSubmission> = moveCommands.submit(
        spaceId = spaceId,
        nodeId = nodeId,
        oldParentId = oldParentId,
        targetParentId = targetParentId,
        name = name,
        expectedRevision = expectedRevision,
    )

    internal suspend fun retryPendingMoveCommands(): Outcome<Unit> =
        moveCommands.retryPending()

    suspend fun deleteNode(
        spaceId: String,
        nodeId: String,
        expectedRevision: Long,
        operationId: String,
    ): Outcome<Unit> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocument(spaceId, nodeId) },
    ) {
        rpc.deleteNode(spaceId, nodeId, expectedRevision, operationId)
        projectionMaintenance.runPostCommit("Failed to purge a deleted document") {
            localCache.purgeDocument(spaceId, nodeId)
        }
    }

    suspend fun listRevisions(
        spaceId: String,
        documentId: String,
        beforeRevision: Long = DocumentRevisionPage.FIRST_PAGE_CURSOR,
        limit: Int = DocumentRevisionPage.DEFAULT_PAGE_SIZE,
    ): Outcome<DocumentRevisionPage> = spaceOutcome(
        spaceId = spaceId,
        onNotFound = { localCache.purgeDocument(spaceId, documentId) },
    ) { rpc.listRevisions(spaceId, documentId, beforeRevision, limit) }

    suspend fun getRevision(
        spaceId: String,
        documentId: String,
        revision: Long,
    ): Outcome<DocumentRevision> = spaceOutcome(
        spaceId = spaceId,
        // 这里的 404 是有歧义的：活跃文档可能存在，只是这个不可变 revision
        // 缺失。该路由不能从这个状态安全地推断出实体墓碑。
        onNotFound = {},
    ) { rpc.getRevision(spaceId, documentId, revision) }

    suspend fun listRecentDocuments(limit: Int): Outcome<List<DocumentHomeItem>> =
        refreshHome(DocumentHomeCollection.RECENT) { rpc.listRecentDocuments(limit) }

    suspend fun listRecentlyCreatedDocuments(limit: Int): Outcome<List<DocumentHomeItem>> =
        refreshHome(DocumentHomeCollection.RECENTLY_CREATED) {
            rpc.listRecentlyCreatedDocuments(limit)
        }

    private suspend inline fun refreshHome(
        collection: DocumentHomeCollection,
        crossinline load: suspend () -> List<DocumentHomeItem>,
    ): Outcome<List<DocumentHomeItem>> = remoteOutcome {
        val lease = localCache.beginDocumentHomeSnapshot(collection)
        try {
            val remote = load()
            localCache.applyDocumentHomeSnapshot(lease, collection, remote)
            localCache.getDocumentHome(collection)
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    private suspend inline fun <T> remoteOutcome(
        crossinline block: suspend () -> T,
    ): Outcome<T> = outcome { requestMutex.withLock { block() } }

    private suspend inline fun <T> spaceOutcome(
        spaceId: String,
        crossinline onNotFound: () -> Unit,
        crossinline block: suspend () -> T,
    ): Outcome<T> = remoteOutcome {
        try {
            block()
        } catch (failure: RpcStatusException) {
            invalidateStableProjectionFailure(failure.status, spaceId, onNotFound)
            throw failure
        } catch (failure: AppError.Business) {
            // 自定义/无头 RpcInvoker 实现可能已经暴露了公开的 SDK 错误。
            invalidateStableProjectionFailure(failure.code, spaceId, onNotFound)
            throw failure
        }
    }

    private inline fun invalidateStableProjectionFailure(
        status: Int,
        spaceId: String,
        onNotFound: () -> Unit,
    ) {
        when (status) {
            403 -> projectionMaintenance.runPostCommit("Failed to purge a forbidden document space") {
                localCache.purgeDocumentSpace(spaceId)
            }
            404 -> projectionMaintenance.runPostCommit("Failed to purge a missing document projection") {
                onNotFound()
            }
        }
    }

}

private fun canonicalDocumentContent(
    markdown: String,
    assets: List<EmbeddedAsset>,
): DocumentContent {
    val validatedMarkdown = DocumentPolicy.validateMarkdownEnvelope(markdown)
    return DocumentContent(
        markdown = validatedMarkdown,
        assets = MarkdownAssetPolicy.canonicalize(validatedMarkdown, assets),
    )
}
