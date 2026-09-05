package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.client.LocalDocumentProjectionLimits
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.shared.repository.DocumentSpaceRefreshCycle
import com.virjar.tk.shared.repository.DocumentSpaceRefreshPageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 远程空间加上保留在一个有界 UI 工作集中的受保护本地草稿根。 */
internal data class DocumentSpaceWorkset(
    val spaces: List<DocumentSpace>,
    val offlineDraftSpaceIds: Set<String>,
    val nextCursor: String?,
    val reachedLimit: Boolean,
)

internal const val MAX_DOCUMENT_SPACE_WORKSET = 1_024
internal const val MAX_DOCUMENT_SPACE_SNAPSHOT_ATTEMPTS = 3

internal class DocumentSpaceSnapshotUnstableException : IllegalStateException(
    "document space directory snapshot stayed unstable after " +
        "$MAX_DOCUMENT_SPACE_SNAPSHOT_ATTEMPTS attempts",
)

/** 把首次请求计为尝试一，并至多允许两次自动重启。 */
internal fun nextDocumentSpaceSnapshotRestartAttempt(completedRestarts: Int): Int {
    require(completedRestarts >= 0) { "document space snapshot restart count must not be negative" }
    val next = completedRestarts + 1
    if (next >= MAX_DOCUMENT_SPACE_SNAPSHOT_ATTEMPTS) {
        throw DocumentSpaceSnapshotUnstableException()
    }
    return next
}

internal data class LoadedDocumentSpaceFirstPage(
    val cycle: DocumentSpacePaginationCycle,
    val page: DocumentSpacePage,
)

/** 与驻留远程工作集大小无关的有界遗漏证明。 */
internal class DocumentSpaceProjectionScan(
    initialRemoteSpaceIds: Set<String>,
) {
    private val initialRemoteSpaceIds = initialRemoteSpaceIds.toSet()
    private val seenInitialRemoteSpaceIds = linkedSetOf<String>()
    private var lastSpaceId: String? = null
    private var snapshotVersion: com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion? = null
    var expectedCursor: String? = null
        private set
    var terminal: Boolean = false
        private set

    init {
        require(initialRemoteSpaceIds.size <= LocalDocumentProjectionLimits.MAX_SPACE_PROJECTION_IDENTITIES) {
            "document workspace projection candidates exceeded their bounded identity set"
        }
    }

    /** 返回仅当本页完成扫描时才被省略的初始远程身份。 */
    fun recordPage(requestedCursor: String?, page: DocumentSpacePage): Set<String> {
        check(!terminal) { "document workspace projection cycle already completed" }
        require(!page.snapshotChanged) {
            "document workspace cannot publish a changed snapshot page"
        }
        require(snapshotVersion == null || snapshotVersion == page.snapshotVersion) {
            "document workspace snapshot version changed inside one cycle"
        }
        require(expectedCursor == requestedCursor) {
            "document workspace page cursor escaped its projection cycle"
        }
        require(page.nextCursor == null || page.nextCursor != requestedCursor) {
            "document workspace page cursor did not advance"
        }
        val pageIds = page.items.mapTo(linkedSetOf(), DocumentSpace::spaceId)
        require(page.items.zipWithNext().all { (first, second) -> first.spaceId < second.spaceId }) {
            "document workspace projection page is not strictly ordered"
        }
        require(page.items.firstOrNull()?.spaceId?.let { first ->
            lastSpaceId?.let { previous -> first > previous } ?: true
        } != false) { "document workspace projection page overlapped its predecessor" }
        pageIds.filterTo(seenInitialRemoteSpaceIds, initialRemoteSpaceIds::contains)
        snapshotVersion = page.snapshotVersion
        page.items.lastOrNull()?.spaceId?.let { lastSpaceId = it }
        expectedCursor = page.nextCursor
        terminal = page.nextCursor == null
        return if (terminal) initialRemoteSpaceIds - seenInitialRemoteSpaceIds else emptySet()
    }

}

/** 一次 Repository/LocalCache 快照扫描的应用侧镜像。 */
internal class DocumentSpacePaginationCycle(
    val generation: Long,
    val repositoryCycle: DocumentSpaceRefreshCycle,
    initialRemoteSpaceIds: Set<String>,
) {
    private val scan = DocumentSpaceProjectionScan(initialRemoteSpaceIds)
    val expectedCursor: String? get() = scan.expectedCursor
    val terminal: Boolean get() = scan.terminal

    fun recordPage(requestedCursor: String?, page: DocumentSpacePage): Set<String> =
        scan.recordPage(requestedCursor, page)

    fun cancel() {
        repositoryCycle.cancel()
    }
}

internal fun isDocumentHomeItemOpenAllowed(
    item: DocumentHomeItem,
    retiringSpaceIds: Set<String>,
    localOnlySpaceIds: Set<String>,
    deletingDocumentIds: Set<String>,
): Boolean = item.spaceId !in retiringSpaceIds && item.spaceId !in localOnlySpaceIds &&
    item.documentId !in deletingDocumentIds

internal fun replaceDocumentSpaceWorkset(
    currentSpaces: List<DocumentSpace>,
    currentOfflineDraftSpaceIds: Set<String>,
    page: DocumentSpacePage,
    retainedRemoteSpaceIds: Set<String> = emptySet(),
    preserveCurrentRemoteSpaces: Boolean = true,
    maxSize: Int = MAX_DOCUMENT_SPACE_WORKSET,
): DocumentSpaceWorkset {
    requireStrictDocumentSpacePageOrder(page)
    val remoteIds = page.items.mapTo(hashSetOf(), DocumentSpace::spaceId)
    val retainedLocal = currentSpaces.filter { space ->
        space.spaceId in currentOfflineDraftSpaceIds && space.spaceId !in remoteIds
    }
    val retainedRemote = currentSpaces.filter { space ->
        (preserveCurrentRemoteSpaces || space.spaceId in retainedRemoteSpaceIds) &&
            space.spaceId !in currentOfflineDraftSpaceIds &&
            space.spaceId !in remoteIds
    }
    return buildDocumentSpaceWorkset(
        remoteSpaces = page.items + retainedRemote,
        retainedLocalSpaces = retainedLocal,
        requiredRemoteSpaceIds = retainedRemoteSpaceIds,
        cursorPageSpaceIds = remoteIds,
        requestedNextCursor = page.nextCursor,
        maxSize = maxSize,
    )
}

internal fun appendDocumentSpaceWorkset(
    currentSpaces: List<DocumentSpace>,
    currentOfflineDraftSpaceIds: Set<String>,
    requestedCursor: String,
    page: DocumentSpacePage,
    retainedRemoteSpaceIds: Set<String> = emptySet(),
    maxSize: Int = MAX_DOCUMENT_SPACE_WORKSET,
): DocumentSpaceWorkset {
    require(page.nextCursor == null || page.nextCursor != requestedCursor) {
        "Document space page cursor did not advance"
    }
    requireStrictDocumentSpacePageOrder(page)
    val pageIds = page.items.mapTo(hashSetOf(), DocumentSpace::spaceId)
    val retainedLocal = currentSpaces.filter { space ->
        space.spaceId in currentOfflineDraftSpaceIds && space.spaceId !in pageIds
    }
    val remoteById = linkedMapOf<String, DocumentSpace>()
    currentSpaces.asSequence()
        .filterNot { it.spaceId in currentOfflineDraftSpaceIds }
        .forEach { remoteById[it.spaceId] = it }
    // 刚创建的本地空间在它之后到达的服务器页面到来时可能已经驻留。
    // 按身份替换保持一行权威记录，而不把那种重叠当作 wire 重复；
    // 单页内部的重复身份由 DocumentSpacePage 拒绝。
    page.items.forEach { remoteById[it.spaceId] = it }
    return buildDocumentSpaceWorkset(
        remoteSpaces = remoteById.values.sortedBy(DocumentSpace::spaceId),
        retainedLocalSpaces = retainedLocal,
        requiredRemoteSpaceIds = retainedRemoteSpaceIds,
        cursorPageSpaceIds = pageIds,
        requestedNextCursor = page.nextCursor,
        preferLatestOptionalRemote = true,
        maxSize = maxSize,
    )
}

private fun buildDocumentSpaceWorkset(
    remoteSpaces: List<DocumentSpace>,
    retainedLocalSpaces: List<DocumentSpace>,
    requiredRemoteSpaceIds: Set<String> = emptySet(),
    cursorPageSpaceIds: Set<String> = emptySet(),
    requestedNextCursor: String?,
    preferLatestOptionalRemote: Boolean = false,
    maxSize: Int,
): DocumentSpaceWorkset {
    require(maxSize > 0) { "Document space workset limit must be positive" }
    val local = retainedLocalSpaces.distinctBy(DocumentSpace::spaceId)
    require(local.size <= maxSize) { "Local document draft roots exceeded the workset limit" }
    val localIds = local.mapTo(hashSetOf(), DocumentSpace::spaceId)
    val remote = remoteSpaces.filterNot { it.spaceId in localIds }.distinctBy(DocumentSpace::spaceId)
    val remoteSlots = maxSize - local.size
    val requiredRemote = remote.filter { it.spaceId in requiredRemoteSpaceIds }
    require(requiredRemote.size <= remoteSlots) {
        "Selected and open document spaces exceeded the workset limit"
    }
    val requiredIds = requiredRemote.mapTo(hashSetOf(), DocumentSpace::spaceId)
    val optionalRemote = remote.filterNot { it.spaceId in requiredIds }
    val optionalSlots = remoteSlots - requiredRemote.size
    val retainedOptional = if (preferLatestOptionalRemote) {
        optionalRemote.takeLast(optionalSlots)
    } else {
        optionalRemote.take(optionalSlots)
    }
    val retainedRemote = (requiredRemote + retainedOptional).sortedBy(DocumentSpace::spaceId)
    val retainedRemoteIds = retainedRemote.mapTo(hashSetOf(), DocumentSpace::spaceId)
    val cursorPageTruncated = cursorPageSpaceIds.any { it !in retainedRemoteIds }
    return DocumentSpaceWorkset(
        spaces = retainedRemote + local,
        offlineDraftSpaceIds = local.mapTo(linkedSetOf(), DocumentSpace::spaceId),
        // 旧的未受保护远程行可能滑出驻留窗口。游标只有在刚请求页面的每一行
        // 都放下时才推进，因此不会跳过任何未见的行。
        nextCursor = requestedNextCursor.takeUnless { cursorPageTruncated },
        reachedLimit = cursorPageTruncated,
    )
}

private fun requireStrictDocumentSpacePageOrder(page: DocumentSpacePage) {
    require(page.items.zipWithNext().all { (first, second) -> first.spaceId < second.spaceId }) {
        "Document space page is not strictly ordered by spaceId"
    }
}

private fun DocumentWorkspaceFeature.captureSpaceProjectionCandidates(): Set<String> = buildSet {
    spaces.asSequence()
        .map(DocumentSpace::spaceId)
        .filterNot(offlineDraftSpaceIds::contains)
        .toCollection(this)
    recentDocuments.mapTo(this) { it.spaceId }
    recentlyCreatedDocuments.mapTo(this) { it.spaceId }
    tabs.asSequence()
        .map(DocumentTabState::spaceId)
        .filterNot(offlineDraftSpaceIds::contains)
        .toCollection(this)
    treeChildren.values.asSequence().flatten().mapTo(this) { it.spaceId }
    grants.mapTo(this) { it.spaceId }
    selectedSpaceId?.takeUnless(offlineDraftSpaceIds::contains)?.let(::add)
}.minus(offlineDraftSpaceIds).also { candidates ->
    require(candidates.size <= LocalDocumentProjectionLimits.MAX_SPACE_PROJECTION_IDENTITIES) {
        "document workspace projection candidates exceeded their bounded identity set"
    }
}

/** 在第一个远程页面开始之前，同时捕获 App 和 LocalCache 的循环边界。 */
internal suspend fun DocumentWorkspaceFeature.beginSpacePaginationCycle():
    DocumentSpacePaginationCycle? {
    val generation = spacePageGeneration
    val candidates = captureSpaceProjectionCandidates()
    val repositoryCycle = readGateway.beginSpaceRefreshCycle()
    if (generation != spacePageGeneration) {
        repositoryCycle.cancel()
        return null
    }
    return DocumentSpacePaginationCycle(
        generation = generation,
        repositoryCycle = repositoryCycle,
        initialRemoteSpaceIds = candidates,
    ).also { spacePaginationCycle = it }
}

/** 只重试显式的 revision 变更信号；传输失败保持其常规策略。 */
internal suspend fun DocumentWorkspaceFeature.loadStableDocumentSpaceFirstPage(
    ownerIsCurrent: () -> Boolean,
): LoadedDocumentSpaceFirstPage? {
    while (ownerIsCurrent()) {
        val cycle = beginSpacePaginationCycle() ?: return null
        val result = try {
            readGateway.refreshSpaces(cycle.repositoryCycle)
        } catch (failure: Exception) {
            cycle.cancel()
            if (spacePaginationCycle === cycle) spacePaginationCycle = null
            throw failure
        }
        if (!ownerIsCurrent()) {
            cycle.cancel()
            return null
        }
        when (result) {
            is DocumentSpaceRefreshPageResult.Page ->
                return LoadedDocumentSpaceFirstPage(cycle, result.value)
            DocumentSpaceRefreshPageResult.RestartRequired -> {
                cycle.cancel()
                if (spacePaginationCycle === cycle) spacePaginationCycle = null
                spaceSnapshotRestartAttempts =
                    nextDocumentSpaceSnapshotRestartAttempt(spaceSnapshotRestartAttempts)
                invalidateSpacePagination()
            }
        }
    }
    return null
}

private fun DocumentWorkspaceFeature.restartWorkspaceAfterSpaceSnapshotChange(
    cycle: DocumentSpacePaginationCycle,
) {
    cycle.cancel()
    if (spacePaginationCycle === cycle) spacePaginationCycle = null
    spaceSnapshotRestartAttempts = try {
        nextDocumentSpaceSnapshotRestartAttempt(spaceSnapshotRestartAttempts)
    } catch (unstable: DocumentSpaceSnapshotUnstableException) {
        reportError(
            unstable,
            "文档空间权限持续变化，请稍后重试",
        )
        return
    }
    refreshWorkspace(resetSpaceSnapshotRestarts = false)
}

internal fun DocumentWorkspaceFeature.invalidateSpacePagination() {
    spacePaginationCycle?.cancel()
    spacePaginationCycle = null
    spacePageGeneration += 1
    spaceNextCursor = null
    loadingMoreSpaces = false
    spaceWorksetLimited = false
}

internal fun DocumentWorkspaceFeature.publishFirstSpacePage(
    page: DocumentSpacePage,
    cycle: DocumentSpacePaginationCycle,
): Boolean {
    if (spacePaginationCycle !== cycle || cycle.generation != spacePageGeneration) {
        cycle.cancel()
        return false
    }
    val omitted = cycle.recordPage(requestedCursor = null, page = page)
    omitted.forEach(::removeOmittedDocumentSpaceProjection)
    val retainedRemoteSpaceIds = buildSet {
        selectedSpaceId?.let(::add)
        tabs.mapTo(this, DocumentTabState::spaceId)
    }
    val workset = replaceDocumentSpaceWorkset(
        currentSpaces = spaces,
        currentOfflineDraftSpaceIds = offlineDraftSpaceIds,
        page = page,
        retainedRemoteSpaceIds = retainedRemoteSpaceIds,
    )
    spaces = workset.spaces
    offlineDraftSpaceIds = workset.offlineDraftSpaceIds
    spaceNextCursor = workset.nextCursor
    spaceWorksetLimited = workset.reachedLimit
    if (cycle.terminal || workset.reachedLimit) {
        if (!cycle.terminal) cycle.cancel()
        if (spacePaginationCycle === cycle) spacePaginationCycle = null
    }
    if (cycle.terminal) spaceSnapshotRestartAttempts = 0
    return true
}

/** 发布完整的持久快照，而不伪造一个协议分页游标。 */
internal fun DocumentWorkspaceFeature.publishCachedSpaceSnapshot(
    cachedSpaces: List<DocumentSpace>,
) {
    val cachedIds = cachedSpaces.mapTo(hashSetOf(), DocumentSpace::spaceId)
    val retainedLocal = spaces.filter { space ->
        space.spaceId in offlineDraftSpaceIds && space.spaceId !in cachedIds
    }
    val workset = buildDocumentSpaceWorkset(
        remoteSpaces = cachedSpaces,
        retainedLocalSpaces = retainedLocal,
        requiredRemoteSpaceIds = buildSet {
            selectedSpaceId?.let(::add)
            tabs.mapTo(this, DocumentTabState::spaceId)
        },
        requestedNextCursor = null,
        maxSize = MAX_DOCUMENT_SPACE_WORKSET,
    )
    spaces = workset.spaces
    offlineDraftSpaceIds = workset.offlineDraftSpaceIds
    spaceNextCursor = null
    spaceWorksetLimited = workset.reachedLimit
}

internal fun mergeDocumentSpaceMutationWorkset(
    updatedSpaces: List<DocumentSpace>,
    currentOfflineDraftSpaceIds: Set<String>,
    updatedSpaceId: String,
    retainedRemoteSpaceIds: Set<String>,
    requestedNextCursor: String?,
    maxSize: Int = MAX_DOCUMENT_SPACE_WORKSET,
): DocumentSpaceWorkset {
    require(updatedSpaces.any { it.spaceId == updatedSpaceId }) {
        "Authoritative document space mutation is missing from the updated workset"
    }
    val remainingOfflineIds = currentOfflineDraftSpaceIds - updatedSpaceId
    val local = updatedSpaces.filter { it.spaceId in remainingOfflineIds }
    val remote = updatedSpaces.filterNot { it.spaceId in remainingOfflineIds }
    return buildDocumentSpaceWorkset(
        remoteSpaces = remote,
        retainedLocalSpaces = local,
        requiredRemoteSpaceIds = retainedRemoteSpaceIds + updatedSpaceId,
        requestedNextCursor = requestedNextCursor,
        maxSize = maxSize,
    )
}

/** 在不允许本地动作增长驻留列表的情况下，反弹 create/update 响应。 */
internal fun DocumentWorkspaceFeature.publishSpaceMutation(
    updatedSpaces: List<DocumentSpace>,
    updatedSpaceId: String,
): Boolean {
    requireNotNull(
        updatedSpaces.firstOrNull { it.spaceId == updatedSpaceId },
    ) { "Authoritative document space mutation is missing from the updated workset" }
    val workset = mergeDocumentSpaceMutationWorkset(
        updatedSpaces = updatedSpaces,
        currentOfflineDraftSpaceIds = offlineDraftSpaceIds,
        updatedSpaceId = updatedSpaceId,
        retainedRemoteSpaceIds = buildSet {
            selectedSpaceId?.let(::add)
            tabs.mapTo(this, DocumentTabState::spaceId)
        },
        requestedNextCursor = spaceNextCursor,
    )
    spaces = workset.spaces
    offlineDraftSpaceIds = workset.offlineDraftSpaceIds
    spaceNextCursor = workset.nextCursor
    spaceWorksetLimited = workset.reachedLimit
    return true
}

internal fun DocumentWorkspaceFeature.loadMoreSpaces() {
    val cursor = spaceNextCursor ?: return
    if (loadingMoreSpaces || spaceWorksetLimited) return
    val cycle = spacePaginationCycle ?: return
    if (cycle.expectedCursor != cursor) return
    val generation = spacePageGeneration
    loadingMoreSpaces = true
    scope.launch {
        try {
            val result = readGateway.refreshSpaces(cycle.repositoryCycle, cursor)
            if (generation != spacePageGeneration || spaceNextCursor != cursor ||
                spacePaginationCycle !== cycle
            ) {
                cycle.cancel()
                return@launch
            }
            val page = when (result) {
                is DocumentSpaceRefreshPageResult.Page -> result.value
                DocumentSpaceRefreshPageResult.RestartRequired -> {
                    restartWorkspaceAfterSpaceSnapshotChange(cycle)
                    return@launch
                }
            }
            val omitted = cycle.recordPage(cursor, page)
            omitted.forEach(::removeOmittedDocumentSpaceProjection)
            val workset = appendDocumentSpaceWorkset(
                currentSpaces = spaces,
                currentOfflineDraftSpaceIds = offlineDraftSpaceIds,
                requestedCursor = cursor,
                page = page,
                retainedRemoteSpaceIds = buildSet {
                    selectedSpaceId?.let(::add)
                    tabs.mapTo(this, DocumentTabState::spaceId)
                },
            )
            spaces = workset.spaces
            offlineDraftSpaceIds = workset.offlineDraftSpaceIds
            spaceNextCursor = workset.nextCursor
            spaceWorksetLimited = workset.reachedLimit
            if (cycle.terminal || workset.reachedLimit) {
                if (!cycle.terminal) cycle.cancel()
                if (spacePaginationCycle === cycle) spacePaginationCycle = null
            }
            if (cycle.terminal) spaceSnapshotRestartAttempts = 0
            saveCoordinator.replayPendingCreates(page.items)
            convergeDraftsForLoadedSpacePage(generation, page.items)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == spacePageGeneration && spaceNextCursor == cursor) {
                reportError(failure, "加载更多文档空间失败")
            }
        } finally {
            if (generation == spacePageGeneration) loadingMoreSpaces = false
        }
    }
}

private suspend fun DocumentWorkspaceFeature.convergeDraftsForLoadedSpacePage(
    generation: Long,
    loadedSpaces: List<DocumentSpace>,
) {
    if (!remoteDraftEnrichmentPending || loadedSpaces.isEmpty()) return
    val restoration = liveDraftSnapshot() ?: return
    val result = enrichRestoredDocumentPaths(
        restoredTabs = restoration.tabs,
        availableSpaceIds = loadedSpaces.mapTo(hashSetOf(), DocumentSpace::spaceId),
        ownerIsCurrent = { generation == spacePageGeneration },
        currentTabs = { tabs },
        fetch = { key -> readGateway.refreshDocument(key.spaceId, key.documentId) },
        publishTabs = { merged ->
            if (generation == spacePageGeneration) {
                tabs = merged
                persistDraftSnapshot()
            }
        },
    )
    if (generation != spacePageGeneration || result is RestoredDocumentPathEnrichment.Superseded) return
    val current = result as RestoredDocumentPathEnrichment.Current
    remoteDraftEnrichmentPending = tabs.any { tab ->
        tab.documentId != null && !tab.pathResolved && !tab.remoteMissing
    }
    current.failures.firstOrNull()?.let { failure ->
        reportError(failure, "部分本机草稿的目录路径暂时无法校验，将在下次加载时重试")
    }
}
