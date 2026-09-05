package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.protocol.model.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 在用户打开工作区之前，不启动任何文档工作。之后，每一次转换回 AUTHENTICATED
 * 至多拥有一个刷新 job；更新的重连会取消更旧的那个。
 */
internal class DocumentWorkspaceReconnectRefreshCoordinator(
    connectionState: StateFlow<ConnectionState>,
    scope: CoroutineScope,
    private val workspaceOpened: () -> Boolean,
    private val refresh: () -> Job,
) {
    private var refreshJob: Job? = null

    @Suppress("unused")
    private val observerJob = scope.launch {
        var authenticated = connectionState.value == ConnectionState.AUTHENTICATED
        connectionState.collect { state ->
            val nowAuthenticated = state == ConnectionState.AUTHENTICATED
            if (nowAuthenticated && !authenticated && workspaceOpened()) {
                refreshJob?.cancel()
                refreshJob = refresh()
            }
            authenticated = nowAuthenticated
        }
    }
}

/** 保存、移动和删除后的首页收敛归工作区请求所有，不因用户切换文档而丢失。 */
internal suspend fun DocumentWorkspaceFeature.refreshHomeProjection() {
    val homeOwner = workspaceRequests.beginHome()
    navigationActions.loadHome {
        workspaceRequests.isCurrent(homeOwner)
    }
}

/**
 * 调和工作区，而不让普通文档导航替换空间、待办工作、首页投影
 * 或选择中立的恢复路径补全。
 */
internal fun DocumentWorkspaceFeature.refreshWorkspace(
    resetSpaceSnapshotRestarts: Boolean = true,
): Job {
    // 同步认领：点击 Refresh 会立即退役每一个更旧的 open/refresh owner，
    // 即使下面的草稿恢复必须在第一次服务器请求之前挂起。
    val owner = workspaceRequests.begin()
    val homeOwner = workspaceRequests.beginHome()
    if (resetSpaceSnapshotRestarts) spaceSnapshotRestartAttempts = 0
    invalidateSpacePagination()
    val generation = navigationActions.beginNavigation()
    return scope.launch {
        try {
            ensureDraftRestorationApplied()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (workspaceRequests.isCurrent(owner)) {
                reportError(failure, "本机草稿暂时无法安全读取，请重试")
            }
            return@launch
        }
        if (!workspaceRequests.isCurrent(owner)) return@launch

        val restoration = if (remoteDraftEnrichmentPending) liveDraftSnapshot() else null
        val spaceIdBeforeRefresh = selectedSpaceId
        val activeTabBeforeRefresh = activeTab?.takeIf { it.spaceId == spaceIdBeforeRefresh }
        val activePathBeforeRefresh = activeTabBeforeRefresh?.let(DocumentPathStamp::capture)
        val selectedParentBeforeRefresh = selectedParentNodeId
        val selectedParentPath = nodeAncestorIds(selectedParentBeforeRefresh, treeChildren)
        loading = true
        try {
            val firstPage = loadStableDocumentSpaceFirstPage {
                workspaceRequests.isCurrent(owner)
            } ?: return@launch
            val paginationCycle = firstPage.cycle
            val firstSpacePage = firstPage.page
            val loadedSpaces = firstSpacePage.items
            var pagePublished = false
            if (!workspaceRequests.publishIfCurrent(owner) {
                    pagePublished = publishFirstSpacePage(firstSpacePage, paginationCycle)
                    if (pagePublished) spaceProjectionStatus = DocumentWorkspaceProjectionStatus.CURRENT
                }
            ) {
                paginationCycle.cancel()
                return@launch
            }
            if (!pagePublished) return@launch

            convergeWorkspacePendingWork(owner, loadedSpaces)
            if (!workspaceRequests.isCurrent(owner)) return@launch
            loadWorkspaceHome(owner, homeOwner)
            if (!workspaceRequests.isCurrent(owner)) return@launch

            refreshSelectedDocumentProjection(
                generation = generation,
                spaceIdBeforeRefresh = spaceIdBeforeRefresh,
                activeTabBeforeRefresh = activeTabBeforeRefresh,
                activePathBeforeRefresh = activePathBeforeRefresh,
                selectedParentBeforeRefresh = selectedParentBeforeRefresh,
                selectedParentPath = selectedParentPath,
            )
            if (!workspaceRequests.isCurrent(owner)) return@launch

            if (restoration == null) {
                remoteDraftEnrichmentPending = false
            } else {
                convergeRestoredDocumentPaths(owner, restoration, loadedSpaces)
            }
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (workspaceRequests.isCurrent(owner)) {
                spaceProjectionStatus = documentProjectionStatusAfterFailure(
                    hadCachedSnapshot = spaceProjectionStatus.hasPublishedSnapshot() ||
                        spaces.isNotEmpty(),
                    failure = failure,
                )
                reportError(failure, "刷新文档工作台失败")
            }
        } finally {
            if (workspaceRequests.isCurrent(owner)) loading = false
        }
    }
}

/** 导航拥有的投影刷新；失去这个 owner 绝不会中止工作区加载。 */
private suspend fun DocumentWorkspaceFeature.refreshSelectedDocumentProjection(
    generation: Long,
    spaceIdBeforeRefresh: String?,
    activeTabBeforeRefresh: DocumentTabState?,
    activePathBeforeRefresh: DocumentPathStamp?,
    selectedParentBeforeRefresh: String?,
    selectedParentPath: List<String>,
) {
    if (!navigationActions.isCurrent(generation)) return
    val spaceId = spaceIdBeforeRefresh ?: return
    if (spaces.none { it.spaceId == spaceId }) {
        navigationActions.clearSpaceSelection()
        return
    }
    if (selectedSpaceId != spaceId) return

    val cachedPathStamp = activePathBeforeRefresh?.takeIf { stamp ->
        navigationActions.revealCachedDocumentSpine(stamp, generation)
    }
    try {
        // 保持这个排序：下面重建的树不比一次成功刷新的文档路径更旧。
        // 活动页面失败仍然是数据，不能压制根加载。
        val refreshLoads = refreshActiveDocumentAndTreeRoot(
            documentId = activeTabBeforeRefresh?.documentId,
            loadDocument = { documentId ->
                readGateway.refreshDocument(spaceId, documentId)
            },
            rebuildRoot = {
                navigationActions.isCurrent(generation, spaceId) && navigationActions.loadTreeRoot(generation)
            },
        )
        if (!navigationActions.isCurrent(generation, spaceId)) return

        var currentTab = activeTabBeforeRefresh?.let { captured ->
            tabs.firstOrNull {
                it.instanceId == captured.instanceId && it.spaceId == captured.spaceId
            }
        }?.takeIf { activeTabId == it.tabId }
        var missingActiveHandled = false
        val currentAtRoot = currentTab
        if (currentAtRoot != null) {
            var settledTab: DocumentTabState? = currentAtRoot
            when (val activeRefresh = refreshLoads.activeDocument) {
                DocumentActiveRefreshResult.NotRequested -> Unit
                is DocumentActiveRefreshResult.Loaded -> {
                    settledTab = applyLoadedActiveDocumentRefresh(currentAtRoot, activeRefresh.document)
                        ?: settledTab
                }
                is DocumentActiveRefreshResult.Missing -> {
                    val outcome = applyMissingActiveDocumentRefresh(currentAtRoot, activeRefresh.failure)
                    if (outcome.handled) {
                        missingActiveHandled = true
                        settledTab = outcome.settledTab
                    }
                }
                is DocumentActiveRefreshResult.Failed -> {
                    reportError(activeRefresh.failure, "刷新活动文档失败，目录已更新")
                }
            }

            currentTab = settledTab
            selectedParentNodeId = settledTab?.resolvedParentIdForNavigation()
            val stamp = settledTab?.let(DocumentPathStamp::capture)
            if (stamp != null) {
                val cachedPathRevealed = cachedPathStamp == stamp ||
                    navigationActions.revealCachedDocumentSpine(stamp, generation)
                val revealResult = try {
                    navigationActions.refreshAndRevealDocumentSpine(stamp, generation)
                } catch (failure: Exception) {
                    failure.rethrowIfDocumentWorkspaceCancelled()
                    val exactOwner = tabs.firstOrNull { it.instanceId == stamp.instanceId }
                        ?.takeIf { it.tabId == activeTabId && stamp.targets(it) }
                    if (exactOwner != null && !cachedPathRevealed) {
                        navigationActions.invalidateDocumentPath(stamp)
                    }
                    throw failure
                }
                if (revealResult == DocumentPathRevealResult.Superseded) return
                currentTab = tabs.firstOrNull { it.instanceId == stamp.instanceId }
                    ?.takeIf { it.tabId == activeTabId }
                if (revealResult == DocumentPathRevealResult.Contradicted && currentTab == null) {
                    missingActiveHandled = true
                }
            }
        }
        if (currentTab == null && !missingActiveHandled && selectedParentBeforeRefresh != null) {
            selectedParentNodeId = selectedParentBeforeRefresh
            if (!navigationActions.revealDocumentPath(selectedParentPath, generation) &&
                navigationActions.isCurrent(generation, spaceId)
            ) {
                selectedParentNodeId = null
            }
        }
    } catch (failure: Exception) {
        failure.rethrowIfDocumentWorkspaceCancelled()
        if (navigationActions.isCurrent(generation)) {
            if (
                failure is AppError.Business &&
                failure.code in setOf(403, 404) &&
                selectedSpaceId == spaceIdBeforeRefresh
            ) {
                // listNodes(root) 是针对从后续页面保留的已选空间的目标 ACL 探测。
                // 只有类型化的 forbidden/missing 状态才能证明它不再可用；
                // 通用的校验、过载和传输失败保留本地上下文。
                navigationActions.clearSpaceSelection()
            }
            reportError(failure, "文档空间已刷新，但当前目录投影刷新失败")
        }
    }
}

/**
 * 活动文档刷新成功分支的收敛：合并最新草稿、移动过分支时重建树分支、
 * 替换标签并持久化草稿快照。返回收敛后的标签；无可合并草稿时返回 null
 * （调用方保留刷新前的标签）。
 */
private fun DocumentWorkspaceFeature.applyLoadedActiveDocumentRefresh(
    original: DocumentTabState,
    refreshed: Document,
): DocumentTabState? {
    val captured = captureLatestActiveDraft(original) ?: return null
    val accepted = mergeDocumentRefresh(captured, refreshed)?.let { refreshed to it }
    val refreshedTab = accepted?.second ?: captured
    if (accepted != null &&
        (captured.parentId != refreshedTab.parentId || captured.ancestorIds != refreshedTab.ancestorIds)
    ) {
        navigationActions.prepareDocumentRefreshBranches(
            document = accepted.first,
            previousParentIds = setOf(original.parentId),
        )
    }
    if (refreshedTab !== captured) {
        tabs = tabs.map {
            if (it.instanceId == captured.instanceId) refreshedTab else it
        }
        persistDraftSnapshot()
    }
    return refreshedTab
}

/** Missing 分支的处置结果：是否已收敛，以及收敛后的活动标签（可为 null=标签已关闭）。 */
private data class MissingActiveRefreshOutcome(
    val handled: Boolean,
    val settledTab: DocumentTabState?,
)

/**
 * 活动文档刷新发现目标缺失/失权时的标签收敛：遗忘身份、按和解结果收敛标签/选区/历史
 * 与冲突提示，并按保留草稿与否选择用户可见提示。未捕获到草稿或无和解方案时不算已处置。
 */
private fun DocumentWorkspaceFeature.applyMissingActiveDocumentRefresh(
    original: DocumentTabState,
    failure: AppError.Business,
): MissingActiveRefreshOutcome {
    val captured = captureLatestActiveDraft(original)
        ?: return MissingActiveRefreshOutcome(handled = false, settledTab = original)
    captured.documentId?.let { documentId ->
        navigationActions.forgetDocumentIdentity(captured.spaceId, documentId)
    }
    val reconciliation = reconcileMissingActiveDocumentRefresh(
        tabs = tabs,
        missingInstanceId = captured.instanceId,
        activeTabId = activeTabId,
    ) ?: return MissingActiveRefreshOutcome(handled = false, settledTab = original)
    tabs = reconciliation.tabs
    activeTabId = reconciliation.activeTabId
    selectedParentNodeId = reconciliation.selectedParentNodeId
    closeHistory()
    revisionConflictActions.dismissStaleConflict()
    persistDraftSnapshot()
    navigationActions.publishMissingDocumentProjection(reconciliation.activeTab != null)
    reportError(
        failure,
        if (reconciliation.orphanRetained && failure.code == 403) {
            "已无权访问活动文档，已保留本机未保存草稿"
        } else if (reconciliation.orphanRetained) {
            "活动文档已被删除，已保留本机未保存草稿"
        } else if (failure.code == 403) {
            "已无权访问活动文档，已关闭标签"
        } else {
            "活动文档已被删除，已关闭标签"
        },
    )
    return MissingActiveRefreshOutcome(handled = true, settledTab = reconciliation.activeTab)
}
