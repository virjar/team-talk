package com.virjar.tk.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.navigation.feature.DocumentDraftLifecycleBridge
import com.virjar.tk.navigation.feature.DocumentTabState

/** 把编辑器同步捕获的最后一拍正文合并回标签快照，供离开/切换前做可靠判断。 */
internal fun DocumentTabState.withDraftSnapshot(
    snapshot: DocumentEditorDraftSnapshot?,
): DocumentTabState = if (snapshot == null) {
    this
} else {
    copy(
        draftTitle = snapshot.title,
        draftMarkdown = snapshot.markdown,
        dirty = snapshot.dirty || creating,
    )
}

/** 多空间标签和编辑器画布；首页与目录树由外层页面负责。 */
@Composable
internal fun DocumentEditorWorkspace(
    spaces: List<DocumentSpace>,
    tabs: List<DocumentTabState>,
    activeTab: DocumentTabState?,
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
    emptyContent: @Composable () -> Unit,
    showTabStrip: Boolean = true,
    mobileSingleDocumentMode: Boolean = false,
    draftLifecycleBridge: DocumentDraftLifecycleBridge,
    onActiveDraftSnapshotChange: ((() -> DocumentEditorDraftSnapshot)?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tabScroll = rememberScrollState()
    val density = LocalDensity.current
    val spaceNames = remember(spaces) { spaces.associate { it.spaceId to it.name } }
    var activeDraftSnapshot by remember {
        mutableStateOf<(() -> DocumentEditorDraftSnapshot)?>(null)
    }
    val canEdit = spaces.firstOrNull { it.spaceId == activeTab?.spaceId }
        ?.myRole?.let { it >= DocumentSpace.ROLE_EDITOR } ?: false

    LaunchedEffect(tabs.size, activeTab?.tabId) {
        withFrameNanos { }
        val activeIndex = tabs.indexOfFirst { it.tabId == activeTab?.tabId }
        if (activeIndex >= 0) {
            val target = with(density) { (activeIndex * 190.dp.toPx()).toInt() }
            tabScroll.animateScrollTo(target.coerceIn(0, tabScroll.maxValue))
        }
    }

    Column(modifier) {
        if (showTabStrip && (tabs.isNotEmpty() || onBack != null)) {
            Row(
                Modifier.fillMaxWidth().height(50.dp).horizontalScroll(tabScroll)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回目录")
                    }
                }
                tabs.forEach { tab ->
                    Surface(
                        color = if (tab.tabId == activeTab?.tabId) MaterialTheme.colorScheme.surface else Color.Transparent,
                        modifier = Modifier.height(50.dp).width(190.dp).clickable { onSelectTab(tab.tabId) }
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
                            IconButton(
                                onClick = {
                                    val latest = if (tab.tabId == activeTab?.tabId) {
                                        activeDraftSnapshot?.invoke()
                                    } else {
                                        null
                                    }
                                    onCloseTab(tab.withDraftSnapshot(latest))
                                },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭标签", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        Box(Modifier.fillMaxSize()) {
            if (activeTab == null) {
                emptyContent()
            } else {
                DocumentTabEditor(
                    tab = activeTab,
                    revisions = revisions,
                    revisionPreview = revisionPreview,
                    saving = saving,
                    canEdit = canEdit,
                    mobileSingleDocumentMode = mobileSingleDocumentMode,
                    draftLifecycleBridge = draftLifecycleBridge,
                    onUpdateDraft = onUpdateDraft,
                    onRegisterDraftSnapshot = {
                        activeDraftSnapshot = it
                        onActiveDraftSnapshotChange(it)
                    },
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
