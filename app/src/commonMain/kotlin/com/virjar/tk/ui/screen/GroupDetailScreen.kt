package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.ScreenHeader
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupDetailScreen(
    chat: Chat?,
    members: List<Member>,
    isOwner: Boolean,
    myUid: String = "",
    onMemberClick: (uid: String) -> Unit,
    onEditNotice: ((String) -> Unit)? = null,
    onInviteMembers: () -> Unit = {},
    onViewInviteLinks: () -> Unit = {},
    onLeaveGroup: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    // 成员管理操作（仅群主/管理员可用）
    onSetAdmin: ((memberUid: String) -> Unit)? = null,
    onRemoveAdmin: ((memberUid: String) -> Unit)? = null,
    onMuteMember: ((memberUid: String) -> Unit)? = null,
    onUnmuteMember: ((memberUid: String) -> Unit)? = null,
    onRemoveMember: ((memberUid: String) -> Unit)? = null,
) {
    var showNoticeEdit by remember { mutableStateOf(false) }
    var noticeText by remember(chat?.notice) { mutableStateOf(chat?.notice ?: "") }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "群组详情", onBack = onBack)

        // 群公告编辑弹窗
        if (showNoticeEdit) {
            AlertDialog(
                onDismissRequest = { showNoticeEdit = false },
                title = { Text("编辑群公告") },
                text = {
                    OutlinedTextField(
                        value = noticeText,
                        onValueChange = { noticeText = it },
                        placeholder = { Text("请输入群公告") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showNoticeEdit = false
                        onEditNotice?.invoke(noticeText)
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showNoticeEdit = false }) { Text("取消") }
                },
            )
        }

        if (chat != null) {
            // 群头部：头像 + 群名 + 成员数
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            chat.name?.take(1) ?: "#",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(chat.name ?: chat.chatId.take(16), style = MaterialTheme.typography.titleMedium)
                Text("成员 ${chat.memberCount} 人", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            // 群公告
            NoticeSection(notice = chat.notice, canEdit = isOwner && onEditNotice != null,
                onClick = { showNoticeEdit = true })

            HorizontalDivider()

            // 创建者信息
            CreatorSection(chat = chat, members = members)

            HorizontalDivider()

            if (isOwner) {
                ListItem(
                    headlineContent = { Text("邀请成员") },
                    modifier = Modifier.clickable(onClick = onInviteMembers).testTag("group.detail.invite"),
                )
                ListItem(
                    headlineContent = { Text("邀请链接") },
                    modifier = Modifier.clickable(onClick = onViewInviteLinks).testTag("group.detail.inviteLinks"),
                )
                HorizontalDivider()
            }

            Text(
                "成员列表 (${members.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(members, key = { it.uid }) { member ->
                    val isSelf = member.uid == myUid
                    val canManage = (isOwner || member.role == 1) && !isSelf
                    var showMenu by remember { mutableStateOf(false) }

                    Box {
                        MemberRow(
                            member = member,
                            modifier = Modifier.combinedClickable(
                                onClick = { onMemberClick(member.uid) },
                                onLongClick = { if (canManage) showMenu = true },
                            ),
                        )
                        if (canManage) {
                            MemberContextMenu(
                                expanded = showMenu,
                                onDismiss = { showMenu = false },
                                isAdmin = member.role == 1,
                                onViewProfile = { showMenu = false; onMemberClick(member.uid) },
                                onSetAdmin = if (isOwner) ({ showMenu = false; onSetAdmin?.invoke(member.uid) }) else null,
                                onRemoveAdmin = if (isOwner) ({ showMenu = false; onRemoveAdmin?.invoke(member.uid) }) else null,
                                onMute = { showMenu = false; onMuteMember?.invoke(member.uid) },
                                onUnmute = { showMenu = false; onUnmuteMember?.invoke(member.uid) },
                                onRemove = { showMenu = false; onRemoveMember?.invoke(member.uid) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = onLeaveGroup,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("group.detail.leave"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (isOwner) "解散群组" else "退出群组")
            }
        }
    }
}

@Composable
private fun NoticeSection(notice: String?, canEdit: Boolean = false, onClick: () -> Unit = {}) {
    ListItem(
        leadingContent = {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = "群公告",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text("群公告", style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notice?.takeIf { it.isNotBlank() } ?: "暂无公告",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canEdit) {
                    TextButton(onClick = onClick) {
                        Text("编辑", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun CreatorSection(chat: Chat, members: List<Member>) {
    val creatorUid = chat.creator ?: return
    val creator = members.firstOrNull { it.uid == creatorUid } ?: return
    ListItem(
        leadingContent = {
            Icon(
                Icons.Filled.Person,
                contentDescription = "群主",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text("群主", style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                creator.displayName(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * 成员长按上下文菜单（仅群主/管理员可见）。
 * 在 [expanded=false] 时不消耗组合树资源。
 */
@Composable
private fun MemberContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isAdmin: Boolean,
    onViewProfile: () -> Unit,
    onSetAdmin: (() -> Unit)?,
    onRemoveAdmin: (() -> Unit)?,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(16.dp, 0.dp),
    ) {
        DropdownMenuItem(text = { Text("查看资料") }, onClick = onViewProfile)
        HorizontalDivider()
        if (isAdmin) {
            onRemoveAdmin?.let { cb ->
                DropdownMenuItem(text = { Text("取消管理员") }, onClick = cb)
            }
        } else {
            onSetAdmin?.let { cb ->
                DropdownMenuItem(text = { Text("设为管理员") }, onClick = cb)
            }
        }
        DropdownMenuItem(text = { Text("禁言") }, onClick = onMute)
        DropdownMenuItem(text = { Text("解除禁言") }, onClick = onUnmute)
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("移出群聊", color = MaterialTheme.colorScheme.error) },
            onClick = onRemove,
        )
    }
}
