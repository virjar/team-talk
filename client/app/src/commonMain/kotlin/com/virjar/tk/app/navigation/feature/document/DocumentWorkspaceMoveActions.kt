package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.shared.client.MAX_PENDING_DOCUMENT_MOVE_COMMANDS
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.shared.repository.DocumentMoveCommandCompletion
import com.virjar.tk.shared.repository.DocumentMoveCommandCompletionStatus
import com.virjar.tk.shared.repository.DocumentMoveCommandSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 仅由文档 move 工作流使用的目录操作。 */
internal class DocumentWorkspaceMovePort(
    val treeChildren: () -> Map<String?, List<DocumentNode>>,
    val invalidateBranch: (String, String?) -> Unit,
    val reloadBranch: suspend (String, String?) -> Unit,
    val prepareNodeBranches: (String, String, String?, Set<String?>) -> Unit,
)

/**
 * 协调文档 move 的提交、恢复和投影刷新。每一个请求都捕获一个稳定的 tab 实例；
 * 任何响应都不得从之后处于活动状态的某个 tab 解析其目标。
 */
internal class DocumentWorkspaceMoveActions(
    private val repository: DocumentRepositoryBoundary,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val state: DocumentWorkspaceMutationStatePort,
    private val port: DocumentWorkspaceMovePort,
) {
    private enum class ProjectionPublication {
        Accepted,
        Superseded,
        LocalPersistenceRejected,
    }

    private var movingInstances by mutableStateOf(emptySet<Long>())
    /** 确切的持久操作所有权；旧的重放绝不能清除一个节点上更新的命令。 */
    private var pendingDocumentMoves by mutableStateOf(emptyMap<String, String>())
    private val recoveredOperationIds = linkedSetOf<String>()
    private val pathRefreshes = DocumentPathRefreshCoordinator()

    fun isMoving(instanceId: Long): Boolean {
        val nodeId = state.tabs().firstOrNull { it.instanceId == instanceId }?.documentId
        return instanceId in movingInstances ||
            nodeId != null && nodeId in pendingDocumentMoves.values
    }

    fun restorePending(commands: List<PendingDocumentMoveCommand>) {
        pendingDocumentMoves = restoredPendingDocumentMoves(commands, recoveredOperationIds)
    }

    private fun trackPending(command: PendingDocumentMoveCommand) {
        // 恢复完成事件可能在前台提交者恢复执行时投递。
        // 绝不能复活这个 feature owner 已经收敛的操作。
        pendingDocumentMoves = pendingDocumentMovesAfterSubmission(
            current = pendingDocumentMoves,
            command = command,
            recoveredOperationIds = recoveredOperationIds,
        )
    }

    fun move(instanceId: Long, targetParentId: String?) {
        val initial = state.tabs().firstOrNull { it.instanceId == instanceId } ?: return
        val tab = state.captureActiveDraft(initial) ?: return
        val descendants = tab.documentId?.let {
            knownDocumentDescendantIds(it, port.treeChildren())
        }.orEmpty()
        if (targetParentId == tab.documentId || targetParentId in descendants) {
            reportError(IllegalArgumentException("不能移动到自身或其子页面"), "移动文档失败")
            return
        }
        if (targetParentId != null) {
            // 校验所选目标仍然属于已加载的树，但绝不在 RPC 期间保留这条路径；
            // move 结果携带权威的祖先链。
            nodeAncestorIds(targetParentId, port.treeChildren())
                .takeIf { it.lastOrNull() == targetParentId } ?: return
        }
        val request = DocumentMoveRequest.capture(tab, targetParentId) ?: return
        if (isMoving(request.instanceId)) return
        movingInstances = movingInstances + request.instanceId
        invalidateMoveBranches(request)
        scope.launch {
            try {
                when (val submission = submit(request)) {
                    is DocumentMoveCommandSubmission.Pending -> {
                        trackPending(submission.command)
                    }
                    is DocumentMoveCommandSubmission.Acknowledged -> {
                        pendingDocumentMoves = pendingDocumentMoves - submission.command.operationId
                        val moved = submission.projection
                            ?: loadCurrentMoveProjection(submission.command)
                        convergeAcknowledged(request, moved)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = if (failure is AppError.Business &&
                    failure.code == DOCUMENT_REVISION_CONFLICT_STATUS
                ) {
                    "文档已被其他成员修改或移动，请刷新后重试"
                } else {
                    "移动文档失败"
                }
                if (!failure.isDocumentSpaceAccessDenied()) reportError(failure, message)
            } finally {
                movingInstances = movingInstances - request.instanceId
            }
        }
    }

    /** 在内容保存之前使用的、同 parent 的可靠重命名。 */
    suspend fun renameBeforeContentSave(
        tab: DocumentTabState,
        title: String,
    ): DocumentRenameBeforeSaveResult {
        val request = DocumentMoveRequest.captureRename(tab, title) ?: run {
            reportError(
                IllegalStateException("Document rename was not admitted by the current tab"),
                "文档位置尚未完成校验，请刷新后重试保存",
            )
            return DocumentRenameBeforeSaveResult.NotAdmitted
        }
        movingInstances = movingInstances + request.instanceId
        invalidateMoveBranches(request)
        return try {
            when (val submission = submit(request)) {
                is DocumentMoveCommandSubmission.Pending -> {
                    trackPending(submission.command)
                    DocumentRenameBeforeSaveResult.Pending
                }
                is DocumentMoveCommandSubmission.Acknowledged -> {
                    pendingDocumentMoves = pendingDocumentMoves - submission.command.operationId
                    val moved = submission.projection ?: loadCurrentMoveProjection(submission.command)
                    when (convergeAcknowledged(request, moved)) {
                        ProjectionPublication.Accepted ->
                            DocumentRenameBeforeSaveResult.Acknowledged(moved)
                        ProjectionPublication.Superseded -> {
                            reportError(
                                IllegalStateException(
                                    "Document rename acknowledgement was superseded",
                                ),
                                "文档名称变更已确认，但当前版本已继续变化；正文草稿仍保留",
                            )
                            DocumentRenameBeforeSaveResult.Superseded
                        }
                        ProjectionPublication.LocalPersistenceRejected ->
                            DocumentRenameBeforeSaveResult.LocalPersistenceRejected
                    }
                }
            }
        } finally {
            movingInstances = movingInstances - request.instanceId
        }
    }

    suspend fun convergeRecovered(completion: DocumentMoveCommandCompletion): Boolean {
        if (recoveredOperationIds.size == MAX_PENDING_DOCUMENT_MOVE_COMMANDS) {
            recoveredOperationIds.remove(recoveredOperationIds.first())
        }
        recoveredOperationIds += completion.command.operationId
        pendingDocumentMoves = pendingDocumentMoves - completion.command.operationId
        try {
            if (completion.status == DocumentMoveCommandCompletionStatus.REJECTED) {
                refreshCommandBranches(completion.command)
                reportError(
                    IllegalStateException("Document move command was rejected"),
                    "文档位置或名称变更未完成，请刷新后重试",
                )
                return false
            }
            val moved = completion.projection ?: loadCurrentMoveProjection(completion.command)
            val request = requestForRecoveredCommand(completion.command)
            if (request != null) {
                return convergeAcknowledged(request, moved) == ProjectionPublication.Accepted
            } else {
                refreshCommandBranches(completion.command)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!failure.isDocumentSpaceAccessDenied()) {
                reportError(failure, "文档变更已提交，但本机目录刷新失败")
            }
        }
        return false
    }

    private suspend fun submit(request: DocumentMoveRequest): DocumentMoveCommandSubmission =
        repository.call(spaceId = request.spaceId) {
            moveNodeRecoverable(
                spaceId = request.spaceId,
                nodeId = request.documentId,
                oldParentId = request.oldParentId,
                targetParentId = request.targetParentId,
                name = request.title,
                expectedRevision = request.revision,
            ).getOrThrow()
        }

    private suspend fun loadCurrentMoveProjection(
        command: PendingDocumentMoveCommand,
    ): DocumentMoveResult = repository.call(spaceId = command.spaceId) {
        val spine = cachedNodePathSpine(command.spaceId, command.nodeId)
            ?: getNodePathSpine(command.spaceId, command.nodeId).getOrThrow()
        DocumentMoveResult(
            node = spine.nodes.last(),
            ancestorIds = spine.nodes.dropLast(1).map(DocumentNode::nodeId),
        )
    }

    private fun requestForRecoveredCommand(
        command: PendingDocumentMoveCommand,
    ): DocumentMoveRequest? {
        val tab = state.tabs().firstOrNull {
            it.spaceId == command.spaceId && it.documentId == command.nodeId &&
                it.revision == command.expectedRevision && it.parentId == command.oldParentId
        } ?: return null
        return DocumentMoveRequest(
            instanceId = tab.instanceId,
            documentId = command.nodeId,
            spaceId = command.spaceId,
            revision = command.expectedRevision,
            editGeneration = tab.editGeneration,
            oldParentId = command.oldParentId,
            targetParentId = command.targetParentId,
            title = command.name,
            preserveDraftTitle = runCatching {
                DocumentPolicy.normalizeNodeName(tab.draftTitle) != command.name
            }.getOrDefault(true),
        )
    }

    private suspend fun convergeAcknowledged(
        request: DocumentMoveRequest,
        moved: DocumentMoveResult,
    ): ProjectionPublication {
        val captureAccepted = state.tabs().firstOrNull(request::targets)
            ?.let(state.captureActiveDraft) != null
        val currentTabs = state.tabs()
        val responseAccepted = moved.matchesDocumentMoveRequest(request)
        if (responseAccepted) {
            port.prepareNodeBranches(
                request.spaceId,
                request.documentId,
                moved.node.parentId,
                setOf(request.oldParentId),
            )
        }
        val merged = if (responseAccepted && captureAccepted) {
            mergeDocumentMoveResponse(currentTabs, request, moved)
        } else {
            null
        }
        val publication = if (merged != null) {
            if (state.persistTabs(merged)) {
                state.replaceTabs(merged)
                merged.firstOrNull { it.instanceId == request.instanceId }
                    ?.takeIf { state.activeTabId() == it.tabId }
                    ?.let(state.updateActiveLocation)
                request.targetParentId?.let(state.expandParent)
                ProjectionPublication.Accepted
            } else {
                // 服务器已经提交，因此即使持久草稿存储拒绝合并，旧的 path/revision
                // 也必须停止授权本进程中的写入。
                val invalidated = invalidateOpenDocumentPathsAfterUnmergedMove(
                    currentTabs,
                    request,
                )
                if (invalidated !== currentTabs) state.replaceTabs(invalidated)
                ProjectionPublication.LocalPersistenceRejected
            }
        } else {
            val invalidated = invalidateOpenDocumentPathsAfterUnmergedMove(currentTabs, request)
            if (invalidated !== currentTabs) {
                state.persistTabs(invalidated)
                state.replaceTabs(invalidated)
            }
            ProjectionPublication.Superseded
        }
        try {
            when (val batch = refreshUnresolvedOpenPaths(request.spaceId)) {
                DocumentPathRefreshBatch.Superseded -> Unit
                is DocumentPathRefreshBatch.Current -> if (batch.failures.isNotEmpty()) {
                    reportError(batch.failures.first(), "部分已打开文档的位置尚未完成校验")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportError(failure, "文档已变更，但打开页面的位置刷新失败")
        }
        invalidateMoveBranches(request)
        try {
            if (state.selectedSpaceId() == request.spaceId) reloadMoveBranches(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportError(failure, "文档已变更，但目录刷新失败")
        }
        try {
            state.refreshHome()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportError(failure, "文档已变更，但首页刷新失败")
        }
        return publication
    }

    private suspend fun refreshCommandBranches(command: PendingDocumentMoveCommand) {
        port.invalidateBranch(command.spaceId, command.oldParentId)
        port.invalidateBranch(command.spaceId, command.targetParentId)
        if (state.selectedSpaceId() == command.spaceId) {
            listOf(command.targetParentId, command.oldParentId).distinct().forEach { parentId ->
                port.reloadBranch(command.spaceId, parentId)
            }
        }
        state.refreshHome()
    }

    private fun invalidateMoveBranches(request: DocumentMoveRequest) {
        port.invalidateBranch(request.spaceId, request.oldParentId)
        port.invalidateBranch(request.spaceId, request.targetParentId)
        port.invalidateBranch(request.spaceId, request.documentId)
    }

    /** 尝试两个权威投影；一个破损的分支绝不能压制另一个。 */
    private suspend fun reloadMoveBranches(request: DocumentMoveRequest) {
        var firstFailure: Exception? = null
        listOf(request.targetParentId, request.oldParentId).distinct().forEach { parentId ->
            try {
                // 每个分支拥有自己的请求排序。成功的第一个分支绝不能压制第二个分支的刷新。
                port.reloadBranch(request.spaceId, parentId)
            } catch (failure: Exception) {
                failure.rethrowIfDocumentWorkspaceCancelled()
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    /**
     * move 结果只对移动的节点做版本化，而不是子树成员关系。在其缓存的祖先链
     * 可能再次驱动导航之前，先从服务器重新校验每一个其他打开的 tab。
     */
    private suspend fun refreshUnresolvedOpenPaths(spaceId: String): DocumentPathRefreshBatch =
        pathRefreshes.refresh(
            spaceId = spaceId,
            currentTargets = {
                unresolvedDocumentPathRefreshTargets(state.tabs(), spaceId)
            },
            fetch = { key ->
                repository.call(
                    spaceId = key.spaceId,
                ) {
                    getDocument(key.spaceId, key.documentId).getOrThrow()
                }
            },
            publish = { current ->
                val currentTabs = state.tabs()
                acceptedDocumentPathRefreshes(currentTabs, current).forEach { path ->
                    state.prepareDocumentBranches(
                        path.document,
                        path.previousParentIds,
                    )
                }
                val refreshedTabs = mergeDocumentPathRefreshBatch(currentTabs, current)
                if (refreshedTabs !== currentTabs && state.persistTabs(refreshedTabs)) {
                    state.replaceTabs(refreshedTabs)
                }
            },
        )
}

/** 隔离一份在 repository 读取挂起期间完成的过期启动快照。 */
internal fun restoredPendingDocumentMoves(
    commands: List<PendingDocumentMoveCommand>,
    recoveredOperationIds: Set<String>,
): Map<String, String> = commands.asSequence()
    .filterNot { it.operationId in recoveredOperationIds }
    .associateTo(linkedMapOf()) { it.operationId to it.nodeId }

/** 前台 Pending 结果绝不能复活已经投递给 UI 的完成事件。 */
internal fun pendingDocumentMovesAfterSubmission(
    current: Map<String, String>,
    command: PendingDocumentMoveCommand,
    recoveredOperationIds: Set<String>,
): Map<String, String> = if (command.operationId in recoveredOperationIds) {
    current
} else {
    current + (command.operationId to command.nodeId)
}
