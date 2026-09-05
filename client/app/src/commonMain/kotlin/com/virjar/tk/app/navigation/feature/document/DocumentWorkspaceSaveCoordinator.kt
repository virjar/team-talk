package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.shared.repository.DocumentMutationResult
import com.virjar.tk.app.telemetry.ClientActionAttempt
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.startActionAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 文档保存使用的具体远程写入口；远端调用仍经过同一个 Repository 边界。 */
internal class DocumentSaveGateway(
    private val repository: DocumentRepositoryBoundary,
) {
    suspend fun create(
        command: PendingDocumentCreateCommand,
    ): DocumentMutationResult<Document> =
        repository.call(spaceId = command.spaceId) {
            createDocument(
                spaceId = command.spaceId,
                documentId = command.documentId,
                parentId = command.parentId,
                title = command.title,
                markdown = command.markdown,
                assets = command.assets,
            ).getOrThrow()
        }

    suspend fun update(
        tab: DocumentTabState,
    ): DocumentMutationResult<Document> =
        repository.call(spaceId = tab.spaceId) {
            updateDocument(
                spaceId = tab.spaceId,
                documentId = requireNotNull(tab.documentId).also {
                    check(!tab.remoteMissing) { "远端已删除的文档只能另存为新文档" }
                },
                markdown = tab.draftMarkdown,
                assets = tab.draftAssets,
                expectedRevision = tab.revision ?: 1,
            ).getOrThrow()
        }

    suspend fun restore(
        target: DocumentRequestTarget,
        preview: DocumentRevision,
        expectedRevision: Long,
    ): DocumentMutationResult<Document> = repository.call(spaceId = target.spaceId) {
        updateDocument(
            spaceId = target.spaceId,
            documentId = target.documentId,
            markdown = preview.markdown,
            assets = preview.assets,
            expectedRevision = expectedRevision,
        ).getOrThrow()
    }
}

/**
 * 拥有文档写准入、稳定创建重放和 revision 恢复协调。
 *
 * 直接使用所属工作区的状态；不在中间适配层复制标签、草稿或导航状态。
 * 每一个请求在派发之前捕获它的 tab。响应针对工作区的最新 tab 列表合并，
 * 因此迟到的响应不可能替换更新的编辑器帧或重新打开的 tab 实例。
 */
internal class DocumentWorkspaceSaveCoordinator(
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val gateway: DocumentSaveGateway,
    private val workspace: DocumentWorkspaceFeature,
    private val createOutbox: DocumentDurableCreateOutbox,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
) {
    private sealed interface TitlePreparation {
        data object Queued : TitlePreparation
        data object Stopped : TitlePreparation

        data class Ready(
            val tab: DocumentTabState,
            val request: DocumentTabRequest,
            val renamed: Boolean,
        ) : TitlePreparation
    }

    private class TitlePreparationPersistenceException(
        val userMessage: String,
    ) : IllegalStateException(userMessage)

    private val pendingMutations = DocumentPendingMutationTracker()

    fun hasPending(tab: DocumentTabState): Boolean = pendingMutations.hasFor(tab)

    fun saveActive() {
        // 同步捕获：在这个调用之后立即切换 tab 绝不能把 RPC 重新指向别处。
        val initial = workspace.activeTab ?: return
        val current = workspace.captureLatestActiveDraft(initial) ?: return
        if (workspace.isTerminallyReadOnly(current) ||
            current.draftRecoveryKey() in workspace.transitioningDraftRecoveryKeys
        ) return
        val attempt = telemetry.startActionAttempt(
            ClientUiPage.DOCUMENTS,
            ClientUiAction.SAVE_DOCUMENT,
        )
        workspace.revisionConflictActions.clearConflict()
        val createCommand = if (current.creating || current.documentId == null) {
            if (createOutbox.pendingDocuments().none { it.matches(current) } &&
                !workspace.hasDocumentDraftRecoveryCapacity(1)
            ) {
                workspace.reportDocumentDraftCapacityReached()
                attempt.fail()
                return
            }
            try {
                createOutbox.acquireDocument(current)
            } catch (failure: Exception) {
                attempt.fail()
                reportError(failure, "文档草稿不满足保存条件")
                return
            }
        } else {
            null
        }
        startSave(current, createCommand, "保存文档失败", attempt)
    }

    /** 恢复的稳定 ID 创建只有在空间成员关系再次权威之后才恢复。 */
    fun replayPendingCreates(loadedSpaces: List<DocumentSpace>) {
        val availableSpaceIds = loadedSpaces.asSequence()
            .map(DocumentSpace::spaceId)
            .filterNot { it in workspace.retiringSpaceIds || it in workspace.offlineDraftSpaceIds }
            .toHashSet()
        createOutbox.replayableDocuments(workspace.tabs, availableSpaceIds).forEach { replay ->
            startSave(replay.tab, replay.command, "重试创建文档失败，草稿已保留")
        }
    }

    fun restorePreview() {
        // 预览和目标是一个不可变的 revision 意图，在派发之前捕获。
        val initial = workspace.activeTab ?: return
        val current = workspace.captureLatestActiveDraft(initial) ?: return
        if (workspace.isTerminallyReadOnly(current) ||
            current.draftRecoveryKey() in workspace.transitioningDraftRecoveryKeys
        ) return
        val target = DocumentRequestTarget.from(current) ?: return
        val preview = workspace.historyActions.previewFor(target) ?: return
        val staged = stageRevisionRestoreDraft(current, preview) ?: return
        val expectedRevision = staged.revision ?: return
        val mutation = beginMutation(staged) ?: return
        workspace.navigationActions.invalidateBranch(staged.spaceId, staged.parentId)
        scope.launch {
            try {
                check(workspace.persistDraftSnapshot() && workspace.draftCollaboration.flush()) {
                    "无法持久保存版本恢复草稿，已阻止远端请求"
                }
                val prepared = when (val title = prepareTitleForContentWrite(
                    tab = staged,
                    request = mutation.request,
                    canonicalTitle = staged.draftTitle,
                    draftAlreadyFlushed = true,
                    beforeRenameFailure = "无法持久保存版本恢复草稿，已阻止远端请求",
                    afterRenameFailure = "版本名称已恢复，但本机正文草稿尚未完成持久化",
                )) {
                    TitlePreparation.Queued -> return@launch
                    TitlePreparation.Stopped -> return@launch
                    is TitlePreparation.Ready -> title
                }
                val restored = gateway.restore(
                    target,
                    preview,
                    prepared.tab.revision ?: expectedRevision,
                ).projection
                    ?: return@launch
                val responseMatches = restored.matchesDocumentMutationRequest(prepared.request)
                val captureAccepted = workspace.tabs.firstOrNull(prepared.request::targets)
                    ?.let(workspace::captureLatestActiveDraft) != null
                if (responseMatches) {
                    prepareDirectoryRefresh(restored, staged.parentId)
                }
                val merge = if (responseMatches && captureAccepted) {
                    mergeDocumentMutationResponse(workspace.tabs, prepared.request, restored)
                } else {
                    null
                }
                if (merge != null) {
                    publishRevisionRestoreMerge(merge, target)
                }
                if (responseMatches) {
                    refreshDirectory(restored, staged.parentId)
                }
                workspace.refreshHomeProjection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestStillTargetsOpenTab(mutation.request)) {
                    reportError(failure, "恢复文档版本失败")
                }
            } finally {
                pendingMutations.end(mutation)
            }
        }
    }

    private fun startSave(
        current: DocumentTabState,
        createCommand: PendingDocumentCreateCommand?,
        failureMessage: String,
        attempt: ClientActionAttempt? = null,
    ) {
        val mutation = beginMutation(current, createCommand?.admittedEditGeneration) ?: run {
            attempt?.cancel()
            return
        }
        val localPersistenceAdmitted = createCommand == null || workspace.persistDraftSnapshot()
        if (createCommand != null && !localPersistenceAdmitted) {
            // 任何 RPC 都不能在持久准入之前启动。释放冻结的载荷，
            // 这样之后的 retry 可以捕获修正后的当前草稿，而不是重放无效正文。
            createOutbox.completeDocument(createCommand)
            workspace.persistDraftSnapshot()
            pendingMutations.end(mutation)
            attempt?.fail()
            return
        }
        workspace.navigationActions.invalidateBranch(current.spaceId, current.parentId)
        val job = scope.launch {
            try {
                val canonicalTitle = try {
                    DocumentPolicy.normalizeNodeName(current.draftTitle)
                } catch (failure: IllegalArgumentException) {
                    attempt?.fail()
                    reportError(failure, "文档标题不符合保存要求")
                    return@launch
                }
                val prepared = if (createCommand == null) {
                    try {
                        prepareTitleForContentWrite(
                            tab = current,
                            request = mutation.request,
                            canonicalTitle = canonicalTitle,
                            draftAlreadyFlushed = false,
                            beforeRenameFailure = "无法持久保存文档草稿，已阻止名称变更请求",
                            afterRenameFailure = "文档名称已更新，但本机草稿尚未完成持久化；正文未发送",
                        )
                    } catch (cancelled: CancellationException) {
                        attempt?.cancel()
                        throw cancelled
                    } catch (failure: Exception) {
                        attempt?.fail()
                        val conflictHandled = failure is AppError.Business &&
                            failure.code == DOCUMENT_REVISION_CONFLICT_STATUS &&
                            workspace.revisionConflictActions.handleSaveConflict(mutation.request)
                        if (failure is TitlePreparationPersistenceException) {
                            reportError(failure, failure.userMessage)
                        } else if (!conflictHandled && !failure.isDocumentSpaceAccessDenied() &&
                            requestStillTargetsOpenTab(mutation.request)
                        ) {
                            reportError(failure, failureMessage)
                        }
                        return@launch
                    }
                } else {
                    TitlePreparation.Ready(current, mutation.request, renamed = false)
                }
                val ready = when (prepared) {
                    TitlePreparation.Queued -> {
                        // 重命名身份是持久的。保留完整的本地草稿，
                        // 让恢复在任何内容 CAS 发出之前完成结构修改。
                        attempt?.queue()
                        return@launch
                    }
                    TitlePreparation.Stopped -> {
                        attempt?.fail()
                        return@launch
                    }
                    is TitlePreparation.Ready -> {
                        if (prepared.renamed &&
                            current.draftMarkdown == current.savedMarkdown &&
                            current.draftAssets == current.savedAssets
                        ) {
                            attempt?.succeed()
                            return@launch
                        }
                        prepared
                    }
                }
                val writeTab = ready.tab
                val writeRequest = ready.request
                val savedResult = try {
                    if (createCommand != null) {
                        check(localPersistenceAdmitted && workspace.draftCollaboration.flush()) {
                            "无法持久保存文档创建命令，已阻止发送请求"
                        }
                        if (!createOutbox.containsDocument(createCommand) ||
                            !requestStillTargetsOpenTab(mutation.request)
                        ) {
                            attempt?.cancel()
                            return@launch
                        }
                        gateway.create(createCommand)
                    } else {
                        gateway.update(writeTab)
                    }
                } catch (cancelled: CancellationException) {
                    attempt?.cancel()
                    throw cancelled
                } catch (failure: Exception) {
                    attempt?.fail()
                    val conflictHandled = failure is AppError.Business &&
                        failure.code == DOCUMENT_REVISION_CONFLICT_STATUS &&
                        workspace.revisionConflictActions.handleSaveConflict(writeRequest)
                    if (!conflictHandled && !failure.isDocumentSpaceAccessDenied() &&
                        requestStillTargetsOpenTab(writeRequest)
                    ) {
                        reportError(failure, failureMessage)
                    }
                    return@launch
                }

                val saved = savedResult.projection
                if (saved == null) {
                    // SDK 证明命令已提交，但没有返回可发布的本地投影。
                    // 一次稳定创建仍然必须离开 outbox；本地孤儿草稿保持不动。
                    attempt?.succeed()
                    if (createCommand != null) {
                        completeCommittedCreateAfterLatestDraftCapture(
                            request = mutation.request,
                            command = createCommand,
                        )
                    }
                    return@launch
                }

                val responseMatches = saved.matchesDocumentMutationRequest(writeRequest)
                if (responseMatches) attempt?.succeed() else attempt?.fail()
                if (!responseMatches) {
                    if (createCommand != null) {
                        completeCommittedCreateAfterLatestDraftCapture(
                            request = mutation.request,
                            command = createCommand,
                        )
                    }
                    return@launch
                }

                try {
                    val captureAccepted = workspace.tabs.firstOrNull(writeRequest::targets)
                        ?.let(workspace::captureLatestActiveDraft) != null
                    // 这刻意在 publishSaveMerge 之前：它的持久化屏障可能在服务器
                    // 已经使新路径权威之后挂起。
                    prepareDirectoryRefresh(saved, current.parentId)
                    val merge = if (captureAccepted) {
                        mergeDocumentMutationResponse(workspace.tabs, writeRequest, saved)
                    } else {
                        null
                    }
                    if (merge != null) {
                        val published = publishSaveMerge(merge, createCommand)
                        if (!published && createCommand != null) {
                            completeCommittedCreateAfterLatestDraftCapture(
                                request = mutation.request,
                                command = createCommand,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportError(failure, "文档已保存到服务器，但本机状态更新失败，请刷新")
                }

                try {
                    // 一个已关闭的 tab 不会撤销已提交的写入；目录/首页仍然收敛。
                    refreshDirectory(saved, current.parentId)
                    workspace.refreshHomeProjection()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportError(failure, "文档已保存，但目录刷新失败，请重试刷新")
                }
            } finally {
                pendingMutations.end(mutation)
            }
        }
        attempt?.let { activeAttempt ->
            job.invokeOnCompletion { failure ->
                if (failure is CancellationException) activeAttempt.cancel() else activeAttempt.fail()
            }
        }
    }

    /**
     * 一个无投影/过期的创建确认已经是已提交的远程命令。在旋转 creating 恢复身份之前，
     * 同步把最新组合的编辑器帧拉入 feature 状态。一次未确认的捕获让 creating tab
     * 和 outbox 都保持不动，这样之后的一次确切重放可以重试本地身份转换而不丢失用户输入。
     */
    private suspend fun completeCommittedCreateAfterLatestDraftCapture(
        request: DocumentTabRequest,
        command: PendingDocumentCreateCommand,
    ): Boolean {
        val liveTab = workspace.tabs.firstOrNull(request::targets)
        if (liveTab != null && workspace.captureLatestActiveDraft(liveTab) == null) return false
        return try {
            workspace.completeCommittedDocumentCreateWithoutPublication(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportError(
                failure,
                "文档已在服务器创建，但本机创建待办收尾失败；本地草稿仍保留",
            )
            false
        }
    }

    private suspend fun prepareTitleForContentWrite(
        tab: DocumentTabState,
        request: DocumentTabRequest,
        canonicalTitle: String,
        draftAlreadyFlushed: Boolean,
        beforeRenameFailure: String,
        afterRenameFailure: String,
    ): TitlePreparation {
        if (canonicalTitle == tab.savedTitle) {
            return TitlePreparation.Ready(tab, request, renamed = false)
        }
        if (!draftAlreadyFlushed) {
            check(workspace.persistDraftSnapshot() && workspace.draftCollaboration.flush()) { beforeRenameFailure }
        }
        val moved = when (val result = workspace.moveActions.renameBeforeContentSave(tab, canonicalTitle)) {
            DocumentRenameBeforeSaveResult.Pending -> return TitlePreparation.Queued
            DocumentRenameBeforeSaveResult.NotAdmitted,
            DocumentRenameBeforeSaveResult.Superseded,
            DocumentRenameBeforeSaveResult.LocalPersistenceRejected,
            -> return TitlePreparation.Stopped
            is DocumentRenameBeforeSaveResult.Acknowledged -> result.projection
        }
        val renamed = tab.copy(
            parentId = moved.node.parentId,
            ancestorIds = moved.ancestorIds,
            pathResolved = true,
            savedTitle = moved.node.name,
            revision = moved.node.revision,
        )
        val renamedRequest = DocumentTabRequest.capture(
            renamed,
            editGeneration = request.editGeneration,
        )
        if (!workspace.persistDraftSnapshot() || !workspace.draftCollaboration.flush()) {
            throw TitlePreparationPersistenceException(afterRenameFailure)
        }
        return TitlePreparation.Ready(renamed, renamedRequest, renamed = true)
    }

    private fun stageRevisionRestoreDraft(
        tab: DocumentTabState,
        preview: DocumentRevision,
    ): DocumentTabState? {
        workspace.updateDraft(
            DocumentDraftUpdate(
                tabId = tab.tabId,
                instanceId = tab.instanceId,
                revision = tab.revision,
                title = preview.title,
                markdown = preview.markdown,
                assets = preview.assets,
            ),
        )
        return workspace.tabs.firstOrNull { it.instanceId == tab.instanceId }
    }

    private suspend fun publishSaveMerge(
        merge: DocumentTabMerge,
        completedCreate: PendingDocumentCreateCommand?,
    ): Boolean = workspace.applyDocumentMergeAfterDurableCleanup(
        merge = merge,
        completedCreate = completedCreate,
        cleanupFailureMessage = "文档已保存到服务器，但本机草稿收尾失败；本地草稿仍保留",
    ) { mergedTab ->
        workspace.selectedParentNodeId = mergedTab.parentId
        workspace.closeHistory()
    }

    private suspend fun publishRevisionRestoreMerge(
        merge: DocumentTabMerge,
        target: DocumentRequestTarget,
    ): Boolean = workspace.applyDocumentMergeAfterDurableCleanup(
        merge = merge,
        completedCreate = null,
        cleanupFailureMessage = "版本已恢复到服务器，但本机草稿收尾失败；本地草稿仍保留",
    ) { mergedTab ->
        workspace.selectedParentNodeId = mergedTab.parentId
        // 只有仍然活动的目标才能刷新它的历史面板。
        workspace.historyActions.refreshIfShowing(target)
    }

    /** 在任何持久化挂起前使旧目录失效，避免旧分支响应重新发布已移动的节点。 */
    private fun prepareDirectoryRefresh(saved: Document, previousParentId: String?) {
        if (workspace.selectedSpaceId == saved.spaceId) {
            workspace.navigationActions.prepareDocumentRefreshBranches(
                document = saved,
                previousParentIds = setOf(previousParentId),
            )
        }
    }

    private suspend fun refreshDirectory(saved: Document, previousParentId: String?) {
        if (workspace.selectedSpaceId == saved.spaceId) {
            workspace.navigationActions.refreshDocumentBranches(
                document = saved,
                previousParentIds = setOf(previousParentId),
            )
            if (workspace.selectedSpaceId == saved.spaceId) saved.parentId?.let { parentId ->
                workspace.expandedNodeIds = workspace.expandedNodeIds + parentId
            }
        }
    }

    private fun beginMutation(
        tab: DocumentTabState,
        editGeneration: Long? = null,
    ): DocumentPendingMutationTracker.Ticket? {
        // 保存收尾或丢弃正在切换恢复身份时，不对旧身份再发起写入。
        if (tab.draftRecoveryKey() in workspace.transitioningDraftRecoveryKeys) return null
        return pendingMutations.begin(tab, editGeneration)
    }

    private fun requestStillTargetsOpenTab(request: DocumentTabRequest): Boolean =
        workspace.tabs.any(request::targets)
}
