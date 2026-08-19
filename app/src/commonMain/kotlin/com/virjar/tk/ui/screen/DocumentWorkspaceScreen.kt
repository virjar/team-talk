package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.navigation.feature.DocumentFolderCrumb
import com.virjar.tk.navigation.feature.DocumentTabState

/** 独立文档工作台：空间、目录树和多文档标签均不依赖聊天上下文。 */
@Composable
fun DocumentWorkspaceScreen(
    spaces: List<DocumentSpace>,
    selectedSpace: DocumentSpace?,
    nodes: List<DocumentNode>,
    folderPath: List<DocumentFolderCrumb>,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
    grants: List<DocumentSpaceGrant>,
    organizationUnits: List<OrganizationUnit>,
    organizationMembers: List<OrganizationMember>,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    loading: Boolean,
    loadingNodes: Boolean,
    loadingDocument: Boolean,
    saving: Boolean,
    onRefresh: () -> Unit,
    onCreateSpace: (String, String?) -> Unit,
    onUpdateSpace: (String, String?) -> Unit,
    onArchiveSpace: () -> Unit,
    onSelectSpace: (String) -> Unit,
    onOpenCrumb: (Int) -> Unit,
    onEnterFolder: (DocumentNode) -> Unit,
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
    detached: Boolean = false,
) {
    var createSpaceDialog by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    var manageSpaceDialog by remember { mutableStateOf(false) }
    var closeCandidate by remember { mutableStateOf<DocumentTabState?>(null) }
    var compactPage by remember { mutableIntStateOf(0) } // 0 spaces, 1 tree, 2 editor

    if (createSpaceDialog) {
        CreateDocumentSpaceDialog(
            onDismiss = { createSpaceDialog = false },
            onCreate = { name, description -> createSpaceDialog = false; onCreateSpace(name, description) },
        )
    }
    if (createFolderDialog) {
        NameDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onDismiss = { createFolderDialog = false },
            onConfirm = { createFolderDialog = false; onCreateFolder(it) },
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
            onArchive = { manageSpaceDialog = false; onArchiveSpace() },
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
                    onClick = { closeCandidate = null; onCloseTab(tab.tabId) },
                    modifier = Modifier.testTag("documents.tab.discard.confirm"),
                ) { Text("放弃并关闭") }
            },
            dismissButton = { TextButton(onClick = { closeCandidate = null }) { Text("继续编辑") } },
        )
    }

    Column(Modifier.fillMaxSize().testTag("documents.workspace")) {
        DocumentWorkspaceHeader(
            detached = detached,
            onRefresh = onRefresh,
            onCreateSpace = { createSpaceDialog = true },
            onDetach = onDetach,
        )
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 700.dp
            if (compact) {
                when (compactPage) {
                    0 -> SpacePane(
                        spaces,
                        selectedSpace?.spaceId,
                        loading,
                        onCreate = { createSpaceDialog = true },
                        onSelect = { onSelectSpace(it); compactPage = 1 },
                        Modifier.fillMaxSize(),
                    )
                    1 -> NodePane(
                        selectedSpace,
                        nodes,
                        folderPath,
                        loadingNodes,
                        onBackToSpaces = { compactPage = 0 },
                        onOpenCrumb = onOpenCrumb,
                        onEnterFolder = onEnterFolder,
                        onOpenDocument = { onOpenDocument(it); compactPage = 2 },
                        onCreateFolder = { createFolderDialog = true },
                        onCreateDocument = { onCreateDocument(); compactPage = 2 },
                        onManageSpace = if ((selectedSpace?.myRole ?: 0) >= DocumentSpace.ROLE_ADMIN) {
                            { onRefreshGrants(); manageSpaceDialog = true }
                        } else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> EditorWorkspace(
                        spaces = spaces,
                        tabs = tabs,
                        activeTab = activeTab,
                        canEdit = spaces.firstOrNull { it.spaceId == activeTab?.spaceId }?.myRole?.let { it >= DocumentSpace.ROLE_EDITOR } ?: false,
                        revisions = revisions,
                        revisionPreview = revisionPreview,
                        saving = saving,
                        loadingDocument = loadingDocument,
                        onBack = { compactPage = 1 },
                        onSelectTab = onSelectTab,
                        onUpdateDraft = onUpdateDraft,
                        onCloseTab = { tab -> if (tab.dirty) closeCandidate = tab else onCloseTab(tab.tabId) },
                        onSave = onSave,
                        onDelete = onDelete,
                        onShowHistory = onShowHistory,
                        onOpenRevision = onOpenRevision,
                        onRestoreRevision = onRestoreRevision,
                        onCloseRevisionPreview = onCloseRevisionPreview,
                        onCloseHistory = onCloseHistory,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    SpacePane(
                        spaces,
                        selectedSpace?.spaceId,
                        loading,
                        onCreate = { createSpaceDialog = true },
                        onSelect = onSelectSpace,
                        Modifier.width(220.dp).fillMaxHeight(),
                    )
                    PaneDivider()
                    NodePane(
                        selectedSpace,
                        nodes,
                        folderPath,
                        loadingNodes,
                        onBackToSpaces = null,
                        onOpenCrumb = onOpenCrumb,
                        onEnterFolder = onEnterFolder,
                        onOpenDocument = onOpenDocument,
                        onCreateFolder = { createFolderDialog = true },
                        onCreateDocument = onCreateDocument,
                        onManageSpace = if ((selectedSpace?.myRole ?: 0) >= DocumentSpace.ROLE_ADMIN) {
                            { onRefreshGrants(); manageSpaceDialog = true }
                        } else null,
                        modifier = Modifier.width(280.dp).fillMaxHeight(),
                    )
                    PaneDivider()
                    EditorWorkspace(
                        spaces = spaces,
                        tabs = tabs,
                        activeTab = activeTab,
                        canEdit = spaces.firstOrNull { it.spaceId == activeTab?.spaceId }?.myRole?.let { it >= DocumentSpace.ROLE_EDITOR } ?: false,
                        revisions = revisions,
                        revisionPreview = revisionPreview,
                        saving = saving,
                        loadingDocument = loadingDocument,
                        onBack = null,
                        onSelectTab = onSelectTab,
                        onUpdateDraft = onUpdateDraft,
                        onCloseTab = { tab -> if (tab.dirty) closeCandidate = tab else onCloseTab(tab.tabId) },
                        onSave = onSave,
                        onDelete = onDelete,
                        onShowHistory = onShowHistory,
                        onOpenRevision = onOpenRevision,
                        onRestoreRevision = onRestoreRevision,
                        onCloseRevisionPreview = onCloseRevisionPreview,
                        onCloseHistory = onCloseHistory,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentWorkspaceHeader(
    detached: Boolean,
    onRefresh: () -> Unit,
    onCreateSpace: () -> Unit,
    onDetach: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(if (detached) "TeamTalk 文档工作台" else "文档", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("documents.refresh")) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
        OutlinedButton(onClick = onCreateSpace, modifier = Modifier.testTag("documents.space.create")) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建空间")
        }
        if (onDetach != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDetach, modifier = Modifier.testTag("documents.detach")) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "在独立窗口打开")
            }
        }
    }
}

@Composable
private fun SpacePane(
    spaces: List<DocumentSpace>,
    selectedId: String?,
    loading: Boolean,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        Text("空间", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        when {
            loading && spaces.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            spaces.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Description, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("还没有可访问的空间", style = MaterialTheme.typography.titleSmall)
                Text("创建空间或请管理员将你、你的部门加入空间", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onCreate) { Text("创建第一个空间") }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(spaces, key = { it.spaceId }) { space ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(space.spaceId) }
                            .testTag("documents.space.${space.spaceId.take(8)}"),
                        colors = ListItemDefaults.colors(
                            containerColor = if (space.spaceId == selectedId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        ),
                        leadingContent = { Icon(Icons.Filled.Description, null) },
                        headlineContent = { Text(space.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(spaceRoleLabel(space.myRole)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodePane(
    space: DocumentSpace?,
    nodes: List<DocumentNode>,
    folderPath: List<DocumentFolderCrumb>,
    loading: Boolean,
    onBackToSpaces: (() -> Unit)?,
    onOpenCrumb: (Int) -> Unit,
    onEnterFolder: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateFolder: () -> Unit,
    onCreateDocument: () -> Unit,
    onManageSpace: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBackToSpaces != null) IconButton(onClick = onBackToSpaces) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回空间")
            }
            Text(space?.name ?: "选择空间", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            if (onManageSpace != null) IconButton(onClick = onManageSpace, modifier = Modifier.testTag("documents.space.settings")) {
                Icon(Icons.Filled.Settings, contentDescription = "空间设置")
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
            folderPath.forEachIndexed { index, crumb ->
                TextButton(onClick = { onOpenCrumb(index) }) { Text(crumb.name, maxLines = 1) }
                if (index < folderPath.lastIndex) Text("/", modifier = Modifier.padding(top = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onCreateFolder,
                enabled = (space?.myRole ?: 0) >= DocumentSpace.ROLE_EDITOR,
                modifier = Modifier.weight(1f).testTag("documents.folder.create"),
            ) { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("文件夹") }
            FilledTonalButton(
                onClick = onCreateDocument,
                enabled = (space?.myRole ?: 0) >= DocumentSpace.ROLE_EDITOR,
                modifier = Modifier.weight(1f).testTag("documents.document.create"),
            ) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("文档") }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize()) {
            when {
                space == null -> Text("从左侧选择文档空间", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                nodes.isEmpty() -> Text("当前目录为空", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(nodes, key = { it.nodeId }) { node ->
                        ListItem(
                            modifier = Modifier.clickable {
                                if (node.nodeType == DocumentNode.TYPE_FOLDER) onEnterFolder(node) else onOpenDocument(node)
                            }.testTag("documents.node.${node.nodeId.take(8)}"),
                            leadingContent = {
                                Icon(
                                    if (node.nodeType == DocumentNode.TYPE_FOLDER) Icons.Filled.Folder else Icons.AutoMirrored.Filled.Article,
                                    contentDescription = null,
                                    tint = if (node.nodeType == DocumentNode.TYPE_FOLDER) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (node.nodeType == DocumentNode.TYPE_DOCUMENT) {
                                { Text(node.excerpt, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else null,
                            trailingContent = if (node.nodeType == DocumentNode.TYPE_DOCUMENT) {
                                { Text("v${node.revision}", style = MaterialTheme.typography.labelSmall) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorWorkspace(
    spaces: List<DocumentSpace>,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
    canEdit: Boolean,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    saving: Boolean,
    loadingDocument: Boolean,
    onBack: (() -> Unit)?,
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
    modifier: Modifier,
) {
    val tabScroll = rememberScrollState()
    val density = LocalDensity.current
    val spaceNames = remember(spaces) { spaces.associate { it.spaceId to it.name } }
    LaunchedEffect(tabs.size, activeTab?.tabId) {
        // 等待标签完成测量后，将新增或重新激活的跨空间文档滚入可视区域。
        withFrameNanos { }
        val activeIndex = tabs.indexOfFirst { it.tabId == activeTab?.tabId }
        if (activeIndex >= 0) {
            val target = with(density) { (activeIndex * 190.dp.toPx()).toInt() }
            tabScroll.animateScrollTo(target.coerceIn(0, tabScroll.maxValue))
        }
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).horizontalScroll(tabScroll).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回目录")
            }
            tabs.forEach { tab ->
                Surface(
                    color = if (tab.tabId == activeTab?.tabId) MaterialTheme.colorScheme.surface else Color.Transparent,
                    modifier = Modifier.height(56.dp).width(190.dp).clickable { onSelectTab(tab.tabId) }
                        .testTag("documents.tab.${tab.tabId.take(12)}"),
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (tab.dirty) "• " else "") + tab.draftTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (tab.tabId == activeTab?.tabId) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                spaceNames[tab.spaceId] ?: "未知空间",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onCloseTab(tab) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭标签", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            if (activeTab == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Description, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("从目录打开文档", style = MaterialTheme.typography.titleMedium)
                    Text("可同时打开多个空间中的文档", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                DocumentTabEditor(
                    tab = activeTab,
                    revisions = revisions,
                    revisionPreview = revisionPreview,
                    saving = saving,
                    canEdit = canEdit,
                    onUpdateDraft = onUpdateDraft,
                    onSave = onSave,
                    onDelete = onDelete,
                    onShowHistory = onShowHistory,
                    onOpenRevision = onOpenRevision,
                    onRestoreRevision = onRestoreRevision,
                    onCloseRevisionPreview = onCloseRevisionPreview,
                    onCloseHistory = onCloseHistory,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (loadingDocument) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PaneDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

private fun spaceRoleLabel(role: Int): String = when (role) {
    DocumentSpace.ROLE_OWNER -> "所有者"
    DocumentSpace.ROLE_ADMIN -> "管理员"
    DocumentSpace.ROLE_EDITOR -> "可编辑"
    else -> "可查看"
}
