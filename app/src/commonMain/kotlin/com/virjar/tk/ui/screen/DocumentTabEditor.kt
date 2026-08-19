package com.virjar.tk.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.navigation.feature.DocumentTabState
import com.virjar.tk.ui.component.rich.MarkdownText

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
    val editorKey = "${tab.tabId}:${tab.revision ?: 0}"
    val richState = rememberRichTextState()
    val inputFocus = remember { FocusRequester() }
    val initialTitle = remember(editorKey) { tab.draftTitle }
    var title by remember(editorKey) { mutableStateOf(tab.draftTitle) }
    var baselineMarkdown by remember(editorKey) { mutableStateOf(tab.savedMarkdown) }
    var editorReady by remember(editorKey) { mutableStateOf(false) }
    var dirty by remember(editorKey) { mutableStateOf(tab.dirty || tab.creating) }
    var previewMode by remember(editorKey, canEdit) { mutableStateOf(!canEdit) }
    var historyDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(editorKey) {
        editorReady = false
        richState.setMarkdown(tab.draftMarkdown)
        // RichTextEditor 会在首次布局时补齐段落与列表信息。等两帧后再记录干净基线，
        // 避免把编辑器自身的 Markdown 规范化误判成用户修改。
        withFrameNanos { }
        withFrameNanos { }
        if (!tab.dirty && !tab.creating) baselineMarkdown = richState.toMarkdown()
        editorReady = true
    }
    val currentMarkdown = if (editorReady) {
        richState.toMarkdown()
    } else tab.draftMarkdown
    LaunchedEffect(editorReady, title, currentMarkdown) {
        if (!editorReady) return@LaunchedEffect
        if (title != initialTitle || currentMarkdown != baselineMarkdown) dirty = true
        onUpdateDraft(tab.tabId, title, currentMarkdown, dirty)
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

    Column(modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
        if (canEdit) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().testTag("documents.editor.title"),
                textStyle = MaterialTheme.typography.titleLarge,
                singleLine = true,
                placeholder = { Text("文档标题") },
                supportingText = {
                    Text(if (tab.creating) "新文档 · 尚未保存" else "版本 ${tab.revision ?: 1}${if (dirty) " · 未保存" else " · 已保存"}")
                },
            )
        } else {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 10.dp))
            Text("只读 · 版本 ${tab.revision ?: 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (canEdit && !previewMode) {
                val bold = remember { SpanStyle(fontWeight = FontWeight.Bold) }
                val italic = remember { SpanStyle(fontStyle = FontStyle.Italic) }
                val strike = remember { SpanStyle(textDecoration = TextDecoration.LineThrough) }
                FormatButton("B") { richState.toggleSpanStyle(bold); inputFocus.requestFocus() }
                Spacer(Modifier.width(4.dp))
                FormatButton("I") { richState.toggleSpanStyle(italic); inputFocus.requestFocus() }
                Spacer(Modifier.width(4.dp))
                FormatButton("S") { richState.toggleSpanStyle(strike); inputFocus.requestFocus() }
                Spacer(Modifier.width(4.dp))
                FormatButton("</>") { richState.toggleCodeSpan(); inputFocus.requestFocus() }
            } else {
                Text("Markdown 预览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (canEdit) {
                TextButton(
                    onClick = { previewMode = !previewMode },
                    modifier = Modifier.testTag("documents.editor.preview"),
                ) {
                    Icon(if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (previewMode) "编辑" else "预览")
                }
            }
            if (!tab.creating) {
                IconButton(
                    onClick = { historyDialog = true; onShowHistory() },
                    modifier = Modifier.testTag("documents.editor.history"),
                ) { Icon(Icons.Filled.History, contentDescription = "版本历史") }
                if (canEdit) IconButton(
                    onClick = { deleteDialog = true },
                    modifier = Modifier.testTag("documents.document.delete"),
                ) { Icon(Icons.Filled.DeleteOutline, contentDescription = "删除文档") }
            }
            if (canEdit) Button(
                onClick = {
                    onUpdateDraft(tab.tabId, title, currentMarkdown, dirty)
                    onSave()
                },
                enabled = title.isNotBlank() && !saving && (dirty || tab.creating),
                modifier = Modifier.testTag("documents.editor.save"),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (saving) "保存中" else "保存")
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
                } else MarkdownText(
                    currentMarkdown,
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                )
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
private fun FormatButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(36.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
        Text(label, fontWeight = if (label == "B") FontWeight.Bold else FontWeight.Normal)
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
