package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 事件读取与用户导航共享既有投影 owner。StateFlow 合并、重放、组织变更与重连都退回有界
 * 驻留工作集校验；正常连续节点事件刷新所属空间的驻留标签与已加载目录，不预取正文或子树。
 */
internal fun DocumentWorkspaceFeature.observeDocumentChanges(): Job = scope.launch {
    var observedSequence = session.eventProcessor.documentChanges.value.sequence
    var observedState = session.connectionState.value
    var fullRefreshRequired = true
    combine(session.eventProcessor.documentChanges, session.connectionState) { signal, state ->
        signal to state
    }.collectLatest { (signal, state) ->
        val connectionChanged = state != observedState
        observedState = state
        if (!workspaceOpened) {
            observedSequence = signal.sequence
            return@collectLatest
        }
        val online = state == ConnectionState.AUTHENTICATED
        val changed = signal.sequence != observedSequence
        // 初始 StateFlow 值并不是一次断线/重连。openWorkspace 拥有初次恢复，不能由这个
        // 延后启动的观察者退役其导航 owner，尤其不能打断离线草稿目录的恢复。
        if (!changed && !connectionChanged) return@collectLatest
        val skipped = changed && signal.sequence != observedSequence + 1L
        val change = signal.change.takeIf { changed }
        if (!changed && online && !fullRefreshRequired) return@collectLatest
        if (changed && !skipped && change?.kind == DocumentChangedPayload.COMMENTS_CHANGED &&
            !fullRefreshRequired && online
        ) {
            observedSequence = signal.sequence
            return@collectLatest
        }
        val refreshAll = fullRefreshRequired || skipped || change == null ||
            change.kind == DocumentChangedPayload.SPACE_CHANGED ||
            change.kind == DocumentChangedPayload.SPACE_REVOKED
        // 一旦后面的 suspend 被新信号取消，下次必须覆盖尚未完成的这次工作。
        fullRefreshRequired = true
        observedSequence = signal.sequence
        navigationActions.markProjectionsStale(offline = !online, spaceId = change?.spaceId.takeUnless { refreshAll })
        if (refreshAll && spaceProjectionStatus.hasPublishedSnapshot()) {
            spaceProjectionStatus = if (online) DocumentWorkspaceProjectionStatus.CACHED
                else DocumentWorkspaceProjectionStatus.OFFLINE_CACHED
        }
        try {
            ensureDraftRestorationApplied()
            when (change?.kind) {
                DocumentChangedPayload.SPACE_REVOKED -> removeDocumentSpaceProjection(change.spaceId)
                DocumentChangedPayload.NODE_DELETED ->
                    removeDocumentNodeProjection(change.spaceId, requireNotNull(change.nodeId))
            }
            // 丢过提示时先从已完成的本地清理收敛 UI，离线状态也不能继续展示被撤销的干净内容。
            if (skipped) reconcileDocumentCacheAfterSkippedChanges()
            if (!online) return@collectLatest
            val selectedBefore = selectedSpaceId
            val loadedBranches = treeChildren.keys.toList()
            if (refreshAll) {
                val refresh = refreshWorkspace()
                try {
                    refresh.join()
                } finally {
                    if (!refresh.isCompleted) refresh.cancel()
                }
            } else {
                try {
                    refreshHomeProjection()
                } catch (failure: Exception) {
                    failure.rethrowIfDocumentWorkspaceCancelled()
                    reportError(failure, "刷新文档首页失败，已保留本机缓存")
                }
            }
            refreshResidentDocumentWorkset(
                changed = change.takeUnless { refreshAll },
                branchSpaceId = selectedBefore,
                loadedBranches = loadedBranches,
            )
            fullRefreshRequired = false
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            reportError(failure, "文档变更暂未同步，已保留本机缓存与草稿")
        }
    }
}

/** 只校验已经打开的正文与当前已加载分支，工作量由原有 tab/branch 容量约束。 */
internal suspend fun DocumentWorkspaceFeature.refreshResidentDocumentWorkset(
    changed: DocumentChangedPayload? = null,
    branchSpaceId: String? = selectedSpaceId,
    loadedBranches: List<String?> = treeChildren.keys.toList(),
) {
    if (session.connectionState.value != ConnectionState.AUTHENTICATED) return
    val navigation = navigationActions.currentNavigation()
    var refreshedActivePath: DocumentPathStamp? = null
    // 分页首页不能证明未列出的驻留空间仍可见；getSpace 用当前授权校验它们，避免组织变更后
    // 仅剩空目录/后台标签的空间长期保留旧角色。只校验打开的标签与当前空间，不遍历服务器全集。
    val residentSpaceIds = if (changed == null) {
        (tabs.map { it.spaceId } + listOfNotNull(selectedSpaceId)).distinct().filter { it !in offlineDraftSpaceIds }
    } else emptyList()
    for (spaceId in residentSpaceIds) {
        try {
            val remote = readGateway.refreshSpace(spaceId)
            publishSpaceMutation(spaces.filterNot { it.spaceId == spaceId } + remote, spaceId)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            // 稳定的 403/404 由 repository boundary 清理空间与 UI，其他失败保持 stale。
            if (failure !is AppError.Business || failure.code !in setOf(403, 404)) {
                spaceProjectionStatus = documentProjectionStatusAfterFailure(
                    hadCachedSnapshot = spaces.isNotEmpty(), failure = failure,
                )
                reportError(failure, "刷新文档空间权限失败，已保留本机缓存")
            }
        }
    }
    val targets = tabs.filter { tab ->
        !tab.creating && tab.documentId != null && tab.spaceId !in offlineDraftSpaceIds &&
            (changed == null || tab.spaceId == changed.spaceId)
    }
    targets.forEach { original ->
        if (original.spaceId in offlineDraftSpaceIds || tabs.none { it.instanceId == original.instanceId }) {
            return@forEach
        }
        try {
            val remote = readGateway.refreshDocument(original.spaceId, requireNotNull(original.documentId))
            val resident = tabs.firstOrNull { it.instanceId == original.instanceId } ?: return@forEach
            val current = captureLatestActiveDraft(resident) ?: return@forEach
            val refreshed = mergeDocumentRefresh(current, remote) ?: return@forEach
            tabs = tabs.map { if (it.instanceId == current.instanceId) refreshed else it }
            if (activeTab?.instanceId == refreshed.instanceId) {
                selectedParentNodeId = refreshed.resolvedParentIdForNavigation()
                navigationActions.publishDocumentRefreshStatus()
                refreshedActivePath = DocumentPathStamp.capture(refreshed)
            }
            persistDraftSnapshot()
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (failure is AppError.Business && failure.code == 404) {
                removeDocumentNodeProjection(original.spaceId, requireNotNull(original.documentId))
            } else if (failure !is AppError.Business || failure.code != 403) {
                // repository 的空间级 403 已移除所有干净标签，并保护独立本机草稿。
                reportError(failure, "刷新已打开文档失败，已保留本机内容")
            }
        }
    }
    if (branchSpaceId != null && branchSpaceId == selectedSpaceId &&
        branchSpaceId !in offlineDraftSpaceIds &&
        (changed == null || changed.spaceId == branchSpaceId)
    ) {
        // 根与已展开分支各读一次，不沿 hasChildren 自动递归。
        (listOf<String?>(null) + loadedBranches).distinct().forEach { parentId ->
            if (selectedSpaceId != branchSpaceId) return@forEach
            try {
                navigationActions.reloadChildren(branchSpaceId, parentId)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (failure is AppError.Business && failure.code == 404 && parentId != null) {
                    removeDocumentNodeProjection(branchSpaceId, parentId)
                } else if (failure !is AppError.Business || failure.code !in setOf(403, 404)) {
                    reportError(failure, "刷新文档目录失败，已保留本机缓存")
                }
            }
        }
    }
    refreshedActivePath?.let { stamp ->
        try {
            // 一次有界 path spine 补齐跨分支移动后的目录；已有用户导航始终优先于后台提示。
            navigationActions.refreshAndRevealDocumentSpine(stamp, navigation)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            reportError(failure, "刷新文档位置失败，已保留本机内容")
        }
    }
}

/** 删除提示覆盖同节点与有界缓存中已知的后代；未保存草稿保留为可另存的新文档。 */
internal fun DocumentWorkspaceFeature.removeDocumentNodeProjection(spaceId: String, nodeId: String) {
    val removedIds = if (selectedSpaceId == spaceId) documentTreeSubtreeIds(treeChildren, nodeId)
        else setOf(nodeId)
    val matches: (DocumentTabState) -> Boolean = { tab ->
        tab.spaceId == spaceId && (tab.documentId == nodeId || nodeId in tab.ancestorIds)
    }
    activeTab?.takeIf(matches)?.let { active ->
        val result = captureLatestActiveDraftResult(active)
        tabs = protectUnconfirmedActiveDraftForProjectionRemoval(tabs, spaceId, active.instanceId, result)
    }
    val oldActive = activeTab
    tabs = reconcileDeletedDocumentTabs(tabs, spaceId, nodeId)
    navigationActions.forgetDocumentIdentity(spaceId, nodeId)
    if (selectedSpaceId == spaceId) {
        if (selectedParentNodeId in removedIds) selectedParentNodeId = null
        expandedNodeIds = expandedNodeIds.filterTo(linkedSetOf()) { it in treeChildren.keys }
    }
    if (oldActive?.let(matches) == true) {
        activeTabId = tabs.firstOrNull { it.instanceId == oldActive.instanceId }?.tabId
        selectedParentNodeId = null
        closeHistory()
        revisionConflictActions.clearConflict()
        navigationActions.publishMissingDocumentProjection(activeTab != null)
    }
    removedIds.forEach { removeDeletedNodeFromDocumentHome(spaceId, it) }
    persistDraftSnapshot()
}

internal fun reconcileDeletedDocumentTabs(
    tabs: List<DocumentTabState>,
    spaceId: String,
    nodeId: String,
): List<DocumentTabState> = tabs.mapNotNull { tab ->
    if (tab.spaceId != spaceId || tab.documentId != nodeId && nodeId !in tab.ancestorIds) tab
    else if (tab.dirty || tab.creating) tab.copy(pathResolved = false, remoteMissing = !tab.creating)
    else null
}

private suspend fun DocumentWorkspaceFeature.reconcileDocumentCacheAfterSkippedChanges() {
    val cached = readGateway.cachedSpaces()
    if (cached.known) {
        val retained = cached.value.mapTo(hashSetOf()) { it.spaceId }
        spaces.filter { it.spaceId !in retained && it.spaceId !in offlineDraftSpaceIds }
            .forEach { removeDocumentSpaceProjection(it.spaceId) }
    }
    tabs.toList().filter { !it.creating && it.documentId != null && it.spaceId !in offlineDraftSpaceIds }
        .forEach { tab ->
            if (readGateway.cachedDocument(tab.spaceId, requireNotNull(tab.documentId)) == null) {
                removeDocumentNodeProjection(tab.spaceId, tab.documentId)
            }
        }
}
