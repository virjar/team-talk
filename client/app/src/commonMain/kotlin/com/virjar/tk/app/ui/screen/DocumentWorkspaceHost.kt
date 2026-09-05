package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceFeature
import com.virjar.tk.app.navigation.feature.document.loadMoreSpaces
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.LocalEmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.EmbeddedAssetMediaConfig
import com.virjar.tk.app.ui.bridge.LocalEmbeddedAssetMediaConfig

/**
 * 文档工作台的平台无关装配点。Android 主入口与 Desktop 主窗口/独立窗口共享同一份状态，
 * 因此跨空间、跨窗口切换时，打开的标签和未保存草稿不会被重建。
 */
@Composable
fun DocumentWorkspaceHost(
    workspace: DocumentWorkspaceFeature,
    actionAdmission: UiActionAdmission,
    onDetach: (() -> Unit)? = null,
    onExitDocuments: (() -> Unit)? = null,
    detached: Boolean = false,
    mobileSingleDocumentMode: Boolean = false,
    mobileExitCoordinator: MobileDocumentExitCoordinator? = null,
    embeddedAssetImports: EmbeddedAssetImportGateway? = null,
    embeddedAssetMedia: EmbeddedAssetMediaConfig? = null,
) {
    CompositionLocalProvider(
        LocalEmbeddedAssetImportGateway provides embeddedAssetImports,
        LocalEmbeddedAssetMediaConfig provides embeddedAssetMedia,
    ) {
    DocumentWorkspaceScreen(
        spaces = workspace.spaces,
        recentDocuments = workspace.recentDocuments,
        recentlyCreatedDocuments = workspace.recentlyCreatedDocuments,
        pendingSpaceCreates = workspace.pendingSpaceCreates,
        selectedSpace = workspace.selectedSpace,
        treeRows = workspace.treeRows,
        expandedNodeIds = workspace.expandedNodeIds,
        moveBlockedNodeIds = workspace.moveBlockedNodeIds,
        selectedParentNodeId = workspace.selectedParentNodeId,
        tabs = workspace.tabs,
        activeTab = workspace.activeTab,
        grants = workspace.grants,
        organizationUnits = workspace.organizationUnits,
        organizationMemberCandidates = workspace.organizationMemberCandidates,
        organizationMemberQuery = workspace.organizationMemberQuery,
        organizationMemberSearchLoading = workspace.organizationMemberSearchLoading,
        organizationMemberSearchSubmitted = workspace.organizationMemberSearchSubmitted,
        organizationMemberSearchFailed = workspace.organizationMemberSearchFailed,
        revisions = workspace.revisions,
        revisionPreview = workspace.revisionPreview,
        revisionConflict = workspace.revisionConflict,
        loadingRevisions = workspace.loadingRevisions,
        loadingMoreRevisions = workspace.loadingMoreRevisions,
        hasMoreRevisions = workspace.hasMoreRevisions,
        loadingMoreSpaces = workspace.loadingMoreSpaces,
        hasMoreSpaces = workspace.hasMoreSpaces,
        spaceWorksetLimited = workspace.spaceWorksetLimited,
        spaceWorksetHasOfflineDrafts = workspace.spaceWorksetHasOfflineDrafts,
        loading = workspace.loading,
        loadingHome = workspace.loadingHome,
        loadingNodes = workspace.loadingNodes,
        loadingDocument = workspace.loadingDocument,
        spaceProjectionStatus = workspace.spaceProjectionStatus,
        homeProjectionStatus = workspace.homeProjectionStatus,
        treeProjectionStatus = workspace.treeProjectionStatus,
        documentProjectionStatus = workspace.documentProjectionStatus,
        saving = workspace.saving,
        moving = workspace.moving,
        destructiveOperationPending = workspace.activeTabDestructiveOperationPending,
        onRefresh = actionAdmission.guard(workspace::refresh),
        onLoadMoreSpaces = actionAdmission.guard(workspace::loadMoreSpaces),
        onCreateSpace = actionAdmission.guard(workspace::createSpace),
        onRetryPendingSpaceCreate = actionAdmission.guard(workspace::retryPendingSpaceCreate),
        onDiscardPendingSpaceCreate = actionAdmission.guard(workspace::discardPendingSpaceCreate),
        onUpdateSpace = actionAdmission.guard(workspace::updateSpace),
        onArchiveSpace = actionAdmission.guard(workspace::archiveSelectedSpace),
        onShowHome = actionAdmission.guard(workspace::showHome),
        onSelectSpace = actionAdmission.guard(workspace::selectSpace),
        onOpenHomeDocument = actionAdmission.guard(workspace::openHomeDocument),
        onToggleNode = actionAdmission.guard(workspace::toggleNode),
        onOpenDocument = actionAdmission.guard(workspace::openDocument),
        onCreateDocument = actionAdmission.guard(workspace::beginDocument),
        onSelectTab = actionAdmission.guard(workspace::selectTab),
        // 最后一帧捕获刻意绕过输入准入，并在已认证 owner 退役期间由
        // 文档草稿生命周期 bridge 串行化。
        onUpdateDraft = workspace::updateDraft,
        onCloseTab = actionAdmission.guard(workspace::closeTab),
        onCloseTabByInstance = actionAdmission.guard(workspace::closeTabByInstance),
        onSave = actionAdmission.guard(workspace::saveActive),
        onMove = actionAdmission.guard(workspace::moveDocument),
        onDelete = actionAdmission.guard(workspace::deleteActive),
        onShowHistory = actionAdmission.guard(workspace::showHistory),
        onLoadMoreRevisions = actionAdmission.guard(workspace::loadMoreRevisions),
        onOpenRevision = actionAdmission.guard(workspace::openRevision),
        onRestoreRevision = actionAdmission.guard(workspace::restorePreview),
        onCloseRevisionPreview = actionAdmission.guard(workspace::closeRevisionPreview),
        onCloseHistory = actionAdmission.guard(workspace::closeHistory),
        onRetryRevisionConflict = actionAdmission.guard(workspace::retryRevisionConflict),
        onAdoptServerVersion = actionAdmission.guard(workspace::adoptServerVersion),
        onKeepDraftOnLatestVersion = actionAdmission.guard(workspace::keepDraftOnLatestVersion),
        onCloseRevisionConflict = actionAdmission.guard(workspace::closeRevisionConflict),
        onRefreshGrants = actionAdmission.guard(workspace::refreshGrants),
        onSearchGrantMembers = actionAdmission.guard(workspace::searchGrantMembers),
        onCloseGrantMemberSearch = actionAdmission.guard(workspace::closeGrantMemberSearch),
        onUpsertGrant = actionAdmission.guard(workspace::upsertGrant),
        onRemoveGrant = actionAdmission.guard(workspace::removeGrant),
        onDetach = onDetach?.let { actionAdmission.guard(it) },
        onExitDocuments = onExitDocuments?.let { actionAdmission.guard(it) },
        detached = detached,
        mobileSingleDocumentMode = mobileSingleDocumentMode,
        draftLifecycleBridge = workspace.draftLifecycleBridge,
        mobileExitCoordinator = mobileExitCoordinator,
    )
    }
}
