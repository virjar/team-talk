package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun DocumentTabState.blocksDocumentDestructiveCommand(): Boolean = dirty || creating

internal fun firstUnsavedDocumentTabInSpace(
    tabs: List<DocumentTabState>,
    spaceId: String,
): DocumentTabState? = tabs.firstOrNull {
    it.spaceId == spaceId && it.blocksDocumentDestructiveCommand()
}

/** 持久化的归档/删除工作流，与导航和编辑器渲染状态隔离。 */
internal fun DocumentWorkspaceFeature.archiveSelectedSpaceDurably() {
    // 在协程派发之前捕获；一次快速的 A → B 切换绝不能把命令重新指向别处。
    val spaceId = selectedSpaceId ?: return
    destructiveOutbox.pendingArchives().firstOrNull { it.spaceId == spaceId }?.let { pending ->
        retiringSpaceIds += spaceId
        startArchiveIntent(pending)
        return
    }
    if (spaceId in retiringSpaceIds) return
    activeTab?.takeIf { it.spaceId == spaceId }?.let { active ->
        if (captureLatestActiveDraft(active) == null) return
    }
    if (firstUnsavedDocumentTabInSpace(tabs, spaceId) != null) {
        reportError(
            IllegalStateException("Document space contains unsaved local drafts"),
            "请先保存或关闭该空间内的未保存文档，再归档空间",
        )
        return
    }
    if (destructiveOutbox.pendingLeafDeletes().any { it.spaceId == spaceId }) {
        reportError(
            IllegalStateException("Document delete is still pending in the target space"),
            "该空间仍有删除结果待确认，请联网重试后再归档",
        )
        return
    }
    if (!destructiveOutbox.canAcquireArchive(spaceId) ||
        !hasDocumentDraftRecoveryCapacity(1)
    ) {
        reportDocumentDraftCapacityReached()
        return
    }
    val intent = try {
        destructiveOutbox.acquireArchive(spaceId)
    } catch (failure: Exception) {
        reportError(failure, "归档文档空间请求无法安全加入本机待办")
        return
    }
    retiringSpaceIds += spaceId
    if (!persistDraftSnapshot()) {
        destructiveOutbox.cancel(intent)
        retiringSpaceIds -= spaceId
        persistDraftSnapshot()
        return
    }
    startArchiveIntent(intent)
}

internal fun DocumentWorkspaceFeature.startArchiveIntent(intent: PendingDocumentSpaceArchiveIntent) {
    if (!destructiveOutbox.contains(intent) ||
        !destructiveOperationsInFlight.add(intent.operationId)
    ) return
    retiringSpaceIds += intent.spaceId
    scope.launch {
        try {
            if (!draftCollaboration.flush()) {
                val cancelled = cancelDestructiveIntentDurably(intent)
                if (cancelled) retiringSpaceIds -= intent.spaceId
                reportError(
                    IllegalStateException("Archive intent durability barrier failed"),
                    if (cancelled) {
                        "归档请求未发送：本机待办无法安全持久保存"
                    } else {
                        "归档请求未发送，本机待办收尾失败；空间暂时保持只读"
                    },
                )
                return@launch
            }
            if (!destructiveOutbox.contains(intent)) return@launch
            try {
                repositoryBoundary.call(
                    spaceId = intent.spaceId,
                    notFoundRetiresSpace = true,
                ) {
                    archiveSpace(
                        spaceId = intent.spaceId,
                        operationId = intent.operationId,
                    ).getOrThrow()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val rejected = failure.isDefinitiveDocumentDestructiveRejection()
                val cancelled = rejected && cancelDestructiveIntentDurably(intent)
                if (cancelled) retiringSpaceIds -= intent.spaceId
                val projectionRemovalReported = cancelled && failure is AppError.Business &&
                    failure.code in setOf(403, 404)
                if (!projectionRemovalReported) {
                    reportError(
                        failure,
                        when {
                            cancelled -> "归档文档空间失败"
                            rejected -> "服务器拒绝归档，但本机待办收尾失败；空间暂时保持只读"
                            else -> "归档结果暂时无法确认，已保留本机待办并将在联网后重试"
                        },
                    )
                }
                return@launch
            }

            if (!completeArchivedSpaceLocally(intent)) {
                reportError(
                    IllegalStateException("Archived space cleanup is not durable"),
                    "空间已在服务器归档，但本机草稿收尾失败；空间暂时保持只读",
                )
                return@launch
            }
            retiringSpaceIds -= intent.spaceId
            try {
                refreshHomeProjection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportError(failure, "空间已归档，但文档首页刷新失败，请重试刷新")
            }
        } finally {
            destructiveOperationsInFlight.remove(intent.operationId)
        }
    }
}

internal suspend fun DocumentWorkspaceFeature.completeArchivedSpaceLocally(
    intent: PendingDocumentSpaceArchiveIntent,
): Boolean {
    if (!destructiveOutbox.contains(intent)) return true
    val discardedTabs = tabs.filter { it.spaceId == intent.spaceId }
    val discardedDocumentCommands = createOutbox.pendingDocuments()
        .filter { it.spaceId == intent.spaceId }
    val discardedSpaceCommands = createOutbox.pendingSpaces()
        .filter { it.spaceId == intent.spaceId }
    val discardedDestructiveIntents = destructiveOutbox.pending()
        .filter { it.spaceId == intent.spaceId }
    val recoveryKeys = buildSet {
        // 每一个打开的实例都被守护并退役，包括当前干净的 tab。
        discardedTabs.forEach { add(it.draftRecoveryKey()) }
        discardedDocumentCommands.forEach { add(it.draftRecoveryKey()) }
        discardedSpaceCommands.forEach { add(it.draftRecoveryKey()) }
        discardedDestructiveIntents.forEach { add(it.draftRecoveryKey()) }
    }
    if (recoveryKeys.any(transitioningDraftRecoveryKeys::contains)) return false
    transitioningDraftRecoveryKeys += recoveryKeys
    var recoveryRetired = false
    return try {
        if (!draftCollaboration.tombstone(recoveryKeys)) return false
        recoveryRetired = true
        spaces = spaces.filterNot { it.spaceId == intent.spaceId }
        offlineDraftSpaceIds = offlineDraftSpaceIds - intent.spaceId
        tabs = tabs.filterNot { it.spaceId == intent.spaceId }
        createOutbox.discardDocumentsInSpace(intent.spaceId)
        discardedSpaceCommands.forEach(createOutbox::completeSpace)
        discardedDestructiveIntents.forEach(destructiveOutbox::complete)
        discardedDestructiveIntents.filterIsInstance<PendingDocumentLeafDeleteIntent>()
            .forEach { deletingDocumentIds -= it.documentId }
        publishPendingSpaceCreates()
        if (activeTabId !in tabs.map { it.tabId }) {
            activeTabId = tabs.lastOrNull()?.tabId
        }
        if (selectedSpaceId == intent.spaceId) {
            navigationActions.clearSpaceSelection()
        } else {
            persistDraftSnapshot()
        }
        true
    } finally {
        transitioningDraftRecoveryKeys -= recoveryKeys
        if (recoveryRetired) {
            recoveryKeys.forEach(deferredDraftUpdates::remove)
        } else {
            replayDeferredDraftUpdates(recoveryKeys)
        }
    }
}

internal fun DocumentWorkspaceFeature.deleteActiveDurably() {
    // 在派发之前捕获；切换到另一个 tab 绝不能把这次删除重新指向别处。
    val initial = activeTab ?: return
    initial.documentId?.let { documentId ->
        destructiveOutbox.pendingLeafDeletes().firstOrNull { pending ->
            pending.spaceId == initial.spaceId && pending.documentId == documentId
        }?.let { pending ->
            deletingDocumentIds += pending.documentId
            startDeleteIntent(pending)
            return
        }
    }
    val current = captureLatestActiveDraft(initial) ?: return
    if (current.blocksDocumentDestructiveCommand()) {
        reportError(
            IllegalStateException("Document has unsaved local edits"),
            "请先保存或关闭未保存内容，再删除文档",
        )
        return
    }
    if (isTerminallyReadOnly(current) || saveCoordinator.hasPending(current) ||
        moveActions.isMoving(current.instanceId) ||
        current.draftRecoveryKey() in transitioningDraftRecoveryKeys
    ) return
    val request = DocumentDeleteRequest.capture(current)
    if (request == null) {
        closeTabByInstance(current.instanceId)
        return
    }
    if (!destructiveOutbox.canAcquireDeleteLeaf(request.spaceId, request.documentId) ||
        !hasDocumentDraftRecoveryCapacity(1)
    ) {
        reportDocumentDraftCapacityReached()
        return
    }
    val intent = try {
        destructiveOutbox.acquireDeleteLeaf(
            spaceId = request.spaceId,
            documentId = request.documentId,
            parentId = request.parentId,
            expectedRevision = request.revision,
        )
    } catch (failure: Exception) {
        reportError(failure, "删除文档请求无法安全加入本机待办")
        return
    }
    deletingDocumentIds += request.documentId
    if (!persistDraftSnapshot()) {
        destructiveOutbox.cancel(intent)
        deletingDocumentIds -= request.documentId
        persistDraftSnapshot()
        return
    }
    navigationActions.invalidateBranch(request.spaceId, request.parentId)
    navigationActions.invalidateBranch(request.spaceId, request.documentId)
    startDeleteIntent(intent)
}

internal fun DocumentWorkspaceFeature.startDeleteIntent(intent: PendingDocumentLeafDeleteIntent) {
    if (!destructiveOutbox.contains(intent) ||
        !destructiveOperationsInFlight.add(intent.operationId)
    ) return
    deletingDocumentIds += intent.documentId
    scope.launch {
        try {
            if (!draftCollaboration.flush()) {
                val cancelled = cancelDestructiveIntentDurably(intent)
                if (cancelled) deletingDocumentIds -= intent.documentId
                reportError(
                    IllegalStateException("Delete intent durability barrier failed"),
                    if (cancelled) {
                        "删除请求未发送：本机待办无法安全持久保存"
                    } else {
                        "删除请求未发送，本机待办收尾失败；文档暂时保持只读"
                    },
                )
                return@launch
            }
            if (!destructiveOutbox.contains(intent)) return@launch
            try {
                repositoryBoundary.call(
                    spaceId = intent.spaceId,
                ) {
                    deleteNode(
                        spaceId = intent.spaceId,
                        nodeId = intent.documentId,
                        expectedRevision = intent.expectedRevision,
                        operationId = intent.operationId,
                    ).getOrThrow()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val rejected = failure.isDefinitiveDocumentDestructiveRejection()
                val cancelled = rejected && cancelDestructiveIntentDurably(intent)
                if (cancelled) deletingDocumentIds -= intent.documentId
                val projectionRemovalReported = cancelled && failure is AppError.Business &&
                    failure.code == 403
                if (!projectionRemovalReported) {
                    reportError(
                        failure,
                        when {
                            cancelled && failure is AppError.Business &&
                                failure.code == DOCUMENT_REVISION_CONFLICT_STATUS ->
                                "文档已被其他成员修改或移动，请刷新后重试"
                            cancelled -> "删除文档失败"
                            rejected -> "服务器拒绝删除，但本机待办收尾失败；文档暂时保持只读"
                            else -> "删除结果暂时无法确认，已保留本机待办并将在联网后重试"
                        },
                    )
                }
                return@launch
            }
            if (!completeDeletedDocumentLocally(intent)) {
                reportError(
                    IllegalStateException("Deleted document cleanup is not durable"),
                    "文档已在服务器删除，但本机草稿收尾失败；文档暂时保持只读",
                )
                return@launch
            }
            deletingDocumentIds -= intent.documentId
            refreshAfterDeletedDocument(intent)
        } finally {
            destructiveOperationsInFlight.remove(intent.operationId)
        }
    }
}

internal suspend fun DocumentWorkspaceFeature.completeDeletedDocumentLocally(
    intent: PendingDocumentLeafDeleteIntent,
): Boolean {
    if (!destructiveOutbox.contains(intent)) return true
    // 原始实例可能在 RPC 进行期间被关闭并重新打开。
    val invalidatedTabs = tabs.filter { tab ->
        tab.spaceId == intent.spaceId && tab.documentId == intent.documentId
    }
    val discardedCommands = createOutbox.pendingDocuments()
        .filter { command -> invalidatedTabs.any(command::matches) }
    val recoveryKeys = buildSet {
        add(intent.draftRecoveryKey())
        // 一个干净的实例可能在持久化屏障挂起期间变 dirty。
        invalidatedTabs.forEach { add(it.draftRecoveryKey()) }
        discardedCommands.forEach { add(it.draftRecoveryKey()) }
    }
    if (recoveryKeys.any(transitioningDraftRecoveryKeys::contains)) return false
    transitioningDraftRecoveryKeys += recoveryKeys
    var recoveryRetired = false
    return try {
        if (!draftCollaboration.tombstone(recoveryKeys)) return false
        recoveryRetired = true
        invalidatedTabs.forEach { closeTabNow(it, force = true) }
        destructiveOutbox.complete(intent)
        expandedNodeIds = expandedNodeIds - intent.documentId
        treeChildren = removeDeletedDocumentTreeIdentity(
            treeChildren = treeChildren,
            spaceId = intent.spaceId,
            documentId = intent.documentId,
        )
        persistDraftSnapshot()
        true
    } finally {
        transitioningDraftRecoveryKeys -= recoveryKeys
        if (recoveryRetired) {
            recoveryKeys.forEach(deferredDraftUpdates::remove)
        } else {
            replayDeferredDraftUpdates(recoveryKeys)
        }
    }
}

internal suspend fun DocumentWorkspaceFeature.refreshAfterDeletedDocument(
    intent: PendingDocumentLeafDeleteIntent,
) {
    var projectionFailure: Exception? = null
    if (selectedSpaceId == intent.spaceId) {
        try {
            navigationActions.reloadChildren(intent.spaceId, intent.parentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            projectionFailure = failure
        }
    }
    try {
        refreshHomeProjection()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (projectionFailure == null) projectionFailure = failure
    }
    projectionFailure?.let {
        reportError(it, "文档已删除，但目录刷新失败，请重试刷新")
    }
}

internal fun DocumentWorkspaceFeature.replayPendingDestructiveIntents() {
    destructiveOutbox.replay().forEach { intent ->
        when (intent) {
            is PendingDocumentSpaceArchiveIntent -> startArchiveIntent(intent)
            is PendingDocumentLeafDeleteIntent -> startDeleteIntent(intent)
        }
    }
}

/** 只退役操作纪元；草稿身份仍然可用于一次新的 retry。 */
internal suspend fun DocumentWorkspaceFeature.cancelDestructiveIntentDurably(
    intent: DocumentDestructiveIntent,
): Boolean {
    if (!destructiveOutbox.contains(intent)) return true
    val recoveryKey = intent.draftRecoveryKey()
    if (!transitioningDraftRecoveryKeys.add(recoveryKey)) return false
    return try {
        if (!draftCollaboration.tombstone(setOf(recoveryKey))) return false
        destructiveOutbox.cancel(intent)
        // 即使压缩热清单失败，墓碑也是权威的。
        persistDraftSnapshot()
        true
    } finally {
        transitioningDraftRecoveryKeys.remove(recoveryKey)
    }
}

private fun Exception.isDefinitiveDocumentDestructiveRejection(): Boolean =
    this is AppError.Business && code in 400..499
