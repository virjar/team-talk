package com.virjar.tk.app.ui.screen

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.app.navigation.feature.document.DocumentSpaceCreateRequest
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceProjectionStatus
import com.virjar.tk.app.ui.component.LocalScreenHeaderLeadingInset

internal fun documentHomeProjectionLabel(
    spaceStatus: DocumentWorkspaceProjectionStatus,
    homeStatus: DocumentWorkspaceProjectionStatus,
): String = when {
    spaceStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING ||
        homeStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING ->
        "当前无网络 · 部分内容尚未缓存"
    spaceStatus == DocumentWorkspaceProjectionStatus.OFFLINE_CACHED ||
        homeStatus == DocumentWorkspaceProjectionStatus.OFFLINE_CACHED ->
        "当前无网络 · 正在显示本地缓存"
    spaceStatus == DocumentWorkspaceProjectionStatus.CACHED ||
        homeStatus == DocumentWorkspaceProjectionStatus.CACHED ->
        "正在显示本地缓存 · 联网后自动更新"
    else -> "企业知识与协作空间"
}

/** 企业文档的资产入口：空间索引和跨空间最近文档。 */
@Composable
internal fun DocumentHomeScreen(
    spaces: List<DocumentSpace>,
    recentDocuments: List<DocumentHomeItem>,
    recentlyCreatedDocuments: List<DocumentHomeItem>,
    pendingSpaceCreates: List<DocumentSpaceCreateRequest>,
    loading: Boolean,
    spaceProjectionStatus: DocumentWorkspaceProjectionStatus,
    homeProjectionStatus: DocumentWorkspaceProjectionStatus,
    loadingMoreSpaces: Boolean,
    hasMoreSpaces: Boolean,
    spaceWorksetLimited: Boolean,
    spaceWorksetHasOfflineDrafts: Boolean,
    detached: Boolean,
    onRefresh: () -> Unit,
    onLoadMoreSpaces: () -> Unit,
    onCreateSpace: () -> Unit,
    onRetryPendingSpaceCreate: (String) -> Unit,
    onDiscardPendingSpaceCreate: (String) -> Unit,
    onSelectSpace: (String) -> Unit,
    onOpenDocument: (DocumentHomeItem) -> Unit,
    onDetach: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("documents.home")) {
        DocumentHomeHeader(
            detached = detached,
            statusLabel = documentHomeProjectionLabel(
                spaceProjectionStatus,
                homeProjectionStatus,
            ),
            onRefresh = onRefresh,
            onCreateSpace = onCreateSpace,
            onDetach = onDetach,
        )
        HorizontalDivider()
        if (pendingSpaceCreates.isNotEmpty()) {
            PendingDocumentSpaceCreates(
                requests = pendingSpaceCreates,
                onRetry = onRetryPendingSpaceCreate,
                onDiscard = onDiscardPendingSpaceCreate,
            )
            HorizontalDivider()
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize()) {
                    DocumentSpaceIndex(
                        spaces = spaces,
                        loading = loading,
                        loadingMore = loadingMoreSpaces,
                        hasMore = hasMoreSpaces,
                        worksetLimited = spaceWorksetLimited,
                        worksetHasOfflineDrafts = spaceWorksetHasOfflineDrafts,
                        projectionStatus = spaceProjectionStatus,
                        onCreateSpace = onCreateSpace,
                        onSelectSpace = onSelectSpace,
                        onLoadMore = onLoadMoreSpaces,
                        modifier = Modifier.width(286.dp).fillMaxHeight(),
                    )
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    DocumentRecentIndex(
                        recentDocuments = recentDocuments,
                        recentlyCreatedDocuments = recentlyCreatedDocuments,
                        loading = loading,
                        projectionStatus = homeProjectionStatus,
                        onOpenDocument = onOpenDocument,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                CompactDocumentHome(
                    spaces = spaces,
                    recentDocuments = recentDocuments,
                    recentlyCreatedDocuments = recentlyCreatedDocuments,
                    loading = loading,
                    spaceProjectionStatus = spaceProjectionStatus,
                    homeProjectionStatus = homeProjectionStatus,
                    loadingMoreSpaces = loadingMoreSpaces,
                    hasMoreSpaces = hasMoreSpaces,
                    spaceWorksetLimited = spaceWorksetLimited,
                    spaceWorksetHasOfflineDrafts = spaceWorksetHasOfflineDrafts,
                    onCreateSpace = onCreateSpace,
                    onSelectSpace = onSelectSpace,
                    onLoadMoreSpaces = onLoadMoreSpaces,
                    onOpenDocument = onOpenDocument,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PendingDocumentSpaceCreates(
    requests: List<DocumentSpaceCreateRequest>,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("documents.space.pending"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(requests, key = { it.spaceId }) { request ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
                tonalElevation = 1.dp,
                modifier = Modifier.widthIn(min = 260.dp, max = 340.dp),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            request.intent.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "等待连接后同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onRetry(request.spaceId) },
                        modifier = Modifier.testTag(
                            "documents.space.pending.retry.${request.spaceId.take(8)}",
                        ),
                    ) { Text("重试") }
                    TextButton(
                        onClick = { onDiscard(request.spaceId) },
                        modifier = Modifier.testTag(
                            "documents.space.pending.discard.${request.spaceId.take(8)}",
                        ),
                    ) { Text("不再重试") }
                }
            }
        }
    }
}

@Composable
private fun DocumentHomeHeader(
    detached: Boolean,
    statusLabel: String,
    onRefresh: () -> Unit,
    onCreateSpace: () -> Unit,
    onDetach: (() -> Unit)?,
) {
    val leadingInset = LocalScreenHeaderLeadingInset.current
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(
            start = 18.dp + leadingInset,
            end = 18.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (detached) "TeamTalk 文档" else "文档", style = MaterialTheme.typography.titleLarge)
            Text(
                statusLabel,
                modifier = Modifier.testTag("documents.home.status"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("documents.refresh")) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
        OutlinedButton(onClick = onCreateSpace, modifier = Modifier.testTag("documents.space.create")) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建空间")
        }
        if (onDetach != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDetach, modifier = Modifier.testTag("documents.detach")) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "在独立窗口打开")
            }
        }
    }
}

@Composable
private fun DocumentSpaceIndex(
    spaces: List<DocumentSpace>,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    worksetLimited: Boolean,
    worksetHasOfflineDrafts: Boolean,
    projectionStatus: DocumentWorkspaceProjectionStatus,
    onCreateSpace: () -> Unit,
    onSelectSpace: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.17f))
            .testTag("documents.home.spaces"),
    ) {
        Text("空间", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(18.dp, 18.dp, 18.dp, 10.dp))
        when {
            loading && spaces.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            spaces.isEmpty() &&
                projectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "文档空间尚未缓存，请联网后重试",
                        modifier = Modifier.testTag("documents.space.cache-miss"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            spaces.isEmpty() -> DocumentSpaceEmptyState(onCreateSpace, Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(spaces, key = { it.spaceId }) { space ->
                    DocumentSpaceIndexRow(space, onSelectSpace)
                }
                item {
                    DocumentSpacePaginationFooter(
                        loading = loadingMore,
                        hasMore = hasMore,
                        worksetLimited = worksetLimited,
                        worksetHasOfflineDrafts = worksetHasOfflineDrafts,
                        onLoadMore = onLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentSpaceIndexRow(space: DocumentSpace, onSelectSpace: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).clickable { onSelectSpace(space.spaceId) }
            .testTag("documents.space.${space.spaceId.take(8)}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(14.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(space.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                space.description?.takeIf(String::isNotBlank) ?: documentSpaceRoleLabel(space.myRole),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun DocumentRecentIndex(
    recentDocuments: List<DocumentHomeItem>,
    recentlyCreatedDocuments: List<DocumentHomeItem>,
    loading: Boolean,
    projectionStatus: DocumentWorkspaceProjectionStatus,
    onOpenDocument: (DocumentHomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 26.dp).testTag("documents.home.recents"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { DocumentHomeLead() }
        item { DocumentIndexSectionHeader("最近访问", "上次访问") }
        if (loading && recentDocuments.isEmpty()) {
            item { DocumentIndexLoading() }
        } else if (recentDocuments.isEmpty()) {
            item {
                DocumentIndexEmpty(
                    if (projectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING) {
                        "最近访问尚未缓存"
                    } else {
                        "打开过的文档会显示在这里"
                    },
                )
            }
        } else {
            items(recentDocuments, key = { "recent-${it.documentId}" }) { item ->
                DocumentHomeItemRow(item, "recent", item.accessedAt, onOpenDocument)
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
        item { DocumentIndexSectionHeader("最近创建", "创建时间") }
        if (loading && recentlyCreatedDocuments.isEmpty()) {
            item { DocumentIndexLoading() }
        } else if (recentlyCreatedDocuments.isEmpty()) {
            item {
                DocumentIndexEmpty(
                    if (projectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING) {
                        "最近创建尚未缓存"
                    } else {
                        "新建的文档会显示在这里"
                    },
                )
            }
        } else {
            items(recentlyCreatedDocuments, key = { "created-${it.documentId}" }) { item ->
                DocumentHomeItemRow(item, "created", item.createdAt, onOpenDocument)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CompactDocumentHome(
    spaces: List<DocumentSpace>,
    recentDocuments: List<DocumentHomeItem>,
    recentlyCreatedDocuments: List<DocumentHomeItem>,
    loading: Boolean,
    spaceProjectionStatus: DocumentWorkspaceProjectionStatus,
    homeProjectionStatus: DocumentWorkspaceProjectionStatus,
    loadingMoreSpaces: Boolean,
    hasMoreSpaces: Boolean,
    spaceWorksetLimited: Boolean,
    spaceWorksetHasOfflineDrafts: Boolean,
    onCreateSpace: () -> Unit,
    onSelectSpace: (String) -> Unit,
    onLoadMoreSpaces: () -> Unit,
    onOpenDocument: (DocumentHomeItem) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.padding(horizontal = 14.dp)) {
        item { DocumentHomeLead(compact = true) }
        item { DocumentIndexSectionHeader("空间", "") }
        if (loading && spaces.isEmpty()) item { DocumentIndexLoading() }
        else if (spaces.isEmpty()) item {
            if (spaceProjectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING) {
                DocumentIndexEmpty("文档空间尚未缓存，请联网后重试")
            } else {
                DocumentSpaceEmptyState(onCreateSpace, Modifier.fillMaxWidth().height(160.dp))
            }
        }
        else {
            items(spaces, key = { "space-${it.spaceId}" }) {
                DocumentSpaceIndexRow(it, onSelectSpace)
            }
            item {
                DocumentSpacePaginationFooter(
                    loading = loadingMoreSpaces,
                    hasMore = hasMoreSpaces,
                    worksetLimited = spaceWorksetLimited,
                    worksetHasOfflineDrafts = spaceWorksetHasOfflineDrafts,
                    onLoadMore = onLoadMoreSpaces,
                )
            }
        }

        item { Spacer(Modifier.height(14.dp)) }
        item { DocumentIndexSectionHeader("最近访问", "") }
        if (loading && recentDocuments.isEmpty()) item { DocumentIndexLoading() }
        else if (recentDocuments.isEmpty()) item {
            DocumentIndexEmpty(
                if (homeProjectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING) {
                    "最近访问尚未缓存"
                } else {
                    "打开过的文档会显示在这里"
                },
            )
        }
        else items(recentDocuments, key = { "recent-${it.documentId}" }) {
            DocumentHomeItemRow(it, "recent", it.accessedAt, onOpenDocument, compact = true)
        }

        item { Spacer(Modifier.height(14.dp)) }
        item { DocumentIndexSectionHeader("最近创建", "") }
        if (loading && recentlyCreatedDocuments.isEmpty()) item { DocumentIndexLoading() }
        else if (recentlyCreatedDocuments.isEmpty()) item {
            DocumentIndexEmpty(
                if (homeProjectionStatus == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING) {
                    "最近创建尚未缓存"
                } else {
                    "新建的文档会显示在这里"
                },
            )
        }
        else items(recentlyCreatedDocuments, key = { "created-${it.documentId}" }) {
            DocumentHomeItemRow(it, "created", it.createdAt, onOpenDocument, compact = true)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DocumentSpacePaginationFooter(
    loading: Boolean,
    hasMore: Boolean,
    worksetLimited: Boolean,
    worksetHasOfflineDrafts: Boolean,
    onLoadMore: () -> Unit,
) {
    when {
        worksetLimited -> Text(
            if (worksetHasOfflineDrafts) {
                "空间工作集已达 1024 个上限；释放足够的本机草稿空间后，再刷新文档页。"
            } else {
                "空间工作集已达 1024 个上限；关闭足够数量的不再需要的文档标签后，再刷新空间列表。"
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("documents.space.workset-limit"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hasMore || loading -> Box(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedButton(
                onClick = onLoadMore,
                enabled = hasMore && !loading,
                modifier = Modifier.testTag("documents.space.load-more"),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) "正在加载" else "加载更多空间")
            }
        }
    }
}

@Composable
private fun DocumentHomeLead(compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 18.dp else 26.dp, bottom = 16.dp)) {
        Text("文档首页", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("从最近工作继续，或进入一个文档空间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocumentIndexSectionHeader(title: String, timeLabel: String) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (timeLabel.isNotEmpty()) Text(timeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun DocumentHomeItemRow(
    item: DocumentHomeItem,
    tagKind: String,
    timestamp: Long,
    onOpenDocument: (DocumentHomeItem) -> Unit,
    compact: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 58.dp else 64.dp)
            .clickable { onOpenDocument(item) }
            .testTag("documents.home.$tagKind.${item.documentId.take(8)}")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.Article, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                if (compact) item.spaceName else listOf(item.spaceName, item.creatorName).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            documentHomeTime(timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
}

@Composable
private fun DocumentIndexLoading() {
    Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun DocumentIndexEmpty(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DocumentSpaceEmptyState(onCreateSpace: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Description, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text("还没有可访问的空间", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onCreateSpace) { Text("创建第一个空间") }
    }
}

private fun documentSpaceRoleLabel(role: Int): String = when (role) {
    DocumentSpace.ROLE_OWNER -> "所有者"
    DocumentSpace.ROLE_ADMIN -> "管理员"
    DocumentSpace.ROLE_EDITOR -> "可编辑"
    else -> "可查看"
}

private fun documentHomeTime(timestamp: Long): String =
    if (timestamp <= 0) "—" else formatListTime(timestamp)
