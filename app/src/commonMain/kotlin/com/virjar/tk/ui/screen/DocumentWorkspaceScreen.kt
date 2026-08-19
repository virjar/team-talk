package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.model.DocumentHomeItem
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.navigation.feature.DocumentTabState
import com.virjar.tk.navigation.feature.DocumentTreeRow
import com.virjar.tk.ui.platform.TkBackHandler

/**
 * 企业文档的共享页面壳。
 *
 * 文档入口首先展示企业资产首页；选择空间后再进入以该空间为根的目录树与编辑工作区。
 * 页面位置由 session-scoped feature 持有，因此 Desktop 拉出窗口时不会退回首页或丢失标签。
 */
@Composable
fun DocumentWorkspaceScreen(
    spaces: List<DocumentSpace>,
    recentDocuments: List<DocumentHomeItem>,
    recentlyCreatedDocuments: List<DocumentHomeItem>,
    selectedSpace: DocumentSpace?,
    treeRows: List<DocumentTreeRow>,
    expandedFolderIds: Set<String>,
    selectedFolderId: String?,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
    grants: List<DocumentSpaceGrant>,
    organizationUnits: List<OrganizationUnit>,
    organizationMembers: List<OrganizationMember>,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    loading: Boolean,
    loadingHome: Boolean,
    loadingNodes: Boolean,
    loadingDocument: Boolean,
    saving: Boolean,
    onRefresh: () -> Unit,
    onCreateSpace: (String, String?) -> Unit,
    onUpdateSpace: (String, String?) -> Unit,
    onArchiveSpace: () -> Unit,
    onShowHome: () -> Unit,
    onSelectSpace: (String) -> Unit,
    onOpenHomeDocument: (DocumentHomeItem) -> Unit,
    onSelectRootFolder: () -> Unit,
    onToggleFolder: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateDocument: () -> Unit,
    onSelectTab: (String) -> Unit,
    onUpdateDraft: (String, String, String, Boolean) -> Unit,
    onCloseTab: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    onRefreshGrants: () -> Unit,
    onUpsertGrant: (Int, String, Int, Boolean) -> Unit,
    onRemoveGrant: (DocumentSpaceGrant) -> Unit,
    onDetach: (() -> Unit)? = null,
    onExitDocuments: (() -> Unit)? = null,
    detached: Boolean = false,
) {
    var createSpaceDialog by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    var manageSpaceDialog by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<DocumentTabState?>(null) }

    if (createSpaceDialog) {
        CreateDocumentSpaceDialog(
            onDismiss = { createSpaceDialog = false },
            onCreate = { name, description ->
                createSpaceDialog = false
                onCreateSpace(name, description)
            },
        )
    }
    if (createFolderDialog) {
        NameDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onDismiss = { createFolderDialog = false },
            onConfirm = {
                createFolderDialog = false
                onCreateFolder(it)
            },
        )
    }
    if (manageSpaceDialog && selectedSpace != null) {
        DocumentSpaceManagementDialog(
            space = selectedSpace,
            grants = grants,
            organizationUnits = organizationUnits,
            organizationMembers = organizationMembers,
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
            onDismissRequest = { closeCandidate = null },
            title = { Text("关闭“${tab.draftTitle}”？") },
            text = { Text("该标签有未保存的修改，关闭后草稿会丢失。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeCandidate = null
                        onCloseTab(tab.tabId)
                    },
                    modifier = Modifier.testTag("documents.tab.discard.confirm"),
                ) { Text("放弃并关闭") }
            },
            dismissButton = {
                TextButton(onClick = { closeCandidate = null }) { Text("继续编辑") }
            },
        )
    }

    Box(Modifier.fillMaxSize().testTag("documents.workspace")) {
        if (selectedSpace == null) {
            TkBackHandler(enabled = onExitDocuments != null) {
                onExitDocuments?.invoke()
            }
            DocumentHomeScreen(
                spaces = spaces,
                recentDocuments = recentDocuments,
                recentlyCreatedDocuments = recentlyCreatedDocuments,
                loading = loading || loadingHome,
                detached = detached,
                onRefresh = onRefresh,
                onCreateSpace = { createSpaceDialog = true },
                onSelectSpace = onSelectSpace,
                onOpenDocument = onOpenHomeDocument,
                onDetach = onDetach,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            DocumentSpaceWorkspaceScreen(
                spaces = spaces,
                space = selectedSpace,
                treeRows = treeRows,
                expandedFolderIds = expandedFolderIds,
                selectedFolderId = selectedFolderId,
                tabs = tabs,
                activeTab = activeTab,
                revisions = revisions,
                revisionPreview = revisionPreview,
                loadingNodes = loadingNodes,
                loadingDocument = loadingDocument,
                saving = saving,
                detached = detached,
                onShowHome = onShowHome,
                onRefresh = onRefresh,
                onSelectRootFolder = onSelectRootFolder,
                onToggleFolder = onToggleFolder,
                onOpenDocument = onOpenDocument,
                onCreateFolder = { createFolderDialog = true },
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
                    if (tab.dirty) closeCandidate = tab else onCloseTab(tab.tabId)
                },
                onSave = onSave,
                onDelete = onDelete,
                onShowHistory = onShowHistory,
                onOpenRevision = onOpenRevision,
                onRestoreRevision = onRestoreRevision,
                onCloseRevisionPreview = onCloseRevisionPreview,
                onCloseHistory = onCloseHistory,
                onDetach = onDetach,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
