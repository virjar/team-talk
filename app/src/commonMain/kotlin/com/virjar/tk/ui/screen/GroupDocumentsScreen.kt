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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import com.virjar.tk.ui.component.ScreenHeader
import com.virjar.tk.ui.component.rich.MarkdownText

/**
 * 群文档工作区。宽屏为列表 + 编辑器，窄屏为分层单页。
 *
 * 这是版本化协作编辑器，不声称提供实时共同编辑；保存时由服务端 revision 检测并发冲突。
 */
@Composable
fun GroupDocumentsScreen(
    documents: List<DocumentSummary>,
    selected: Document?,
    creating: Boolean,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    loading: Boolean,
    loadingDocument: Boolean,
    saving: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (DocumentSummary) -> Unit,
    onCloseEditor: () -> Unit,
    onSave: (title: String, markdown: String) -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    onBack: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        var pendingOpen by remember { mutableStateOf<DocumentSummary?>(null) }
        var pendingCreate by remember { mutableStateOf(false) }
        var pendingBack by remember { mutableStateOf(false) }
        var pendingCloseEditor by remember { mutableStateOf(false) }
        var deleteDialog by remember { mutableStateOf(false) }
        var historyDialog by remember { mutableStateOf(false) }

        val editorVisible = creating || selected != null
        val editorKey = when {
            creating -> "new"
            selected != null -> "${selected.documentId}:${selected.revision}"
            else -> "none"
        }
        val richState = rememberRichTextState()
        val inputFocus = remember { FocusRequester() }
        var title by remember(editorKey) { mutableStateOf(selected?.title.orEmpty()) }
        var baselineMarkdown by remember(editorKey) { mutableStateOf(selected?.markdown.orEmpty()) }
        var editorReady by remember(editorKey) { mutableStateOf(false) }
        var previewMode by remember(editorKey) { mutableStateOf(false) }

        LaunchedEffect(editorKey) {
            richState.setMarkdown(selected?.markdown.orEmpty())
            // RichEditor 会把等价 Markdown 规范化（例如列表和样式标记）。脏状态必须与
            // 编辑器加载后的规范形式比较，否则保存成功后可能立即重新显示“未保存”。
            baselineMarkdown = richState.toMarkdown()
            editorReady = editorVisible
        }
        // 订阅编辑器文本变化，随后再导出 Markdown，避免仅样式变化不触发 dirty 计算。
        val currentMarkdown = if (editorReady) {
            richState.annotatedString.text
            richState.toMarkdown()
        } else selected?.markdown.orEmpty()
        val dirty = editorReady && editorVisible &&
            (title != selected?.title.orEmpty() || currentMarkdown != baselineMarkdown)

        fun requestOpen(summary: DocumentSummary) {
            if (dirty) pendingOpen = summary else onOpen(summary)
        }

        fun requestCreate() {
            if (dirty) pendingCreate = true else onCreate()
        }

        fun requestBack() {
            if (dirty) pendingBack = true else onBack()
        }

        fun requestCloseEditor() {
            if (dirty) pendingCloseEditor = true else onCloseEditor()
        }

        val dismissDiscard = {
            pendingOpen = null
            pendingCreate = false
            pendingBack = false
            pendingCloseEditor = false
        }
        if (pendingOpen != null || pendingCreate || pendingBack || pendingCloseEditor) {
            AlertDialog(
                onDismissRequest = dismissDiscard,
                modifier = Modifier.testTag("group.documents.discard.dialog"),
                title = { Text("放弃未保存的修改？") },
                text = { Text("当前编辑内容还没有保存，放弃后无法恢复。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = pendingOpen
                            val create = pendingCreate
                            val back = pendingBack
                            val close = pendingCloseEditor
                            dismissDiscard()
                            when {
                                target != null -> onOpen(target)
                                create -> onCreate()
                                back -> onBack()
                                close -> onCloseEditor()
                            }
                        },
                        modifier = Modifier.testTag("group.documents.discard.confirm"),
                    ) { Text("放弃修改") }
                },
                dismissButton = {
                    TextButton(onClick = dismissDiscard) { Text("继续编辑") }
                },
            )
        }

        if (deleteDialog && selected != null) {
            AlertDialog(
                onDismissRequest = { deleteDialog = false },
                modifier = Modifier.testTag("group.documents.delete.dialog"),
                title = { Text("删除“${selected.title}”？") },
                text = { Text("文档将从群工作区移除。历史修订不会继续对客户端开放。") },
                confirmButton = {
                    TextButton(
                        onClick = { deleteDialog = false; onDelete() },
                        modifier = Modifier.testTag("group.documents.delete.confirm"),
                    ) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
            )
        }

        if (historyDialog && selected != null) {
            DocumentHistoryDialog(
                document = selected,
                revisions = revisions,
                preview = revisionPreview,
                saving = saving,
                onOpenRevision = onOpenRevision,
                onRestore = onRestoreRevision,
                onClosePreview = onCloseRevisionPreview,
                onDismiss = { historyDialog = false; onCloseHistory() },
            )
        }

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = if (!wide && editorVisible) (selected?.title ?: "新建文档") else "协作文档",
                onBack = if (!wide && editorVisible) ::requestCloseEditor else ::requestBack,
                trailing = {
                    if (wide && editorVisible) {
                        IconButton(onClick = ::requestCloseEditor, modifier = Modifier.testTag("group.documents.editor.close")) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭文档")
                        }
                    }
                },
            )
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    DocumentListPane(
                        documents = documents,
                        selectedId = selected?.documentId,
                        loading = loading,
                        onRefresh = onRefresh,
                        onCreate = ::requestCreate,
                        onOpen = ::requestOpen,
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (editorVisible) {
                            DocumentEditorPane(
                                title = title,
                                onTitleChange = { title = it },
                                richState = richState,
                                inputFocus = inputFocus,
                                previewMode = previewMode,
                                onPreviewModeChange = { previewMode = it },
                                dirty = dirty,
                                creating = creating,
                                revision = selected?.revision,
                                saving = saving,
                                onSave = { onSave(title, richState.toMarkdown()) },
                                onHistory = {
                                    historyDialog = true
                                    onShowHistory()
                                },
                                onDelete = { deleteDialog = true },
                            )
                        } else {
                            EmptyDocumentSelection(Modifier.align(Alignment.Center))
                        }
                        if (loadingDocument) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            } else if (editorVisible) {
                DocumentEditorPane(
                    title = title,
                    onTitleChange = { title = it },
                    richState = richState,
                    inputFocus = inputFocus,
                    previewMode = previewMode,
                    onPreviewModeChange = { previewMode = it },
                    dirty = dirty,
                    creating = creating,
                    revision = selected?.revision,
                    saving = saving,
                    onSave = { onSave(title, richState.toMarkdown()) },
                    onHistory = {
                        historyDialog = true
                        onShowHistory()
                    },
                    onDelete = { deleteDialog = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                DocumentListPane(
                    documents = documents,
                    selectedId = null,
                    loading = loading || loadingDocument,
                    onRefresh = onRefresh,
                    onCreate = ::requestCreate,
                    onOpen = ::requestOpen,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DocumentListPane(
    documents: List<DocumentSummary>,
    selectedId: String?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (DocumentSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onCreate,
                modifier = Modifier.weight(1f).testTag("group.documents.create"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建文档")
            }
            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.testTag("group.documents.refresh")) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新文档")
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            when {
                loading && documents.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                documents.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("这里还没有文档", style = MaterialTheme.typography.titleSmall)
                    Text("创建 Markdown 文档，沉淀群内知识", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(documents, key = { it.documentId }) { document ->
                        val selected = document.documentId == selectedId
                        ListItem(
                            modifier = Modifier
                                .clickable { onOpen(document) }
                                .testTag("group.documents.item.${document.documentId.take(8)}"),
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Article, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(document.excerpt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = { Text("v${document.revision}", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentEditorPane(
    title: String,
    onTitleChange: (String) -> Unit,
    richState: com.mohamedrejeb.richeditor.model.RichTextState,
    inputFocus: FocusRequester,
    previewMode: Boolean,
    onPreviewModeChange: (Boolean) -> Unit,
    dirty: Boolean,
    creating: Boolean,
    revision: Long?,
    saving: Boolean,
    onSave: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bold = remember { SpanStyle(fontWeight = FontWeight.Bold) }
    val italic = remember { SpanStyle(fontStyle = FontStyle.Italic) }
    val strike = remember { SpanStyle(textDecoration = TextDecoration.LineThrough) }

    Column(modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth().testTag("group.documents.title"),
            textStyle = MaterialTheme.typography.titleLarge,
            singleLine = true,
            placeholder = { Text("文档标题") },
            supportingText = {
                Text(if (creating) "新文档" else "版本 ${revision ?: 1}${if (dirty) " · 未保存" else " · 已保存"}")
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!previewMode) {
                FormatButton("B") { richState.toggleSpanStyle(bold); inputFocus.requestFocus() }
                FormatButton("I") { richState.toggleSpanStyle(italic); inputFocus.requestFocus() }
                FormatButton("S") { richState.toggleSpanStyle(strike); inputFocus.requestFocus() }
                FormatButton("</>") { richState.toggleCodeSpan(); inputFocus.requestFocus() }
            } else {
                Text("Markdown 预览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onPreviewModeChange(!previewMode) },
                modifier = Modifier.testTag("group.documents.preview"),
            ) {
                Icon(if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (previewMode) "编辑" else "预览")
            }
            if (!creating) {
                IconButton(onClick = onHistory, modifier = Modifier.testTag("group.documents.history")) {
                    Icon(Icons.Filled.History, contentDescription = "版本历史")
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("group.documents.delete")) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除文档")
                }
            }
            Button(
                onClick = onSave,
                enabled = title.isNotBlank() && !saving && (dirty || creating),
                modifier = Modifier.testTag("group.documents.save"),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (saving) "保存中" else "保存")
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (previewMode) {
                val markdown = richState.toMarkdown()
                if (markdown.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("文档内容为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    MarkdownText(
                        content = markdown,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    BasicRichTextEditor(
                        state = richState,
                        modifier = Modifier.fillMaxSize().testTag("group.documents.editor").focusRequester(inputFocus).padding(18.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = androidx.compose.material3.LocalContentColor.current),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                    if (richState.annotatedString.text.isEmpty()) {
                        Text(
                            "使用 Markdown 组织正文，支持多行、粗体、斜体、删除线和代码。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(label, fontWeight = if (label == "B") FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun EmptyDocumentSelection(modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Filled.Article, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(14.dp))
        Text("选择或新建一篇文档", style = MaterialTheme.typography.titleMedium)
        Text("文档保存后对当前群成员可见", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocumentHistoryDialog(
    document: Document,
    revisions: List<DocumentRevisionSummary>,
    preview: DocumentRevision?,
    saving: Boolean,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestore: () -> Unit,
    onClosePreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 720.dp).testTag("group.documents.revisions.dialog"),
        title = { Text("${document.title} · 版本历史") },
        text = {
            if (preview != null) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClosePreview) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                        Text("版本 ${preview.revision}", style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                    MarkdownText(
                        preview.markdown,
                        Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            } else if (revisions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(revisions, key = { it.revision }) { revision ->
                        ListItem(
                            modifier = Modifier.clickable { onOpenRevision(revision) }
                                .testTag("group.documents.revision.${revision.revision}"),
                            headlineContent = { Text("版本 ${revision.revision} · ${revision.title}") },
                            supportingContent = { Text("${revision.contentLength} 个字符") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (preview != null && preview.revision != document.revision) {
                Button(
                    onClick = onRestore,
                    enabled = !saving,
                    modifier = Modifier.testTag("group.documents.restore"),
                ) { Text("恢复为新版本") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
