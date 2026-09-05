package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.feature.document.DocumentDraftLifecycleBridge
import com.virjar.tk.app.navigation.feature.document.DocumentDraftUpdate
import com.virjar.tk.app.navigation.feature.document.DocumentRevisionConflictState
import com.virjar.tk.app.navigation.feature.document.DocumentSpaceCreateRequest
import com.virjar.tk.app.navigation.feature.document.DocumentTabCloseOutcome
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.app.navigation.feature.document.DocumentTreeRow
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceProjectionStatus
import com.virjar.tk.app.ui.platform.TkBackHandler

/** Android 一级导航在离开“文档”前，通过该协调器复用工作台的同步草稿保护。 */
class MobileDocumentExitCoordinator {
    private var exitRequest: (((() -> Unit)) -> Unit)? = null

    fun requestExit(onExit: () -> Unit) {
        exitRequest?.invoke(onExit) ?: onExit()
    }

    internal fun attach(request: ((() -> Unit)) -> Unit) {
        exitRequest = request
    }

    internal fun detach(request: ((() -> Unit)) -> Unit) {
        if (exitRequest === request) exitRequest = null
    }
}

private data class MobileWorkspaceDiscardRequest(
    val tabInstanceId: Long,
    val title: String,
    val message: String,
    val onDiscard: () -> Unit,
)

/**
 * 企业文档的共享页面壳。
 *
 * 文档入口首先展示企业资产首页；选择空间后再进入以该空间为根的目录树与编辑工作区。
 * 页面位置由 session-scoped feature 持有，因此 Desktop 拉出窗口时不会退回首页或丢失标签。
 */
@Composable
internal fun DocumentWorkspaceScreen(
    spaces: List<DocumentSpace>,
    recentDocuments: List<DocumentHomeItem>,
    recentlyCreatedDocuments: List<DocumentHomeItem>,
    pendingSpaceCreates: List<DocumentSpaceCreateRequest>,
    selectedSpace: DocumentSpace?,
    treeRows: List<DocumentTreeRow>,
    expandedNodeIds: Set<String>,
    moveBlockedNodeIds: Set<String>,
    selectedParentNodeId: String?,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
    grants: List<DocumentSpaceGrant>,
    organizationUnits: List<OrganizationUnit>,
    organizationMemberCandidates: List<User>,
    organizationMemberQuery: String,
    organizationMemberSearchLoading: Boolean,
    organizationMemberSearchSubmitted: Boolean,
    organizationMemberSearchFailed: Boolean,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    revisionConflict: DocumentRevisionConflictState?,
    loadingRevisions: Boolean,
    loadingMoreRevisions: Boolean,
    hasMoreRevisions: Boolean,
    loadingMoreSpaces: Boolean,
    hasMoreSpaces: Boolean,
    spaceWorksetLimited: Boolean,
    spaceWorksetHasOfflineDrafts: Boolean,
    loading: Boolean,
    loadingHome: Boolean,
    loadingNodes: Boolean,
    loadingDocument: Boolean,
    spaceProjectionStatus: DocumentWorkspaceProjectionStatus,
    homeProjectionStatus: DocumentWorkspaceProjectionStatus,
    treeProjectionStatus: DocumentWorkspaceProjectionStatus,
    documentProjectionStatus: DocumentWorkspaceProjectionStatus,
    saving: Boolean,
    moving: Boolean,
    destructiveOperationPending: Boolean,
    onRefresh: () -> Unit,
    onLoadMoreSpaces: () -> Unit,
    onCreateSpace: (String, String?) -> Unit,
    onRetryPendingSpaceCreate: (String) -> Unit,
    onDiscardPendingSpaceCreate: (String) -> Unit,
    onUpdateSpace: (String, String?) -> Unit,
    onArchiveSpace: () -> Unit,
    onShowHome: () -> Unit,
    onSelectSpace: (String) -> Unit,
    onOpenHomeDocument: (DocumentHomeItem) -> Unit,
    onToggleNode: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateDocument: (String?) -> Unit,
    onSelectTab: (String) -> Unit,
    onUpdateDraft: (DocumentDraftUpdate) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseTabByInstance: (Long, (DocumentTabCloseOutcome) -> Unit) -> Unit,
    onSave: () -> Unit,
    onMove: (Long, String?) -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onLoadMoreRevisions: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    onRetryRevisionConflict: () -> Unit,
    onAdoptServerVersion: () -> Unit,
    onKeepDraftOnLatestVersion: () -> Unit,
    onCloseRevisionConflict: () -> Unit,
    onRefreshGrants: () -> Unit,
    onSearchGrantMembers: (String) -> Unit,
    onCloseGrantMemberSearch: () -> Unit,
    onUpsertGrant: (Int, String, Int, Boolean) -> Unit,
    onRemoveGrant: (DocumentSpaceGrant) -> Unit,
    onDetach: (() -> Unit)? = null,
    onExitDocuments: (() -> Unit)? = null,
    detached: Boolean = false,
    mobileSingleDocumentMode: Boolean = false,
    draftLifecycleBridge: DocumentDraftLifecycleBridge,
    mobileExitCoordinator: MobileDocumentExitCoordinator? = null,
) {
    var createSpaceDialog by remember { mutableStateOf(false) }
    var manageSpaceDialog by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<DocumentTabState?>(null) }
    var closeCandidateClosing by remember { mutableStateOf(false) }
    var closeCandidateOutcome by remember { mutableStateOf<DocumentTabCloseOutcome?>(null) }
    var mobileDraftCapture by remember { mutableStateOf<(() -> DocumentEditorDraftSnapshot)?>(null) }
    var mobileWorkspaceDiscardRequest by remember { mutableStateOf<MobileWorkspaceDiscardRequest?>(null) }
    var mobileWorkspaceDiscardClosing by remember { mutableStateOf(false) }
    var mobileWorkspaceDiscardOutcome by remember { mutableStateOf<DocumentTabCloseOutcome?>(null) }

    fun requestMobileWorkspaceTransition(
        targetDocumentId: String?,
        title: String,
        message: String,
        onProceed: () -> Unit,
    ) {
        val decision = prepareMobileSingleDocumentTransition(
            currentTab = activeTab,
            captureDraft = mobileDraftCapture,
            targetDocumentId = targetDocumentId,
        )
        when (decision.transition) {
            MobileSingleDocumentTransition.OPEN_DIRECTLY,
            MobileSingleDocumentTransition.RESUME_CURRENT -> onProceed()
            MobileSingleDocumentTransition.CLOSE_AND_CONTINUE -> {
                continueMobileTransitionAfterTabClose(
                    tabInstanceId = decision.currentTab?.instanceId,
                    closeTabByInstance = onCloseTabByInstance,
                    onContinue = onProceed,
                )
            }
            MobileSingleDocumentTransition.CONFIRM_DISCARD -> {
                mobileWorkspaceDiscardClosing = false
                mobileWorkspaceDiscardOutcome = null
                mobileWorkspaceDiscardRequest = MobileWorkspaceDiscardRequest(
                    tabInstanceId = requireNotNull(decision.currentTab).instanceId,
                    title = title,
                    message = message,
                    onDiscard = onProceed,
                )
            }
        }
    }

    val latestMobileExitRequest by rememberUpdatedState<((() -> Unit) -> Unit)> { onExit ->
        requestMobileWorkspaceTransition(
            targetDocumentId = null,
            title = "离开文档？",
            message = "该文档有未保存修改，放弃后将离开文档。",
            onProceed = onExit,
        )
    }
    DisposableEffect(mobileExitCoordinator, mobileSingleDocumentMode) {
        if (!mobileSingleDocumentMode || mobileExitCoordinator == null) return@DisposableEffect onDispose { }
        val request: ((() -> Unit) -> Unit) = { onExit -> latestMobileExitRequest(onExit) }
        mobileExitCoordinator.attach(request)
        onDispose { mobileExitCoordinator.detach(request) }
    }

    LaunchedEffect(selectedSpace?.spaceId, selectedSpace?.myRole) {
        if (selectedSpace == null || selectedSpace.myRole < DocumentSpace.ROLE_ADMIN) {
            manageSpaceDialog = false
            onCloseGrantMemberSearch()
        }
    }

    if (createSpaceDialog) {
        CreateDocumentSpaceDialog(
            onDismiss = { createSpaceDialog = false },
            onCreate = { name, description ->
                createSpaceDialog = false
                onCreateSpace(name, description)
            },
        )
    }
    if (manageSpaceDialog && selectedSpace != null &&
        selectedSpace.myRole >= DocumentSpace.ROLE_ADMIN
    ) {
        DisposableEffect(selectedSpace.spaceId) {
            onDispose { onCloseGrantMemberSearch() }
        }
        DocumentSpaceManagementDialog(
            space = selectedSpace,
            grants = grants,
            organizationUnits = organizationUnits,
            organizationMemberCandidates = organizationMemberCandidates,
            organizationMemberQuery = organizationMemberQuery,
            organizationMemberSearchLoading = organizationMemberSearchLoading,
            organizationMemberSearchSubmitted = organizationMemberSearchSubmitted,
            organizationMemberSearchFailed = organizationMemberSearchFailed,
            onSearchOrganizationMembers = onSearchGrantMembers,
            onDismiss = { manageSpaceDialog = false },
            onSave = onUpdateSpace,
            onArchive = {
                manageSpaceDialog = false
                onArchiveSpace()
            },
            onUpsertGrant = onUpsertGrant,
            onRemoveGrant = onRemoveGrant,
        )
    }
    closeCandidate?.let { tab ->
        AlertDialog(
            onDismissRequest = {
                if (!closeCandidateClosing) {
                    closeCandidate = null
                    closeCandidateOutcome = null
                }
            },
            title = { Text("关闭“${tab.draftTitle}”？") },
            text = {
                Text(
                    when (closeCandidateOutcome) {
                        DocumentTabCloseOutcome.BLOCKED_BY_SAVE -> "文档正在保存，请等待保存完成后重试。"
                        DocumentTabCloseOutcome.BLOCKED_BY_DISCARD -> "草稿正在安全关闭，请稍候。"
                        DocumentTabCloseOutcome.PERSISTENCE_FAILED -> "草稿取消标记未能持久保存，标签仍保留，请重试。"
                        else -> "该标签有未保存的修改，关闭后草稿会丢失。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeCandidateClosing = true
                        closeCandidateOutcome = null
                        onCloseTabByInstance(tab.instanceId) { outcome ->
                            if (outcome == DocumentTabCloseOutcome.CLOSED) {
                                closeCandidate = null
                                closeCandidateClosing = false
                                closeCandidateOutcome = null
                            } else {
                                closeCandidateClosing = false
                                closeCandidateOutcome = outcome
                            }
                        }
                    },
                    enabled = !closeCandidateClosing,
                    modifier = Modifier.testTag("documents.tab.discard.confirm"),
                ) { Text(if (closeCandidateClosing) "正在关闭…" else "放弃并关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        closeCandidate = null
                        closeCandidateOutcome = null
                    },
                    enabled = !closeCandidateClosing,
                ) { Text("继续编辑") }
            },
        )
    }
    mobileWorkspaceDiscardRequest?.let { request ->
        AlertDialog(
            onDismissRequest = {
                if (!mobileWorkspaceDiscardClosing) {
                    mobileWorkspaceDiscardRequest = null
                    mobileWorkspaceDiscardOutcome = null
                }
            },
            title = { Text(request.title) },
            text = {
                Text(
                    when (mobileWorkspaceDiscardOutcome) {
                        DocumentTabCloseOutcome.BLOCKED_BY_SAVE -> "文档正在保存，请等待保存完成后重试。"
                        DocumentTabCloseOutcome.BLOCKED_BY_DISCARD -> "草稿正在安全关闭，请稍候。"
                        DocumentTabCloseOutcome.PERSISTENCE_FAILED -> "草稿取消标记未能持久保存，文档仍保留，请重试。"
                        else -> request.message
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mobileWorkspaceDiscardClosing = true
                        mobileWorkspaceDiscardOutcome = null
                        // 新建保存会迁移 tabId；确认时只用稳定实例重新解析当前标签。
                        continueMobileTransitionAfterTabClose(
                            tabInstanceId = request.tabInstanceId,
                            closeTabByInstance = onCloseTabByInstance,
                            onBlocked = { outcome ->
                                mobileWorkspaceDiscardClosing = false
                                mobileWorkspaceDiscardOutcome = outcome
                            },
                            onContinue = {
                                mobileWorkspaceDiscardRequest = null
                                mobileWorkspaceDiscardClosing = false
                                mobileWorkspaceDiscardOutcome = null
                                request.onDiscard()
                            },
                        )
                    },
                    enabled = !mobileWorkspaceDiscardClosing,
                    modifier = Modifier.testTag("documents.mobile.discard.confirm"),
                ) { Text(if (mobileWorkspaceDiscardClosing) "正在关闭…" else "放弃修改") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        mobileWorkspaceDiscardRequest = null
                        mobileWorkspaceDiscardOutcome = null
                    },
                    enabled = !mobileWorkspaceDiscardClosing,
                    modifier = Modifier.testTag("documents.mobile.discard.cancel"),
                ) { Text("继续编辑") }
            },
            modifier = Modifier.testTag("documents.mobile.discard.dialog"),
        )
    }
    DocumentRevisionConflictDialog(
        state = revisionConflict,
        activeTab = activeTab,
        onRetry = onRetryRevisionConflict,
        onAdoptServer = onAdoptServerVersion,
        onKeepDraft = onKeepDraftOnLatestVersion,
        onDismiss = onCloseRevisionConflict,
    )

    Box(Modifier.fillMaxSize().testTag("documents.workspace")) {
        if (selectedSpace == null) {
            TkBackHandler(enabled = onExitDocuments != null) {
                if (mobileSingleDocumentMode) {
                    requestMobileWorkspaceTransition(
                        targetDocumentId = null,
                        title = "离开文档？",
                        message = "该文档有未保存修改，放弃后将离开文档。",
                    ) { onExitDocuments?.invoke() }
                } else {
                    onExitDocuments?.invoke()
                }
            }
            DocumentHomeScreen(
                spaces = spaces,
                recentDocuments = recentDocuments,
                recentlyCreatedDocuments = recentlyCreatedDocuments,
                pendingSpaceCreates = pendingSpaceCreates,
                loading = loading || loadingHome,
                spaceProjectionStatus = spaceProjectionStatus,
                homeProjectionStatus = homeProjectionStatus,
                loadingMoreSpaces = loadingMoreSpaces,
                hasMoreSpaces = hasMoreSpaces,
                spaceWorksetLimited = spaceWorksetLimited,
                spaceWorksetHasOfflineDrafts = spaceWorksetHasOfflineDrafts,
                detached = detached,
                onRefresh = onRefresh,
                onLoadMoreSpaces = onLoadMoreSpaces,
                onCreateSpace = { createSpaceDialog = true },
                onRetryPendingSpaceCreate = onRetryPendingSpaceCreate,
                onDiscardPendingSpaceCreate = onDiscardPendingSpaceCreate,
                onSelectSpace = { spaceId ->
                    if (mobileSingleDocumentMode) {
                        requestMobileWorkspaceTransition(
                            targetDocumentId = null,
                            title = "切换空间？",
                            message = "该文档有未保存修改，放弃后将切换空间。",
                        ) { onSelectSpace(spaceId) }
                    } else {
                        onSelectSpace(spaceId)
                    }
                },
                onOpenDocument = { item ->
                    if (mobileSingleDocumentMode) {
                        requestMobileWorkspaceTransition(
                            targetDocumentId = item.documentId,
                            title = "切换文档？",
                            message = "该文档有未保存修改，放弃后将切换到另一篇文档。",
                        ) { onOpenHomeDocument(item) }
                    } else {
                        onOpenHomeDocument(item)
                    }
                },
                onDetach = onDetach,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DocumentSpaceWorkspaceScreen(
                spaces = spaces,
                space = selectedSpace,
                treeRows = treeRows,
                expandedNodeIds = expandedNodeIds,
                moveBlockedNodeIds = moveBlockedNodeIds,
                selectedParentNodeId = selectedParentNodeId,
                tabs = tabs,
                activeTab = activeTab,
                revisions = revisions,
                revisionPreview = revisionPreview,
                loadingRevisions = loadingRevisions,
                loadingMoreRevisions = loadingMoreRevisions,
                hasMoreRevisions = hasMoreRevisions,
                loadingNodes = loadingNodes,
                loadingDocument = loadingDocument,
                treeProjectionStatus = treeProjectionStatus,
                documentProjectionStatus = documentProjectionStatus,
                saving = saving,
                moving = moving,
                destructiveOperationPending = destructiveOperationPending,
                detached = detached,
                onShowHome = onShowHome,
                onRefresh = onRefresh,
                onToggleNode = onToggleNode,
                onOpenDocument = onOpenDocument,
                onCreateDocument = onCreateDocument,
                onManageSpace = if (selectedSpace.myRole >= DocumentSpace.ROLE_ADMIN) {
                    {
                        onRefreshGrants()
                        manageSpaceDialog = true
                    }
                } else null,
                onSelectTab = onSelectTab,
                onUpdateDraft = onUpdateDraft,
                onCloseTab = { tab ->
                    if (tab.dirty) {
                        closeCandidateClosing = false
                        closeCandidateOutcome = null
                        closeCandidate = tab
                    } else {
                        onCloseTab(tab.tabId)
                    }
                },
                onCloseTabNow = onCloseTabByInstance,
                onSave = onSave,
                onMove = onMove,
                onDelete = onDelete,
                onShowHistory = onShowHistory,
                onLoadMoreRevisions = onLoadMoreRevisions,
                onOpenRevision = onOpenRevision,
                onRestoreRevision = onRestoreRevision,
                onCloseRevisionPreview = onCloseRevisionPreview,
                onCloseHistory = onCloseHistory,
                onDetach = onDetach,
                mobileSingleDocumentMode = mobileSingleDocumentMode,
                draftLifecycleBridge = draftLifecycleBridge,
                onActiveDraftSnapshotChange = { mobileDraftCapture = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
