package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.app.ui.component.ScreenHeader

/** 群文件空间。平台壳负责文件选择、上传与系统打开，本组件只表达业务交互。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupFilesScreen(
    entries: List<GroupFileEntry>,
    path: List<GroupFileEntry>,
    selectedFile: GroupFileEntry?,
    versions: List<GroupFileVersion>,
    loading: Boolean,
    uploading: Boolean,
    /** true=当前列表是离线本地投影（stale 展示），不是权威页。 */
    stale: Boolean = false,
    onRefresh: () -> Unit,
    onEnter: (GroupFileEntry) -> Unit,
    onUp: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onUpload: () -> Unit,
    onOpenFile: (Attachment) -> Unit,
    onShowVersions: (GroupFileEntry?) -> Unit,
    onUploadVersion: (GroupFileEntry) -> Unit,
    onRename: (GroupFileEntry, String) -> Unit,
    onDelete: (GroupFileEntry) -> Unit,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    var showCreateFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<GroupFileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<GroupFileEntry?>(null) }

    if (showCreateFolder) {
        NameDialog(
            title = "新建文件夹",
            initialValue = "",
            confirmLabel = "创建",
            testTagPrefix = "group.files.createFolder",
            onDismiss = { showCreateFolder = false },
            onConfirm = { showCreateFolder = false; onCreateFolder(it) },
        )
    }
    renameTarget?.let { target ->
        NameDialog(
            title = "重命名",
            initialValue = target.name,
            confirmLabel = "保存",
            testTagPrefix = "group.files.rename",
            onDismiss = { renameTarget = null },
            onConfirm = { renameTarget = null; onRename(target, it) },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            modifier = Modifier.testTag("group.files.delete.dialog"),
            title = { Text("删除“${target.name}”？") },
            text = {
                Text(if (target.kind == GroupFileEntry.KIND_FOLDER) "只能删除空文件夹。" else "该文件及其版本将不再对群成员开放。")
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteTarget = null; onDelete(target) },
                    modifier = Modifier.testTag("group.files.delete.confirm"),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.testTag("group.files.delete.cancel"),
                ) { Text("取消") }
            },
        )
    }
    if (selectedFile != null) {
        VersionDialog(
            file = selectedFile,
            versions = versions,
            onOpen = onOpenFile,
            onUploadVersion = { onShowVersions(null); onUploadVersion(selectedFile) },
            onDismiss = { onShowVersions(null) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "群文件",
            onBack = onBack,
            trailing = {
                if (onBack == null && onClose != null) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("group.files.close")) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭群文件")
                    }
                }
            },
        )

        if (stale) {
            // 离线 stale 横幅（文档工作区范式）：明确本地投影状态，不作为远端操作依据。
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("group.files.stale"),
            ) {
                Text(
                    "群文件服务暂不可用，已显示本地缓存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onUpload,
                enabled = !uploading,
                modifier = Modifier.weight(1f).testTag("group.files.upload"),
            ) {
                if (uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.UploadFile, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (uploading) "上传中" else "上传文件")
            }
            OutlinedButton(
                onClick = { showCreateFolder = true },
                modifier = Modifier.weight(1f).testTag("group.files.createFolder"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("新建文件夹")
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        if (path.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onUp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    path.joinToString(" / ") { it.name },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading && entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                entries.isEmpty() -> EmptyGroupFiles(Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.entryId }) { entry ->
                        GroupFileRow(
                            entry = entry,
                            onOpen = {
                                if (entry.kind == GroupFileEntry.KIND_FOLDER) onEnter(entry)
                                else entry.attachment?.let(onOpenFile)
                            },
                            onVersions = { onShowVersions(entry) },
                            onUploadVersion = { onUploadVersion(entry) },
                            onRename = { renameTarget = entry },
                            onDelete = { deleteTarget = entry },
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupFileRow(
    entry: GroupFileEntry,
    onOpen: () -> Unit,
    onVersions: () -> Unit,
    onUploadVersion: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).testTag("group.files.entry.${entry.entryId.take(8)}"),
        leadingContent = {
            Icon(
                if (entry.kind == GroupFileEntry.KIND_FOLDER) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (entry.kind == GroupFileEntry.KIND_FOLDER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (entry.kind == GroupFileEntry.KIND_FOLDER) "文件夹"
                else "${formatBytes(entry.attachment?.size ?: 0)} · v${entry.contentVersion}",
            )
        },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("group.files.entry.${entry.entryId.take(8)}.more"),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (entry.kind == GroupFileEntry.KIND_FILE) {
                        DropdownMenuItem(
                            text = { Text("版本记录") },
                            leadingIcon = { Icon(Icons.Filled.History, null) },
                            onClick = { menuOpen = false; onVersions() },
                        )
                        DropdownMenuItem(
                            text = { Text("上传新版本") },
                            leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                            onClick = { menuOpen = false; onUploadVersion() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
    )
}

@Composable
private fun EmptyGroupFiles(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text("这里还没有文件", style = MaterialTheme.typography.titleSmall)
        Text("上传文件或新建文件夹开始协作", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    testTagPrefix: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("$testTagPrefix.dialog"),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("$testTagPrefix.input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.testTag("$testTagPrefix.confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("$testTagPrefix.cancel")) { Text("取消") }
        },
    )
}

@Composable
private fun VersionDialog(
    file: GroupFileEntry,
    versions: List<GroupFileVersion>,
    onOpen: (Attachment) -> Unit,
    onUploadVersion: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("group.files.versions.dialog"),
        title = { Text("${file.name} 的版本") },
        text = {
            if (versions.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                    items(versions, key = { it.version }) { version ->
                        ListItem(
                            headlineContent = { Text("版本 ${version.version}") },
                            supportingContent = { Text("${formatBytes(version.attachment.size)} · ${version.attachment.name}") },
                            trailingContent = {
                                TextButton(
                                    onClick = { onOpen(version.attachment) },
                                    modifier = Modifier.testTag("group.files.version.${version.version}.open"),
                                ) { Text("打开") }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onUploadVersion, modifier = Modifier.testTag("group.files.versions.upload")) {
                Text("上传新版本")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("group.files.versions.close")) { Text("关闭") }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
