package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.navigation.feature.DocumentTabState
import com.virjar.tk.navigation.feature.DocumentTreeRow
import com.virjar.tk.ui.platform.TkBackHandler

/** 进入某个文档空间后的工作区：紧凑目录树 + 空间概览或多标签编辑器。 */
@Composable
internal fun DocumentSpaceWorkspaceScreen(
    spaces: List<DocumentSpace>,
    space: DocumentSpace,
    treeRows: List<DocumentTreeRow>,
    expandedFolderIds: Set<String>,
    selectedFolderId: String?,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    loadingNodes: Boolean,
    loadingDocument: Boolean,
    saving: Boolean,
    detached: Boolean,
    onShowHome: () -> Unit,
    onRefresh: () -> Unit,
    onSelectRootFolder: () -> Unit,
    onToggleFolder: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateDocument: () -> Unit,
    onManageSpace: (() -> Unit)?,
    onSelectTab: (String) -> Unit,
    onUpdateDraft: (String, String, String, Boolean) -> Unit,
    onCloseTab: (DocumentTabState) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    onDetach: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var compactEditor by remember(space.spaceId) {
        mutableStateOf(activeTab?.spaceId == space.spaceId)
    }
    val visibleActiveTab = activeTab?.takeIf { it.spaceId == space.spaceId }

    LaunchedEffect(space.spaceId, visibleActiveTab?.tabId) {
        if (visibleActiveTab != null) compactEditor = true
    }

    Column(modifier.testTag("documents.space.workspace")) {
        DocumentSpaceHeader(
            space = space,
            detached = detached,
            onShowHome = onShowHome,
            onRefresh = onRefresh,
            onManageSpace = onManageSpace,
            onDetach = onDetach,
        )
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 700.dp
            TkBackHandler {
                if (compact && compactEditor && visibleActiveTab != null) {
                    // 只退回目录，标签和未保存草稿仍由 session-scoped 工作台持有。
                    compactEditor = false
                } else {
                    onShowHome()
                }
            }
            if (compact) {
                if (compactEditor && visibleActiveTab != null) {
                    DocumentEditorWorkspace(
                        spaces = spaces,
                        tabs = tabs,
                        activeTab = visibleActiveTab,
                        revisions = revisions,
                        revisionPreview = revisionPreview,
                        saving = saving,
                        loadingDocument = loadingDocument,
                        onBack = { compactEditor = false },
                        onSelectTab = {
                            compactEditor = true
                            onSelectTab(it)
                        },
                        onUpdateDraft = onUpdateDraft,
                        onCloseTab = onCloseTab,
                        onSave = onSave,
                        onDelete = onDelete,
                        onShowHistory = onShowHistory,
                        onOpenRevision = onOpenRevision,
                        onRestoreRevision = onRestoreRevision,
                        onCloseRevisionPreview = onCloseRevisionPreview,
                        onCloseHistory = onCloseHistory,
                        emptyContent = { DocumentSpaceOverview(space) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DocumentTreePane(
                        space = space,
                        treeRows = treeRows,
                        expandedFolderIds = expandedFolderIds,
                        selectedFolderId = selectedFolderId,
                        activeDocumentId = visibleActiveTab?.documentId,
                        loading = loadingNodes,
                        onSelectRootFolder = onSelectRootFolder,
                        onToggleFolder = onToggleFolder,
                        onOpenDocument = {
                            compactEditor = true
                            onOpenDocument(it)
                        },
                        onCreateFolder = onCreateFolder,
                        onCreateDocument = {
                            compactEditor = true
                            onCreateDocument()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    DocumentTreePane(
                        space = space,
                        treeRows = treeRows,
                        expandedFolderIds = expandedFolderIds,
                        selectedFolderId = selectedFolderId,
                        activeDocumentId = visibleActiveTab?.documentId,
                        loading = loadingNodes,
                        onSelectRootFolder = onSelectRootFolder,
                        onToggleFolder = onToggleFolder,
                        onOpenDocument = onOpenDocument,
                        onCreateFolder = onCreateFolder,
                        onCreateDocument = onCreateDocument,
                        modifier = Modifier.width(276.dp).fillMaxHeight(),
                    )
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    DocumentEditorWorkspace(
                        spaces = spaces,
                        tabs = tabs,
                        activeTab = visibleActiveTab,
                        revisions = revisions,
                        revisionPreview = revisionPreview,
                        saving = saving,
                        loadingDocument = loadingDocument,
                        onBack = null,
                        onSelectTab = onSelectTab,
                        onUpdateDraft = onUpdateDraft,
                        onCloseTab = onCloseTab,
                        onSave = onSave,
                        onDelete = onDelete,
                        onShowHistory = onShowHistory,
                        onOpenRevision = onOpenRevision,
                        onRestoreRevision = onRestoreRevision,
                        onCloseRevisionPreview = onCloseRevisionPreview,
                        onCloseHistory = onCloseHistory,
                        emptyContent = { DocumentSpaceOverview(space) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentSpaceHeader(
    space: DocumentSpace,
    detached: Boolean,
    onShowHome: () -> Unit,
    onRefresh: () -> Unit,
    onManageSpace: (() -> Unit)?,
    onDetach: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShowHome, modifier = Modifier.testTag("documents.space.back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回文档首页")
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Description, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(space.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (detached) "TeamTalk 文档 · ${documentSpaceWorkspaceRole(space.myRole)}" else documentSpaceWorkspaceRole(space.myRole),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("documents.refresh")) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
        if (onManageSpace != null) {
            IconButton(onClick = onManageSpace, modifier = Modifier.testTag("documents.space.settings")) {
                Icon(Icons.Filled.Settings, contentDescription = "空间设置")
            }
        }
        if (onDetach != null) {
            IconButton(onClick = onDetach, modifier = Modifier.testTag("documents.detach")) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "在独立窗口打开")
            }
        }
    }
}

@Composable
private fun DocumentTreePane(
    space: DocumentSpace,
    treeRows: List<DocumentTreeRow>,
    expandedFolderIds: Set<String>,
    selectedFolderId: String?,
    activeDocumentId: String?,
    loading: Boolean,
    onSelectRootFolder: () -> Unit,
    onToggleFolder: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateDocument: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.17f))
            .testTag("documents.tree"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("目录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(
                onClick = onCreateFolder,
                enabled = space.myRole >= DocumentSpace.ROLE_EDITOR,
                modifier = Modifier.size(38.dp).testTag("documents.folder.create"),
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹", Modifier.size(19.dp))
            }
            IconButton(
                onClick = onCreateDocument,
                enabled = space.myRole >= DocumentSpace.ROLE_EDITOR,
                modifier = Modifier.size(38.dp).testTag("documents.document.create"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建文档", Modifier.size(20.dp))
            }
        }
        HorizontalDivider()
        DocumentTreeRootRow(targeted = selectedFolderId == null, onClick = onSelectRootFolder)
        Box(Modifier.fillMaxSize()) {
            when {
                loading && treeRows.isEmpty() -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp,
                )
                treeRows.isEmpty() -> Text(
                    "空间中还没有文档",
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(treeRows, key = { it.node.nodeId }) { row ->
                        DocumentTreeNodeRow(
                            row = row,
                            expanded = row.node.nodeId in expandedFolderIds,
                            creationTarget = row.node.nodeType == DocumentNode.TYPE_FOLDER &&
                                row.node.nodeId == selectedFolderId,
                            active = row.node.nodeType == DocumentNode.TYPE_DOCUMENT &&
                                row.node.nodeId == activeDocumentId,
                            onToggleFolder = onToggleFolder,
                            onOpenDocument = onOpenDocument,
                        )
                    }
                }
            }
            if (loading && treeRows.isNotEmpty()) CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun DocumentTreeRootRow(targeted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(38.dp)
            .clickable(onClick = onClick)
            .testTag("documents.tree.root")
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(18.dp)
                .background(
                    if (targeted) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(2.dp),
                )
                .testTag("documents.tree.root.target"),
        )
        Spacer(Modifier.width(5.dp))
        Icon(Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.Folder,
            null,
            Modifier.size(18.dp),
            tint = if (targeted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(7.dp))
        Text("全部文档", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DocumentTreeNodeRow(
    row: DocumentTreeRow,
    expanded: Boolean,
    creationTarget: Boolean,
    active: Boolean,
    onToggleFolder: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
) {
    val node = row.node
    val folder = node.nodeType == DocumentNode.TYPE_FOLDER
    val startPadding = (10 + row.depth.coerceIn(0, 12) * 14).dp
    Row(
        Modifier.fillMaxWidth().height(38.dp)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { if (folder) onToggleFolder(node) else onOpenDocument(node) }
            .testTag("documents.node.${node.nodeId.take(8)}")
            .padding(start = startPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(18.dp)
                .background(
                    if (creationTarget) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(2.dp),
                )
                .testTag("documents.tree.target.${node.nodeId.take(8)}"),
        )
        Spacer(Modifier.width(5.dp))
        if (folder) {
            IconButton(
                onClick = { onToggleFolder(node) },
                modifier = Modifier.size(24.dp).testTag("documents.tree.toggle.${node.nodeId.take(8)}"),
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "折叠文件夹" else "展开文件夹",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            if (folder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = when {
                creationTarget -> MaterialTheme.colorScheme.primary
                folder -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.width(7.dp))
        Text(
            node.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DocumentSpaceOverview(space: DocumentSpace) {
    Box(Modifier.fillMaxSize().testTag("documents.space.overview"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Description, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(space.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            space.description?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "你的权限：${documentSpaceWorkspaceRole(space.myRole)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(28.dp))
            Text("从左侧目录打开文档，或新建一篇文档开始协作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun documentSpaceWorkspaceRole(role: Int): String = when (role) {
    DocumentSpace.ROLE_OWNER -> "所有者"
    DocumentSpace.ROLE_ADMIN -> "管理员"
    DocumentSpace.ROLE_EDITOR -> "可编辑"
    else -> "可查看"
}
