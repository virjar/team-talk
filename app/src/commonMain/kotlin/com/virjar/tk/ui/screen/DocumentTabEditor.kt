package com.virjar.tk.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.navigation.feature.DocumentTabState
import com.virjar.tk.ui.component.rich.DocumentBlockEditor
import com.virjar.tk.ui.component.rich.DocumentBlockFormattingToolbar
import com.virjar.tk.ui.component.rich.DocumentMarkdownPreview
import com.virjar.tk.ui.component.rich.normalizeRichTextLink
import com.virjar.tk.ui.component.rich.rememberDocumentBlockEditorController

internal data class DocumentEditorDraftSnapshot(
    val title: String,
    val markdown: String,
    val dirty: Boolean,
)

/** A per-editor-instance stable handle; an old tab must never read a new tab's capture lambda. */
internal class DocumentDraftCaptureHandle(
    var action: () -> DocumentEditorDraftSnapshot,
) {
    fun capture(): DocumentEditorDraftSnapshot = action()
}

@Composable
internal fun DocumentTabEditor(
    tab: DocumentTabState,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    saving: Boolean,
    canEdit: Boolean,
    onUpdateDraft: (String, String, String, Boolean) -> Unit,
    onRegisterDraftSnapshot: ((() -> DocumentEditorDraftSnapshot)?) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val editorKey = "${tab.tabId}:${tab.revision ?: 0}"
    val blockController = rememberDocumentBlockEditorController(editorKey)
    val baselineTitle = remember(editorKey) { tab.savedTitle }
    var title by remember(editorKey) { mutableStateOf(tab.draftTitle) }
    val baselineMarkdown = remember(editorKey) { tab.savedMarkdown }
    var blockMarkdown by remember(editorKey) { mutableStateOf(tab.draftMarkdown) }
    var sourceMarkdown by remember(editorKey) { mutableStateOf(tab.draftMarkdown) }
    var sourceMode by remember(editorKey) { mutableStateOf(false) }
    var editorReady by remember(editorKey) { mutableStateOf(false) }
    var dirty by remember(editorKey) { mutableStateOf(tab.dirty || tab.creating) }
    var previewMode by remember(editorKey, canEdit) { mutableStateOf(!canEdit) }
    var historyDialog by remember(editorKey) { mutableStateOf(false) }
    var deleteDialog by remember(editorKey) { mutableStateOf(false) }
    var documentMenu by remember(editorKey) { mutableStateOf(false) }

    LaunchedEffect(editorKey) {
        editorReady = false
        blockMarkdown = tab.draftMarkdown
        sourceMarkdown = tab.draftMarkdown
        sourceMode = false
        // Block codec 会原样保留所有未编辑源码。等待画布挂载后再开始同步草稿，
        // 避免初始化期间的子编辑器状态被误判为用户输入。
        withFrameNanos { }
        withFrameNanos { }
        editorReady = true
    }
    val currentMarkdown = if (editorReady) {
        if (sourceMode) sourceMarkdown else blockMarkdown
    } else tab.draftMarkdown
    LaunchedEffect(editorReady, title, currentMarkdown, sourceMode) {
        if (!editorReady) return@LaunchedEffect
        dirty = tab.creating || title != baselineTitle || currentMarkdown != baselineMarkdown
        onUpdateDraft(tab.tabId, title, currentMarkdown, dirty)
    }

    fun latestVisualMarkdown(): String = blockController.snapshotMarkdown(blockMarkdown)
    fun publishDraft(markdown: String): DocumentEditorDraftSnapshot {
        blockMarkdown = markdown
        dirty = tab.creating || title != baselineTitle || markdown != baselineMarkdown
        onUpdateDraft(tab.tabId, title, markdown, dirty)
        return DocumentEditorDraftSnapshot(title, markdown, dirty)
    }
    fun captureLatestDraft(): DocumentEditorDraftSnapshot = publishDraft(
        if (sourceMode) sourceMarkdown else latestVisualMarkdown()
    )
    val draftCaptureHandle = remember(editorKey) {
        DocumentDraftCaptureHandle { captureLatestDraft() }
    }
    SideEffect { draftCaptureHandle.action = { captureLatestDraft() } }
    val stableDraftCapture = remember(editorKey) {
        { draftCaptureHandle.capture() }
    }
    DisposableEffect(editorKey) {
        onRegisterDraftSnapshot(stableDraftCapture)
        onDispose {
            stableDraftCapture()
            onRegisterDraftSnapshot(null)
        }
    }
    LaunchedEffect(canEdit) {
        if (!canEdit) stableDraftCapture()
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("删除“$title”？") },
            text = { Text("文档将从空间目录中移除，普通成员不能继续访问。") },
            confirmButton = {
                TextButton(
                    onClick = { deleteDialog = false; onDelete() },
                    modifier = Modifier.testTag("documents.document.delete.confirm"),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
        )
    }
    if (historyDialog && tab.documentId != null) {
        DocumentRevisionDialog(
            title = tab.savedTitle,
            currentRevision = tab.revision ?: 1,
            revisions = revisions,
            preview = revisionPreview,
            saving = saving,
            canRestore = canEdit,
            onOpenRevision = onOpenRevision,
            onRestore = onRestoreRevision,
            onClosePreview = onCloseRevisionPreview,
            onDismiss = { historyDialog = false; onCloseHistory() },
        )
    }

    val toggleSourceMode = {
        if (sourceMode) {
            blockMarkdown = sourceMarkdown
            sourceMode = false
        } else {
            val latest = latestVisualMarkdown()
            publishDraft(latest)
            sourceMarkdown = latest
            sourceMode = true
        }
    }
    val togglePreviewMode = {
        if (!previewMode) {
            val latest = if (sourceMode) sourceMarkdown else latestVisualMarkdown()
            if (!sourceMode) publishDraft(latest)
        }
        previewMode = !previewMode
    }
    val saveDocument = {
        val latest = if (sourceMode) sourceMarkdown else latestVisualMarkdown()
        publishDraft(latest)
        onSave()
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactHeader = maxWidth < 620.dp
            if (compactHeader) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DocumentTitleBlock(
                            title = title,
                            onTitleChange = { title = it },
                            canEdit = canEdit,
                            creating = tab.creating,
                            revision = tab.revision,
                            dirty = dirty,
                            modifier = Modifier.weight(1f),
                        )
                        if (canEdit) DocumentSaveAction(
                            saving = saving,
                            enabled = title.isNotBlank() && !saving && (dirty || tab.creating),
                            compact = true,
                            onSave = saveDocument,
                        )
                    }
                    if (canEdit || !tab.creating) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DocumentHeaderActions(
                                canEdit = canEdit,
                                creating = tab.creating,
                                previewMode = previewMode,
                                sourceMode = sourceMode,
                                documentMenu = documentMenu,
                                onToggleSource = toggleSourceMode,
                                onTogglePreview = togglePreviewMode,
                                onShowHistory = { historyDialog = true; onShowHistory() },
                                onShowDocumentMenu = { documentMenu = true },
                                onDismissDocumentMenu = { documentMenu = false },
                                onDelete = { documentMenu = false; deleteDialog = true },
                            )
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DocumentTitleBlock(
                        title = title,
                        onTitleChange = { title = it },
                        canEdit = canEdit,
                        creating = tab.creating,
                        revision = tab.revision,
                        dirty = dirty,
                        modifier = Modifier.weight(1f),
                    )
                    DocumentHeaderActions(
                        canEdit = canEdit,
                        creating = tab.creating,
                        previewMode = previewMode,
                        sourceMode = sourceMode,
                        documentMenu = documentMenu,
                        onToggleSource = toggleSourceMode,
                        onTogglePreview = togglePreviewMode,
                        onShowHistory = { historyDialog = true; onShowHistory() },
                        onShowDocumentMenu = { documentMenu = true },
                        onDismissDocumentMenu = { documentMenu = false },
                        onDelete = { documentMenu = false; deleteDialog = true },
                    )
                    if (canEdit) DocumentSaveAction(
                        saving = saving,
                        enabled = title.isNotBlank() && !saving && (dirty || tab.creating),
                        compact = false,
                        onSave = saveDocument,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (canEdit && !previewMode && !sourceMode) {
                DocumentBlockFormattingToolbar(
                    controller = blockController,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                )
            } else {
                Text(
                    if (previewMode || !canEdit) {
                        "Markdown 预览"
                    } else {
                        "Markdown 源码"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (previewMode || !canEdit) {
                if (currentMarkdown.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("文档内容为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    DocumentMarkdownPreview(
                        markdown = currentMarkdown,
                        modifier = Modifier.fillMaxSize(),
                        onUrlClick = { url -> normalizeRichTextLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
                    )
                }
            } else if (sourceMode) {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = sourceMarkdown,
                        onValueChange = { sourceMarkdown = it },
                        modifier = Modifier.fillMaxSize().testTag("documents.editor.source.body").padding(18.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = androidx.compose.material3.LocalContentColor.current,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                    if (sourceMarkdown.isEmpty()) Text(
                        "输入 Markdown 正文…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            } else {
                DocumentBlockEditor(
                    documentKey = editorKey,
                    initialMarkdown = blockMarkdown,
                    controller = blockController,
                    onMarkdownChange = { blockMarkdown = it },
                    modifier = Modifier.fillMaxSize().testTag("documents.editor.body"),
                )
            }
        }
    }
}

@Composable
private fun DocumentTitleBlock(
    title: String,
    onTitleChange: (String) -> Unit,
    canEdit: Boolean,
    creating: Boolean,
    revision: Long?,
    dirty: Boolean,
    modifier: Modifier = Modifier,
) {
    if (canEdit) {
        Column(modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            Box(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth().testTag("documents.editor.title"),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        color = androidx.compose.material3.LocalContentColor.current,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                )
                if (title.isEmpty()) Text(
                    "文档标题",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (creating) "新文档 · 尚未保存" else "版本 ${revision ?: 1}${if (dirty) " · 未保存" else " · 已保存"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(modifier.padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(
                "只读 · 版本 ${revision ?: 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentHeaderActions(
    canEdit: Boolean,
    creating: Boolean,
    previewMode: Boolean,
    sourceMode: Boolean,
    documentMenu: Boolean,
    onToggleSource: () -> Unit,
    onTogglePreview: () -> Unit,
    onShowHistory: () -> Unit,
    onShowDocumentMenu: () -> Unit,
    onDismissDocumentMenu: () -> Unit,
    onDelete: () -> Unit,
) {
    if (canEdit) {
        if (!previewMode) TextButton(
            onClick = onToggleSource,
            modifier = Modifier.testTag("documents.editor.source"),
        ) {
            Icon(if (sourceMode) Icons.Filled.Edit else Icons.Filled.Code, null, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (sourceMode) "富文本" else "源码")
        }
        TextButton(
            onClick = onTogglePreview,
            modifier = Modifier.testTag("documents.editor.preview"),
        ) {
            Icon(if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility, null, Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (previewMode) "编辑" else "预览")
        }
    }
    if (!creating) {
        IconButton(
            onClick = onShowHistory,
            modifier = Modifier.testTag("documents.editor.history"),
        ) { Icon(Icons.Filled.History, contentDescription = "版本历史") }
        if (canEdit) Box {
            IconButton(
                onClick = onShowDocumentMenu,
                modifier = Modifier.testTag("documents.document.more"),
            ) { Icon(Icons.Filled.MoreVert, contentDescription = "文档更多操作") }
            DropdownMenu(expanded = documentMenu, onDismissRequest = onDismissDocumentMenu) {
                DropdownMenuItem(
                    text = { Text("删除文档", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = onDelete,
                    modifier = Modifier.testTag("documents.document.delete"),
                )
            }
        }
    }
}

@Composable
private fun DocumentSaveAction(
    saving: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onSave: () -> Unit,
) {
    if (compact) {
        IconButton(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier.testTag("documents.editor.save"),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Save, contentDescription = "保存文档")
        }
    } else {
        Button(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier.testTag("documents.editor.save"),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (saving) "保存中" else "保存")
        }
    }
}

@Composable
private fun DocumentRevisionDialog(
    title: String,
    currentRevision: Long,
    revisions: List<DocumentRevisionSummary>,
    preview: DocumentRevision?,
    saving: Boolean,
    canRestore: Boolean,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestore: () -> Unit,
    onClosePreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 720.dp).testTag("documents.revisions.dialog"),
        title = { Text("$title · 版本历史") },
        text = {
            if (preview != null) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClosePreview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        Text("版本 ${preview.revision}", style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                    DocumentMarkdownPreview(
                        markdown = preview.markdown,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp),
                        onUrlClick = { url -> normalizeRichTextLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
                    )
                }
            } else if (revisions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(revisions, key = { it.revision }) { revision ->
                        ListItem(
                            modifier = Modifier.clickable { onOpenRevision(revision) }
                                .testTag("documents.revision.${revision.revision}"),
                            headlineContent = { Text("版本 ${revision.revision} · ${revision.title}") },
                            supportingContent = { Text("${revision.contentLength} 个字符") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (preview != null && preview.revision != currentRevision && canRestore) Button(
                onClick = onRestore,
                enabled = !saving,
                modifier = Modifier.testTag("documents.revision.restore"),
            ) { Text("恢复为新版本") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
