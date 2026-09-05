package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.LatestRequestGate
import com.virjar.tk.app.navigation.feature.shouldReportCacheRefreshFailure

import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentSpace
import kotlinx.coroutines.CancellationException

/** 工作区加载的最近请求边界；文档导航有单独的 owner。 */
internal class DocumentWorkspaceRequestCoordinator {
    internal class Owner internal constructor(
        internal val token: LatestRequestGate.Token<Unit>,
    )

    internal class HomeOwner internal constructor(
        internal val token: LatestRequestGate.Token<Unit>,
    )

    private val workspaceRequests = LatestRequestGate<Unit>()
    private val homeRequests = LatestRequestGate<Unit>()

    fun begin(): Owner = Owner(workspaceRequests.begin(Unit))

    fun beginHome(): HomeOwner = HomeOwner(homeRequests.begin(Unit))

    fun isCurrent(owner: Owner): Boolean = workspaceRequests.isCurrent(owner.token)

    fun isCurrent(owner: HomeOwner): Boolean = homeRequests.isCurrent(owner.token)

    fun publishIfCurrent(owner: Owner, publish: () -> Unit): Boolean {
        if (!isCurrent(owner)) return false
        publish()
        return true
    }
}

internal sealed interface RestoredDocumentPathEnrichment {
    data object Superseded : RestoredDocumentPathEnrichment

    data class Current(
        val retryPending: Boolean,
        val failures: List<Throwable>,
    ) : RestoredDocumentPathEnrichment
}

/**
 * 在不拥有用户当前空间、tab、parent 选择或树的情况下，重新校验恢复的路径。
 * 因此，在 bootstrap 挂起期间被选中的 tab 保持选中；这个批次只把更新的服务器路径事实
 * 合并进它最初观察到的那些确切的未解析 tab 实例。
 */
internal suspend fun enrichRestoredDocumentPaths(
    restoredTabs: List<DocumentTabState>,
    availableSpaceIds: Set<String>,
    ownerIsCurrent: () -> Boolean,
    currentTabs: () -> List<DocumentTabState>,
    fetch: suspend (DocumentPathRefreshKey) -> Document,
    publishTabs: (List<DocumentTabState>) -> Unit,
): RestoredDocumentPathEnrichment {
    val restoredInstanceIds = restoredTabs.asSequence()
        .map(DocumentTabState::instanceId)
        .toHashSet()
    val targetSpaceIds = restoredTabs.asSequence()
        .map(DocumentTabState::spaceId)
        .filter(availableSpaceIds::contains)
        .distinct()
        .toList()
    val failures = mutableListOf<Throwable>()

    for (spaceId in targetSpaceIds) {
        if (!ownerIsCurrent()) return RestoredDocumentPathEnrichment.Superseded
        val coordinator = DocumentPathRefreshCoordinator()
        val result = coordinator.refresh(
            spaceId = spaceId,
            currentTargets = {
                unresolvedDocumentPathRefreshTargets(
                    tabs = currentTabs().filter { it.instanceId in restoredInstanceIds },
                    spaceId = spaceId,
                )
            },
            fetch = fetch,
            publish = { batch ->
                if (ownerIsCurrent()) {
                    val current = currentTabs()
                    val merged = mergeDocumentPathRefreshBatch(current, batch)
                    if (merged !== current) publishTabs(merged)
                }
            },
        )
        if (!ownerIsCurrent()) return RestoredDocumentPathEnrichment.Superseded
        when (result) {
            DocumentPathRefreshBatch.Superseded ->
                return RestoredDocumentPathEnrichment.Superseded
            is DocumentPathRefreshBatch.Current -> failures += result.failures
        }
    }

    val retryPending = currentTabs().any { tab ->
        tab.instanceId in restoredInstanceIds &&
            tab.spaceId in availableSpaceIds &&
            tab.documentId != null &&
            !tab.pathResolved &&
            !tab.remoteMissing
    }
    return RestoredDocumentPathEnrichment.Current(
        retryPending = retryPending,
        failures = failures.toList(),
    )
}

/** 本地优先入口：工作区收敛在之后的文档/空间导航中存续。 */
internal suspend fun DocumentWorkspaceFeature.openWorkspace() {
    val owner = workspaceRequests.begin()
    val homeOwner = workspaceRequests.beginHome()
    spaceSnapshotRestartAttempts = 0
    invalidateSpacePagination()
    // 这个 token 只拥有旧目录场景的可选恢复。用户动作可以取代它，
    // 而不会取代上面独立的工作区请求 owner。
    val restorationNavigation = navigationActions.beginNavigation()
    var localDraftPublished = false
    var cachedSpacePublished = false
    var cachedHomePublished = false
    loading = true
    try {
        ensureDraftRestorationApplied()
        if (!workspaceRequests.isCurrent(owner)) return
        val restoration = if (remoteDraftEnrichmentPending) liveDraftSnapshot() else null
        localDraftPublished = restoration?.tabs?.isNotEmpty() == true

        // 正常进入 Documents 会打开其首页。第一次进程级恢复保持其本地草稿可见，
        // 除非用户在此期间已经选择了更新的目的地。
        if (restoration == null && navigationActions.isCurrent(restorationNavigation)) {
            navigationActions.clearSpaceSelection()
        }

        val cachedSpaces = readGateway.cachedSpaces()
        if (!workspaceRequests.isCurrent(owner)) return
        if (cachedSpaces.known) {
            publishCachedSpaceSnapshot(cachedSpaces.value)
            spaceProjectionStatus = DocumentWorkspaceProjectionStatus.CACHED
            cachedSpacePublished = true
        } else {
            spaceProjectionStatus = DocumentWorkspaceProjectionStatus.LOADING
        }

        val cachedHome = readGateway.cachedHome()
        if (!workspaceRequests.isCurrent(owner)) return
        cachedHomePublished = navigationActions.publishCachedHome(cachedHome) {
            workspaceRequests.isCurrent(owner) && workspaceRequests.isCurrent(homeOwner)
        }

        val firstPage = try {
            loadStableDocumentSpaceFirstPage { workspaceRequests.isCurrent(owner) }
                ?: return
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (!workspaceRequests.isCurrent(owner)) return
            spaceProjectionStatus = documentProjectionStatusAfterFailure(
                hadCachedSnapshot = cachedSpacePublished,
                failure = failure,
            )
            recoverWorkspaceAfterFirstPageFailure(
                failure = failure,
                restoration = restoration,
                restorationNavigation = restorationNavigation,
                owner = owner,
                localDraftPublished = localDraftPublished,
                cachedSpacePublished = cachedSpacePublished,
                cachedHomePublished = cachedHomePublished,
            )
            return
        }
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
            return
        }
        if (!pagePublished) return

        convergeWorkspacePendingWork(owner, loadedSpaces)
        if (!workspaceRequests.isCurrent(owner)) return
        loadWorkspaceHome(owner, homeOwner)
        if (!workspaceRequests.isCurrent(owner)) return

        if (restoration == null) {
            remoteDraftEnrichmentPending = false
            return
        }

        // 只有未改变的导航可以恢复它旧的树/选择。如果用户选择了一个 tab 或空间，
        // 下面的选择中立批次仍然解析每一个可恢复的路径。
        if (navigationActions.isCurrent(restorationNavigation)) {
            try {
                restoreDraftWorkspace(restoration, restorationNavigation)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (workspaceRequests.isCurrent(owner)) {
                    reportError(failure, "恢复文档目录失败，草稿已保留")
                }
            }
        }
        if (!workspaceRequests.isCurrent(owner)) return
        convergeRestoredDocumentPaths(owner, restoration, loadedSpaces)
    } catch (failure: Exception) {
        failure.rethrowIfDocumentWorkspaceCancelled()
        if (workspaceRequests.isCurrent(owner)) {
            val hasLocalProjection =
                localDraftPublished || cachedSpacePublished || cachedHomePublished
            if (shouldReportCacheRefreshFailure(failure, hasLocalProjection)) {
                reportError(
                    failure,
                    when {
                        failure is DocumentDraftReadRetryableException ->
                            "本机草稿暂时无法安全读取，请重试"
                        localDraftPublished -> "文档服务离线，已恢复本机未保存草稿"
                        cachedSpacePublished || cachedHomePublished ->
                            "文档服务暂不可用，已显示本地缓存"
                        else -> "加载文档首页失败"
                    },
                )
            }
        }
    } finally {
        if (workspaceRequests.isCurrent(owner)) loading = false
    }
}

internal suspend fun DocumentWorkspaceFeature.convergeRestoredDocumentPaths(
    owner: DocumentWorkspaceRequestCoordinator.Owner,
    restoration: DocumentWorkspaceDraftSnapshot,
    loadedSpaces: List<DocumentSpace>,
): Boolean {
    return when (val enrichment = enrichRestoredDocumentPaths(
        restoredTabs = restoration.tabs,
        availableSpaceIds = loadedSpaces.mapTo(hashSetOf(), DocumentSpace::spaceId),
        ownerIsCurrent = { workspaceRequests.isCurrent(owner) },
        currentTabs = { tabs },
        fetch = { key ->
            readGateway.refreshDocument(key.spaceId, key.documentId)
        },
        publishTabs = { merged ->
            tabs = merged
            persistDraftSnapshot()
        },
    )) {
        RestoredDocumentPathEnrichment.Superseded -> false
        is RestoredDocumentPathEnrichment.Current -> {
            remoteDraftEnrichmentPending = enrichment.retryPending
            if (enrichment.retryPending) {
                enrichment.failures.firstOrNull()?.let { failure ->
                    reportError(failure, "部分本机草稿的目录路径暂时无法校验，将在下次打开时重试")
                }
            }
            true
        }
    }
}

internal suspend fun DocumentWorkspaceFeature.convergeWorkspacePendingWork(
    owner: DocumentWorkspaceRequestCoordinator.Owner,
    loadedSpaces: List<DocumentSpace>,
) {
    try {
        reconcilePendingSpaceCreates(loadedSpaces)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (workspaceRequests.isCurrent(owner)) {
            reportError(failure, "服务器空间已同步，但本机创建待办暂时无法收尾")
        }
    }
    if (!workspaceRequests.isCurrent(owner)) return

    runBootstrapReplay(owner, "文档删除或归档待办暂时无法重试") {
        replayPendingDestructiveIntents()
    }
    runBootstrapReplay(owner, "待创建文档空间暂时无法重试") {
        replayPendingSpaceCreates()
    }
    runBootstrapReplay(owner, "待创建文档暂时无法重试，草稿已保留") {
        saveCoordinator.replayPendingCreates(loadedSpaces)
    }
}

private inline fun DocumentWorkspaceFeature.runBootstrapReplay(
    owner: DocumentWorkspaceRequestCoordinator.Owner,
    failureMessage: String,
    replay: () -> Unit,
) {
    if (!workspaceRequests.isCurrent(owner)) return
    try {
        replay()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (workspaceRequests.isCurrent(owner)) reportError(failure, failureMessage)
    }
}

internal suspend fun DocumentWorkspaceFeature.loadWorkspaceHome(
    owner: DocumentWorkspaceRequestCoordinator.Owner,
    homeOwner: DocumentWorkspaceRequestCoordinator.HomeOwner,
) {
    try {
        navigationActions.loadHome {
            workspaceRequests.isCurrent(owner) && workspaceRequests.isCurrent(homeOwner)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (workspaceRequests.isCurrent(owner) && workspaceRequests.isCurrent(homeOwner)) {
            if (shouldReportCacheRefreshFailure(
                    failure,
                    homeProjectionStatus.hasPublishedSnapshot(),
                )
            ) {
                reportError(failure, "加载文档首页失败")
            }
        }
    }
}

/**
 * 首页首拉失败后的缓存优先恢复：先重放未被取代的本地草稿目录（服务器空间刷新不拥有
 * 已持久化的目录；更新的用户导航仍通过 generation 胜出），再按已发布的本地投影选择
 * 用户可见的失败提示。恢复自身已报告错误时不重复报告。
 */
private suspend fun DocumentWorkspaceFeature.recoverWorkspaceAfterFirstPageFailure(
    failure: Exception,
    restoration: DocumentWorkspaceDraftSnapshot?,
    restorationNavigation: Long,
    owner: DocumentWorkspaceRequestCoordinator.Owner,
    localDraftPublished: Boolean,
    cachedSpacePublished: Boolean,
    cachedHomePublished: Boolean,
) {
    var recoveryErrorReported = false
    if (restoration != null && navigationActions.isCurrent(restorationNavigation)) {
        try {
            restoreDraftWorkspace(
                rawSnapshot = restoration,
                generation = restorationNavigation,
                onRecoveryError = { recoveryFailure, message ->
                    recoveryErrorReported = true
                    reportError(recoveryFailure, message)
                },
            )
        } catch (restorationFailure: Exception) {
            restorationFailure.rethrowIfDocumentWorkspaceCancelled()
            if (workspaceRequests.isCurrent(owner) &&
                navigationActions.isCurrent(restorationNavigation)
            ) {
                recoveryErrorReported = true
                reportError(restorationFailure, "恢复本地文档目录失败，草稿已保留")
            }
        }
    }
    val hasLocalProjection = localDraftPublished || cachedSpacePublished || cachedHomePublished
    if (!recoveryErrorReported &&
        shouldReportCacheRefreshFailure(failure, hasLocalProjection)
    ) {
        reportError(
            failure,
            when {
                localDraftPublished -> "文档服务离线，已恢复本机未保存草稿"
                cachedSpacePublished || cachedHomePublished ->
                    "文档服务暂不可用，已显示本地缓存"
                failure === com.virjar.tk.shared.AppError.Network ||
                    failure === com.virjar.tk.shared.AppError.Timeout ->
                    "当前无网络，文档首页尚未缓存"
                else -> "加载文档首页失败"
            },
        )
    }
}
