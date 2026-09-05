package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine

/**
 * 拥有惰性文档树投影以及每一个可以修改它的请求租约。
 *
 * 分支响应只有在它的分支租约和外围导航 generation 都仍然现行时才可发布。
 * 把这两项检查放在树修改旁边，可以防止正文激活或之后的空间选择发布进错误的目录。
 */
internal class DocumentWorkspaceTreeNavigation(
    private val readGateway: DocumentWorkspaceReadGateway,
    private val reportError: (Throwable, String) -> Unit,
    private val port: DocumentWorkspaceNavigationPort,
    private val projectionState: DocumentWorkspaceNavigationProjectionState,
    private val isCurrentNavigation: (Long, String?) -> Boolean,
) {
    private val branchRequestGate = DocumentBranchRequestGate()
    private var branchLoadsInFlight by mutableStateOf(0)
    private var partialBranchParentIds = emptySet<String?>()

    val loading: Boolean get() = branchLoadsInFlight > 0

    fun invalidateAll() {
        branchRequestGate.invalidateAll()
    }

    fun clearVisibleProjection() {
        port.setTreeChildren(emptyMap())
        port.setExpandedNodeIds(emptySet())
        partialBranchParentIds = emptySet()
        projectionState.tree = DocumentWorkspaceProjectionStatus.NOT_LOADED
    }

    suspend fun toggleNode(node: DocumentNode) {
        val spaceId = port.selectedSpaceId() ?: return
        if (node.spaceId != spaceId) return
        if (node.nodeId in port.expandedNodeIds()) {
            port.setExpandedNodeIds(port.expandedNodeIds() - node.nodeId)
            return
        }
        try {
            if (!port.treeChildren().containsKey(node.nodeId) ||
                node.nodeId in partialBranchParentIds
            ) {
                if (!node.hasChildren ||
                    !loadChildren(node.nodeId, expectedSpaceId = spaceId)
                ) return
            }
            if (port.selectedSpaceId() != spaceId) return
            if (port.treeChildren()[node.nodeId].orEmpty().isNotEmpty()) {
                port.setExpandedNodeIds(port.expandedNodeIds() + node.nodeId)
            }
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            reportError(failure, "展开子文档失败")
        }
    }

    suspend fun loadRoot(generation: Long): Boolean {
        val spaceId = port.selectedSpaceId() ?: return false
        if (!isCurrentNavigation(generation, spaceId)) return false
        branchRequestGate.invalidateAll()
        return loadChildren(null, generation, expectedSpaceId = spaceId)
    }

    suspend fun loadChildren(
        parentId: String?,
        generation: Long? = null,
        expectedSpaceId: String? = null,
    ): Boolean {
        val spaceId = resolveDocumentBranchSpace(
            selectedSpaceId = port.selectedSpaceId(),
            expectedSpaceId = expectedSpaceId,
        ) ?: return false
        if (generation != null && !isCurrentNavigation(generation, spaceId)) return false
        val request = branchRequestGate.begin(spaceId, parentId)
        branchLoadsInFlight = branchRequestGate.inFlightCount
        var hadPublishedSnapshot = port.treeChildren().containsKey(parentId)
        try {
            val cached = readGateway.cachedNodes(spaceId, parentId)
            if (!owns(request, generation)) return false

            if (cached.known) {
                publishBranch(spaceId, parentId, cached.value)
                hadPublishedSnapshot = true
                projectionState.tree = DocumentWorkspaceProjectionStatus.CACHED
            } else if (!hadPublishedSnapshot) {
                projectionState.tree = DocumentWorkspaceProjectionStatus.LOADING
            }

            val children = try {
                readGateway.refreshNodes(spaceId, parentId)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (failure is AppError.Business && failure.code == 403) {
                    if (!port.isSpaceLocalOnly(spaceId)) {
                        port.removeSpaceProjection(spaceId, failure)
                    }
                    return false
                }
                val stillCurrent = owns(request, generation)
                if (stillCurrent) {
                    projectionState.tree = documentProjectionStatusAfterFailure(
                        hadCachedSnapshot = hadPublishedSnapshot,
                        failure = failure,
                    )
                    if (failure is AppError.Business &&
                        failure.code == 404 && parentId == null
                    ) {
                        if (!port.isSpaceLocalOnly(spaceId)) {
                            port.removeSpaceProjection(spaceId, failure)
                        }
                        return false
                    } else if (failure is AppError.Business &&
                        failure.code == 404 && parentId != null
                    ) {
                        reconcileMissingParent(spaceId, parentId, failure)
                        projectionState.tree = DocumentWorkspaceProjectionStatus.CACHED
                        return false
                    }
                }
                if (stillCurrent) return hadPublishedSnapshot
                throw failure
            }
            if (!owns(request, generation)) return false
            publishBranch(spaceId, parentId, children)
            projectionState.tree = DocumentWorkspaceProjectionStatus.CURRENT
            return true
        } finally {
            branchRequestGate.finish(request)
            branchLoadsInFlight = branchRequestGate.inFlightCount
        }
    }

    private fun owns(
        request: DocumentBranchRequestGate.Token,
        generation: Long?,
    ): Boolean = port.selectedSpaceId() == request.spaceId &&
        (generation == null || isCurrentNavigation(generation, request.spaceId)) &&
        branchRequestGate.isCurrent(request)

    private fun publishBranch(
        spaceId: String,
        parentId: String?,
        children: List<DocumentNode>,
    ) {
        val previousTreeChildren = port.treeChildren()
        val publishedTreeChildren = publishDocumentTreeBranch(
            treeChildren = previousTreeChildren,
            spaceId = spaceId,
            parentId = parentId,
            children = children,
        )
        port.setTreeChildren(publishedTreeChildren)
        partialBranchParentIds = (partialBranchParentIds - parentId)
            .filterTo(linkedSetOf(), publishedTreeChildren::containsKey)
        port.setExpandedNodeIds(
            reconcileExpandedDocumentTreeBranches(
                expandedNodeIds = port.expandedNodeIds(),
                previousTreeChildren = previousTreeChildren,
                publishedTreeChildren = publishedTreeChildren,
            ),
        )
    }

    /** 子分支 404 只证明那个父文档，绝不证明整个空间。 */
    private fun reconcileMissingParent(
        spaceId: String,
        documentId: String,
        failure: AppError.Business,
    ) {
        val previousTree = port.treeChildren()
        val removedIds = buildSet {
            add(documentId)
            addAll(knownDocumentDescendantIds(documentId, previousTree))
        }
        val nextTree = previousTree
            .filterKeys { parentId -> parentId !in removedIds }
            .mapValues { (_, children) -> children.filterNot { it.nodeId in removedIds } }
        port.setTreeChildren(nextTree)
        partialBranchParentIds = partialBranchParentIds
            .filterTo(linkedSetOf(), nextTree::containsKey)
        port.setExpandedNodeIds(port.expandedNodeIds() - removedIds)

        val currentTabs = port.tabs()
        val activeBefore = currentTabs.firstOrNull { it.tabId == port.activeTabId() }
        var orphanRetained = false
        val nextTabs = currentTabs.mapNotNull { tab ->
            if (tab.spaceId != spaceId) return@mapNotNull tab
            when {
                tab.documentId == documentId && (tab.dirty || tab.creating) -> {
                    orphanRetained = true
                    tab.copy(pathResolved = false, remoteMissing = !tab.creating)
                }
                tab.documentId == documentId -> null
                tab.documentId in removedIds || documentId in tab.ancestorIds ->
                    tab.copy(pathResolved = false)
                else -> tab
            }
        }
        if (nextTabs != currentTabs) port.setTabs(nextTabs)
        val activeAfter = activeBefore?.let { captured ->
            nextTabs.firstOrNull { it.instanceId == captured.instanceId }
        } ?: nextTabs.lastOrNull { it.spaceId == spaceId }
        if (activeBefore?.documentId in removedIds) {
            port.setActiveTabId(activeAfter?.tabId)
            port.setSelectedParentNodeId(activeAfter?.resolvedParentIdForNavigation())
            port.closeHistory()
            projectionState.document = if (activeAfter == null) {
                DocumentWorkspaceProjectionStatus.NOT_LOADED
            } else {
                DocumentWorkspaceProjectionStatus.CACHED
            }
        } else if (port.selectedSpaceId() == spaceId &&
            port.selectedParentNodeId() in removedIds
        ) {
            port.setSelectedParentNodeId(null)
        }
        port.persistDrafts()
        reportError(
            failure,
            when {
                orphanRetained -> "文档已不存在，已保留本地未保存内容"
                activeBefore?.documentId == documentId -> "文档已不存在，已关闭本地标签"
                else -> "文档已不存在，已从目录移除"
            },
        )
    }

    suspend fun reloadChildren(spaceId: String, parentId: String?) {
        if (port.selectedSpaceId() == spaceId) {
            loadChildren(parentId, expectedSpaceId = spaceId)
        }
    }

    /**
     * 在任何分支重载可以挂起之前，把完整的服务器文档与惰性树调和。
     * 活动空间是显式的，因此切换空间绝不可能把旧的 parent ID
     * 重新指向新树。
     */
    fun prepareDocumentRefreshBranches(
        document: Document,
        previousParentIds: Set<String?> = emptySet(),
    ): List<String?>? {
        require(document.hasValidDocumentPath()) { "服务器返回了非法文档路径" }
        return prepareDocumentNodeRefreshBranches(
            spaceId = document.spaceId,
            nodeId = document.documentId,
            parentId = document.parentId,
            previousParentIds = previousParentIds,
        )
    }

    fun prepareDocumentNodeRefreshBranches(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        previousParentIds: Set<String?> = emptySet(),
    ): List<String?>? {
        if (port.selectedSpaceId() != spaceId) return null
        val plan = planDocumentNodeTreeRefresh(
            treeChildren = port.treeChildren(),
            spaceId = spaceId,
            nodeId = nodeId,
            parentId = parentId,
            previousParentIds = previousParentIds,
        )
        // 撤销每一个过期请求，并把重复的身份作为一个不可挂起的发布移除。
        // 已经在途的响应不能再把旧节点放回去。
        plan.parentIdsToRefresh.forEach { branchParentId ->
            branchRequestGate.invalidate(spaceId, branchParentId)
        }
        port.setTreeChildren(plan.treeChildren)
        partialBranchParentIds = partialBranchParentIds
            .filterTo(linkedSetOf(), plan.treeChildren::containsKey)
        return plan.parentIdsToRefresh
    }

    suspend fun refreshDocumentBranches(
        document: Document,
        previousParentIds: Set<String?> = emptySet(),
        generation: Long? = null,
    ): Boolean {
        if (generation != null && !isCurrentNavigation(generation, document.spaceId)) return false
        val parentIdsToRefresh = prepareDocumentRefreshBranches(
            document = document,
            previousParentIds = previousParentIds,
        ) ?: return false

        var firstFailure: Exception? = null
        for (parentId in parentIdsToRefresh) {
            if (port.selectedSpaceId() != document.spaceId ||
                (generation != null && !isCurrentNavigation(generation, document.spaceId))
            ) return false
            try {
                loadChildren(
                    parentId = parentId,
                    generation = generation,
                    expectedSpaceId = document.spaceId,
                )
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
        return port.selectedSpaceId() == document.spaceId &&
            (generation == null || isCurrentNavigation(generation, document.spaceId))
    }

    /** 揭示一个仅目录的选择，其最终 ID 包含在 [ancestorIds] 中。 */
    suspend fun revealPath(ancestorIds: List<String>, generation: Long): Boolean {
        val spaceId = port.selectedSpaceId() ?: return false
        if (!isCurrentNavigation(generation, spaceId)) return false
        var parentId: String? = null
        val visited = mutableSetOf<String>()
        for (ancestorId in ancestorIds) {
            if (!visited.add(ancestorId) || !isCurrentNavigation(generation, spaceId)) return false
            if (!port.treeChildren().containsKey(parentId) &&
                !loadChildren(parentId, generation, expectedSpaceId = spaceId)
            ) return false
            if (!isCurrentNavigation(generation, spaceId)) return false
            val ancestor = port.treeChildren()[parentId].orEmpty().firstOrNull {
                it.nodeId == ancestorId
            } ?: return false
            if (ancestor.spaceId != spaceId) return false
            port.setExpandedNodeIds(port.expandedNodeIds() + ancestorId)
            if (!port.treeChildren().containsKey(ancestorId) &&
                !loadChildren(ancestorId, generation, expectedSpaceId = spaceId)
            ) return false
            if (!isCurrentNavigation(generation, spaceId)) return false
            parentId = ancestorId
        }
        return isCurrentNavigation(generation, spaceId)
    }

    /** 揭示一条持久化路径，而不把它单例的边变成完整分支。 */
    suspend fun revealCachedSpine(
        stamp: DocumentPathStamp,
        generation: Long,
    ): Boolean {
        if (!owns(stamp, generation)) return false
        if (loadedDocumentPathMatches(port.treeChildren(), stamp)) {
            port.setExpandedNodeIds(port.expandedNodeIds() + stamp.ancestorIds)
            return true
        }
        val spine = try {
            readGateway.cachedNodePathSpine(stamp.spaceId, stamp.documentId)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            return false
        } ?: return false
        if (!owns(stamp, generation) || !spine.matches(stamp)) return false
        return publishSpine(spine, stamp, generation)
    }

    /**
     * 只为未解析的 dirty tab 恢复可见的缓存路径。该 tab 在其正文被重新校验之前保持未解析；
     * 这个投影仅仅防止无关的正文故障隐藏已经持久化的目录。
     */
    suspend fun revealCachedRestoredSpine(
        intent: DocumentTabNavigationIntent,
        documentId: String,
    ): Boolean {
        val captured = intent.resolve(port.tabs(), isCurrentNavigation)
            ?.takeIf { it.documentId == documentId }
            ?: return false
        val minimumRevision = captured.revision ?: return false
        val spine = try {
            readGateway.cachedNodePathSpine(intent.spaceId, documentId)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            return false
        } ?: return false
        val current = intent.resolve(port.tabs(), isCurrentNavigation)
            ?.takeIf { it.documentId == documentId }
            ?: return false
        if (spine.spaceId != intent.spaceId || spine.targetNodeId != documentId ||
            spine.nodes.last().revision < minimumRevision ||
            current.revision != captured.revision
        ) return false

        val projection = mergeDocumentPathSpineIntoTree(
            treeChildren = port.treeChildren(),
            partialBranchParentIds = partialBranchParentIds,
            spine = spine,
        )
        if (intent.resolve(port.tabs(), isCurrentNavigation)?.documentId != documentId) return false
        spine.nodes.forEach { node ->
            branchRequestGate.invalidate(intent.spaceId, node.parentId)
        }
        port.setTreeChildren(projection.treeChildren)
        partialBranchParentIds = projection.partialBranchParentIds
        port.setExpandedNodeIds(
            port.expandedNodeIds() + spine.nodes.dropLast(1).map(DocumentNode::nodeId),
        )
        projectionState.tree = DocumentWorkspaceProjectionStatus.CACHED
        return true
    }

    /** 获取一条有界的远程路径，并只调和一次确切拥有的目标失败。 */
    suspend fun refreshAndRevealSpine(
        stamp: DocumentPathStamp,
        generation: Long,
    ): DocumentPathRevealResult {
        if (!owns(stamp, generation)) return DocumentPathRevealResult.Superseded
        val spine = try {
            readGateway.refreshNodePathSpine(stamp.spaceId, stamp.documentId)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (!owns(stamp, generation)) return DocumentPathRevealResult.Superseded
            when {
                failure is AppError.Business && failure.code == 403 -> {
                    if (!port.isSpaceLocalOnly(stamp.spaceId)) {
                        port.removeSpaceProjection(stamp.spaceId, failure)
                    }
                    return DocumentPathRevealResult.Superseded
                }
                failure is AppError.Business &&
                    failure.code == DOCUMENT_NOT_FOUND_STATUS -> {
                    reconcileMissingSpineTarget(stamp, failure)
                    return DocumentPathRevealResult.Contradicted
                }
                else -> {
                    projectionState.tree = documentProjectionStatusAfterFailure(
                        hadCachedSnapshot = loadedDocumentPathMatches(
                            port.treeChildren(),
                            stamp,
                        ),
                        failure = failure,
                    )
                    throw failure
                }
            }
        }
        if (!owns(stamp, generation)) return DocumentPathRevealResult.Superseded
        if (!spine.matches(stamp)) {
            invalidatePath(stamp)
            return DocumentPathRevealResult.Contradicted
        }
        return if (publishSpine(spine, stamp, generation)) {
            projectionState.tree = DocumentWorkspaceProjectionStatus.CURRENT
            DocumentPathRevealResult.Revealed
        } else if (owns(stamp, generation)) {
            invalidatePath(stamp)
            DocumentPathRevealResult.Contradicted
        } else {
            DocumentPathRevealResult.Superseded
        }
    }

    private fun publishSpine(
        spine: DocumentPathSpine,
        stamp: DocumentPathStamp,
        generation: Long,
    ): Boolean {
        if (!owns(stamp, generation)) return false
        val projection = mergeDocumentPathSpineIntoTree(
            treeChildren = port.treeChildren(),
            partialBranchParentIds = partialBranchParentIds,
            spine = spine,
        )
        if (!loadedDocumentPathMatches(projection.treeChildren, stamp) ||
            !owns(stamp, generation)
        ) return false
        spine.nodes.forEach { node ->
            branchRequestGate.invalidate(stamp.spaceId, node.parentId)
        }
        port.setTreeChildren(projection.treeChildren)
        partialBranchParentIds = projection.partialBranchParentIds
        port.setExpandedNodeIds(port.expandedNodeIds() + stamp.ancestorIds)
        projectionState.tree = DocumentWorkspaceProjectionStatus.CACHED
        return true
    }

    private fun owns(stamp: DocumentPathStamp, generation: Long): Boolean {
        if (!isCurrentNavigation(generation, stamp.spaceId) ||
            port.selectedSpaceId() != stamp.spaceId
        ) return false
        val tab = port.tabs().firstOrNull { it.instanceId == stamp.instanceId } ?: return false
        return tab.tabId == port.activeTabId() && stamp.targets(tab)
    }

    private fun DocumentPathSpine.matches(stamp: DocumentPathStamp): Boolean =
        spaceId == stamp.spaceId && targetNodeId == stamp.documentId &&
            nodes.dropLast(1).map(DocumentNode::nodeId) == stamp.ancestorIds &&
            nodes.last().parentId == stamp.parentId

    private fun invalidatePath(stamp: DocumentPathStamp) {
        val currentTabs = port.tabs()
        val invalidated = invalidateDocumentPathStamp(currentTabs, stamp)
        if (invalidated === currentTabs) return
        port.setTabs(invalidated)
        val active = invalidated.firstOrNull { it.instanceId == stamp.instanceId }
        if (active?.tabId == port.activeTabId()) port.setSelectedParentNodeId(null)
        port.persistDrafts()
    }

    fun invalidateBranch(spaceId: String, parentId: String?) {
        branchRequestGate.invalidate(spaceId, parentId)
    }

    fun invalidateDocumentPath(stamp: DocumentPathStamp) = invalidatePath(stamp)

    fun forgetDocumentIdentity(spaceId: String, documentId: String) {
        if (port.selectedSpaceId() != spaceId) return
        val nextTree = removeDeletedDocumentTreeIdentity(
            treeChildren = port.treeChildren(),
            spaceId = spaceId,
            documentId = documentId,
        )
        port.setTreeChildren(nextTree)
        partialBranchParentIds = partialBranchParentIds
            .filterTo(linkedSetOf(), nextTree::containsKey)
        port.setExpandedNodeIds(port.expandedNodeIds() - documentId)
    }

    /** 目标级 404 退役它的树行，同时只保留独立 dirty 的编辑器数据。 */
    private fun reconcileMissingSpineTarget(
        stamp: DocumentPathStamp,
        failure: AppError.Business,
    ) {
        val current = port.tabs().firstOrNull(stamp::targets) ?: return
        if (current.tabId != port.activeTabId()) return
        val captured = port.captureLatestActiveDraft(current) ?: return
        if (!stamp.targets(captured)) return

        forgetDocumentIdentity(stamp.spaceId, stamp.documentId)
        val reconciliation = reconcileMissingActiveDocumentRefresh(
            tabs = port.tabs(),
            missingInstanceId = captured.instanceId,
            activeTabId = port.activeTabId(),
        ) ?: return
        port.setTabs(reconciliation.tabs)
        port.setActiveTabId(reconciliation.activeTabId)
        port.setSelectedParentNodeId(reconciliation.selectedParentNodeId)
        port.persistDrafts()
        projectionState.document = if (reconciliation.activeTab == null) {
            DocumentWorkspaceProjectionStatus.NOT_LOADED
        } else {
            DocumentWorkspaceProjectionStatus.CACHED
        }
        reportError(
            failure,
            if (reconciliation.orphanRetained) {
                "文档已被删除，已保留本地未保存内容"
            } else {
                "文档已被删除，已关闭本地标签"
            },
        )
    }

    fun invalidateSpace(spaceId: String) {
        branchRequestGate.invalidateSpace(spaceId)
    }
}
