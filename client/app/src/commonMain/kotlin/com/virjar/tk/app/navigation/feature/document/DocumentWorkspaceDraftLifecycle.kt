package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.shouldReportCacheRefreshFailure

import com.virjar.tk.protocol.model.DocumentSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** 编辑器 tab 退役、恢复和崩溃安全的草稿发布。 */
internal fun DocumentWorkspaceFeature.closeTabDurably(
    tabId: String,
    onResult: (DocumentTabCloseOutcome) -> Unit = {},
) {
    val closing = tabs.firstOrNull { it.tabId == tabId } ?: run {
        onResult(DocumentTabCloseOutcome.CLOSED)
        return
    }
    if (saveCoordinator.hasPending(closing)) {
        onResult(DocumentTabCloseOutcome.BLOCKED_BY_SAVE)
        return
    }
    if (!closing.dirty && !closing.creating) {
        onResult(
            if (closeTabNow(closing)) {
                DocumentTabCloseOutcome.CLOSED
            } else if (saveCoordinator.hasPending(closing)) {
                DocumentTabCloseOutcome.BLOCKED_BY_SAVE
            } else {
                DocumentTabCloseOutcome.PERSISTENCE_FAILED
            },
        )
        return
    }
    val recoveryKeys = buildSet {
        add(closing.draftRecoveryKey())
        createOutbox.pendingDocuments()
            .firstOrNull { it.tabInstanceId == closing.instanceId && it.documentId == closing.tabId }
            ?.let { add(it.draftRecoveryKey()) }
    }
    if (recoveryKeys.any(transitioningDraftRecoveryKeys::contains)) {
        onResult(DocumentTabCloseOutcome.BLOCKED_BY_DISCARD)
        return
    }
    transitioningDraftRecoveryKeys += recoveryKeys
    scope.launch {
        var closed = false
        var persistenceFailed = false
        var cancellation: CancellationException? = null
        try {
            check(draftCollaboration.tombstone(recoveryKeys)) {
                "无法持久保存取消标记，文档草稿仍会保留"
            }
            closed = closeTabNow(closing)
        } catch (cancelled: CancellationException) {
            persistenceFailed = true
            cancellation = cancelled
        } catch (failure: Exception) {
            persistenceFailed = true
            reportError(failure, "关闭文档草稿失败")
        } finally {
            transitioningDraftRecoveryKeys -= recoveryKeys
            replayDeferredDraftUpdates(recoveryKeys)
        }
        // 移动端替换/退出是持久丢弃事务的延续。过早启动它会让 closeTabNow()
        // 在替换已经开始之后推进导航 generation，确定性地撤销用户选择的目的地。
        onResult(
            when {
                closed -> DocumentTabCloseOutcome.CLOSED
                persistenceFailed -> DocumentTabCloseOutcome.PERSISTENCE_FAILED
                tabs.none {
                    it.instanceId == closing.instanceId && it.recoveryId == closing.recoveryId
                } -> DocumentTabCloseOutcome.CLOSED
                saveCoordinator.hasPending(closing) -> DocumentTabCloseOutcome.BLOCKED_BY_SAVE
                else -> DocumentTabCloseOutcome.PERSISTENCE_FAILED
            },
        )
        cancellation?.let { throw it }
    }
}

internal fun DocumentWorkspaceFeature.closeTabNow(
    requestedClosing: DocumentTabState,
    force: Boolean = false,
): Boolean {
    val closing = tabs.firstOrNull {
        it.instanceId == requestedClosing.instanceId && it.recoveryId == requestedClosing.recoveryId
    } ?: return false
    if (!force && saveCoordinator.hasPending(closing)) return false
    createOutbox.discardDocument(closing)
    val remainingTabs = tabs.filterNot { it.instanceId == closing.instanceId }
    tabs = remainingTabs
    revisionConflictActions.dismissStaleConflict()
    if (activeTabId != closing.tabId) {
        persistDraftSnapshot()
        return true
    }

    val generation = navigationActions.beginNavigation()
    val replacement = replacementDocumentTab(remainingTabs, closing.spaceId)
    if (replacement == null) {
        activeTabId = null
        // 关闭最后一页时保留其目录上下文，而不是回到空间根。
        selectedParentNodeId = selectedParentAfterClosingDocumentTab(closing, replacement)
        closeHistory()
        persistDraftSnapshot()
        return true
    }
    val replacementIntent = DocumentTabNavigationIntent.capture(replacement, generation)

    if (selectedSpaceId == replacement.spaceId) {
        activeTabId = replacement.takeIf { it.pathResolved || it.dirty || it.creating }?.tabId
        selectedParentNodeId = selectedParentAfterClosingDocumentTab(closing, replacement)
    } else {
        activeTabId = null
        selectedParentNodeId = null
    }
    closeHistory()
    persistDraftSnapshot()
    scope.launch {
        var directoryFailure: Exception? = null
        try {
            if (selectedSpaceId != replacement.spaceId) {
                try {
                    if (!navigationActions.selectSpaceNow(replacement.spaceId, generation)) return@launch
                } catch (failure: Exception) {
                    failure.rethrowIfDocumentWorkspaceCancelled()
                    replacementIntent.resolveLocalDraftAfterDirectoryFailure(
                        tabs,
                    ) { candidate, spaceId ->
                        navigationActions.isCurrent(candidate, spaceId)
                    }?.let { fallback ->
                        DocumentPathStamp.capture(fallback)?.let(navigationActions::invalidateDocumentPath)
                    } ?: throw failure
                    directoryFailure = failure
                }
            }
            var currentReplacement = replacementIntent.resolve(tabs) { candidate, spaceId ->
                navigationActions.isCurrent(candidate, spaceId)
            } ?: return@launch
            if (!currentReplacement.pathResolved && directoryFailure == null) {
                try {
                    val documentId = currentReplacement.documentId
                        ?: throw IllegalStateException("未保存文档缺少可校验的目录位置")
                    val verified = readGateway.cachedDocument(
                        currentReplacement.spaceId,
                        documentId,
                    ) ?: readGateway.refreshDocument(currentReplacement.spaceId, documentId)
                    val latest = replacementIntent.resolve(tabs) { candidate, spaceId ->
                        navigationActions.isCurrent(candidate, spaceId)
                    } ?: return@launch
                    currentReplacement = refreshRestoredDocumentPath(latest, verified) ?: return@launch
                    navigationActions.prepareDocumentRefreshBranches(
                        document = verified,
                        previousParentIds = setOf(latest.parentId),
                    )
                    val resolvedReplacement = currentReplacement
                    tabs = tabs.map {
                        if (it.instanceId == resolvedReplacement.instanceId) resolvedReplacement else it
                    }
                    currentReplacement = replacementIntent.resolve(tabs) { candidate, spaceId ->
                        navigationActions.isCurrent(candidate, spaceId)
                    } ?: return@launch
                } catch (failure: Exception) {
                    failure.rethrowIfDocumentWorkspaceCancelled()
                    currentReplacement = replacementIntent.resolveLocalDraftAfterDirectoryFailure(
                        tabs,
                    ) { candidate, spaceId ->
                        navigationActions.isCurrent(candidate, spaceId)
                    } ?: throw failure
                    directoryFailure = failure
                }
            }
            activeTabId = currentReplacement.tabId
            selectedParentNodeId = currentReplacement.resolvedParentIdForNavigation()
            persistDraftSnapshot()
            DocumentPathStamp.capture(currentReplacement)?.let { stamp ->
                val cachedPathRevealed = navigationActions.revealCachedDocumentSpine(stamp, generation)
                try {
                    navigationActions.refreshAndRevealDocumentSpine(stamp, generation)
                } catch (failure: Exception) {
                    failure.rethrowIfDocumentWorkspaceCancelled()
                    val stillCurrent = replacementIntent.resolve(tabs) { candidate, spaceId ->
                        navigationActions.isCurrent(candidate, spaceId)
                    }
                    if (stillCurrent != null) {
                        if (!cachedPathRevealed) navigationActions.invalidateDocumentPath(stamp)
                        if (directoryFailure == null &&
                            shouldReportCacheRefreshFailure(failure, cachedPathRevealed)
                        ) {
                            directoryFailure = failure
                        }
                    }
                }
            }
            val settledReplacement = replacementIntent.resolve(tabs) { candidate, spaceId ->
                navigationActions.isCurrent(candidate, spaceId)
            } ?: return@launch
            activeTabId = settledReplacement.tabId
            selectedParentNodeId = settledReplacement.resolvedParentIdForNavigation()
            persistDraftSnapshot()
            directoryFailure?.let {
                reportError(it, "文档目录离线，已打开本机未保存草稿")
            }
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            if (navigationActions.isCurrent(generation)) reportError(failure, "恢复文档标签失败")
        }
    }
    return true
}

/** 重新水合 dirty 本地正文；ACL 和路径仍然来自新的服务器元数据。 */
internal suspend fun DocumentWorkspaceFeature.restoreDraftWorkspace(
    rawSnapshot: DocumentWorkspaceDraftSnapshot,
    generation: Long,
    onRecoveryError: ((Throwable, String) -> Unit)? = null,
) {
    val snapshot = rawSnapshot.normalized() ?: return
    if (!navigationActions.isCurrent(generation)) return

    val targetSpaceId = snapshot.selectedSpaceId
    if (targetSpaceId == null || spaces.none { it.spaceId == targetSpaceId }) {
        activeTabId = null
        selectedSpaceId = null
        selectedParentNodeId = null
        treeChildren = emptyMap()
        expandedNodeIds = emptySet()
        persistDraftSnapshot()
        return
    }

    selectedSpaceId = targetSpaceId
    treeChildren = emptyMap()
    expandedNodeIds = emptySet()
    grants = emptyList()
    closeHistory()

    var bodyValidationFailure: Exception? = null
    var active = snapshot.activeTabInstanceId?.let { instanceId ->
        tabs.firstOrNull { it.instanceId == instanceId && it.spaceId == targetSpaceId }
    }
    val unresolvedActive = active
    if (unresolvedActive != null && !unresolvedActive.pathResolved) {
        val documentId = unresolvedActive.documentId
        if (documentId != null) {
            when (val body = loadRestoredDocumentBody(
                ownerIsCurrent = {
                    navigationActions.isCurrent(generation, targetSpaceId)
                },
                readCached = {
                    readGateway.cachedDocument(targetSpaceId, documentId)
                },
                refresh = {
                    readGateway.refreshDocument(targetSpaceId, documentId)
                },
            )) {
                RestoredDocumentBodyLoad.Superseded -> return
                is RestoredDocumentBodyLoad.Failed -> {
                    bodyValidationFailure = body.failure
                }
                is RestoredDocumentBodyLoad.Available -> {
                    if (!navigationActions.isCurrent(generation, targetSpaceId)) return
                    val current = tabs.firstOrNull {
                        it.instanceId == unresolvedActive.instanceId &&
                            it.spaceId == targetSpaceId
                    }
                    val refreshed = current?.let {
                        refreshRestoredDocumentPath(it, body.document)
                    }
                    if (refreshed != null) {
                        if (!navigationActions.isCurrent(generation, targetSpaceId)) return
                        tabs = tabs.map {
                            if (it.instanceId == refreshed.instanceId) refreshed else it
                        }
                        active = refreshed
                        persistDraftSnapshot()
                    }
                }
            }
        }
    }
    if (!navigationActions.isCurrent(generation, targetSpaceId)) return

    val activeIntent = active?.let { DocumentTabNavigationIntent.capture(it, generation) }
    activeTabId = active?.tabId
    selectedParentNodeId = active?.resolvedParentIdForNavigation()
    var directoryFailure: Exception? = null
    val activeStamp = activeIntent?.resolve(tabs) { candidate, spaceId ->
        navigationActions.isCurrent(candidate, spaceId)
    }?.let(DocumentPathStamp::capture)
    val cachedPathRevealed = if (activeStamp != null) {
        navigationActions.revealCachedDocumentSpine(activeStamp, generation)
    } else {
        val activeDocumentId = active?.documentId
        if (activeIntent != null && activeDocumentId != null) {
            navigationActions.revealCachedRestoredDocumentSpine(activeIntent, activeDocumentId)
        } else {
            false
        }
    }
    if (!navigationActions.isCurrent(generation, targetSpaceId)) return

    // 根完整性和活动路径恢复是独立的有界读取。一次根 miss 绝不能压制
    // 一条缓存或可远程恢复的草稿脊柱，而且这两条路径都不会遍历深度。
    try {
        navigationActions.loadTreeRoot(generation)
    } catch (failure: Exception) {
        failure.rethrowIfDocumentWorkspaceCancelled()
        directoryFailure = failure
    }
    if (!navigationActions.isCurrent(generation, targetSpaceId)) return

    if (activeStamp != null) {
        try {
            navigationActions.refreshAndRevealDocumentSpine(activeStamp, generation)
        } catch (failure: Exception) {
            failure.rethrowIfDocumentWorkspaceCancelled()
            val stillCurrent = activeIntent.resolve(tabs) { candidate, spaceId ->
                navigationActions.isCurrent(candidate, spaceId)
            }
            if (stillCurrent != null) {
                if (!cachedPathRevealed) navigationActions.invalidateDocumentPath(activeStamp)
                if (directoryFailure == null &&
                    shouldReportCacheRefreshFailure(failure, cachedPathRevealed)
                ) {
                    directoryFailure = failure
                }
            }
        }
    }
    if (!navigationActions.isCurrent(generation, targetSpaceId)) return

    val settledActive = activeIntent?.resolve(tabs) { candidate, spaceId ->
        navigationActions.isCurrent(candidate, spaceId)
    }
    activeTabId = settledActive?.tabId
    selectedParentNodeId = settledActive?.resolvedParentIdForNavigation()
    persistDraftSnapshot()
    val publishRecoveryError = onRecoveryError ?: reportError
    when {
        bodyValidationFailure != null -> publishRecoveryError(
            bodyValidationFailure,
            "文档正文暂时无法校验，已恢复本机目录和草稿",
        )
        directoryFailure != null -> publishRecoveryError(
            directoryFailure,
            "恢复文档目录失败，草稿已保留",
        )
    }
}

/** 让持久化的 dirty tab 变得可编辑，而不假装目录或干净文档存在。 */
internal fun DocumentWorkspaceFeature.publishOfflineDraftWorkspace(
    snapshot: DocumentWorkspaceDraftSnapshot,
) {
    val publication = offlineDraftPublication(snapshot)
    tabs = publication.tabs
    draftCollaboration.trackRestoredTabs(tabs)
    spaces = publication.spaces
    offlineDraftSpaceIds = publication.spaces.map { it.spaceId }.toSet()
    selectedSpaceId = publication.selectedSpaceId
    activeTabId = publication.activeTabId
    // 目录选择是服务器投影，不是可恢复的本地草稿状态。
    selectedParentNodeId = null
    treeChildren = emptyMap()
    expandedNodeIds = emptySet()
    grants = emptyList()
    closeHistory()
}

internal suspend fun DocumentWorkspaceFeature.ensureDraftRestorationApplied() =
    draftRestorationLock.withLock {
        if (draftRestorationApplied) return@withLock
        val restoration = draftCollaboration.loadInitialRestoration()?.normalized()
        createOutbox.restore(
            spaces = restoration?.pendingSpaceCreates.orEmpty(),
            documents = restoration?.pendingDocumentCreates.orEmpty(),
        )
        check(destructiveOutbox.restore(restoration?.pendingDestructiveIntents.orEmpty())) {
            "本机文档破坏性操作待办超过安全上限"
        }
        destructiveOutbox.pendingArchives().forEach { pending ->
            beginSpaceRetirement(pending.spaceId)
        }
        deletingDocumentIds += destructiveOutbox.pendingLeafDeletes().map { it.documentId }
        publishPendingSpaceCreates()
        restoration?.takeIf { it.tabs.isNotEmpty() }?.let(::publishOfflineDraftWorkspace)
        remoteDraftEnrichmentPending = restoration?.tabs?.isNotEmpty() == true
        draftCollaboration.restorationApplied()
        draftRestorationApplied = true
    }

internal fun DocumentWorkspaceFeature.liveDraftSnapshot(): DocumentWorkspaceDraftSnapshot? =
    DocumentWorkspaceDraftSnapshot(
        tabs = tabs,
        activeTabInstanceId = activeTab?.instanceId,
        selectedSpaceId = selectedSpaceId,
        pendingSpaceCreates = createOutbox.pendingSpaces(),
        pendingDocumentCreates = createOutbox.pendingDocuments(),
        pendingDestructiveIntents = destructiveOutbox.pending(),
    ).normalized()

internal fun DocumentWorkspaceFeature.persistDraftSnapshot(
    snapshotTabs: List<DocumentTabState> = tabs,
    snapshotActiveTabId: String? = activeTabId,
    snapshotSelectedSpaceId: String? = selectedSpaceId,
): Boolean {
    val admitted = draftCollaboration.save(
        tabs = snapshotTabs,
        activeTabId = snapshotActiveTabId,
        selectedSpaceId = snapshotSelectedSpaceId,
        pendingSpaceCreates = createOutbox.pendingSpaces(),
        pendingDocumentCreates = createOutbox.pendingDocuments(),
        pendingDestructiveIntents = destructiveOutbox.pending(),
    )
    if (!admitted && !draftPersistenceAdmissionFailureReported) {
        reportError(
            IllegalStateException("Document draft snapshot was not admitted"),
            "本机草稿暂时无法安全持久保存；请减少未保存文档或稍后重试",
        )
    }
    draftPersistenceAdmissionFailureReported = !admitted
    return admitted
}

/** 只有在每一个被取代的恢复身份都持久之后，才发布保存响应。 */
internal suspend fun DocumentWorkspaceFeature.applyDocumentMergeAfterDurableCleanup(
    merge: DocumentTabMerge,
    completedCreate: PendingDocumentCreateCommand?,
    cleanupFailureMessage: String,
    onActiveMerged: (DocumentTabState) -> Unit,
): Boolean {
    val tabRecoveryKey = merge.tab.draftRecoveryKey()
    val requiresCreateTransitionBarrier = completedCreate != null && merge.tab.dirty
    val tombstoneKeys = buildSet {
        if (!requiresCreateTransitionBarrier) completedCreate?.let { add(it.draftRecoveryKey()) }
        if (!merge.tab.dirty && !merge.tab.creating) add(merge.tab.draftRecoveryKey())
    }
    val guardedKeys = buildSet {
        addAll(tombstoneKeys)
        add(merge.tab.draftRecoveryKey())
        completedCreate?.let { add(it.draftRecoveryKey()) }
    }
    if (guardedKeys.any(transitioningDraftRecoveryKeys::contains)) return false
    transitioningDraftRecoveryKeys += guardedKeys
    var tabIdentityTombstoned = false
    var mergedPublished = false
    return try {
        val cleanupReady = prepareDurableDraftCleanup(
            merge = merge,
            completedCreate = completedCreate,
            requiresCreateTransitionBarrier = requiresCreateTransitionBarrier,
            tombstoneKeys = tombstoneKeys,
            cleanupFailureMessage = cleanupFailureMessage,
            onTabIdentityTombstoned = { tabIdentityTombstoned = it },
        )
        if (!cleanupReady) {
            false
        } else {
            val deferredUpdate = deferredDraftUpdates.remove(tabRecoveryKey)
            var latestDeferredFrame = deferredUpdate
            var latestMerge = mergeDocumentMutationAfterDurableCleanup(
                latestTabs = tabs,
                merge = merge,
                deferredUpdate = deferredUpdate,
                rotateRecoveryIdentity = tabIdentityTombstoned,
            ) ?: run {
                deferredUpdate?.let { deferredDraftUpdates[tabRecoveryKey] = it }
                return false
            }
            val finalFrameNeedsDurability = latestDeferredFrame != null ||
                (tabIdentityTombstoned && latestMerge.tab.dirty)
            if (finalFrameNeedsDurability) {
                while (true) {
                    val admitted = draftCollaboration.save(
                        tabs = latestMerge.tabs,
                        activeTabId = activeTabId,
                        selectedSpaceId = selectedSpaceId,
                        pendingSpaceCreates = createOutbox.pendingSpaces(),
                        pendingDocumentCreates = createOutbox.pendingDocuments(),
                        pendingDestructiveIntents = destructiveOutbox.pending(),
                    )
                    if (!admitted || !draftCollaboration.flush()) {
                        latestDeferredFrame?.let { deferredDraftUpdates[tabRecoveryKey] = it }
                        reportError(IllegalStateException(cleanupFailureMessage), cleanupFailureMessage)
                        return false
                    }
                    val newerFrame = deferredDraftUpdates.remove(tabRecoveryKey) ?: break
                    latestDeferredFrame = newerFrame
                    latestMerge = rebaseDeferredDocumentDraftUpdate(latestMerge, newerFrame)
                        ?: run {
                            deferredDraftUpdates[tabRecoveryKey] = newerFrame
                            return false
                        }
                }
            }
            completedCreate?.let(createOutbox::completeDocument)
            val shouldRemainActive = activeTabId == merge.requestTabId
            tabs = latestMerge.tabs
            if (shouldRemainActive) {
                activeTabId = latestMerge.tab.tabId
                onActiveMerged(latestMerge.tab)
            }
            persistDraftSnapshot()
            mergedPublished = true
            true
        }
    } finally {
        transitioningDraftRecoveryKeys -= guardedKeys
        if (!mergedPublished) {
            compensateUnpublishedDocumentMerge(
                merge = merge,
                cleanupFailureMessage = cleanupFailureMessage,
                tabIdentityTombstoned = tabIdentityTombstoned,
                tabRecoveryKey = tabRecoveryKey,
            )
        }
    }
}

/**
 * 持久清理的第一阶段：需要 create 过渡屏障时先把过渡帧保存落盘，否则以墓碑覆盖被取代的
 * 恢复身份。失败时报告 [cleanupFailureMessage] 并返回 false；成功且标签身份被墓碑覆盖时
 * 经 [onTabIdentityTombstoned] 回告调用方。
 */
private suspend fun DocumentWorkspaceFeature.prepareDurableDraftCleanup(
    merge: DocumentTabMerge,
    completedCreate: PendingDocumentCreateCommand?,
    requiresCreateTransitionBarrier: Boolean,
    tombstoneKeys: Set<String>,
    cleanupFailureMessage: String,
    onTabIdentityTombstoned: (Boolean) -> Unit,
): Boolean {
    val tabRecoveryKey = merge.tab.draftRecoveryKey()
    return if (requiresCreateTransitionBarrier) {
        val prospective = mergeDocumentMutationAfterDurableCleanup(
            latestTabs = tabs,
            merge = merge,
            deferredUpdate = null,
            rotateRecoveryIdentity = false,
        )
        val admitted = prospective != null && draftCollaboration.save(
            tabs = prospective.tabs,
            activeTabId = activeTabId,
            selectedSpaceId = selectedSpaceId,
            pendingSpaceCreates = createOutbox.pendingSpaces(),
            pendingDocumentCreates = createOutbox.pendingDocuments(),
            pendingDestructiveIntents = destructiveOutbox.pending(),
        )
        if (!admitted || !draftCollaboration.flush()) {
            reportError(IllegalStateException(cleanupFailureMessage), cleanupFailureMessage)
            false
        } else {
            true
        }
    } else if (tombstoneKeys.isNotEmpty()) {
        if (!draftCollaboration.tombstone(tombstoneKeys)) {
            reportError(IllegalStateException(cleanupFailureMessage), cleanupFailureMessage)
            false
        } else {
            onTabIdentityTombstoned(tabRecoveryKey in tombstoneKeys)
            true
        }
    } else {
        true
    }
}

/**
 * 合并未发布时的补偿：墓碑过的标签身份轮换新恢复身份，迟到的 deferred 帧按当前修订
 * 重新入队；身份已轮换且替换标签仍脏时补一次持久化快照与 flush。
 */
private suspend fun DocumentWorkspaceFeature.compensateUnpublishedDocumentMerge(
    merge: DocumentTabMerge,
    cleanupFailureMessage: String,
    tabIdentityTombstoned: Boolean,
    tabRecoveryKey: String,
) {
    if (tabIdentityTombstoned) {
        tabs = rotateDocumentTabRecoveryIdentity(
            tabs = tabs,
            request = merge.request,
            retiredRecoveryId = merge.tab.recoveryId,
        )
    }
    deferredDraftUpdates.remove(tabRecoveryKey)?.let { deferred ->
        val current = tabs.firstOrNull(merge.request::targets)
        updateDraft(deferred.copy(revision = current?.revision ?: deferred.revision))
    }
    if (tabIdentityTombstoned) {
        val admitted = persistDraftSnapshot()
        val replacement = tabs.firstOrNull(merge.request::targets)
        if (replacement?.let { it.dirty || it.creating } == true &&
            (!admitted || !draftCollaboration.flush())
        ) {
            reportError(IllegalStateException(cleanupFailureMessage), cleanupFailureMessage)
        }
    }
}

internal fun DocumentWorkspaceFeature.hasDocumentDraftRecoveryCapacity(
    additionalIdentities: Int,
): Boolean {
    val activeKeys = buildSet {
        tabs.asSequence().filter { it.dirty || it.creating }
            .forEach { add(it.draftRecoveryKey()) }
        createOutbox.pendingSpaces().forEach { add(it.draftRecoveryKey()) }
        createOutbox.pendingDocuments().forEach { add(it.draftRecoveryKey()) }
        destructiveOutbox.pending().forEach { add(it.draftRecoveryKey()) }
    }
    return activeKeys.size + additionalIdentities <= MAX_DOCUMENT_DRAFT_RECORDS
}

internal fun DocumentWorkspaceFeature.reportDocumentDraftCapacityReached() {
    reportError(
        IllegalStateException("Document draft recovery identity limit reached"),
        "本机未保存文档过多，请先保存或放弃部分草稿",
    )
}

/** 只在它确切的编辑器 tab 仍然存活时，重放最新的完整帧。 */
internal fun DocumentWorkspaceFeature.replayDeferredDraftUpdates(recoveryKeys: Set<String>) {
    recoveryKeys.mapNotNull(deferredDraftUpdates::remove).forEach { update ->
        val current = tabs.firstOrNull { tab ->
            tab.tabId == update.tabId && tab.instanceId == update.instanceId
        } ?: return@forEach
        updateDraft(update.copy(revision = current.revision))
    }
}

internal suspend fun DocumentWorkspaceFeature.reconcilePendingSpaceCreates(
    loadedSpaces: List<DocumentSpace>,
) {
    val committedSpaceIds = loadedSpaces.asSequence().map(DocumentSpace::spaceId).toHashSet()
    val committed = createOutbox.pendingSpaces().filter { it.spaceId in committedSpaceIds }
    if (committed.isEmpty()) return
    val recoveryKeys = committed.mapTo(linkedSetOf(), DocumentSpaceCreateRequest::draftRecoveryKey)
    if (recoveryKeys.any(transitioningDraftRecoveryKeys::contains)) return
    transitioningDraftRecoveryKeys += recoveryKeys
    try {
        if (!draftCollaboration.tombstone(recoveryKeys)) {
            reportError(
                IllegalStateException("Committed space command cleanup is not durable"),
                "服务器空间已存在，但本机创建命令收尾失败；待办仍保留",
            )
            return
        }
        committed.forEach(createOutbox::completeSpace)
        publishPendingSpaceCreates()
        persistDraftSnapshot()
    } finally {
        transitioningDraftRecoveryKeys -= recoveryKeys
    }
}

internal fun DocumentWorkspaceFeature.replayPendingSpaceCreates() {
    createOutbox.pendingSpaces().forEach(spaceActions::retryPending)
}

internal fun DocumentWorkspaceFeature.publishPendingSpaceCreates() {
    pendingSpaceCreates = createOutbox.pendingSpaces()
}

internal fun DocumentWorkspaceFeature.nextTabInstanceId(): Long =
    draftCollaboration.nextTabInstanceId()
