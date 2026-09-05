package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.app.navigation.feature.document.DocumentTreeRow

private data class DocumentMoveTarget(val parentId: String?)

/** 紧凑的 lazy 树选择器。展开行时委托给工作区的分支加载器。 */
@Composable
internal fun DocumentMoveDialog(
    document: DocumentTabState,
    treeRows: List<DocumentTreeRow>,
    expandedNodeIds: Set<String>,
    blockedNodeIds: Set<String>,
    moving: Boolean,
    onToggleNode: (DocumentNode) -> Unit,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember(document.instanceId) { mutableStateOf<DocumentMoveTarget?>(null) }
    val targetParentId = target?.parentId
    val canConfirm = target != null && targetParentId != document.parentId && !moving
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动“${document.draftTitle}”到…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item(key = "root") {
                    DocumentMoveTargetRow(
                        title = "空间顶层",
                        depth = 0,
                        selected = target?.parentId == null && target != null,
                        enabled = document.parentId != null,
                        testTag = "documents.move.target.root",
                        onSelect = { target = DocumentMoveTarget(null) },
                    )
                }
                items(treeRows, key = { it.node.nodeId }) { row ->
                    val blocked = row.node.nodeId == document.documentId || row.node.nodeId in blockedNodeIds
                    DocumentMoveTargetRow(
                        title = row.node.name,
                        depth = row.depth,
                        selected = targetParentId == row.node.nodeId,
                        enabled = !blocked && row.node.nodeId != document.parentId,
                        testTag = "documents.move.target.${row.node.nodeId.take(12)}",
                        expanded = row.node.nodeId in expandedNodeIds,
                        expandable = row.hasChildren,
                        onToggle = { onToggleNode(row.node) },
                        onSelect = { target = DocumentMoveTarget(row.node.nodeId) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(targetParentId) },
                modifier = Modifier.testTag("documents.move.confirm"),
            ) { Text(if (moving) "移动中" else "移动") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        modifier = Modifier.testTag("documents.move.dialog"),
    )
}

@Composable
private fun DocumentMoveTargetRow(
    title: String,
    depth: Int,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    expanded: Boolean = false,
    expandable: Boolean = false,
    onToggle: () -> Unit = {},
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(testTag)
            .clickable(enabled = enabled, onClick = onSelect)
            // 协议允许深层树，但无界缩进会在远未达到该上限前就把每个操作挤出屏幕。
            // 与主树的紧凑视觉上限保持一致。
            .padding(start = (depth.coerceIn(0, 14) * 12).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expandable) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(34.dp).testTag("documents.move.expand.${testTag.substringAfterLast('.')}")
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起子页面" else "展开子页面",
                )
            }
        } else {
            Spacer(Modifier.width(34.dp))
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Icon(Icons.Filled.Description, null, Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!enabled) Box(Modifier.padding(horizontal = 6.dp)) {
            Text("不可选", style = MaterialTheme.typography.labelSmall)
        }
    }
}
