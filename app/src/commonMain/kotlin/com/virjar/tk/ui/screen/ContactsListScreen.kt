package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Contact
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.PinyinInitials
import com.virjar.tk.ui.theme.Tk
import kotlinx.coroutines.launch

/**
 * 通讯录列表（双端共享）：搜索框（§2.4 高 36 圆角 8 全宽）+ 拼音首字母分组
 * sticky 头 + 右侧字母索引条（点击跳转分组）。
 *
 * 桌面在中栏 ListHeader 下方，Android 在 TopAppBar 下方；密度由 Tk.dimens 决定。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsListScreen(
    contacts: List<Contact>,
    onContactClick: (friendUid: String) -> Unit,
    modifier: Modifier = Modifier,
    pendingApplyCount: Int = 0,
    onFriendApplies: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 过滤（显示名/用户名/备注）+ 首字母分组，字母序 A-Z、# 殿后
    val groups = remember(contacts, query) {
        contacts.mapNotNull { contact ->
            val user = contact.user
            val displayName = contact.remark ?: user?.name ?: user?.username ?: contact.friendUid
            val searchable = listOf(displayName, user?.username, user?.name, contact.remark)
                .filterNotNull().joinToString(" ")
            if (query.isNotBlank() && !searchable.contains(query.trim(), ignoreCase = true)) null
            else contact to displayName
        }
            .groupBy { (_, name) -> PinyinInitials.initialOf(name) }
            .toSortedMap(compareBy({ it == '#' }, { it }))
    }

    // 分组 sticky 头在 LazyColumn 中的全局索引：索引条跳转用
    val letterIndexMap = remember(groups) {
        var idx = 0
        buildMap {
            groups.forEach { (letter, list) ->
                put(letter, idx)
                idx += 1 + list.size  // sticky 头 + 组内条目
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (onFriendApplies != null) {
            NewFriendsRow(pendingApplyCount = pendingApplyCount, onClick = onFriendApplies)
            HorizontalDivider(color = Tk.colors.divider)
        }
        ContactSearchField(query, { query = it })

        Row(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                groups.forEach { (letter, groupContacts) ->
                    stickyHeader(key = "letter.$letter") {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                letter.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = 2.dp),
                            )
                        }
                    }
                    items(groupContacts, key = { it.first.friendUid }) { (contact, displayName) ->
                        val user = contact.user
                        val subName = when {
                            contact.remark != null && user?.name != null -> user.name
                            else -> user?.username
                        }?.takeIf { it != displayName }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tk.dimens.listItemHeight)
                                .clickable { onContactClick(contact.friendUid) }
                                .testTag("contact.${contact.friendUid.take(8)}")
                                .padding(horizontal = Tk.spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarPlaceholder(name = displayName, size = Tk.dimens.listAvatar.value.toInt())
                            Spacer(Modifier.width(Tk.spacing.md))
                            Column {
                                Text(
                                    displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subName != null) {
                                    Text(
                                        subName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Tk.colors.secondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Tk.spacing.xxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (query.isBlank()) "暂无联系人" else "未找到「${query.trim()}」",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tk.colors.metaText,
                            )
                        }
                    }
                }
            }

            LettersIndexBar(
                letters = groups.keys.toList(),
                letterIndexMap = letterIndexMap,
                listState = listState,
                onJump = { index -> scope.launch { listState.scrollToItem(index) } },
            )
        }
    }
}

/**
 * 好友申请是通讯录内容的一部分，而不是标题栏里的全局动作。
 * 即使没有未处理申请也保留稳定入口，避免用户只能靠红点发现功能。
 */
@Composable
private fun NewFriendsRow(pendingApplyCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .testTag("contacts.friendApplies")
            .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(Tk.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text("新的朋友", style = MaterialTheme.typography.titleSmall)
            Text("好友申请与验证", style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
        }
        if (pendingApplyCount > 0) {
            Text(
                "$pendingApplyCount 条待处理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 圆角 8 全宽搜索框（§2.4；36dp 高，BasicTextField 精确控高）。 */
@Composable
private fun ContactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag("contacts.search")
                .padding(horizontal = Tk.spacing.md),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "搜索联系人",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索联系人",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tk.colors.metaText,
                            )
                        }
                        inner()
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onQueryChange("") }
                                .padding(2.dp),
                        )
                    }
                }
            },
        )
    }
}

/** 右侧字母索引条：仅显示当前分组出现的字母，点击滚动到对应 sticky 头。 */
@Composable
private fun LettersIndexBar(
    letters: List<Char>,
    letterIndexMap: Map<Char, Int>,
    listState: LazyListState,
    onJump: (Int) -> Unit,
) {
    if (letters.isEmpty()) return
    var activeLetter by remember { mutableStateOf<Char?>(null) }
    // 当前滚动位置命中的字母（驱动高亮）
    LaunchedEffect(listState.firstVisibleItemIndex) {
        activeLetter = letterIndexMap.entries
            .sortedBy { it.value }
            .lastOrNull { it.value <= listState.firstVisibleItemIndex }?.key
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(20.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.Center,
    ) {
        letters.forEach { letter ->
            Text(
                letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (letter == activeLetter) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { letterIndexMap[letter]?.let(onJump) }
                    .padding(vertical = 1.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
