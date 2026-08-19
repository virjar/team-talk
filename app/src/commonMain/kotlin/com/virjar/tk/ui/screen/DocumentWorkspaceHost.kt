package com.virjar.tk.ui.screen

import androidx.compose.runtime.Composable
import com.virjar.tk.navigation.feature.DocumentWorkspaceFeature

/**
 * 文档工作台的平台无关装配点。Android 主入口与 Desktop 主窗口/独立窗口共享同一份状态，
 * 因此跨空间、跨窗口切换时，打开的标签和未保存草稿不会被重建。
 */
@Composable
fun DocumentWorkspaceHost(
    workspace: DocumentWorkspaceFeature,
    onDetach: (() -> Unit)? = null,
    onExitDocuments: (() -> Unit)? = null,
    detached: Boolean = false,
) {
    DocumentWorkspaceScreen(
        spaces = workspace.spaces,
        recentDocuments = workspace.recentDocuments,
        recentlyCreatedDocuments = workspace.recentlyCreatedDocuments,
        selectedSpace = workspace.selectedSpace,
        treeRows = workspace.treeRows,
        expandedFolderIds = workspace.expandedFolderIds,
        selectedFolderId = workspace.selectedFolderId,
        tabs = workspace.tabs,
        activeTab = workspace.activeTab,
        grants = workspace.grants,
        organizationUnits = workspace.organizationUnits,
        organizationMembers = workspace.organizationMembers,
        revisions = workspace.revisions,
        revisionPreview = workspace.revisionPreview,
        loading = workspace.loading,
        loadingHome = workspace.loadingHome,
        loadingNodes = workspace.loadingNodes,
        loadingDocument = workspace.loadingDocument,
        saving = workspace.saving,
        onRefresh = workspace::refresh,
        onCreateSpace = workspace::createSpace,
        onUpdateSpace = workspace::updateSpace,
        onArchiveSpace = workspace::archiveSelectedSpace,
        onShowHome = workspace::showHome,
        onSelectSpace = workspace::selectSpace,
        onOpenHomeDocument = workspace::openHomeDocument,
        onSelectRootFolder = workspace::selectRootFolder,
        onToggleFolder = workspace::toggleFolder,
        onOpenDocument = workspace::openDocument,
        onCreateFolder = workspace::createFolder,
        onCreateDocument = workspace::beginDocument,
        onSelectTab = workspace::selectTab,
        onUpdateDraft = workspace::updateDraft,
        onCloseTab = workspace::closeTab,
        onSave = workspace::saveActive,
        onDelete = workspace::deleteActive,
        onShowHistory = workspace::showHistory,
        onOpenRevision = workspace::openRevision,
        onRestoreRevision = workspace::restorePreview,
        onCloseRevisionPreview = workspace::closeRevisionPreview,
        onCloseHistory = workspace::closeHistory,
        onRefreshGrants = workspace::refreshGrants,
        onUpsertGrant = workspace::upsertGrant,
        onRemoveGrant = workspace::removeGrant,
        onDetach = onDetach,
        onExitDocuments = onExitDocuments,
        detached = detached,
    )
}
