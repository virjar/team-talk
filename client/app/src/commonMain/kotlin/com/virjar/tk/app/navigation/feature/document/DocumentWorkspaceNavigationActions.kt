package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.LatestRequestGate
import com.virjar.tk.app.navigation.feature.GenerationGate
import com.virjar.tk.app.navigation.feature.shouldReportCacheRefreshFailure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 导航、树投影和驻留文档所有权共享的状态边界。 */
internal class DocumentWorkspaceNavigationPort(
    val spaces: () -> List<DocumentSpace>,
    val selectedSpaceId: () -> String?,
    val setSelectedSpaceId: (String?) -> Unit,
    val home: () -> Pair<List<DocumentHomeItem>, List<DocumentHomeItem>>,
    val setHome: (List<DocumentHomeItem>, List<DocumentHomeItem>) -> Unit,
    val treeChildren: () -> Map<String?, List<DocumentNode>>,
    val setTreeChildren: (Map<String?, List<DocumentNode>>) -> Unit,
    val expandedNodeIds: () -> Set<String>,
    val setExpandedNodeIds: (Set<String>) -> Unit,
    val selectedParentNodeId: () -> String?,
    val setSelectedParentNodeId: (String?) -> Unit,
    val tabs: () -> List<DocumentTabState>,
    val setTabs: (List<DocumentTabState>) -> Unit,
    val activeTabId: () -> String?,
    val setActiveTabId: (String?) -> Unit,
    val clearGrants: () -> Unit,
    val closeHistory: () -> Unit,
    val persistDrafts: () -> Unit,
    val captureLatestActiveDraft: (DocumentTabState) -> DocumentTabState?,
    val nextTabInstanceId: () -> Long,
    val isSpaceLocalOnly: (String) -> Boolean,
    val removeSpaceProjection: (String, AppError.Business) -> Unit,
    val onNavigationStarted: () -> Unit,
)

/** 只由两个能够调和缺失文档的协作者共享的投影状态。 */
internal class DocumentWorkspaceNavigationProjectionState {
    var tree by mutableStateOf(DocumentWorkspaceProjectionStatus.NOT_LOADED)
    var document by mutableStateOf(DocumentWorkspaceProjectionStatus.NOT_LOADED)
}

/** 两个文档首页投影及其共享加载状态的最近者胜出所有权。 */
internal class DocumentHomeRequestCoordinator {
    private val requestGate = LatestRequestGate<Unit>()

    var loading by mutableStateOf(false)
        private set

    fun invalidate() {
        requestGate.invalidate()
        loading = false
    }

    suspend fun load(
        ownerIsCurrent: () -> Boolean,
        fetch: suspend () -> Pair<List<DocumentHomeItem>, List<DocumentHomeItem>>,
        publish: (List<DocumentHomeItem>, List<DocumentHomeItem>) -> Unit,
    ) {
        // 为过时导航派发的协程绝不能取代由当前导航拥有的、已经在运行的请求。
        if (!ownerIsCurrent()) return
        val request = requestGate.begin(Unit)
        loading = true
        try {
            val (recent, created) = fetch()
            if (!requestGate.isCurrent(request) || !ownerIsCurrent()) return
            publish(recent, created)
        } finally {
            // 更旧的完成绝不能清除由它更新的替换者拥有的 spinner。
            if (requestGate.isCurrent(request)) loading = false
        }
    }

    suspend fun loadCachedThenRemote(
        ownerIsCurrent: () -> Boolean,
        current: () -> Pair<List<DocumentHomeItem>, List<DocumentHomeItem>>,
        readCached: suspend () -> CachedDocumentHome,
        refresh: suspend () -> Pair<List<DocumentHomeItem>, List<DocumentHomeItem>>,
        publish: (List<DocumentHomeItem>, List<DocumentHomeItem>) -> Unit,
        publishStatus: (DocumentWorkspaceProjectionStatus) -> Unit,
    ) {
        if (!ownerIsCurrent()) return
        val request = requestGate.begin(Unit)
        loading = true
        var cachedSnapshotPublished = false
        try {
            val cached = readCached()
            if (!requestGate.isCurrent(request) || !ownerIsCurrent()) return
            if (cached.hasAnySnapshot) {
                val (currentRecent, currentCreated) = current()
                publish(
                    if (cached.recent.known) cached.recent.value else currentRecent,
                    if (cached.recentlyCreated.known) {
                        cached.recentlyCreated.value
                    } else {
                        currentCreated
                    },
                )
                cachedSnapshotPublished = true
                publishStatus(DocumentWorkspaceProjectionStatus.CACHED)
            } else {
                publishStatus(DocumentWorkspaceProjectionStatus.LOADING)
            }

            val (recent, created) = refresh()
            if (!requestGate.isCurrent(request) || !ownerIsCurrent()) return
            publish(recent, created)
            publishStatus(DocumentWorkspaceProjectionStatus.CURRENT)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (requestGate.isCurrent(request) && ownerIsCurrent()) {
                publishStatus(documentProjectionStatusAfterFailure(cachedSnapshotPublished, failure))
            }
            throw failure
        } finally {
            if (requestGate.isCurrent(request)) loading = false
        }
    }
}

/**
 * 协调导航 generation，并把可变投影委托给单一目的的 owner。
 *
 * [DocumentWorkspaceTreeNavigation] 拥有分支租约和路径揭示，
 * 而 [DocumentWorkspaceDocumentNavigation] 拥有驻留正文激活和远程收敛。
 * 这个 facade 在不共享两者任一请求生命周期的情况下，保留工作区的内部 API。
 */
internal class DocumentWorkspaceNavigationActions(
    private val readGateway: DocumentWorkspaceReadGateway,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val port: DocumentWorkspaceNavigationPort,
) {
    private val navigationGeneration = GenerationGate()
    private val projectionState = DocumentWorkspaceNavigationProjectionState()
    private val homeRequests = DocumentHomeRequestCoordinator()
    private val treeNavigation = DocumentWorkspaceTreeNavigation(
        readGateway = readGateway,
        reportError = reportError,
        port = port,
        projectionState = projectionState,
        isCurrentNavigation = { generation, spaceId -> isCurrent(generation, spaceId) },
    )
    private val documentNavigation = DocumentWorkspaceDocumentNavigation(
        readGateway = readGateway,
        scope = scope,
        reportError = reportError,
        port = port,
        projectionState = projectionState,
        treeNavigation = treeNavigation,
        beginNavigation = ::beginNavigation,
        isCurrentNavigation = { generation, spaceId -> isCurrent(generation, spaceId) },
        selectSpaceNow = ::selectSpaceNow,
    )

    val loadingHome: Boolean get() = homeRequests.loading
    val loadingNodes: Boolean get() = treeNavigation.loading
    val loadingDocument: Boolean get() = documentNavigation.loading
    val homeProjectionStatus: DocumentWorkspaceProjectionStatus
        get() = homeProjectionState
    val treeProjectionStatus: DocumentWorkspaceProjectionStatus
        get() = projectionState.tree
    val documentProjectionStatus: DocumentWorkspaceProjectionStatus
        get() = projectionState.document

    private var homeProjectionState by mutableStateOf(DocumentWorkspaceProjectionStatus.NOT_LOADED)

    fun beginNavigation(): Long {
        val generation = navigationGeneration.next()
        treeNavigation.invalidateAll()
        documentNavigation.resetForNavigation()
        port.onNavigationStarted()
        return generation
    }

    fun isCurrent(generation: Long, spaceId: String? = null): Boolean =
        navigationGeneration.isCurrent(generation) &&
            (spaceId == null || port.selectedSpaceId() == spaceId)

    fun showHome(additionalOwnerIsCurrent: () -> Boolean) {
        val generation = beginNavigation()
        clearSpaceSelection()
        scope.launch {
            try {
                loadHome {
                    additionalOwnerIsCurrent() && isCurrent(generation)
                }
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (additionalOwnerIsCurrent() && isCurrent(generation) &&
                    shouldReportCacheRefreshFailure(
                        failure,
                        homeProjectionState.hasPublishedSnapshot(),
                    )
                ) {
                    reportError(failure, "刷新文档首页失败")
                }
            }
        }
    }

    fun selectSpace(spaceId: String) {
        val generation = beginNavigation()
        port.closeHistory()
        scope.launch {
            try {
                selectSpaceNow(spaceId, generation)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (isCurrent(generation)) reportError(failure, "打开文档空间失败")
            }
        }
    }

    fun toggleNode(node: DocumentNode) = scope.launch {
        treeNavigation.toggleNode(node)
    }

    fun openDocument(node: DocumentNode) = documentNavigation.openDocument(node)

    fun openHomeDocument(item: DocumentHomeItem) = documentNavigation.openHomeDocument(item)

    /** 类型化引用入口：外部已重校验权限，复用既有标签打开链路。 */
    fun openReferenced(spaceId: String, documentId: String) =
        documentNavigation.openReferenced(spaceId, documentId)

    fun selectTab(tabId: String) = documentNavigation.selectTab(tabId)

    /** 在不重试一个已经被 403 拒绝的空间的情况下，选择保留的本地孤儿。 */
    fun selectLocalOrphanSpace(spaceId: String) {
        val generation = beginNavigation()
        port.closeHistory()
        selectLocalOrphanSpaceNow(spaceId, generation)
    }

    suspend fun selectSpaceNow(spaceId: String, generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        require(port.spaces().any { it.spaceId == spaceId }) { "文档空间不存在" }
        if (port.isSpaceLocalOnly(spaceId)) {
            return selectLocalOrphanSpaceNow(spaceId, generation)
        }
        val spaceChanged = port.selectedSpaceId() != spaceId
        port.setSelectedSpaceId(spaceId)
        port.setActiveTabId(null)
        port.closeHistory()
        port.setSelectedParentNodeId(null)
        if (spaceChanged) treeNavigation.clearVisibleProjection()
        port.clearGrants()
        treeNavigation.loadRoot(generation)
        port.persistDrafts()
        // 目录可用性不拥有导航。一次离线缓存 miss 仍然打开本地空间界面，
        // 这样缓存的正文或草稿保持可及。
        return isCurrent(generation, spaceId)
    }

    private fun selectLocalOrphanSpaceNow(spaceId: String, generation: Long): Boolean {
        if (!isCurrent(generation) || !port.isSpaceLocalOnly(spaceId) ||
            port.spaces().none { it.spaceId == spaceId }
        ) return false
        port.setSelectedSpaceId(spaceId)
        treeNavigation.clearVisibleProjection()
        port.setSelectedParentNodeId(null)
        port.clearGrants()
        val localTab = port.tabs().lastOrNull { tab ->
            tab.spaceId == spaceId && (tab.dirty || tab.creating)
        }
        port.setActiveTabId(localTab?.tabId)
        projectionState.tree = DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN
        projectionState.document = DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN
        port.persistDrafts()
        return isCurrent(generation, spaceId)
    }

    suspend fun loadTreeRoot(generation: Long): Boolean = treeNavigation.loadRoot(generation)

    suspend fun loadChildren(
        parentId: String?,
        generation: Long? = null,
        expectedSpaceId: String? = null,
    ): Boolean = treeNavigation.loadChildren(parentId, generation, expectedSpaceId)

    suspend fun reloadChildren(spaceId: String, parentId: String?) =
        treeNavigation.reloadChildren(spaceId, parentId)

    fun prepareDocumentRefreshBranches(
        document: Document,
        previousParentIds: Set<String?> = emptySet(),
    ): List<String?>? = treeNavigation.prepareDocumentRefreshBranches(
        document,
        previousParentIds,
    )

    fun prepareDocumentNodeRefreshBranches(
        spaceId: String,
        nodeId: String,
        parentId: String?,
        previousParentIds: Set<String?> = emptySet(),
    ): List<String?>? = treeNavigation.prepareDocumentNodeRefreshBranches(
        spaceId,
        nodeId,
        parentId,
        previousParentIds,
    )

    suspend fun refreshDocumentBranches(
        document: Document,
        previousParentIds: Set<String?> = emptySet(),
        generation: Long? = null,
    ): Boolean = treeNavigation.refreshDocumentBranches(
        document,
        previousParentIds,
        generation,
    )

    suspend fun openDocumentNow(spaceId: String, documentId: String, generation: Long) {
        documentNavigation.openDocumentNow(spaceId, documentId, generation)
    }

    suspend fun loadHome(ownerIsCurrent: () -> Boolean) = homeRequests.loadCachedThenRemote(
        ownerIsCurrent = ownerIsCurrent,
        current = port.home,
        readCached = readGateway::cachedHome,
        refresh = readGateway::refreshHome,
        publish = port.setHome,
        publishStatus = { homeProjectionState = it },
    )

    /** 在任何远程请求可以延迟文档首页之前的 bootstrap 发布。 */
    fun publishCachedHome(
        cached: CachedDocumentHome,
        ownerIsCurrent: () -> Boolean,
    ): Boolean {
        if (!ownerIsCurrent() || !cached.hasAnySnapshot) return false
        val (currentRecent, currentCreated) = port.home()
        port.setHome(
            if (cached.recent.known) cached.recent.value else currentRecent,
            if (cached.recentlyCreated.known) cached.recentlyCreated.value else currentCreated,
        )
        homeProjectionState = DocumentWorkspaceProjectionStatus.CACHED
        return true
    }

    suspend fun revealDocumentPath(ancestorIds: List<String>, generation: Long): Boolean =
        treeNavigation.revealPath(ancestorIds, generation)

    suspend fun revealCachedDocumentSpine(
        stamp: DocumentPathStamp,
        generation: Long,
    ): Boolean = treeNavigation.revealCachedSpine(stamp, generation)

    suspend fun revealCachedRestoredDocumentSpine(
        intent: DocumentTabNavigationIntent,
        documentId: String,
    ): Boolean = treeNavigation.revealCachedRestoredSpine(intent, documentId)

    suspend fun refreshAndRevealDocumentSpine(
        stamp: DocumentPathStamp,
        generation: Long,
    ): DocumentPathRevealResult = treeNavigation.refreshAndRevealSpine(stamp, generation)

    fun invalidateBranch(spaceId: String, parentId: String?) {
        treeNavigation.invalidateBranch(spaceId, parentId)
    }

    fun invalidateDocumentPath(stamp: DocumentPathStamp) {
        treeNavigation.invalidateDocumentPath(stamp)
    }

    fun forgetDocumentIdentity(spaceId: String, documentId: String) {
        treeNavigation.forgetDocumentIdentity(spaceId, documentId)
    }

    fun publishMissingDocumentProjection(hasResidentDocument: Boolean) {
        projectionState.document = if (hasResidentDocument) {
            DocumentWorkspaceProjectionStatus.CACHED
        } else {
            DocumentWorkspaceProjectionStatus.NOT_LOADED
        }
    }

    /** 在 feature 退役一个空间工作集之后，隔离每一个挂起的分支/正文延续。 */
    fun removeSpaceProjection(spaceId: String, selectedProjectionRetired: Boolean) {
        treeNavigation.invalidateSpace(spaceId)
        if (selectedProjectionRetired) beginNavigation()
        if (selectedProjectionRetired && port.selectedSpaceId() == spaceId) {
            projectionState.tree = DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN
            projectionState.document = DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN
        } else if (selectedProjectionRetired) {
            projectionState.tree = DocumentWorkspaceProjectionStatus.NOT_LOADED
            projectionState.document = DocumentWorkspaceProjectionStatus.NOT_LOADED
        }
    }

    fun clearSpaceSelection() {
        treeNavigation.invalidateAll()
        port.setSelectedSpaceId(null)
        treeNavigation.clearVisibleProjection()
        projectionState.document = DocumentWorkspaceProjectionStatus.NOT_LOADED
        port.setSelectedParentNodeId(null)
        port.clearGrants()
        port.closeHistory()
        port.persistDrafts()
    }
}
