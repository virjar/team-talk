package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentSpace

/**
 * 把一个不可用的空间从可观察的工作区中移除。
 *
 * 服务器仍然是授权 owner。到这个回调运行时，repository 已经移除了它干净的持久投影。
 * 应用只把独立的 dirty 草稿保留为本地孤儿，并使普通的请求 owner 失效，
 * 这样更旧的响应就不能重新发布退役的行。
 */
internal fun DocumentWorkspaceFeature.removeDocumentSpaceProjection(
    spaceId: String,
    failure: AppError.Business? = null,
) {
    val activeRetirementTarget = tabs.firstOrNull {
        it.tabId == activeTabId && it.spaceId == spaceId
    }
    val hadRemoteProjection = spaces.any { space ->
        space.spaceId == spaceId && spaceId !in offlineDraftSpaceIds
    } || tabs.any { tab ->
        tab.spaceId == spaceId && !tab.dirty && !tab.creating
    } || recentDocuments.any { it.spaceId == spaceId } ||
        recentlyCreatedDocuments.any { it.spaceId == spaceId }

    val captureResult = activeRetirementTarget?.let(::captureLatestActiveDraftResult)
    if (activeRetirementTarget != null && captureResult != null) {
        tabs = protectUnconfirmedActiveDraftForProjectionRemoval(
            tabs = tabs,
            spaceId = spaceId,
            expectedInstanceId = activeRetirementTarget.instanceId,
            captureResult = captureResult,
        )
    }
    val captureFallbackRetained = activeRetirementTarget != null &&
        captureResult !is ActiveDocumentDraftCaptureResult.Captured &&
        tabs.any { tab ->
            tab.instanceId == activeRetirementTarget.instanceId && tab.spaceId == spaceId &&
                tab.dirty && !tab.pathResolved
        }
    val selectedProjectionRemoved = selectedSpaceId == spaceId
    val projection = removeUnavailableDocumentSpaceProjection(
        spaceId = spaceId,
        tabs = tabs,
        spaces = spaces,
        offlineDraftSpaceIds = offlineDraftSpaceIds,
        selectedSpaceId = selectedSpaceId,
        activeTabId = activeTabId,
        selectedParentNodeId = selectedParentNodeId,
    )

    tabs = projection.tabs
    spaces = projection.spaces
    offlineDraftSpaceIds = projection.offlineDraftSpaceIds
    selectedSpaceId = projection.selectedSpaceId
    activeTabId = projection.activeTabId
    selectedParentNodeId = projection.selectedParentNodeId
    removeRetiredSpaceFromDocumentHome(spaceId)
    if (selectedProjectionRemoved) {
        treeChildren = emptyMap()
        expandedNodeIds = emptySet()
        grants = emptyList()
        closeHistory()
    }
    navigationActions.removeSpaceProjection(spaceId, selectedProjectionRemoved)
    persistDraftSnapshot()

    if (failure != null && hadRemoteProjection) {
        reportSpaceProjectionRemoval(
            failure = failure,
            captureFallbackRetained = captureFallbackRetained,
            orphanRetained = projection.orphanRetained,
        )
    }
}

/** 当前空间级 403 或根列表 404 的 repository 回调。 */
internal fun DocumentWorkspaceFeature.removeUnavailableSpaceProjection(
    spaceId: String,
    failure: AppError.Business,
) {
    invalidateSpacePagination()
    removeDocumentSpaceProjection(spaceId, failure)
}

/** 一次完整的 list-spaces 扫描只能移除在其终止页被省略的身份。 */
internal fun DocumentWorkspaceFeature.removeOmittedDocumentSpaceProjection(spaceId: String) =
    removeDocumentSpaceProjection(spaceId)

/**
 * 权限修改结果是一个确认，而不是完整的 DocumentSpace 投影。
 * 自我移除会立即应用；每一个正向结果由一次新的类型化快照重建，
 * 而不是把 role/revision 字段拼接到更旧的缓存行中。
 */
internal fun DocumentWorkspaceFeature.publishDocumentPolicyMutation(
    result: DocumentPolicyMutationResult,
): Boolean {
    if (result.effectiveRole == DocumentSpace.ROLE_NONE) {
        removeDocumentSpaceProjection(result.spaceId)
        return true
    }
    refreshWorkspace()
    return true
}

private fun DocumentWorkspaceFeature.reportSpaceProjectionRemoval(
    failure: AppError.Business,
    captureFallbackRetained: Boolean,
    orphanRetained: Boolean,
) {
    reportError(
        failure,
        when {
            captureFallbackRetained && failure.code == 404 ->
                "文档空间已不存在；编辑器最新内容未能同步确认，当前标签已按本机草稿保留"
            captureFallbackRetained ->
                "空间访问权已失效；编辑器最新内容未能同步确认，当前标签已按本机草稿保留"
            orphanRetained && failure.code == 404 ->
                "文档空间已不存在，已保留本机未保存内容"
            orphanRetained ->
                "空间访问权已失效，已保留本机未保存内容"
            failure.code == 404 -> "文档空间已不存在，已清除本机缓存"
            else -> "空间访问权已失效，已清除本机缓存"
        },
    )
}

/** 归档/删除准入是本地操作状态，独立于服务器授权。 */
internal fun DocumentWorkspaceFeature.beginSpaceRetirement(spaceId: String) {
    retiringSpaceIds += spaceId
}
