package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.client.FriendPresence
import com.virjar.tk.shared.client.FriendPresenceStatus
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.PinyinInitials
import com.virjar.tk.app.ui.theme.Tk
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
    friendPresenceByUid: Map<String, FriendPresence> = emptyMap(),
    onContactClick: (friendUid: String) -> Unit,
    modifier: Modifier = Modifier,
    pendingApplyCount: Int = 0,
    onFriendApplies: (() -> Unit)? = null,
    showAlphabetIndex: Boolean = true,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 过滤（显示名/用户名/备注）+ 首字母分组，字母序 A-Z、# 殿后
    val groups = remember(contacts) {
        contacts.mapNotNull { contact ->
            val user = contact.user
            val displayName = contact.remark ?: user?.name ?: user?.username ?: contact.friendUid
            contact to displayName
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
                            ContactAvatar(
                                displayName = displayName,
                                avatar = user?.avatar,
                                friendUid = contact.friendUid,
                                presence = friendPresenceByUid[contact.friendUid],
                            )
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
                                "暂无联系人",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tk.colors.metaText,
                            )
                        }
                    }
                }
            }

            if (showAlphabetIndex) {
                LettersIndexBar(
                    letters = groups.keys.toList(),
                    letterIndexMap = letterIndexMap,
                    listState = listState,
                    onJump = { index -> scope.launch { listState.scrollToItem(index) } },
                )
            }
        }
    }
}

internal data class ContactPresenceIndicatorPresentation(
    val testTag: String,
    val contentDescription: String,
)

internal fun contactPresenceIndicatorPresentation(
    friendUid: String,
    presence: FriendPresence?,
): ContactPresenceIndicatorPresentation? {
    if (presence?.status != FriendPresenceStatus.ONLINE) return null
    return ContactPresenceIndicatorPresentation(
        testTag = "contact.presence.${friendUid.take(8)}",
        contentDescription = "在线",
    )
}

@Composable
private fun ContactAvatar(
    displayName: String,
    avatar: com.virjar.tk.protocol.model.Attachment?,
    friendUid: String,
    presence: FriendPresence?,
) {
    val indicator = contactPresenceIndicatorPresentation(friendUid, presence)
    Box(modifier = Modifier.size(Tk.dimens.listAvatar)) {
        AvatarPlaceholder(
            name = displayName,
            avatar = avatar,
            size = Tk.dimens.listAvatar.value.toInt(),
        )
        if (indicator != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .testTag(indicator.testTag)
                    .semantics { contentDescription = indicator.contentDescription },
                shape = CircleShape,
                color = Tk.colors.online,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface),
                content = {},
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
            Text("收到和发出的好友申请", style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
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
