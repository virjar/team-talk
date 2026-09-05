package com.virjar.tk.app.navigation.feature.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.MAX_PENDING_DOCUMENT_MOVE_COMMANDS
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.DocumentMoveCommandCompletion
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 企业文档工作台状态；不依赖聊天上下文，可同时保留来自多个空间的文档标签。 */
class DocumentWorkspaceFeature internal constructor(
    internal val session: ClientSession,
    internal val scope: CoroutineScope,
    internal val reportError: (Throwable, String) -> Unit,
    private val draftStore: DocumentDraftStore,
    localData: UiLocalDataBoundary,
    telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
) {
    internal val draftLifecycleBridge = DocumentDraftLifecycleBridge()
    internal val draftCollaboration = DocumentWorkspaceDraftCollaboration(
        ownerKey = DocumentDraftOwnerKey(
            deploymentFingerprint = session.deploymentIdentity.fingerprint,
            datasetId = session.datasetId,
            uid = session.ownerUid,
        ),
        draftStore = draftStore,
        lifecycleBridge = draftLifecycleBridge,
    )
    internal val createOutbox = DocumentDurableCreateOutbox()
    internal val destructiveOutbox = DocumentDestructiveOutbox()
    internal val workspaceRequests = DocumentWorkspaceRequestCoordinator()
    internal val draftRestorationLock = Mutex()
    internal var draftRestorationApplied = false
    internal var remoteDraftEnrichmentPending = false
    /**
     * 正在保存收尾、关闭或删除中替换/退役的草稿恢复身份，由对应操作取得并在 finally 释放。
     * updateDraft 暂存这些身份的编辑器帧，操作结束后由原操作重放或在确认丢弃后移除。
     */
    internal val transitioningDraftRecoveryKeys = mutableSetOf<String>()
    /** 在其恢复身份正在退役期间捕获的最新完整编辑器帧。 */
    internal val deferredDraftUpdates = mutableMapOf<String, DocumentDraftUpdate>()
    /** 在退役运行期间绝不能准入新本地草稿身份的空间操作。 */
    internal var retiringSpaceIds by mutableStateOf(emptySet<String>())
    internal var workspaceOpened = false
    /** 叶子删除把新的本地 child/open/move 与正在消失的 parent 身份隔离开。 */
    internal var deletingDocumentIds by mutableStateOf(emptySet<String>())
    /** 当前正在执行的稳定 operation ID；恢复的命令可能被重复提供。 */
    internal val destructiveOperationsInFlight = mutableSetOf<String>()
    internal var draftPersistenceAdmissionFailureReported = false

    internal val repositoryBoundary = DocumentRepositoryBoundary(
        session = session,
        localData = localData,
        onSpaceProjectionRemoved = ::removeUnavailableSpaceProjection,
    )
    internal val readGateway = DocumentWorkspaceReadGateway(repository = repositoryBoundary)

    var spaces by mutableStateOf(emptyList<DocumentSpace>())
        internal set
    var spaceProjectionStatus by mutableStateOf(DocumentWorkspaceProjectionStatus.NOT_LOADED)
        internal set
    internal var offlineDraftSpaceIds = emptySet<String>()
    internal var spaceNextCursor: String? = null
    internal var spacePageGeneration = 0L
    internal var spacePaginationCycle: DocumentSpacePaginationCycle? = null
    internal var spaceSnapshotRestartAttempts = 0
    var loadingMoreSpaces by mutableStateOf(false)
        internal set
    var spaceWorksetLimited by mutableStateOf(false)
        internal set
    val spaceWorksetHasOfflineDrafts: Boolean
        get() = offlineDraftSpaceIds.isNotEmpty()
    val hasMoreSpaces: Boolean
        get() = spaceNextCursor != null && !spaceWorksetLimited
    var selectedSpaceId by mutableStateOf<String?>(null)
        internal set
    val selectedSpace get() = spaces.firstOrNull { it.spaceId == selectedSpaceId }

    var recentDocuments by mutableStateOf(emptyList<DocumentHomeItem>())
        private set
    var recentlyCreatedDocuments by mutableStateOf(emptyList<DocumentHomeItem>())
        private set

    var treeChildren by mutableStateOf<Map<String?, List<DocumentNode>>>(emptyMap())
        internal set
    var expandedNodeIds by mutableStateOf<Set<String>>(emptySet())
        internal set
    private val treeRowsProjection = DocumentTreeRowsProjection()
    private val moveBlockedNodesProjection = DocumentKnownDescendantsProjection()
    /** 最近使用的父节点，只用于恢复草稿所在位置；新建动作始终显式传入 parentId。 */
    var selectedParentNodeId by mutableStateOf<String?>(null)
        internal set
    val treeRows: List<DocumentTreeRow>
        get() = treeRowsProjection.rows(treeChildren, expandedNodeIds)
    internal val moveBlockedNodeIds: Set<String>
        get() = moveBlockedNodesProjection.descendants(activeTab?.documentId, treeChildren)

    private var residentTabs by mutableStateOf(emptyList<DocumentTabState>())
    var tabs: List<DocumentTabState>
        get() = residentTabs
        internal set(value) {
            // 所有普通生产者都针对更小的运行目标做规划。这个 setter 是
            // 最终的全进程不变量：任何直接 merge/restoration 路径都不得绕过
            // 由持久化推导的绝对正文上限。
            check(value.size <= MAX_RECOVERED_DOCUMENT_TABS) {
                "Document resident tabs exceeded the absolute recovery ceiling"
            }
            check(reservedDocumentBodyChars(value, activeInstanceId = null) <=
                MAX_RECOVERED_DOCUMENT_BODY_CHARS) {
                "Document resident bodies exceeded the absolute recovery ceiling"
            }
            residentTabs = value
        }
    var activeTabId by mutableStateOf<String?>(null)
        internal set
    val activeTab get() = tabs.firstOrNull { it.tabId == activeTabId }
    val activeTabDestructiveOperationPending: Boolean
        get() = activeTab?.let(::isTerminallyReadOnly) == true

    var grants by mutableStateOf(emptyList<DocumentSpaceGrant>())
        internal set
    var organizationUnits by mutableStateOf(emptyList<OrganizationUnit>())
        private set
    private var grantMemberSearch by mutableStateOf(DocumentGrantMemberSearchState())
    val organizationMemberCandidates get() = grantMemberSearch.candidates
    val organizationMemberQuery get() = grantMemberSearch.query
    val organizationMemberSearchLoading get() = grantMemberSearch.loading
    val organizationMemberSearchSubmitted get() = grantMemberSearch.submitted
    val organizationMemberSearchFailed get() = grantMemberSearch.failed
    private val grantActions = DocumentWorkspaceGrantActions(
        repository = repositoryBoundary,
        scope = scope,
        reportError = reportError,
        organization = DocumentGrantOrganizationController(
            scope = scope,
            localData = localData,
            loadUnits = session.organizationRepo::listUnits,
            searchUsers = session.userRepo::search,
            reportError = reportError,
            publishUnits = { organizationUnits = it },
            publishMemberSearch = { grantMemberSearch = it },
        ),
        selectedSpace = { selectedSpace }, selectedSpaceId = { selectedSpaceId },
        currentGrants = { grants }, updateGrants = { grants = it },
        publishPolicyMutation = ::publishDocumentPolicyMutation,
    )
    internal val spaceActions = DocumentWorkspaceSpaceActions(
        repositoryBoundary, scope, reportError,
        selectedSpaceId = { selectedSpaceId }, spaces = { spaces },
        updateSpaces = ::publishSpaceMutation, beginNavigation = { navigationActions.beginNavigation() },
        isCurrentNavigation = { navigationActions.isCurrent(it) },
        selectSpace = { spaceId, generation -> navigationActions.selectSpaceNow(spaceId, generation) },
        createOutbox = createOutbox,
        persistDrafts = ::persistDraftSnapshot,
        awaitDraftDurability = draftCollaboration::flush,
        tombstoneDrafts = draftCollaboration::tombstone,
        onPendingCreatesChanged = ::publishPendingSpaceCreates,
    )
    internal val historyActions = DocumentWorkspaceHistoryActions(
        repositoryBoundary,
        scope,
        reportError,
    ) { activeTab }
    val revisions: List<DocumentRevisionSummary> get() = historyActions.revisions
    val revisionPreview: DocumentRevision? get() = historyActions.revisionPreview
    val loadingRevisions: Boolean get() = historyActions.loadingRevisions
    val loadingMoreRevisions: Boolean get() = historyActions.loadingMoreRevisions
    val hasMoreRevisions: Boolean get() = historyActions.hasMoreRevisions
    internal val navigationActions: DocumentWorkspaceNavigationActions = DocumentWorkspaceNavigationActions(
        readGateway = readGateway,
        scope = scope,
        reportError = reportError,
        port = DocumentWorkspaceNavigationPort(
            spaces = { spaces }, selectedSpaceId = { selectedSpaceId },
            setSelectedSpaceId = { selectedSpaceId = it },
            home = { recentDocuments to recentlyCreatedDocuments },
            setHome = ::publishDocumentHomeProjection,
            treeChildren = { treeChildren }, setTreeChildren = { treeChildren = it },
            expandedNodeIds = { expandedNodeIds }, setExpandedNodeIds = { expandedNodeIds = it },
            selectedParentNodeId = { selectedParentNodeId },
            setSelectedParentNodeId = { selectedParentNodeId = it },
            tabs = { tabs }, setTabs = { tabs = it }, activeTabId = { activeTabId },
            setActiveTabId = { activeTabId = it }, clearGrants = { grants = emptyList() },
            closeHistory = ::closeHistory, persistDrafts = { persistDraftSnapshot() },
            captureLatestActiveDraft = ::captureLatestActiveDraft,
            nextTabInstanceId = { draftCollaboration.nextTabInstanceId() },
            isSpaceLocalOnly = { it in offlineDraftSpaceIds },
            removeSpaceProjection = ::removeUnavailableSpaceProjection,
            onNavigationStarted = {
                revisionConflictActions.clearConflict()
                loading = false
            },
        ),
    )

    var loading by mutableStateOf(false)
        internal set
    internal var pendingSpaceCreates by mutableStateOf(emptyList<DocumentSpaceCreateRequest>())
        internal set
    val loadingHome: Boolean get() = navigationActions.loadingHome
    val loadingNodes: Boolean get() = navigationActions.loadingNodes
    val loadingDocument: Boolean get() = navigationActions.loadingDocument
    val homeProjectionStatus: DocumentWorkspaceProjectionStatus
        get() = navigationActions.homeProjectionStatus
    val treeProjectionStatus: DocumentWorkspaceProjectionStatus
        get() = navigationActions.treeProjectionStatus
    val documentProjectionStatus: DocumentWorkspaceProjectionStatus
        get() = navigationActions.documentProjectionStatus

    /** 仍保留 UI 现有布尔接口，但含义收口为“当前活动标签是否有写请求在途”。 */
    val saving: Boolean
        get() {
            val current = activeTab ?: return false
            return saveCoordinator.hasPending(current)
        }
    val moving: Boolean get() = activeTab?.let { moveActions.isMoving(it.instanceId) } == true
    internal val revisionConflict: DocumentRevisionConflictState?
        get() = revisionConflictActions.revisionConflict

    private val mutationStatePort = DocumentWorkspaceMutationStatePort(
        tabs = { tabs }, replaceTabs = { tabs = it },
        selectedSpaceId = { selectedSpaceId }, activeTabId = { activeTabId },
        captureActiveDraft = ::captureLatestActiveDraft,
        updateActiveLocation = {
            selectedParentNodeId = it.parentId
            closeHistory()
        },
        persistTabs = { prospectiveTabs ->
            persistDraftSnapshot(snapshotTabs = prospectiveTabs)
        },
        prepareDocumentBranches = { document, previousParents ->
            navigationActions.prepareDocumentRefreshBranches(document, previousParents)
        },
        expandParent = { expandedNodeIds = expandedNodeIds + it },
        refreshHome = { refreshHomeProjection() },
    )
    internal val moveActions = DocumentWorkspaceMoveActions(
        repositoryBoundary, scope, reportError, mutationStatePort,
        DocumentWorkspaceMovePort(
            treeChildren = { treeChildren },
            invalidateBranch = { spaceId, parentId -> navigationActions.invalidateBranch(spaceId, parentId) },
            reloadBranch = { spaceId, parentId -> navigationActions.reloadChildren(spaceId, parentId) },
            prepareNodeBranches = { spaceId, nodeId, parentId, previousParents ->
                navigationActions.prepareDocumentNodeRefreshBranches(
                    spaceId = spaceId,
                    nodeId = nodeId,
                    parentId = parentId,
                    previousParentIds = previousParents,
                )
            },
        ),
    )
    internal val revisionConflictActions = DocumentRevisionConflictActions(
        repositoryBoundary, scope, reportError, mutationStatePort,
        DocumentRevisionConflictPort(
            refreshDocumentBranches = { document, previousParents ->
                navigationActions.refreshDocumentBranches(document, previousParents)
            },
        ),
    )
    internal val saveCoordinator = DocumentWorkspaceSaveCoordinator(
        scope = scope,
        reportError = reportError,
        gateway = DocumentSaveGateway(repositoryBoundary),
        workspace = this,
        createOutbox = createOutbox,
        telemetry = telemetry,
    )
    private val deferredDocumentMoveCompletions = linkedMapOf<String, DocumentMoveCommandCompletion>()
    @Suppress("unused")
    private val documentMoveRecoveryCollector = scope.launch {
        // 在初始 outbox 快照之前订阅：恢复可能在 feature 构造的同时
        // 清除一条命令并发出发其完成事件。
        session.documentMoveRecoveryCompletions.collect(::acceptRecoveredDocumentMove)
    }
    @Suppress("unused")
    private val documentMoveRecoveryBootstrap = scope.launch {
        moveActions.restorePending(repositoryBoundary.call { pendingMoveCommands() })
        tryApplyDeferredDocumentMoves()
    }
    @Suppress("unused")
    private val reconnectRefreshes = DocumentWorkspaceReconnectRefreshCoordinator(
        connectionState = session.connectionState,
        scope = scope,
        workspaceOpened = { workspaceOpened },
        refresh = ::refreshWorkspace,
    )

    suspend fun open() {
        workspaceOpened = true
        openWorkspace()
        tryApplyDeferredDocumentMoves()
    }

    private suspend fun acceptRecoveredDocumentMove(completion: DocumentMoveCommandCompletion) {
        check(deferredDocumentMoveCompletions.size < MAX_PENDING_DOCUMENT_MOVE_COMMANDS ||
            completion.command.operationId in deferredDocumentMoveCompletions
        ) { "Document move completion buffer exceeded its fixed command capacity" }
        deferredDocumentMoveCompletions[completion.command.operationId] = completion
        tryApplyDeferredDocumentMoves()
    }

    private suspend fun tryApplyDeferredDocumentMoves() {
        if (deferredDocumentMoveCompletions.isEmpty()) return
        try {
            ensureDraftRestorationApplied()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 保留有界的完成集合。open()/下一次恢复完成会在草稿 owner 可读之后重试，
            // 因此一次启动存储失败不可能丢失它。
            reportError(failure, "本机草稿暂时无法读取，文档变更结果将在恢复后应用")
            return
        }
        val ready = deferredDocumentMoveCompletions.values.toList()
        deferredDocumentMoveCompletions.clear()
        ready.forEach { completion ->
            moveActions.convergeRecovered(completion)
        }
    }

    fun refresh() = refreshWorkspace()

    fun showHome() {
        // Home 的最近 owner 比空间、待办工作和路径刷新更窄。这会在不取消
        // 该 bootstrap 工作流其余部分的情况下，退役一个更旧的 bootstrap home 响应。
        val homeOwner = workspaceRequests.beginHome()
        navigationActions.showHome {
            workspaceRequests.isCurrent(homeOwner)
        }
    }

    fun createSpace(name: String, description: String?) {
        if (draftRestorationApplied) {
            createSpaceAfterRestoration(name, description)
            return
        }
        scope.launch {
            try {
                ensureDraftRestorationApplied()
                createSpaceAfterRestoration(name, description)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportError(failure, "本机草稿暂时无法安全读取，已阻止创建空间")
            }
        }
    }

    private fun createSpaceAfterRestoration(name: String, description: String?) {
        val normalizedName: String
        val normalizedDescription: String?
        try {
            normalizedName = DocumentPolicy.normalizeSpaceName(name)
            normalizedDescription = DocumentPolicy.normalizeDescription(description)
        } catch (failure: IllegalArgumentException) {
            reportError(failure, failure.message ?: "文档空间信息不合法")
            return
        }
        if (!createOutbox.canAcquireSpace(normalizedName, normalizedDescription)) {
            reportError(
                IllegalStateException("Pending document space create limit reached"),
                "待创建文档空间过多，请先重试或取消部分待办",
            )
            return
        }
        val additionalIdentities = if (
            createOutbox.willAcquireNewSpace(normalizedName, normalizedDescription)
        ) 1 else 0
        if (!hasDocumentDraftRecoveryCapacity(additionalIdentities)) {
            reportDocumentDraftCapacityReached()
            return
        }
        try {
            spaceActions.create(normalizedName, normalizedDescription)
        } catch (failure: Exception) {
            reportError(failure, "创建文档空间请求无法安全加入本机待办")
        }
    }

    fun updateSpace(name: String, description: String?) {
        if (selectedSpaceId?.let { it in retiringSpaceIds || it in offlineDraftSpaceIds } != true) {
            spaceActions.update(name, description)
        }
    }

    internal fun retryPendingSpaceCreate(spaceId: String) {
        createOutbox.pendingSpaces().firstOrNull { it.spaceId == spaceId }
            ?.let(spaceActions::retryPending)
    }

    internal fun discardPendingSpaceCreate(spaceId: String) {
        val request = createOutbox.pendingSpaces().firstOrNull { it.spaceId == spaceId } ?: return
        val recoveryKey = request.draftRecoveryKey()
        if (!transitioningDraftRecoveryKeys.add(recoveryKey)) return
        scope.launch {
            try {
                check(draftCollaboration.tombstone(setOf(recoveryKey))) {
                    "无法持久保存取消标记，待创建空间仍会保留"
                }
                spaceActions.discardPendingAfterTombstone(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportError(failure, "取消待创建文档空间失败")
            } finally {
                transitioningDraftRecoveryKeys.remove(recoveryKey)
            }
        }
    }

    fun archiveSelectedSpace() {
        if (selectedSpaceId?.let(offlineDraftSpaceIds::contains) != true) {
            archiveSelectedSpaceDurably()
        }
    }

    fun selectSpace(spaceId: String) {
        when {
            spaceId in retiringSpaceIds -> Unit
            spaceId in offlineDraftSpaceIds -> navigationActions.selectLocalOrphanSpace(spaceId)
            else -> navigationActions.selectSpace(spaceId)
        }
    }

    /** 展开动作与打开正文完全分离，也不再暗中改变新建文档的父节点。 */
    fun toggleNode(node: DocumentNode) = navigationActions.toggleNode(node)

    /** [parentId] 为 null 时创建顶层文档，否则在指定文档下创建子文档。 */
    fun beginDocument(parentId: String?) {
        if (!draftRestorationApplied) {
            scope.launch {
                try {
                    ensureDraftRestorationApplied()
                    beginDocumentAfterRestoration(parentId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportError(failure, "本机草稿暂时无法安全读取，已阻止新建文档")
                }
            }
            return
        }
        beginDocumentAfterRestoration(parentId)
    }

    private fun beginDocumentAfterRestoration(parentId: String?) {
        val spaceId = selectedSpaceId ?: return
        if (spaceId in offlineDraftSpaceIds || spaceId in retiringSpaceIds ||
            (parentId != null && parentId in deletingDocumentIds)
        ) return
        // 挂载的编辑器可能包含一个尚未到达 feature 状态的最终合并帧。
        // 在决定那个干净的 tab 是否可以安全驱逐之前先捕获它。
        activeTab?.let { current ->
            if (captureLatestActiveDraft(current) == null) return
        }
        when (val admission = decideDocumentTabOpen(tabs, target = null)) {
            is DocumentTabOpenDecision.RejectAtCapacity -> {
                reportError(admission.asFailure(), admission.userMessage())
                return
            }
            // 一个 null 目标表示一个新草稿，因此复用不可能。如果这个不变量
            // 有朝一日改变，保持这个意外分支 fail-closed。
            is DocumentTabOpenDecision.ReuseResident -> return
            DocumentTabOpenDecision.AdmitNew -> Unit
        }
        // 一个从未保存的页面需要一条 tab 记录，并且在首次保存时需要一条不可变的创建命令。
        if (!hasDocumentDraftRecoveryCapacity(2)) {
            reportDocumentDraftCapacityReached()
            return
        }
        val location = documentCreationLocation(spaceId, parentId, treeChildren) ?: return
        // 这个 UUID 既是稳定的本地 tab 资源 ID，也是幂等的创建 ID。
        val tabId = UUID.randomUUID().toString()
        val tab = newDocumentDraftTab(
            tabId = tabId,
            instanceId = nextTabInstanceId(),
            spaceId = spaceId,
            location = location,
        )
        val nextTabs = tabs + tab
        val bodyPlan = when (val plan = planDocumentResidentBodies(
            tabs = nextTabs,
            activeInstanceId = tab.instanceId,
            allowRecoveryDebt = false,
        )) {
            is DocumentResidentBodyPlan.Admitted -> plan
            is DocumentResidentBodyPlan.Rejected -> {
                reportError(plan.asFailure(), plan.userMessage())
                return
            }
        }
        // 在新身份暴露之前，准入覆盖记录/聚合/清单预算。
        if (!persistDraftSnapshot(
                snapshotTabs = bodyPlan.tabs,
                snapshotActiveTabId = tabId,
                snapshotSelectedSpaceId = selectedSpaceId,
            )
        ) return
        // 只有本地准入的草稿才会成为导航事实。一次容量/存储拒绝
        // 绝不能取消用户先前的文档加载或冲突决策。
        navigationActions.beginNavigation()
        tabs = bodyPlan.tabs
        activeTabId = tabId
        selectedParentNodeId = location.parentId
        location.parentId?.let { expandedNodeIds = expandedNodeIds + it }
        closeHistory()
    }

    fun openDocument(node: DocumentNode) {
        if (node.spaceId !in retiringSpaceIds && node.spaceId !in offlineDraftSpaceIds &&
            node.nodeId !in deletingDocumentIds
        ) {
            navigationActions.openDocument(node)
        }
    }

    /** 从类型化引用打开文档：调用方 MessageActionsFeature.openReference 已确认目标可读。 */
    fun openDocumentRef(spaceId: String, documentId: String) {
        navigationActions.openReferenced(spaceId, documentId)
    }

    fun openHomeDocument(item: DocumentHomeItem) {
        if (isDocumentHomeItemOpenAllowed(
                item = item,
                retiringSpaceIds = retiringSpaceIds,
                localOnlySpaceIds = offlineDraftSpaceIds,
                deletingDocumentIds = deletingDocumentIds,
            )
        ) {
            navigationActions.openHomeDocument(item)
        }
    }

    fun selectTab(tabId: String) = navigationActions.selectTab(tabId)

    internal fun updateDraft(update: DocumentDraftUpdate) {
        draftLifecycleBridge.publishIfOpen {
            val target = tabs.firstOrNull {
                it.tabId == update.tabId && it.instanceId == update.instanceId
            }
            if (target != null && target.draftRecoveryKey() in transitioningDraftRecoveryKeys) {
                deferredDraftUpdates[target.draftRecoveryKey()] = update
                return@publishIfOpen
            }
            val updatedTabs = updateDocumentDraftTabs(tabs, update)
            if (updatedTabs === tabs) return@publishIfOpen
            val activeInstanceId = updatedTabs.firstOrNull { it.tabId == activeTabId }?.instanceId
            val bodyPlan = when (val plan = planDocumentEditorFramePublication(
                tabs = updatedTabs,
                activeInstanceId = activeInstanceId,
            )) {
                is DocumentResidentBodyPlan.Admitted -> plan
                is DocumentResidentBodyPlan.Rejected -> {
                    reportError(plan.asFailure(), plan.userMessage())
                    error(
                        "A valid document editor frame exceeded the absolute resident " +
                            "recovery ceiling",
                    )
                }
            }
            tabs = bodyPlan.tabs
            revisionConflictActions.dismissStaleConflict()
            persistDraftSnapshot()
        }
    }

    fun closeTab(tabId: String) = closeTabDurably(tabId)

    internal fun closeTabByInstance(
        instanceId: Long,
        onResult: (DocumentTabCloseOutcome) -> Unit = {},
    ) {
        val currentTabId = documentTabIdByInstance(tabs, instanceId) ?: run {
            onResult(DocumentTabCloseOutcome.CLOSED)
            return
        }
        closeTabDurably(currentTabId, onResult)
    }

    fun saveActive() {
        val current = activeTab ?: return
        if (current.spaceId in offlineDraftSpaceIds) {
            reportError(
                IllegalStateException("Local-only document draft space cannot be synchronized"),
                "当前仅保留本机草稿，不能继续同步",
            )
            return
        }
        if (!current.remoteMissing) {
            saveCoordinator.saveActive()
            return
        }
        if (saveCoordinator.hasPending(current) ||
            current.draftRecoveryKey() in transitioningDraftRecoveryKeys
        ) return
        // 消失的远程路径绝不被复用。在发现删除的那次权威根刷新之后，
        // 根始终是一个有效位置。
        val location = documentCreationLocation(current.spaceId, parentId = null, treeChildren)
            ?: return
        if (!hasDocumentDraftRecoveryCapacity(1)) {
            reportDocumentDraftCapacityReached()
            return
        }
        val replacement = prepareRemoteMissingDocumentCreate(
            tab = current,
            newDocumentId = UUID.randomUUID().toString(),
            location = location,
        ) ?: return
        val nextTabs = tabs.map { if (it.instanceId == current.instanceId) replacement else it }
        if (!persistDraftSnapshot(
                snapshotTabs = nextTabs,
                snapshotActiveTabId = replacement.tabId,
                snapshotSelectedSpaceId = replacement.spaceId,
            )
        ) return
        navigationActions.beginNavigation()
        tabs = nextTabs
        activeTabId = replacement.tabId
        selectedParentNodeId = location.parentId
        closeHistory()
        saveCoordinator.saveActive()
    }

    fun deleteActive() = deleteActiveDurably()

    fun moveDocument(instanceId: Long, targetParentId: String?) {
        val tab = tabs.firstOrNull { it.instanceId == instanceId } ?: return
        val documentId = tab.documentId
        if (tab.spaceId !in retiringSpaceIds && tab.spaceId !in offlineDraftSpaceIds &&
            (documentId == null || documentId !in deletingDocumentIds)
        ) {
            moveActions.move(instanceId, targetParentId)
        }
    }

    fun retryRevisionConflict() = revisionConflictActions.retryConflict()

    fun adoptServerVersion() {
        val ready = revisionConflictActions.readyConflict() ?: return
        val initial = tabs.firstOrNull(ready.request::targetsUnchanged) ?: return
        val current = captureLatestActiveDraft(initial) ?: return
        if (!ready.request.targetsUnchanged(current)) {
            revisionConflictActions.dismissStaleConflict()
            reportError(
                IllegalStateException("Conflict draft changed before adopting server version"),
                "本地内容已变化，已保留最新编辑；请重新保存后再处理冲突",
            )
            return
        }
        val recoveryKey = current.draftRecoveryKey()
        if (!transitioningDraftRecoveryKeys.add(recoveryKey)) return
        val adopting = revisionConflictActions.beginAdoptingServerVersion(ready) ?: run {
            transitioningDraftRecoveryKeys.remove(recoveryKey)
            return
        }
        scope.launch {
            var tombstoned = false
            var adopted = false
            try {
                check(draftCollaboration.tombstone(setOf(recoveryKey))) {
                    "无法持久保存本地草稿取消标记"
                }
                tombstoned = true
                adopted = revisionConflictActions.completeAdoptingServerVersion(adopting)
                check(adopted) { "采用服务器版本时标签状态已改变" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportError(failure, "采用服务器版本失败，本地草稿仍保留")
            } finally {
                if (!adopted) revisionConflictActions.cancelAdoptingServerVersion(adopting)
                transitioningDraftRecoveryKeys.remove(recoveryKey)
                val deferred = deferredDraftUpdates.remove(recoveryKey)
                if (!adopted) {
                    if (tombstoned) {
                        tabs = rotateDocumentTabRecoveryIdentity(
                            tabs = tabs,
                            request = ready.request,
                            retiredRecoveryId = current.recoveryId,
                        )
                    }
                    deferred?.let { update ->
                        val latest = tabs.firstOrNull(ready.request::targets)
                        updateDraft(update.copy(revision = latest?.revision ?: update.revision))
                    }
                    if (tombstoned) persistDraftSnapshot()
                }
            }
        }
    }

    fun keepDraftOnLatestVersion() {
        val ready = revisionConflictActions.readyConflict() ?: return
        val initial = tabs.firstOrNull(ready.request::targetsUnchanged) ?: return
        val current = captureLatestActiveDraft(initial) ?: return
        if (!ready.request.targetsUnchanged(current)) {
            revisionConflictActions.dismissStaleConflict()
            reportError(
                IllegalStateException("Conflict draft changed before rebase"),
                "本地内容已变化并已保留，请重新保存后再处理冲突",
            )
            return
        }
        revisionConflictActions.keepDraftOnLatestVersion()
    }

    fun closeRevisionConflict() = revisionConflictActions.clearConflict()

    fun showHistory() {
        if (activeTab?.spaceId?.let(offlineDraftSpaceIds::contains) != true) historyActions.show()
    }

    fun loadMoreRevisions() = historyActions.loadMore()

    fun openRevision(summary: DocumentRevisionSummary) = historyActions.open(summary)

    fun restorePreview() {
        if (activeTab?.spaceId?.let(offlineDraftSpaceIds::contains) != true) {
            saveCoordinator.restorePreview()
        }
    }

    fun closeHistory() = historyActions.close()

    fun closeRevisionPreview() = historyActions.closePreview()

    fun refreshGrants() {
        if (selectedSpaceId?.let(offlineDraftSpaceIds::contains) != true) grantActions.refresh()
    }

    fun searchGrantMembers(query: String) = grantActions.searchMembers(query)

    fun closeGrantMemberSearch() = grantActions.closeMemberSearch()

    fun upsertGrant(principalType: Int, principalId: String, role: Int, includeDescendants: Boolean) {
        if (selectedSpaceId?.let(offlineDraftSpaceIds::contains) != true) {
            grantActions.upsert(principalType, principalId, role, includeDescendants)
        }
    }

    fun removeGrant(grant: DocumentSpaceGrant) {
        if (grant.spaceId !in offlineDraftSpaceIds) grantActions.remove(grant)
    }

    /**
     * 把编辑器的最后一个视觉/源码帧发布进不可变的工作区快照。
     * 平台外壳同步调用它，然后排入它们自己的非阻塞持久化屏障；
     * 通用 feature 绝不在 UI 线程上执行文件系统等待。
     */
    fun captureDrafts(): Boolean = draftCollaboration.captureLatest()

    /** 终止性 AppData/平台边界；最终捕获胜出，每一个迟到的编辑器回调都失效。 */
    fun retireDraftCapture(): Boolean = draftLifecycleBridge.captureAndRetire()

    internal fun isTerminallyReadOnly(tab: DocumentTabState): Boolean =
        tab.spaceId in retiringSpaceIds || tab.spaceId in offlineDraftSpaceIds ||
            (tab.documentId != null && tab.documentId in deletingDocumentIds)

    private fun publishDocumentHomeProjection(
        recent: List<DocumentHomeItem>,
        recentlyCreated: List<DocumentHomeItem>,
    ) {
        recentDocuments = recent
        recentlyCreatedDocuments = recentlyCreated
    }

    internal fun removeRetiredSpaceFromDocumentHome(spaceId: String) {
        recentDocuments = recentDocuments.filterNot { it.spaceId == spaceId }
        recentlyCreatedDocuments = recentlyCreatedDocuments.filterNot { it.spaceId == spaceId }
    }

}
