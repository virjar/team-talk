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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.navigation.feature.DocumentTabState
import com.virjar.tk.ui.component.rich.DocumentMarkdownCompatibility
import com.virjar.tk.ui.component.rich.MarkdownText
import com.virjar.tk.ui.component.rich.RichTextFormattingToolbar
import com.virjar.tk.ui.component.rich.RichTextToolbarMode
import com.virjar.tk.ui.component.rich.normalizeRichTextLink

@Composable
internal fun DocumentTabEditor(
    tab: DocumentTabState,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    saving: Boolean,
    canEdit: Boolean,
    onUpdateDraft: (String, String, String, Boolean) -> Unit,
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
    val richState = rememberRichTextState()
    val inputFocus = remember { FocusRequester() }
    val initialTitle = remember(editorKey) { tab.draftTitle }
    var title by remember(editorKey) { mutableStateOf(tab.draftTitle) }
    var baselineMarkdown by remember(editorKey) { mutableStateOf(tab.savedMarkdown) }
    var sourceMarkdown by remember(editorKey) { mutableStateOf(tab.draftMarkdown) }
    var sourceMode by remember(editorKey) {
        mutableStateOf(DocumentMarkdownCompatibility.inspect(tab.draftMarkdown).requiresSourceMode)
    }
    var editorReady by remember(editorKey) { mutableStateOf(false) }
    var dirty by remember(editorKey) { mutableStateOf(tab.dirty || tab.creating) }
    var previewMode by remember(editorKey, canEdit) { mutableStateOf(!canEdit) }
    var historyDialog by remember(editorKey) { mutableStateOf(false) }
    var deleteDialog by remember(editorKey) { mutableStateOf(false) }
    var documentMenu by remember(editorKey) { mutableStateOf(false) }
    var pendingRichEditorFocus by remember(editorKey) { mutableStateOf(false) }

    LaunchedEffect(editorKey) {
        editorReady = false
        sourceMarkdown = tab.draftMarkdown
        sourceMode = DocumentMarkdownCompatibility.inspect(tab.draftMarkdown).requiresSourceMode
        if (!sourceMode) richState.setMarkdown(tab.draftMarkdown)
        // RichTextEditor 会在首次布局时补齐段落与列表信息。等两帧后再记录干净基线，
        // 避免把编辑器自身的 Markdown 规范化误判成用户修改。
        withFrameNanos { }
        withFrameNanos { }
        if (!tab.dirty && !tab.creating) {
            baselineMarkdown = if (sourceMode) tab.savedMarkdown else richState.toMarkdown()
        }
        editorReady = true
    }
    val currentMarkdown = if (editorReady) {
        if (sourceMode) sourceMarkdown else richState.toMarkdown()
    } else tab.draftMarkdown
    val sourceCompatibility = remember(sourceMarkdown) { DocumentMarkdownCompatibility.inspect(sourceMarkdown) }
    val previewCompatibility = remember(currentMarkdown) { DocumentMarkdownCompatibility.inspect(currentMarkdown) }
    LaunchedEffect(editorReady, title, currentMarkdown, sourceMode) {
        if (!editorReady) return@LaunchedEffect
        if (title != initialTitle || currentMarkdown != baselineMarkdown) dirty = true
        onUpdateDraft(tab.tabId, title, currentMarkdown, dirty)
    }
    LaunchedEffect(pendingRichEditorFocus, sourceMode, previewMode, canEdit) {
        if (pendingRichEditorFocus && canEdit && !sourceMode && !previewMode) {
            // sourceMode 变更提交后 BasicRichTextEditor 才会进入焦点树，再等一帧请求焦点。
            // 直接在按钮回调中请求会命中尚未挂载的 FocusRequester 并抛异常。
            withFrameNanos { }
            pendingRichEditorFocus = false
            inputFocus.requestFocus()
        }
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
            if (!sourceCompatibility.requiresSourceMode) {
                richState.setMarkdown(sourceMarkdown)
                sourceMode = false
                pendingRichEditorFocus = true
            }
        } else {
            sourceMarkdown = richState.toMarkdown()
            sourceMode = true
        }
    }
    val saveDocument = {
        onUpdateDraft(tab.tabId, title, currentMarkdown, dirty)
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
                                canUseRichMode = !sourceCompatibility.requiresSourceMode,
                                documentMenu = documentMenu,
                                onToggleSource = toggleSourceMode,
                                onTogglePreview = { previewMode = !previewMode },
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
                        canUseRichMode = !sourceCompatibility.requiresSourceMode,
                        documentMenu = documentMenu,
                        onToggleSource = toggleSourceMode,
                        onTogglePreview = { previewMode = !previewMode },
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
                RichTextFormattingToolbar(
                    state = richState,
                    mode = RichTextToolbarMode.DOCUMENT,
                    onRequestFocus = { inputFocus.requestFocus() },
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    testTagPrefix = "documents.editor.format",
                )
            } else {
                Text(
                    if ((previewMode || !canEdit) && previewCompatibility.requiresSourceMode) {
                        "Markdown 只读源码"
                    } else if (previewMode || !canEdit) {
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
        if (canEdit && !previewMode && sourceMode && sourceCompatibility.requiresSourceMode) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "正文包含代码块、引用、表格或其他高级 Markdown，已使用源码模式保护原始内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                } else if (previewCompatibility.requiresSourceMode) {
                    UnsupportedMarkdownSourcePreview(currentMarkdown, Modifier.fillMaxSize())
                } else {
                    MarkdownText(
                        currentMarkdown,
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
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
                Box(Modifier.fillMaxSize()) {
                    BasicRichTextEditor(
                        state = richState,
                        modifier = Modifier.fillMaxSize().testTag("documents.editor.body").focusRequester(inputFocus).padding(18.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = androidx.compose.material3.LocalContentColor.current),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                    if (richState.annotatedString.text.isEmpty()) Text(
                        "使用 Markdown 组织正文，支持多行、粗体、斜体、删除线和代码。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp),
                    )
                }
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
    canUseRichMode: Boolean,
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
            enabled = !sourceMode || canUseRichMode,
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
private fun UnsupportedMarkdownSourcePreview(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("documents.editor.preview.source")) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.68f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    "完整预览暂不可用",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "正文包含当前预览器不能完整呈现的高级 Markdown，以下按只读源码显示，内容不会被省略。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                markdown,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
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
                val previewCompatibility = remember(preview.markdown) {
                    DocumentMarkdownCompatibility.inspect(preview.markdown)
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClosePreview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        Text("版本 ${preview.revision}", style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                    if (previewCompatibility.requiresSourceMode) {
                        UnsupportedMarkdownSourcePreview(
                            markdown = preview.markdown,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp),
                        )
                    } else {
                        MarkdownText(
                            preview.markdown,
                            Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp)
                                .verticalScroll(rememberScrollState()).padding(12.dp),
                            onUrlClick = { url -> normalizeRichTextLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
                        )
                    }
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
