package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.app.navigation.feature.document.DocumentTreeRow
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceProjectionStatus

internal fun shouldShowCreateChildDocumentAction(
    canCreateChild: Boolean,
    active: Boolean,
    mobile: Boolean,
): Boolean = canCreateChild && (active || mobile)

internal data class DocumentTreeFocusTarget(val nodeId: String, val index: Int)

/** 活动正文优先；活动行尚未加载时退回最近的父页面。 */
internal fun documentTreeFocusTarget(
    treeRows: List<DocumentTreeRow>,
    activeDocumentId: String?,
    selectedParentNodeId: String?,
): DocumentTreeFocusTarget? {
    listOfNotNull(activeDocumentId, selectedParentNodeId).distinct().forEach { nodeId ->
        val index = treeRows.indexOfFirst { it.node.nodeId == nodeId }
        if (index >= 0) return DocumentTreeFocusTarget(nodeId, index)
    }
    return null
}

/** 留两行上文，深列表定位后仍能看清目标所处层级。 */
internal fun documentTreeFocusScrollIndex(targetIndex: Int, leadingContextRows: Int = 2): Int =
    (targetIndex - leadingContextRows.coerceAtLeast(0)).coerceAtLeast(0)

internal fun shouldScrollToDocumentTreeFocus(targetIndex: Int, visibleIndices: Iterable<Int>): Boolean =
    targetIndex !in visibleIndices

/** 数百页面场景使用的紧凑懒加载树；页面正文打开与子页面展开是两个独立动作。 */
@Composable
internal fun DocumentTreePane(
    space: DocumentSpace,
    treeRows: List<DocumentTreeRow>,
    expandedNodeIds: Set<String>,
    selectedParentNodeId: String?,
    activeDocumentId: String?,
    listState: LazyListState,
    loading: Boolean,
    projectionStatus: DocumentWorkspaceProjectionStatus,
    mobile: Boolean,
    onToggleNode: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateDocument: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusTarget = remember(treeRows, activeDocumentId, selectedParentNodeId) {
        documentTreeFocusTarget(treeRows, activeDocumentId, selectedParentNodeId)
    }
    // key 刻意取语义目标，而不是每次行/索引变化。普通重组与兄弟节点展开绝不能
    // 反复把用户的手动滚动拉走。
    LaunchedEffect(focusTarget?.nodeId) {
        val target = focusTarget ?: return@LaunchedEffect
        val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (shouldScrollToDocumentTreeFocus(target.index, visibleIndices)) {
            listState.scrollToItem(documentTreeFocusScrollIndex(target.index))
        }
    }

    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.17f))
            .testTag("documents.tree"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (mobile) 48.dp else 40.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("页面", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(if (mobile) 40.dp else 32.dp)
                    .clickable(
                        enabled = space.myRole >= DocumentSpace.ROLE_EDITOR,
                        onClick = { onCreateDocument(null) },
                    )
                    .testTag("documents.document.create"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "新建顶层文档",
                    modifier = Modifier.size(18.dp),
                    tint = if (space.myRole >= DocumentSpace.ROLE_EDITOR) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            when {
                loading && treeRows.isEmpty() -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp,
                )
                treeRows.isEmpty() -> Text(
                    when (projectionStatus) {
                        DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN ->
                            "空间已不可访问，目录缓存已清除"
                        DocumentWorkspaceProjectionStatus.OFFLINE_MISSING ->
                            "文档目录尚未缓存，请联网后重试"
                        else -> "空间中还没有文档"
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(treeRows, key = { it.node.nodeId }) { row ->
                        DocumentTreeNodeRow(
                            row = row,
                            expanded = row.node.nodeId in expandedNodeIds,
                            active = row.node.nodeId == activeDocumentId,
                            canCreateChild = space.myRole >= DocumentSpace.ROLE_EDITOR,
                            mobile = mobile,
                            onToggleNode = onToggleNode,
                            onOpenDocument = onOpenDocument,
                            onCreateChildDocument = { onCreateDocument(row.node.nodeId) },
                        )
                    }
                }
            }
            if (loading && treeRows.isNotEmpty()) CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun DocumentTreeNodeRow(
    row: DocumentTreeRow,
    expanded: Boolean,
    active: Boolean,
    canCreateChild: Boolean,
    mobile: Boolean,
    onToggleNode: (DocumentNode) -> Unit,
    onOpenDocument: (DocumentNode) -> Unit,
    onCreateChildDocument: () -> Unit,
) {
    val node = row.node
    val rowHeight = if (mobile) 44.dp else 32.dp
    val disclosureSize = if (mobile) 36.dp else 24.dp
    val startPadding = (6 + row.depth.coerceIn(0, 14) * 12).dp
    Row(
        Modifier.fillMaxWidth().height(rowHeight)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { onOpenDocument(node) }
            .testTag("documents.node.${node.nodeId.take(8)}")
            .padding(start = startPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            Box(
                Modifier.size(disclosureSize)
                    .clickable { onToggleNode(node) }
                    .testTag("documents.tree.toggle.${node.nodeId.take(8)}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "折叠子文档" else "展开子文档",
                    modifier = Modifier.size(if (mobile) 20.dp else 16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.size(disclosureSize))
        }
        Spacer(Modifier.width(2.dp))
        Text(
            node.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        // Desktop 只需要活动行上的一个常驻入口。移动端没有 hover，
        // 因此每个可见行都暴露相同的小巧、显式子页面操作。
        if (shouldShowCreateChildDocumentAction(canCreateChild, active, mobile)) {
            Box(
                Modifier.size(disclosureSize)
                    .clickable(onClick = onCreateChildDocument)
                    .testTag("documents.node.${node.nodeId.take(8)}.createChild"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "新建子文档",
                    modifier = Modifier.size(if (mobile) 20.dp else 16.dp),
                )
            }
        }
    }
}
