package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.Document

internal const val DOCUMENT_NOT_FOUND_STATUS = 404

/** 活动页面的失败是值，这样独立权威的树根仍然能收敛。 */
internal sealed interface DocumentActiveRefreshResult {
    data object NotRequested : DocumentActiveRefreshResult
    data class Loaded(val document: Document) : DocumentActiveRefreshResult
    data class Missing(val failure: AppError.Business) : DocumentActiveRefreshResult
    data class Failed(val failure: Exception) : DocumentActiveRefreshResult
}

internal data class DocumentActiveAndRootRefresh(
    val activeDocument: DocumentActiveRefreshResult,
    val rootLoaded: Boolean,
)

/**
 * 在不共享失败边界的情况下保留新鲜度排序（文档先于根）。
 * 取消和致命 VM 错误仍然中止刷新；每一个普通的活动页面结果都继续进入 [rebuildRoot]。
 */
internal suspend fun refreshActiveDocumentAndTreeRoot(
    documentId: String?,
    loadDocument: suspend (String) -> Document,
    rebuildRoot: suspend () -> Boolean,
): DocumentActiveAndRootRefresh {
    val activeDocument = if (documentId == null) {
        DocumentActiveRefreshResult.NotRequested
    } else {
        try {
            DocumentActiveRefreshResult.Loaded(loadDocument(documentId))
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (failure is AppError.Business &&
                failure.code in setOf(403, DOCUMENT_NOT_FOUND_STATUS)
            ) {
                DocumentActiveRefreshResult.Missing(failure)
            } else {
                DocumentActiveRefreshResult.Failed(failure)
            }
        }
    }
    return DocumentActiveAndRootRefresh(
        activeDocument = activeDocument,
        rootLoaded = rebuildRoot(),
    )
}

internal data class MissingActiveDocumentRefreshReconciliation(
    val tabs: List<DocumentTabState>,
    val activeTabId: String?,
    val selectedParentNodeId: String?,
    val orphanRetained: Boolean,
) {
    val activeTab: DocumentTabState?
        get() = tabs.firstOrNull { it.tabId == activeTabId }
}

/**
 * 一个干净的、远端已消失的 tab 没有需要保留的本地事实，会立即关闭。一个 dirty tab
 * 保留它完整的草稿/恢复身份，但它旧的远端位置会被移除，这样它就会暴露为
 * 一个可编辑、可恢复的孤儿，而不是服务器支撑的树节点。
 */
internal fun reconcileMissingActiveDocumentRefresh(
    tabs: List<DocumentTabState>,
    missingInstanceId: Long,
    activeTabId: String?,
): MissingActiveDocumentRefreshReconciliation? {
    val missing = tabs.firstOrNull { it.instanceId == missingInstanceId } ?: return null
    if (missing.tabId != activeTabId) return null
    if (missing.dirty || missing.creating) {
        val orphan = missing.copy(pathResolved = false, remoteMissing = !missing.creating)
        val reconciledTabs = if (orphan === missing) {
            tabs
        } else {
            tabs.map { if (it.instanceId == missing.instanceId) orphan else it }
        }
        return MissingActiveDocumentRefreshReconciliation(
            tabs = reconciledTabs,
            activeTabId = orphan.tabId,
            selectedParentNodeId = null,
            orphanRetained = true,
        )
    }

    val remainingTabs = tabs.filterNot { it.instanceId == missing.instanceId }
    val replacement = remainingTabs.lastOrNull { candidate ->
        candidate.spaceId == missing.spaceId &&
            (candidate.pathResolved || candidate.dirty || candidate.creating)
    }
    return MissingActiveDocumentRefreshReconciliation(
        tabs = remainingTabs,
        activeTabId = replacement?.tabId,
        selectedParentNodeId = replacement?.resolvedParentIdForNavigation(),
        orphanRetained = false,
    )
}
