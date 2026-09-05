package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.app.navigation.feature.document.DocumentDraftLifecycleBridge
import com.virjar.tk.app.navigation.feature.document.DocumentDraftUpdate
import com.virjar.tk.app.navigation.feature.document.DocumentTabCloseOutcome
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.app.navigation.feature.document.DocumentTreeRow
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceProjectionStatus
import com.virjar.tk.app.ui.component.LocalScreenHeaderLeadingInset
import com.virjar.tk.app.ui.platform.TkBackHandler

internal fun documentSpaceProjectionLabel(
    treeStatus: DocumentWorkspaceProjectionStatus,
    documentStatus: DocumentWorkspaceProjectionStatus,
): String? = when {
    treeStatus == DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN ||
        documentStatus == DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN ->
        "空间已不可访问 · 仅保留本机草稿"
    treeStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING ||
        documentStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING ->
        "当前无网络 · 内容尚未缓存"
    treeStatus == DocumentWorkspaceProjectionStatus.OFFLINE_CACHED ||
        documentStatus == DocumentWorkspaceProjectionStatus.OFFLINE_CACHED ->
        "当前无网络 · 本地缓存"
    treeStatus == DocumentWorkspaceProjectionStatus.CACHED ||
        documentStatus == DocumentWorkspaceProjectionStatus.CACHED ->
        "正在显示本地缓存"
    else -> null
}

internal enum class MobileSingleDocumentTransition {
    OPEN_DIRECTLY,
    RESUME_CURRENT,
    CLOSE_AND_CONTINUE,
    CONFIRM_DISCARD,
}

internal data class MobileSingleDocumentDecision(
    val transition: MobileSingleDocumentTransition,
    val currentTab: DocumentTabState?,
)

/** 首页目标异步到达后进入正文；断网且无缓存时仍保留同一移动文档表面展示缺页。 */
internal fun shouldShowMobileSingleDocumentSurface(
    activeTab: DocumentTabState?,
    selectedSpaceId: String,
    documentProjectionStatus: DocumentWorkspaceProjectionStatus,
): Boolean = activeTab?.spaceId == selectedSpaceId ||
    (activeTab == null && documentProjectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING)

internal fun shouldRenderCompactDocumentSurface(
    compactEditor: Boolean,
    hasVisibleActiveTab: Boolean,
    mobileSingleDocumentMode: Boolean,
    documentProjectionStatus: DocumentWorkspaceProjectionStatus,
): Boolean {
    if (!compactEditor) return false
    return hasVisibleActiveTab ||
        (mobileSingleDocumentMode &&
            documentProjectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING)
}

/**
 * 移动端离开当前文档前必须主动拉取编辑器的最后一拍；Compose state 可能还没完成下一帧提交。
 * [targetDocumentId] 仅在打开已有文档时传入，用于识别“重新打开当前文档”。
 */
internal fun prepareMobileSingleDocumentTransition(
    currentTab: DocumentTabState?,
    captureDraft: (() -> DocumentEditorDraftSnapshot)?,
    targetDocumentId: String?,
): MobileSingleDocumentDecision {
    val captured = currentTab?.withDraftSnapshot(captureDraft?.invoke())
        ?: return MobileSingleDocumentDecision(MobileSingleDocumentTransition.OPEN_DIRECTLY, null)
    if (targetDocumentId != null && captured.documentId == targetDocumentId) {
        return MobileSingleDocumentDecision(MobileSingleDocumentTransition.RESUME_CURRENT, captured)
    }
    return MobileSingleDocumentDecision(
        transition = if (captured.dirty || captured.creating) {
            MobileSingleDocumentTransition.CONFIRM_DISCARD
        } else {
            MobileSingleDocumentTransition.CLOSE_AND_CONTINUE
        },
        currentTab = captured,
    )
}

/** 在捕获的标签页被持久退役之前，目的地不拥有任何发布权。 */
internal fun continueMobileTransitionAfterTabClose(
    tabInstanceId: Long?,
    closeTabByInstance: (Long, (DocumentTabCloseOutcome) -> Unit) -> Unit,
    onBlocked: (DocumentTabCloseOutcome) -> Unit = {},
    onContinue: () -> Unit,
) {
    if (tabInstanceId == null) {
        onContinue()
    } else {
        closeTabByInstance(tabInstanceId) { outcome ->
            if (outcome == DocumentTabCloseOutcome.CLOSED) onContinue() else onBlocked(outcome)
        }
    }
}

private sealed interface MobileDocumentDestination {
    data object Directory : MobileDocumentDestination
    data class NewDocument(val parentId: String?) : MobileDocumentDestination
    data class ExistingDocument(val node: DocumentNode) : MobileDocumentDestination
}

private data class MobileDiscardRequest(
    val tabInstanceId: Long,
    val destination: MobileDocumentDestination,
)

/** 进入某个文档空间后的工作区：紧凑目录树 + 空间概览或多标签编辑器。 */
@Composable
internal fun DocumentSpaceWorkspaceScreen(
    spaces: List<DocumentSpace>,
    space: DocumentSpace,
    treeRows: List<DocumentTreeRow>,
    expandedNodeIds: Set<String>,
    moveBlockedNodeIds: Set<String>,
    selectedParentNodeId: String?,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    loadingRevisions: Boolean,
    loadingMoreRevisions: Boolean,
    hasMoreRevisions: Boolean,
    loadingNodes: Boolean,
    loadingDocument: Boolean,
    treeProjectionStatus: DocumentWorkspaceProjectionStatus,
    documentProjectionStatus: DocumentWorkspaceProjectionStatus,
    saving: Boolean,
    moving: Boolean,
    destructiveOperationPending: Boolean,
    detached: Boolean,
    onShowHome: () -> Unit,
    onRefresh: () -> Unit,
    onToggleNode: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateDocument: (String?) -> Unit,
    onManageSpace: (() -> Unit)?,
    onSelectTab: (String) -> Unit,
    onUpdateDraft: (DocumentDraftUpdate) -> Unit,
    onCloseTab: (DocumentTabState) -> Unit,
    onCloseTabNow: (Long, (DocumentTabCloseOutcome) -> Unit) -> Unit,
    onSave: () -> Unit,
    onMove: (Long, String?) -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onLoadMoreRevisions: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    onDetach: (() -> Unit)?,
    mobileSingleDocumentMode: Boolean,
    draftLifecycleBridge: DocumentDraftLifecycleBridge,
    onActiveDraftSnapshotChange: ((() -> DocumentEditorDraftSnapshot)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var compactEditor by remember(space.spaceId) {
        mutableStateOf(activeTab?.spaceId == space.spaceId)
    }
    val visibleActiveTab = activeTab?.takeIf { it.spaceId == space.spaceId }
    var activeDraftCapture by remember(space.spaceId) {
        mutableStateOf<(() -> DocumentEditorDraftSnapshot)?>(null)
    }
    var mobileDiscardRequest by remember(space.spaceId) { mutableStateOf<MobileDiscardRequest?>(null) }
    var mobileDiscardClosing by remember(space.spaceId) { mutableStateOf(false) }
    var mobileDiscardCloseOutcome by remember(space.spaceId) {
        mutableStateOf<DocumentTabCloseOutcome?>(null)
    }
    var moveCandidate by remember(space.spaceId) { mutableStateOf<DocumentTabState?>(null) }
    // 紧凑与宽屏分支复用同一个按空间隔离的对象。因此把目录树藏在移动编辑器后面
    // 不会在 index 0 处重建它。
    val treeListState = remember(space.spaceId) { LazyListState() }

    LaunchedEffect(moveCandidate?.instanceId, visibleActiveTab?.instanceId) {
        if (moveCandidate?.instanceId != visibleActiveTab?.instanceId) moveCandidate = null
    }

    LaunchedEffect(
        space.spaceId,
        mobileSingleDocumentMode,
        visibleActiveTab?.tabId,
        documentProjectionStatus,
    ) {
        if (mobileSingleDocumentMode) {
            // 单文档前台的页面完全由活动文档决定：首页最近项异步打开也会自然进入编辑器；
            // 无缓存正文的断网结果同样是明确的文档目标，必须保留前台容器展示缺页。
            compactEditor = shouldShowMobileSingleDocumentSurface(
                activeTab = activeTab,
                selectedSpaceId = space.spaceId,
                documentProjectionStatus = documentProjectionStatus,
            )
            if (visibleActiveTab == null) activeDraftCapture = null
        } else if (visibleActiveTab != null) {
            compactEditor = true
        }
    }

    fun performMobileDestination(destination: MobileDocumentDestination) {
        activeDraftCapture = null
        onActiveDraftSnapshotChange(null)
        when (destination) {
            MobileDocumentDestination.Directory -> {
                compactEditor = false
            }
            is MobileDocumentDestination.NewDocument -> {
                onCreateDocument(destination.parentId)
                // beginDocument(parentId) 同步创建活动草稿；本次事件提交后直接显示新编辑器。
                compactEditor = true
            }
            is MobileDocumentDestination.ExistingDocument -> {
                compactEditor = false
                onOpenDocument(destination.node)
            }
        }
    }

    fun requestMobileDestination(destination: MobileDocumentDestination) {
        val targetDocumentId = (destination as? MobileDocumentDestination.ExistingDocument)?.node?.nodeId
        val decision = prepareMobileSingleDocumentTransition(
            currentTab = visibleActiveTab,
            captureDraft = activeDraftCapture,
            targetDocumentId = targetDocumentId,
        )
        when (decision.transition) {
            MobileSingleDocumentTransition.OPEN_DIRECTLY -> performMobileDestination(destination)
            MobileSingleDocumentTransition.RESUME_CURRENT -> {
                compactEditor = true
                if (destination is MobileDocumentDestination.ExistingDocument) onOpenDocument(destination.node)
            }
            MobileSingleDocumentTransition.CLOSE_AND_CONTINUE -> {
                continueMobileTransitionAfterTabClose(
                    tabInstanceId = decision.currentTab?.instanceId,
                    closeTabByInstance = onCloseTabNow,
                ) { performMobileDestination(destination) }
            }
            MobileSingleDocumentTransition.CONFIRM_DISCARD -> {
                mobileDiscardClosing = false
                mobileDiscardCloseOutcome = null
                mobileDiscardRequest = MobileDiscardRequest(
                    tabInstanceId = requireNotNull(decision.currentTab).instanceId,
                    destination = destination,
                )
            }
        }
    }

    mobileDiscardRequest?.let { request ->
        val returningToDirectory = request.destination == MobileDocumentDestination.Directory
        AlertDialog(
            onDismissRequest = {
                if (!mobileDiscardClosing) {
                    mobileDiscardRequest = null
                    mobileDiscardCloseOutcome = null
                }
            },
            title = { Text(if (returningToDirectory) "返回目录？" else "切换文档？") },
            text = {
                Text(
                    when (mobileDiscardCloseOutcome) {
                        DocumentTabCloseOutcome.BLOCKED_BY_SAVE -> "文档正在保存，请等待保存完成后重试。"
                        DocumentTabCloseOutcome.BLOCKED_BY_DISCARD -> "草稿正在安全关闭，请稍候。"
                        DocumentTabCloseOutcome.PERSISTENCE_FAILED -> "草稿取消标记未能持久保存，文档仍保留，请重试。"
                        else -> if (returningToDirectory) {
                        "该文档有未保存修改，放弃后返回目录。"
                        } else {
                        "该文档有未保存修改，放弃后将切换到另一篇文档。"
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mobileDiscardClosing = true
                        mobileDiscardCloseOutcome = null
                        continueMobileTransitionAfterTabClose(
                            tabInstanceId = request.tabInstanceId,
                            closeTabByInstance = onCloseTabNow,
                            onBlocked = { outcome ->
                                mobileDiscardClosing = false
                                mobileDiscardCloseOutcome = outcome
                            },
                        ) {
                            mobileDiscardRequest = null
                            mobileDiscardClosing = false
                            mobileDiscardCloseOutcome = null
                            performMobileDestination(request.destination)
                        }
                    },
                    enabled = !mobileDiscardClosing,
                    modifier = Modifier.testTag("documents.mobile.discard.confirm"),
                ) { Text(if (mobileDiscardClosing) "正在关闭…" else "放弃修改") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        mobileDiscardRequest = null
                        mobileDiscardCloseOutcome = null
                    },
                    enabled = !mobileDiscardClosing,
                    modifier = Modifier.testTag("documents.mobile.discard.cancel"),
                ) { Text("继续编辑") }
            },
            modifier = Modifier.testTag("documents.mobile.discard.dialog"),
        )
    }

    moveCandidate?.let { candidate ->
        DocumentMoveDialog(
            document = candidate,
            treeRows = treeRows,
            expandedNodeIds = expandedNodeIds,
            blockedNodeIds = moveBlockedNodeIds,
            moving = moving,
            onToggleNode = onToggleNode,
            onConfirm = { targetParentId ->
                moveCandidate = null
                onMove(candidate.instanceId, targetParentId)
            },
            onDismiss = { moveCandidate = null },
        )
    }

    Column(modifier.testTag("documents.space.workspace")) {
        val compactDocumentSurfaceVisible = shouldRenderCompactDocumentSurface(
            compactEditor = compactEditor,
            hasVisibleActiveTab = visibleActiveTab != null,
            mobileSingleDocumentMode = mobileSingleDocumentMode,
            documentProjectionStatus = documentProjectionStatus,
        )
        val mobileEditorVisible = mobileSingleDocumentMode && compactDocumentSurfaceVisible
        DocumentSpaceHeader(
            space = space,
            detached = detached,
            onBack = if (mobileEditorVisible) {
                { requestMobileDestination(MobileDocumentDestination.Directory) }
            } else {
                onShowHome
            },
            backContentDescription = if (mobileEditorVisible) "返回目录" else "返回文档首页",
            backTestTag = if (mobileEditorVisible) "documents.editor.back" else "documents.space.back",
            statusLabel = documentSpaceProjectionLabel(
                treeProjectionStatus,
                documentProjectionStatus,
            ),
            onRefresh = onRefresh,
            onManageSpace = onManageSpace,
            onDetach = onDetach,
        )
        HorizontalDivider()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = mobileSingleDocumentMode || maxWidth < 700.dp
            TkBackHandler {
                if (mobileSingleDocumentMode && compactDocumentSurfaceVisible) {
                    requestMobileDestination(MobileDocumentDestination.Directory)
                } else if (compact && compactEditor && visibleActiveTab != null) {
                    // Desktop 窄窗口保留多标签工作台，只改变目录/编辑器的前台页。
                    compactEditor = false
                } else {
                    onShowHome()
                }
            }
            if (compact) {
                if (compactDocumentSurfaceVisible) {
                    DocumentEditorWorkspace(
                        spaces = spaces,
                        tabs = tabs,
                        activeTab = visibleActiveTab,
                        revisions = revisions,
                        revisionPreview = revisionPreview,
                        loadingRevisions = loadingRevisions,
                        loadingMoreRevisions = loadingMoreRevisions,
                        hasMoreRevisions = hasMoreRevisions,
                        saving = saving,
                        moving = moving,
                        destructiveOperationPending = destructiveOperationPending,
                        loadingDocument = loadingDocument,
                        documentProjectionStatus = documentProjectionStatus,
                        onBack = if (mobileSingleDocumentMode) null else { { compactEditor = false } },
                        onSelectTab = {
                            compactEditor = true
                            onSelectTab(it)
                        },
                        onUpdateDraft = onUpdateDraft,
                        onCloseTab = onCloseTab,
                        onSave = onSave,
                        onRequestMove = { instanceId ->
                            moveCandidate = tabs.firstOrNull {
                                it.instanceId == instanceId && !it.dirty && !it.creating
                            }
                        },
                        onDelete = onDelete,
                        onShowHistory = onShowHistory,
                        onLoadMoreRevisions = onLoadMoreRevisions,
                        onOpenRevision = onOpenRevision,
                        onRestoreRevision = onRestoreRevision,
                        onCloseRevisionPreview = onCloseRevisionPreview,
                        onCloseHistory = onCloseHistory,
                        emptyContent = { DocumentSpaceOverview(space) },
                        showTabStrip = !mobileSingleDocumentMode,
                        mobileSingleDocumentMode = mobileSingleDocumentMode,
                        draftLifecycleBridge = draftLifecycleBridge,
                        onActiveDraftSnapshotChange = {
                            activeDraftCapture = it
                            onActiveDraftSnapshotChange(it)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DocumentTreePane(
                        space = space,
                        treeRows = treeRows,
                        expandedNodeIds = expandedNodeIds,
                        selectedParentNodeId = selectedParentNodeId,
                        activeDocumentId = visibleActiveTab?.documentId,
                        listState = treeListState,
                        loading = loadingNodes,
                        projectionStatus = treeProjectionStatus,
                        mobile = mobileSingleDocumentMode,
                        onToggleNode = onToggleNode,
                        onOpenDocument = if (mobileSingleDocumentMode) {
                            { requestMobileDestination(MobileDocumentDestination.ExistingDocument(it)) }
                        } else {
                            {
                                compactEditor = true
                                onOpenDocument(it)
                            }
                        },
                        onCreateDocument = if (mobileSingleDocumentMode) {
                            { parentId ->
                                requestMobileDestination(MobileDocumentDestination.NewDocument(parentId))
                            }
                        } else {
                            { parentId ->
                                compactEditor = true
                                onCreateDocument(parentId)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    DocumentTreePane(
                        space = space,
                        treeRows = treeRows,
                        expandedNodeIds = expandedNodeIds,
                        selectedParentNodeId = selectedParentNodeId,
                        activeDocumentId = visibleActiveTab?.documentId,
                        listState = treeListState,
                        loading = loadingNodes,
                        projectionStatus = treeProjectionStatus,
                        mobile = false,
                        onToggleNode = onToggleNode,
                        onOpenDocument = onOpenDocument,
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
                        loadingRevisions = loadingRevisions,
                        loadingMoreRevisions = loadingMoreRevisions,
                        hasMoreRevisions = hasMoreRevisions,
                        saving = saving,
                        moving = moving,
                        destructiveOperationPending = destructiveOperationPending,
                        loadingDocument = loadingDocument,
                        documentProjectionStatus = documentProjectionStatus,
                        onBack = null,
                        onSelectTab = onSelectTab,
                        onUpdateDraft = onUpdateDraft,
                        onCloseTab = onCloseTab,
                        onSave = onSave,
                        onRequestMove = { instanceId ->
                            moveCandidate = tabs.firstOrNull {
                                it.instanceId == instanceId && !it.dirty && !it.creating
                            }
                        },
                        onDelete = onDelete,
                        onShowHistory = onShowHistory,
                        onLoadMoreRevisions = onLoadMoreRevisions,
                        onOpenRevision = onOpenRevision,
                        onRestoreRevision = onRestoreRevision,
                        onCloseRevisionPreview = onCloseRevisionPreview,
                        onCloseHistory = onCloseHistory,
                        emptyContent = { DocumentSpaceOverview(space) },
                        draftLifecycleBridge = draftLifecycleBridge,
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
    onBack: () -> Unit,
    backContentDescription: String,
    backTestTag: String,
    statusLabel: String?,
    onRefresh: () -> Unit,
    onManageSpace: (() -> Unit)?,
    onDetach: (() -> Unit)?,
) {
    val leadingInset = LocalScreenHeaderLeadingInset.current
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(
            start = 8.dp + leadingInset,
            end = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTestTag)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backContentDescription)
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
                buildString {
                    if (detached) append("TeamTalk 文档 · ")
                    append(documentSpaceWorkspaceRole(space.myRole))
                    statusLabel?.let {
                        append(" · ")
                        append(it)
                    }
                },
                modifier = Modifier.testTag("documents.space.status"),
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
