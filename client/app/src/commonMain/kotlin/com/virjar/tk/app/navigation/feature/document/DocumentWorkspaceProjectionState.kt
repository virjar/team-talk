package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant

/**
 * 在树/空间请求可以挂起之前捕获的稳定导航所有权。延续必须再次按实例解析当前 tab，
 * 而不是保留过期的 tabId 或路径。
 */
internal data class DocumentTabNavigationIntent(
    val instanceId: Long,
    val spaceId: String,
    val generation: Long,
) {
    fun resolve(
        tabs: List<DocumentTabState>,
        isCurrent: (generation: Long, spaceId: String) -> Boolean,
    ): DocumentTabState? {
        if (!isCurrent(generation, spaceId)) return null
        return tabs.firstOrNull { it.instanceId == instanceId && it.spaceId == spaceId }
    }

    companion object {
        fun capture(tab: DocumentTabState, generation: Long) = DocumentTabNavigationIntent(
            instanceId = tab.instanceId,
            spaceId = tab.spaceId,
            generation = generation,
        )
    }
}

/**
 * 一个仍然打开的 tab 实例所拥有的确切解析路径纪元。
 *
 * 目录请求可能在另一个文档响应已经移动同一个 tab 之后失败。
 * 比较完整的戳记可以防止那个迟到的失败使更新的路径失效。
 */
internal data class DocumentPathStamp(
    val instanceId: Long,
    val documentId: String,
    val spaceId: String,
    val parentId: String?,
    val ancestorIds: List<String>,
    val revision: Long,
) {
    fun targets(tab: DocumentTabState): Boolean = tab.pathResolved &&
        tab.instanceId == instanceId && tab.documentId == documentId && tab.spaceId == spaceId &&
        tab.parentId == parentId && tab.ancestorIds == ancestorIds && tab.revision == revision

    companion object {
        fun capture(tab: DocumentTabState): DocumentPathStamp? {
            val documentId = tab.documentId ?: return null
            val revision = tab.revision ?: return null
            if (!tab.pathResolved) return null
            return DocumentPathStamp(
                instanceId = tab.instanceId,
                documentId = documentId,
                spaceId = tab.spaceId,
                parentId = tab.parentId,
                // 独立于模型提供的任何可变列表，拥有路径戳记。
                ancestorIds = tab.ancestorIds.toList(),
                revision = revision,
            )
        }
    }
}

/** 只使观察到当前目录失败的那个确切的路径快照失效。 */
internal fun invalidateDocumentPathStamp(
    tabs: List<DocumentTabState>,
    stamp: DocumentPathStamp,
): List<DocumentTabState> {
    val index = tabs.indexOfFirst(stamp::targets)
    if (index < 0) return tabs
    return tabs.toMutableList().also { updated ->
        updated[index] = updated[index].copy(pathResolved = false)
    }
}

/** 只有当那个 tab 仍然拥有未保存状态时，目录失败才能揭示本地正文。 */
internal fun DocumentTabNavigationIntent.resolveLocalDraftAfterDirectoryFailure(
    tabs: List<DocumentTabState>,
    isCurrent: (generation: Long, spaceId: String) -> Boolean,
): DocumentTabState? = resolve(tabs, isCurrent)?.takeIf { it.dirty || it.creating }

/**
 * 仅用于在服务不可达时暴露持久 dirty tab 的最小空间投影。
 * 它们刻意不包含任何目录或已保存文档缓存。
 */
internal data class OfflineDraftPublication(
    val tabs: List<DocumentTabState>,
    val spaces: List<DocumentSpace>,
    val selectedSpaceId: String?,
    val activeTabId: String?,
)

/** 由 offlineDraftSpaceIds 跟踪的本地占位空间的结构性 principal。 */
internal const val OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID = "local-offline-draft"

internal fun offlineDocumentDraftSpace(spaceId: String, accessUnavailable: Boolean = false): DocumentSpace =
    DocumentSpace(
        spaceId = spaceId,
        name = "离线草稿 · ${spaceId.take(8)}",
        description = if (accessUnavailable) {
            "空间访问权已失效；仅保留本机未保存内容，不能继续同步。"
        } else {
            "仅恢复本机未保存草稿；目录与服务器文档未缓存。"
        },
        myRole = if (accessUnavailable) DocumentSpace.ROLE_VIEWER else DocumentSpace.ROLE_EDITOR,
        createdBy = OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID,
        createdAt = 0,
        updatedAt = 0,
        ownerPrincipalType = DocumentSpaceGrant.PRINCIPAL_USER,
        ownerPrincipalId = OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID,
        stewardUid = OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID,
        custodyRevision = 1,
    )

/** 在当前空间级 403/根 404 之后应用的纯投影计划。 */
internal data class UnavailableDocumentSpaceProjection(
    val tabs: List<DocumentTabState>,
    val spaces: List<DocumentSpace>,
    val offlineDraftSpaceIds: Set<String>,
    val selectedSpaceId: String?,
    val activeTabId: String?,
    val selectedParentNodeId: String?,
    val orphanRetained: Boolean,
)

/**
 * 移除 [spaceId] 拥有的每一个干净缓存身份，同时在一个显式的本地空间根下保留未保存的正文。
 * 403 绝不改变草稿的 CAS 基线，也不会把它变成文档级 404。
 * 本地标记在新鲜空间快照到达之前阻止写入。
 */
internal fun removeUnavailableDocumentSpaceProjection(
    spaceId: String,
    tabs: List<DocumentTabState>,
    spaces: List<DocumentSpace>,
    offlineDraftSpaceIds: Set<String>,
    selectedSpaceId: String?,
    activeTabId: String?,
    selectedParentNodeId: String?,
): UnavailableDocumentSpaceProjection {
    val activeBefore = tabs.firstOrNull { it.tabId == activeTabId }
    val retainedTabs = tabs.mapNotNull { tab ->
        when {
            tab.spaceId != spaceId -> tab
            tab.dirty || tab.creating -> tab.copy(pathResolved = false)
            else -> null
        }
    }
    val localOrphans = retainedTabs.filter { tab ->
        tab.spaceId == spaceId && (tab.dirty || tab.creating)
    }
    val orphanRetained = localOrphans.isNotEmpty()
    val remainingSpaces = spaces.filterNot { it.spaceId == spaceId }
    val nextSpaces = if (orphanRetained) {
        remainingSpaces + offlineDocumentDraftSpace(spaceId, accessUnavailable = true)
    } else {
        remainingSpaces
    }
    val nextOfflineSpaceIds = (offlineDraftSpaceIds - spaceId).let { remaining ->
        if (orphanRetained) remaining + spaceId else remaining
    }
    val retiredSelection = selectedSpaceId == spaceId
    val nextSelectedSpaceId = when {
        !retiredSelection -> selectedSpaceId
        orphanRetained -> spaceId
        else -> null
    }
    val retainedActive = activeBefore?.let { captured ->
        retainedTabs.firstOrNull { it.instanceId == captured.instanceId }
    }
    val nextActive = when {
        retiredSelection -> localOrphans.lastOrNull()
        retainedActive != null -> retainedActive
        else -> retainedTabs.lastOrNull { it.spaceId == nextSelectedSpaceId }
    }
    val nextSelectedParentNodeId = when {
        retiredSelection -> nextActive?.resolvedParentIdForNavigation()
        activeBefore?.spaceId == spaceId -> nextActive?.resolvedParentIdForNavigation()
        else -> selectedParentNodeId
    }
    return UnavailableDocumentSpaceProjection(
        tabs = retainedTabs,
        spaces = nextSpaces,
        offlineDraftSpaceIds = nextOfflineSpaceIds,
        selectedSpaceId = nextSelectedSpaceId,
        activeTabId = nextActive?.tabId,
        selectedParentNodeId = nextSelectedParentNodeId,
        orphanRetained = orphanRetained,
    )
}

internal fun offlineDraftPublication(snapshot: DocumentWorkspaceDraftSnapshot): OfflineDraftPublication {
    val recoveredTabs = snapshot.tabs.map { tab ->
        if (tab.pathResolved) tab.copy(pathResolved = false) else tab
    }
    val recoveredActive = snapshot.activeTabInstanceId?.let { instanceId ->
        recoveredTabs.firstOrNull { it.instanceId == instanceId }
    } ?: recoveredTabs.lastOrNull()
    val offlineTabs = when (val plan = planDocumentResidentBodies(
        tabs = recoveredTabs,
        activeInstanceId = recoveredActive?.instanceId,
        allowRecoveryDebt = true,
    )) {
        is DocumentResidentBodyPlan.Admitted -> plan.tabs
        is DocumentResidentBodyPlan.Rejected -> error(
            "A persisted document draft exceeded its persistence-derived resident ceiling: " +
                "required=${plan.requiredProtectedBodyChars}, limit=${plan.bodyLimitChars}",
        )
    }
    val spaces = offlineTabs
        .asSequence()
        .map(DocumentTabState::spaceId)
        .distinct()
        .map(::offlineDocumentDraftSpace)
        .toList()
    val activeByInstance = snapshot.activeTabInstanceId?.let { instanceId ->
        offlineTabs.firstOrNull { it.instanceId == instanceId }
    }
    val selectedSpaceId = activeByInstance?.spaceId
        ?: snapshot.selectedSpaceId?.takeIf { selected ->
            offlineTabs.any { it.spaceId == selected }
        }
        ?: offlineTabs.last().spaceId
    val active = activeByInstance
        ?: offlineTabs.lastOrNull { it.spaceId == selectedSpaceId }
    return OfflineDraftPublication(
        tabs = offlineTabs,
        spaces = spaces,
        selectedSpaceId = selectedSpaceId,
        activeTabId = active?.tabId,
    )
}
