package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.shouldReportCacheRefreshFailure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class ResidentDocumentActivation(
    val intent: DocumentTabNavigationIntent,
    val spaceChanged: Boolean,
)

/**
 * 拥有驻留正文准入、即时本地激活和远程正文收敛。
 *
 * 编辑器的驻留快照总是在远程读取之前发布。远程延续在每一次修改之前
 * 都会再次解析它们捕获的 tab 身份和导航 generation，因此迟到的刷新
 * 不可能替换更新的草稿、重新打开的 tab 或已选择的空间。
 */
internal class DocumentWorkspaceDocumentNavigation(
    private val readGateway: DocumentWorkspaceReadGateway,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val port: DocumentWorkspaceNavigationPort,
    private val projectionState: DocumentWorkspaceNavigationProjectionState,
    private val treeNavigation: DocumentWorkspaceTreeNavigation,
    private val beginNavigation: () -> Long,
    private val isCurrentNavigation: (Long, String?) -> Boolean,
    private val selectSpaceNow: suspend (String, Long) -> Boolean,
) {
    var loading by mutableStateOf(false)
        private set

    fun resetForNavigation() {
        loading = false
        projectionState.document = DocumentWorkspaceProjectionStatus.NOT_LOADED
    }

    fun openDocument(node: DocumentNode) {
        openTarget(node.spaceId, node.nodeId)
    }

    fun openHomeDocument(item: DocumentHomeItem) {
        openTarget(item.spaceId, item.documentId)
    }

    /** 类型化引用入口：外部已重校验权限，直接按既有标签容量规则打开目标。 */
    internal fun openReferenced(spaceId: String, documentId: String) = openTarget(spaceId, documentId)

    private fun openTarget(spaceId: String, documentId: String) {
        val target = DocumentTabTarget(spaceId, documentId)
        val admission = decideDocumentTabOpen(port.tabs(), target)
        if (admission is DocumentTabOpenDecision.RejectAtCapacity) {
            reportTabCapacityReached(admission)
            return
        }
        val generation = beginNavigation()
        port.closeHistory()
        if (admission is DocumentTabOpenDecision.ReuseResident) {
            val activation = activateResidentTab(admission.tab, generation) ?: return
            refreshResidentTabInBackground(activation)
            return
        }
        scope.launch {
            try {
                if (port.selectedSpaceId() != spaceId &&
                    !selectSpaceNow(spaceId, generation)
                ) return@launch
                if (!isCurrentNavigation(generation, spaceId)) return@launch
                openDocumentNow(spaceId, documentId, generation)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (isCurrentNavigation(generation, null)) {
                    reportError(failure, "加载文档失败")
                }
            }
        }
    }

    fun selectTab(tabId: String) {
        val tab = port.tabs().firstOrNull { it.tabId == tabId } ?: return
        val generation = beginNavigation()
        port.closeHistory()
        val activation = activateResidentTab(tab, generation) ?: return
        if (port.isSpaceLocalOnly(tab.spaceId)) return
        refreshResidentTabInBackground(activation)
    }

    private fun reportTabCapacityReached(rejection: DocumentTabOpenDecision.RejectAtCapacity) {
        reportError(rejection.asFailure(), rejection.userMessage())
    }

    private fun reportBodyCapacityReached(rejection: DocumentResidentBodyPlan.Rejected) {
        reportError(rejection.asFailure(), rejection.userMessage())
    }

    /** 在一个干净的 tab 成为驱逐候选之前，捕获唯一挂载的编辑器。 */
    private fun captureCurrentActiveBeforeResidentChange(): Boolean {
        val current = port.tabs().firstOrNull { it.tabId == port.activeTabId() } ?: return true
        return port.captureLatestActiveDraft(current) != null
    }

    /** 在任何远程 ACL、内容或目录读取可以挂起之前，发布驻留正文。 */
    private fun activateResidentTab(
        captured: DocumentTabState,
        generation: Long,
    ): ResidentDocumentActivation? {
        if (!isCurrentNavigation(generation, null)) return null
        if (!captureCurrentActiveBeforeResidentChange()) return null
        val current = port.tabs().firstOrNull {
            it.instanceId == captured.instanceId && it.spaceId == captured.spaceId
        } ?: return null
        if (port.spaces().none { it.spaceId == current.spaceId }) {
            reportError(
                IllegalStateException("Resident document space is unavailable"),
                "文档所属空间已不在本地工作区",
            )
            return null
        }
        val bodyPlan = when (val plan = planDocumentResidentBodies(
            tabs = port.tabs(),
            activeInstanceId = current.instanceId,
            // 重新激活一个已恢复的 dirty 身份，绝不能仅仅因为它高于今天的运行目标就销毁它。
            // 这条路径不会准入任何新身份。
            allowRecoveryDebt = true,
        )) {
            is DocumentResidentBodyPlan.Admitted -> plan
            is DocumentResidentBodyPlan.Rejected -> {
                reportBodyCapacityReached(plan)
                return null
            }
        }
        val spaceChanged = port.selectedSpaceId() != current.spaceId
        if (spaceChanged) {
            // 正文由驻留 tab 拥有。目录和授权投影属于先前选择的空间，
            // 绝不能跨越这次即时的本地切换泄漏。
            port.setSelectedSpaceId(current.spaceId)
            treeNavigation.clearVisibleProjection()
            port.clearGrants()
        }
        if (!isCurrentNavigation(generation, current.spaceId)) return null
        // 在传入的 tab 仍然驻留时转移编辑器所有权；只有那时才退役干净的非活动正文。
        // 因此 Compose 绝不会观察到被驱逐的旧 tab 作为活动 tab。
        port.setActiveTabId(current.tabId)
        projectionState.document = DocumentWorkspaceProjectionStatus.CACHED
        if (bodyPlan.tabs != port.tabs()) port.setTabs(bodyPlan.tabs)
        port.setSelectedParentNodeId(current.resolvedParentIdForNavigation())
        port.persistDrafts()
        return ResidentDocumentActivation(
            intent = DocumentTabNavigationIntent.capture(current, generation),
            spaceChanged = spaceChanged,
        )
    }

    private fun refreshResidentTabInBackground(activation: ResidentDocumentActivation) {
        scope.launch {
            try {
                refreshResidentTab(activation)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (isCurrentNavigation(activation.intent.generation, null)) {
                    reportError(failure, "刷新文档失败，已继续显示本地内容")
                }
            }
        }
    }

    private suspend fun refreshResidentTab(activation: ResidentDocumentActivation) {
        val intent = activation.intent
        fun current(): DocumentTabState? = intent.resolve(port.tabs(), isCurrentNavigation)

        val captured = current() ?: return
        val documentId = captured.documentId
        if (documentId == null) {
            if (activation.spaceChanged) refreshResidentDirectory(activation)
            return
        }

        val cachedPathStamp = DocumentPathStamp.capture(captured)
        val cachedPathRevealed = cachedPathStamp?.let { stamp ->
            treeNavigation.revealCachedSpine(stamp, intent.generation)
        } ?: false

        // 驻留 tab 同步切换空间，这样它的正文可以立即渲染。它的缓存脊柱可以同样快地
        // 揭示活动路径，但脊柱刻意不是一个完整的根分支。刷新那一个根分支一次，
        // 这样根兄弟节点和 move 目标保持可及，而无需回到旧的逐级 RPC 遍历。
        if (activation.spaceChanged) {
            refreshResidentDirectory(activation)
            if (current() == null) return
        }

        val remote = try {
            readGateway.refreshDocument(captured.spaceId, documentId)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (failure is AppError.Business && failure.code == 403) {
                if (current() == null) return
                if (!port.isSpaceLocalOnly(captured.spaceId)) {
                    port.removeSpaceProjection(captured.spaceId, failure)
                }
                return
            }
            if (failure is AppError.Business && failure.code == DOCUMENT_NOT_FOUND_STATUS) {
                val latest = current() ?: return
                if (port.captureLatestActiveDraft(latest) == null) return
                treeNavigation.forgetDocumentIdentity(captured.spaceId, documentId)
                reconcileMissingResidentTab(intent, failure)
                projectionState.document = if (port.tabs().any { it.tabId == port.activeTabId() }) {
                    DocumentWorkspaceProjectionStatus.CACHED
                } else {
                    DocumentWorkspaceProjectionStatus.NOT_LOADED
                }
                return
            }
            if (current() != null) {
                if (cachedPathRevealed) {
                    projectionState.tree = documentProjectionStatusAfterFailure(
                        hadCachedSnapshot = true,
                        failure = failure,
                    )
                }
                projectionState.document = documentProjectionStatusAfterFailure(
                    hadCachedSnapshot = true,
                    failure = failure,
                )
                if (shouldReportCacheRefreshFailure(failure, hasLocalProjection = true)) {
                    reportError(failure, "文档服务暂不可用，已打开本地文档")
                }
            }
            return
        }

        if (current() != null) projectionState.document = DocumentWorkspaceProjectionStatus.CURRENT
        publishResidentDocument(
            activation = activation,
            remote = remote,
            cachedPathStamp = cachedPathStamp.takeIf { cachedPathRevealed },
        )
    }

    /** 把已经获取的快照发布进那一个驻留 tab，而无需另一次 RPC。 */
    private suspend fun publishResidentDocument(
        activation: ResidentDocumentActivation,
        remote: Document,
        cachedPathStamp: DocumentPathStamp? = null,
    ) {
        val intent = activation.intent
        fun current(): DocumentTabState? = intent.resolve(port.tabs(), isCurrentNavigation)

        val latestBeforeCapture = current() ?: return
        val latest = port.captureLatestActiveDraft(latestBeforeCapture) ?: return
        if (current()?.instanceId != latest.instanceId) return
        val refreshed = mergeDocumentRefresh(latest, remote)
        if (refreshed == null) {
            reportError(
                IllegalStateException("Remote document snapshot did not match the resident tab"),
                "服务器文档快照无效，已继续显示本地内容",
            )
            return
        }
        val pathChanged = latest.parentId != refreshed.parentId ||
            latest.ancestorIds != refreshed.ancestorIds
        if (pathChanged) {
            // 完整正文已经是一个权威的路径事实。在下一次挂起之前移除它旧的行位置，
            // 但保留文档自己的子分支，这样移动一个 parent 就不会丢弃一棵
            // 在其他方面仍然有效的缓存子树。
            treeNavigation.prepareDocumentRefreshBranches(
                document = remote,
                previousParentIds = setOf(latest.parentId),
            )
        }
        if (refreshed != latest) {
            port.setTabs(port.tabs().map { tab ->
                if (tab.instanceId == refreshed.instanceId) refreshed else tab
            })
        }
        if (current() == null) return
        port.setActiveTabId(refreshed.tabId)
        port.setSelectedParentNodeId(refreshed.resolvedParentIdForNavigation())
        port.persistDrafts()

        val stamp = DocumentPathStamp.capture(refreshed)
        if (stamp == null) return
        val previousCachedPathStillMatches = cachedPathStamp?.let { cached ->
            cached.spaceId == stamp.spaceId && cached.documentId == stamp.documentId &&
                cached.parentId == stamp.parentId && cached.ancestorIds == stamp.ancestorIds
        } == true
        val cachedPathRevealedForStamp = previousCachedPathStillMatches ||
            treeNavigation.revealCachedSpine(stamp, intent.generation)
        try {
            treeNavigation.refreshAndRevealSpine(stamp, intent.generation)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (!cachedPathRevealedForStamp) {
                treeNavigation.invalidateDocumentPath(stamp)
            }
            projectionState.tree = documentProjectionStatusAfterFailure(
                hadCachedSnapshot = cachedPathRevealedForStamp,
                failure = failure,
            )
            if (shouldReportCacheRefreshFailure(failure, cachedPathRevealedForStamp)) {
                reportError(failure, "文档目录暂不可用，正文仍保留")
            }
        }
    }

    private fun reconcileMissingResidentTab(
        intent: DocumentTabNavigationIntent,
        failure: AppError.Business,
    ) {
        val current = intent.resolve(port.tabs(), isCurrentNavigation) ?: return
        val reconciliation = reconcileMissingActiveDocumentRefresh(
            tabs = port.tabs(),
            missingInstanceId = current.instanceId,
            activeTabId = port.activeTabId(),
        ) ?: return
        port.setTabs(reconciliation.tabs)
        port.setActiveTabId(reconciliation.activeTabId)
        port.setSelectedParentNodeId(reconciliation.selectedParentNodeId)
        port.persistDrafts()
        reportError(
            failure,
            if (reconciliation.orphanRetained && failure.code == 403) {
                "已无权访问该文档，已保留本地未保存内容"
            } else if (reconciliation.orphanRetained) {
                "文档已被删除，已保留本地未保存内容"
            } else if (failure.code == 403) {
                "已无权访问该文档，已关闭本地标签"
            } else {
                "文档已被删除，已关闭本地标签"
            },
        )
    }

    private suspend fun refreshResidentDirectory(activation: ResidentDocumentActivation) {
        if (port.isSpaceLocalOnly(activation.intent.spaceId)) return
        try {
            if (!treeNavigation.loadRoot(activation.intent.generation)) return
            val current = activation.intent.resolve(port.tabs(), isCurrentNavigation) ?: return
            if (current.documentId == null && current.pathResolved &&
                current.ancestorIds.isNotEmpty()
            ) {
                treeNavigation.revealPath(current.ancestorIds, activation.intent.generation)
            }
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            val stillCurrent = activation.intent.resolve(port.tabs(), isCurrentNavigation)
            if (stillCurrent != null) {
                reportError(failure, "当前无网络，已打开本地文档，目录将在联网后刷新")
            }
        }
    }

    suspend fun openDocumentNow(spaceId: String, documentId: String, generation: Long) {
        if (!isCurrentNavigation(generation, spaceId)) return
        val target = DocumentTabTarget(spaceId, documentId)
        when (val admission = decideDocumentTabOpen(port.tabs(), target)) {
            is DocumentTabOpenDecision.ReuseResident -> {
                val activation = activateResidentTab(admission.tab, generation) ?: return
                refreshResidentTab(activation)
                return
            }
            is DocumentTabOpenDecision.RejectAtCapacity -> {
                reportTabCapacityReached(admission)
                return
            }
            DocumentTabOpenDecision.AdmitNew -> Unit
        }
        loading = true
        try {
            val cached = try {
                readGateway.cachedDocument(spaceId, documentId)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (isCurrentNavigation(generation, spaceId)) {
                    reportError(failure, "读取本机文档缓存失败")
                }
                null
            }
            if (!isCurrentNavigation(generation, spaceId)) return
            if (cached != null) {
                require(cached.spaceId == spaceId && cached.documentId == documentId) {
                    "本机文档缓存返回了错误身份"
                }
                val activation = admitDocumentSnapshot(cached, target, generation) ?: return
                projectionState.document = DocumentWorkspaceProjectionStatus.CACHED
                loading = false
                refreshResidentTab(activation)
                return
            }

            projectionState.document = DocumentWorkspaceProjectionStatus.LOADING
            val remote = try {
                readGateway.refreshDocument(spaceId, documentId)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (failure is AppError.Business && failure.code == 403) {
                    if (!isCurrentNavigation(generation, spaceId)) return
                    if (!port.isSpaceLocalOnly(spaceId)) {
                        port.removeSpaceProjection(spaceId, failure)
                    }
                    return
                }
                if (!isCurrentNavigation(generation, spaceId)) return
                projectionState.document = documentProjectionStatusAfterFailure(
                    hadCachedSnapshot = false,
                    failure = failure,
                )
                when {
                    failure is AppError.Business && failure.code == DOCUMENT_NOT_FOUND_STATUS ->
                        reportError(failure, "文档已被删除或不存在")
                    failure === AppError.Network || failure === AppError.Timeout ->
                        reportError(failure, "当前无网络，正文尚未缓存")
                    else -> reportError(failure, "正文尚未缓存，文档服务暂不可用")
                }
                return
            }
            if (!isCurrentNavigation(generation, spaceId)) return
            val activation = admitDocumentSnapshot(remote, target, generation) ?: return
            projectionState.document = DocumentWorkspaceProjectionStatus.CURRENT
            publishResidentDocument(activation, remote)
        } finally {
            if (isCurrentNavigation(generation, spaceId)) loading = false
        }
    }

    /** 在不执行另一次读取的情况下准入一个正文；更新的驻留草稿身份胜出。 */
    private fun admitDocumentSnapshot(
        document: Document,
        target: DocumentTabTarget,
        generation: Long,
    ): ResidentDocumentActivation? {
        if (document.spaceId != target.spaceId || document.documentId != target.documentId ||
            !isCurrentNavigation(generation, target.spaceId)
        ) return null
        if (!captureCurrentActiveBeforeResidentChange()) return null
        when (val publication = decideDocumentTabOpen(port.tabs(), target)) {
            is DocumentTabOpenDecision.RejectAtCapacity -> {
                reportTabCapacityReached(publication)
                return null
            }
            is DocumentTabOpenDecision.ReuseResident ->
                return activateResidentTab(publication.tab, generation)
            DocumentTabOpenDecision.AdmitNew -> Unit
        }
        val tab = DocumentTabState.from(document, instanceId = port.nextTabInstanceId())
        val bodyPlan = when (val plan = planDocumentResidentBodies(
            tabs = port.tabs() + tab,
            activeInstanceId = tab.instanceId,
            allowRecoveryDebt = false,
        )) {
            is DocumentResidentBodyPlan.Admitted -> plan
            is DocumentResidentBodyPlan.Rejected -> {
                reportBodyCapacityReached(plan)
                return null
            }
        }
        port.setTabs(bodyPlan.tabs)
        port.setActiveTabId(tab.tabId)
        port.setSelectedParentNodeId(tab.parentId)
        port.persistDrafts()
        return ResidentDocumentActivation(
            intent = DocumentTabNavigationIntent.capture(tab, generation),
            spaceChanged = false,
        )
    }
}
