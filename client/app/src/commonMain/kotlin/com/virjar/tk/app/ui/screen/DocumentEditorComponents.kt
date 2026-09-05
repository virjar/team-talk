package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.app.ui.component.rich.DocumentMarkdownPreview
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetMarkdownContent
import com.virjar.tk.app.ui.component.rich.normalizeRichTextLink

@Composable
internal fun DocumentTitleBlock(
    title: String,
    onTitleChange: (String) -> Unit,
    canEdit: Boolean,
    creating: Boolean,
    remoteMissing: Boolean,
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
                when {
                    remoteMissing -> "原文档已删除 · 本机草稿"
                    creating -> "新文档 · 尚未保存"
                    else -> "版本 ${revision ?: 1}${if (dirty) " · 未保存" else " · 已保存"}"
                },
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
internal fun DocumentHeaderActions(
    canEdit: Boolean,
    creating: Boolean,
    remoteMissing: Boolean,
    historyAvailable: Boolean,
    moveEnabled: Boolean,
    moveDisabledMessage: String?,
    previewMode: Boolean,
    sourceMode: Boolean,
    documentMenu: Boolean,
    onToggleSource: () -> Unit,
    onTogglePreview: () -> Unit,
    onShowHistory: () -> Unit,
    onShowDocumentMenu: () -> Unit,
    onDismissDocumentMenu: () -> Unit,
    onMove: () -> Unit,
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
    if (!creating && !remoteMissing) {
        if (historyAvailable) {
            IconButton(
                onClick = onShowHistory,
                modifier = Modifier.testTag("documents.editor.history"),
            ) { Icon(Icons.Filled.History, contentDescription = "版本历史") }
        }
        if (canEdit) Box {
            IconButton(
                onClick = onShowDocumentMenu,
                modifier = Modifier.testTag("documents.document.more"),
            ) { Icon(Icons.Filled.MoreVert, contentDescription = "文档更多操作") }
            DropdownMenu(expanded = documentMenu, onDismissRequest = onDismissDocumentMenu) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("移动到…")
                            moveDisabledMessage?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                    enabled = moveEnabled,
                    onClick = onMove,
                    modifier = Modifier.testTag("documents.document.move"),
                )
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
internal fun DocumentSaveAction(
    saving: Boolean,
    enabled: Boolean,
    compact: Boolean,
    saveAsNew: Boolean,
    onSave: () -> Unit,
) {
    if (compact) {
        IconButton(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier.testTag("documents.editor.save"),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(
                Icons.Filled.Save,
                contentDescription = if (saveAsNew) "另存为新文档" else "保存文档",
            )
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
            Text(if (saving) "保存中" else if (saveAsNew) "另存为新文档" else "保存")
        }
    }
}

@Composable
internal fun DocumentRevisionDialog(
    title: String,
    currentRevision: Long,
    revisions: List<DocumentRevisionSummary>,
    preview: DocumentRevision?,
    loadingRevisions: Boolean,
    loadingMoreRevisions: Boolean,
    hasMoreRevisions: Boolean,
    saving: Boolean,
    canRestore: Boolean,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onLoadMore: () -> Unit,
    onRestore: () -> Unit,
    onClosePreview: () -> Unit,
    onDismiss: () -> Unit,
    embeddedAssetContent: EmbeddedAssetMarkdownContent? = null,
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
                        IconButton(
                            onClick = onClosePreview,
                            modifier = Modifier.testTag("documents.revisions.preview.back"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回版本列表")
                        }
                        Text("版本 ${preview.revision}", style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                    DocumentMarkdownPreview(
                        markdown = preview.markdown,
                        assets = preview.assets,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp),
                        onUrlClick = { url -> normalizeRichTextLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
                        embeddedAssetContent = embeddedAssetContent,
                    )
                }
            } else if (revisions.isEmpty() && loadingRevisions) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (revisions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("暂无版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    if (hasMoreRevisions || loadingMoreRevisions) {
                        item(key = "load-more-revisions") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                TextButton(
                                    onClick = onLoadMore,
                                    enabled = !loadingMoreRevisions,
                                    modifier = Modifier.testTag("documents.revisions.load-more"),
                                ) {
                                    if (loadingMoreRevisions) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(if (loadingMoreRevisions) "加载中" else "加载更多")
                                }
                            }
                        }
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
