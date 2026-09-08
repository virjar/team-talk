package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.navigation.feature.ContentSearchFeature
import com.virjar.tk.app.navigation.feature.ContentSearchSection
import com.virjar.tk.app.navigation.feature.contentSearchHitKey
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchRequest

internal val contentSearchKinds = listOf(
    ContentSearchRequest.KIND_DOCUMENT, ContentSearchRequest.KIND_GROUP_FILE, ContentSearchRequest.KIND_CHAT_ATTACHMENT,
)
internal data class ContentSearchScopeFilter(val id: String, val name: String)

internal val contentSearchScopesSaver = listSaver<SnapshotStateMap<Int, ContentSearchScopeFilter>, String>(
    save = { scopes -> scopes.flatMap { (kind, scope) -> listOf(kind.toString(), scope.id, scope.name) } },
    restore = { values ->
        mutableStateMapOf<Int, ContentSearchScopeFilter>().apply {
            values.chunked(3).forEach { (kind, id, name) -> put(kind.toInt(), ContentSearchScopeFilter(id, name)) }
        }
    },
)

internal fun contentSearchKindLabel(kind: Int): String = when (kind) {
    ContentSearchRequest.KIND_DOCUMENT -> "文档"
    ContentSearchRequest.KIND_GROUP_FILE -> "群文件"
    else -> "聊天附件"
}

@Composable
internal fun ContentSearchFileFilters(source: Int, type: Int, onSource: (Int) -> Unit, onType: (Int) -> Unit) {
    val sources = listOf(0 to "全部来源", ContentSearchRequest.KIND_GROUP_FILE to "群文件", ContentSearchRequest.KIND_CHAT_ATTACHMENT to "聊天附件")
    val types = listOf(0 to "全部类型", 1 to "图片", 2 to "视频", 3 to "音频", 4 to "其他文件")
    listOf("source" to sources, "type" to types).forEach { (name, options) ->
        LazyRow(contentPadding = PaddingValues(horizontal = Tk.spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
            items(options) { (value, label) ->
                FilterChip(selected = value == if (name == "source") source else type,
                    onClick = { if (name == "source") onSource(value) else onType(value) },
                    label = { Text(label) }, modifier = Modifier.testTag("global.search.file.$name.$value"))
            }
        }
    }
}

@Composable
internal fun ContentSearchScopeFilters(kinds: List<Int>, scopes: Map<Int, ContentSearchScopeFilter>, onClear: (Int) -> Unit) {
    if (kinds.none(scopes::containsKey)) return
    LazyRow(contentPadding = PaddingValues(horizontal = Tk.spacing.lg), horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm)) {
        items(kinds.filter(scopes::containsKey)) { kind ->
            InputChip(selected = true, onClick = { onClear(kind) },
                label = { Text("${contentSearchKindLabel(kind)}：${scopes.getValue(kind).name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "恢复全部范围", modifier = Modifier.size(16.dp)) },
                modifier = Modifier.widthIn(max = 280.dp).testTag("global.search.container.clear.$kind"))
        }
    }
}

internal fun LazyListScope.contentSearchSection(
    kind: Int,
    state: ContentSearchSection,
    preview: Boolean,
    openingKey: String?,
    onOpen: (ContentSearchHit) -> Unit,
    onScope: (ContentSearchHit) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onShowAll: () -> Unit,
) {
    if (preview && state.items.isEmpty() && !state.loading && state.error == null && state.nextCursor == null) return
    item("content.$kind.header") { SearchSectionHeader(contentSearchKindLabel(kind)) }
    items(state.items.take(if (preview) 6 else ContentSearchFeature.MAX_RESIDENT_RESULTS), key = ::contentSearchHitKey) { hit ->
        val key = contentSearchHitKey(hit)
        ListItem(
            headlineContent = { Text(hit.title.ifBlank { "未命名内容" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    if (hit.snippet.isNotBlank()) Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (hit.mimeType != null) Text("${hit.mimeType} · ${contentSearchSize(hit.size ?: 0)}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onScope(hit) }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.testTag("global.search.container.$key")) {
                        Text("仅搜索 ${hit.scopeName.ifBlank { hit.scopeId.take(12) }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            leadingContent = { Icon(if (kind == ContentSearchRequest.KIND_DOCUMENT) Icons.Default.Description else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null, tint = Tk.colors.secondaryText) },
            trailingContent = {
                if (openingKey == key) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (kind == ContentSearchRequest.KIND_CHAT_ATTACHMENT) "查看消息" else "打开", style = MaterialTheme.typography.labelMedium)
            },
            modifier = Modifier.clickable(enabled = openingKey == null) { onOpen(hit) }.testTag("global.search.content.$key"),
        )
    }
    item("content.$kind.status") {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm)) {
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("global.search.content.error.$kind"))
                TextButton(onClick = onRetry, modifier = Modifier.testTag("global.search.content.retry.$kind")) { Text("重试") }
            } else if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("global.search.content.loading.$kind"))
            } else if (state.items.isEmpty() && state.nextCursor == null) {
                Text("没有匹配的${contentSearchKindLabel(kind)}", color = Tk.colors.secondaryText)
            } else if (preview) {
                TextButton(onClick = onShowAll, modifier = Modifier.testTag("global.search.content.show-all.$kind")) { Text("查看全部${contentSearchKindLabel(kind)}") }
            } else if (state.atCapacity) {
                Text("已显示前 ${ContentSearchFeature.MAX_RESIDENT_RESULTS} 条，请缩小关键词或搜索范围", color = Tk.colors.secondaryText)
            } else if (state.nextCursor != null) {
                TextButton(onClick = onMore, modifier = Modifier.testTag("global.search.content.more.$kind")) { Text("加载更多") }
            }
        }
    }
}

private fun contentSearchSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
