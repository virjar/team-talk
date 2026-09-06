package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.component.SettingsDangerAction
import com.virjar.tk.app.ui.component.SettingsEntryRow
import com.virjar.tk.app.ui.component.SettingsGroupCard
import com.virjar.tk.app.ui.component.SettingsIconButton
import com.virjar.tk.app.ui.component.SettingsSectionLabel
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.app.ui.theme.Tk

/** 群主可管理非群主；管理员只能管理普通成员；普通成员永远不能因目标角色而获得权限。 */
internal fun canManageGroupMember(
    actorRole: Int,
    targetRole: Int,
    isSelf: Boolean,
    targetUserRole: Int = UserRole.HUMAN,
): Boolean {
    if (isSelf || targetUserRole != UserRole.HUMAN) return false
    return when (actorRole) {
        2 -> targetRole < 2
        1 -> targetRole == 0
        else -> false
    }
}

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
    onGroupFiles: () -> Unit = {},
    onGroupBots: () -> Unit = {},
    onLeaveGroup: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    // 成员管理操作（仅群主/管理员可用）
    onSetAdmin: ((memberUid: String) -> Unit)? = null,
    onRemoveAdmin: ((memberUid: String) -> Unit)? = null,
    onMuteMember: ((memberUid: String) -> Unit)? = null,
    onUnmuteMember: ((memberUid: String) -> Unit)? = null,
    onRemoveMember: ((memberUid: String) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    var showNoticeEdit by remember(chat?.chatId) { mutableStateOf(false) }
    var noticeText by remember(chat?.chatId, chat?.notice) { mutableStateOf(chat?.notice ?: "") }
    val listState = key(chat?.chatId) { rememberLazyListState() }
    val myRole = members.firstOrNull { it.uid == myUid }?.role ?: -1
    // isOwner 来自平台路由；成员快照也必须确认同一登录者身份，缺任一项时 fail-safe。
    val currentUserIsOwner = isOwner && myRole == 2
    val currentUserCanManage = myRole == 1 || currentUserIsOwner

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        ScreenHeader(
            title = "群设置",
            onBack = onBack,
            trailing = {
                if (onBack == null && onClose != null) {
                    SettingsIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "关闭群设置",
                        onClick = onClose,
                        tag = "chat.inspector.close",
                    )
                }
            },
        )

        if (chat != null) {
            // 页头之外只有一个滚动所有者；固定设置区不能挤掉成员视口或遮挡退出操作。
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("group.detail.content"),
                contentPadding = PaddingValues(Tk.spacing.lg),
            ) {
                item(key = "overview") {
                    SettingsGroupCard(modifier = Modifier.padding(bottom = Tk.spacing.lg)) {
                        GroupSummary(chat)
                        HorizontalDivider(color = Tk.colors.divider, modifier = Modifier.padding(horizontal = Tk.spacing.md))
                        NoticeSection(
                            notice = chat.notice,
                            canEdit = currentUserCanManage && onEditNotice != null,
                            onClick = { showNoticeEdit = true },
                        )
                        CreatorSection(chat = chat, members = members)
                    }
                }
                item(key = "tools") {
                    SettingsSectionLabel("群功能")
                    SettingsGroupCard(modifier = Modifier.padding(bottom = Tk.spacing.lg)) {
                        SettingsEntryRow(
                            icon = Icons.Filled.FolderShared,
                            title = "共享文件",
                            description = "群成员共同维护的文件和版本",
                            onClick = onGroupFiles,
                            modifier = Modifier.heightIn(min = Tk.dimens.headerHeight),
                            tag = "group.detail.files",
                        )
                        SettingsEntryRow(
                            icon = Icons.Filled.SmartToy,
                            title = "机器人",
                            description = "接收来自外部系统的群通知",
                            onClick = onGroupBots,
                            modifier = Modifier.heightIn(min = Tk.dimens.headerHeight),
                            tag = "group.detail.bots",
                        )
                    }
                }
                if (currentUserCanManage) {
                    item(key = "invitations") {
                        SettingsSectionLabel("成员管理")
                        SettingsGroupCard(modifier = Modifier.padding(bottom = Tk.spacing.lg)) {
                            SettingsEntryRow(
                                icon = Icons.Filled.PersonAdd,
                                title = "邀请成员",
                                onClick = onInviteMembers,
                                modifier = Modifier.heightIn(min = Tk.dimens.headerHeight),
                                tag = "group.detail.invite",
                            )
                            SettingsEntryRow(
                                icon = Icons.Filled.Link,
                                title = "邀请链接",
                                onClick = onViewInviteLinks,
                                modifier = Modifier.heightIn(min = Tk.dimens.headerHeight),
                                tag = "group.detail.inviteLinks",
                            )
                        }
                    }
                }
                item(key = "members-title") {
                    SettingsSectionLabel("成员列表 (${members.size})", modifier = Modifier.padding(bottom = Tk.spacing.xs))
                }
                itemsIndexed(members, key = { _, member -> "member.${member.uid}" }, contentType = { _, _ -> "member" }) { index, member ->
                    val canManage = canManageGroupMember(
                        actorRole = myRole,
                        targetRole = member.role,
                        isSelf = member.uid == myUid,
                        targetUserRole = member.user?.role ?: UserRole.SYSTEM,
                    )
                    var showMenu by remember { mutableStateOf(false) }
                    // 成员逐项惰性布局，首尾圆角把整份名单连成同一张卡片。
                    val corners = MaterialTheme.shapes.medium
                    val square = CornerSize(0.dp)
                    Box(
                        modifier = Modifier.testTag("group.member.${member.uid.take(8)}").clip(corners.copy(
                            topStart = if (index == 0) corners.topStart else square,
                            topEnd = if (index == 0) corners.topEnd else square,
                            bottomStart = if (index == members.lastIndex) corners.bottomStart else square,
                            bottomEnd = if (index == members.lastIndex) corners.bottomEnd else square,
                        )).combinedClickable(
                            onClick = { onMemberClick(member.uid) },
                            onLongClick = { if (canManage) showMenu = true },
                        ),
                    ) {
                        MemberRow(member = member)
                        if (canManage) {
                            MemberContextMenu(
                                expanded = showMenu,
                                onDismiss = { showMenu = false },
                                isAdmin = member.role == 1,
                                onViewProfile = { showMenu = false; onMemberClick(member.uid) },
                                onSetAdmin = if (currentUserIsOwner && member.role == 0) {
                                    ({ showMenu = false; onSetAdmin?.invoke(member.uid) })
                                } else null,
                                onRemoveAdmin = if (currentUserIsOwner && member.role == 1) {
                                    ({ showMenu = false; onRemoveAdmin?.invoke(member.uid) })
                                } else null,
                                onMute = { showMenu = false; onMuteMember?.invoke(member.uid) },
                                onUnmute = { showMenu = false; onUnmuteMember?.invoke(member.uid) },
                                onRemove = { showMenu = false; onRemoveMember?.invoke(member.uid) },
                            )
                        }
                    }
                }
                item(key = "leave") {
                    SettingsDangerAction(
                        text = if (currentUserIsOwner) "解散群组" else "退出群组",
                        onClick = onLeaveGroup,
                        modifier = Modifier.padding(top = Tk.spacing.lg).heightIn(min = Tk.dimens.headerHeight),
                        tag = "group.detail.leave",
                    )
                }
            }
        }
    }

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
}

@Composable
private fun GroupSummary(chat: Chat) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Tk.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPlaceholder(name = chat.name ?: chat.chatId, size = Tk.dimens.listAvatar.value.toInt())
        Spacer(Modifier.width(Tk.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                chat.name ?: chat.chatId.take(16),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("成员 ${chat.memberCount} 人", style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
        }
    }
}

@Composable
private fun NoticeSection(notice: String?, canEdit: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Tk.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Campaign, contentDescription = null, tint = Tk.colors.secondaryText, modifier = Modifier.size(Tk.dimens.iconSize))
        Spacer(Modifier.width(Tk.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text("群公告", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Tk.spacing.xs))
            Text(
                notice?.takeIf { it.isNotBlank() } ?: "暂无公告",
                style = MaterialTheme.typography.bodySmall,
                color = Tk.colors.secondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canEdit) {
            TextButton(onClick = onClick, modifier = Modifier.testTag("group.detail.editNotice")) {
                Text("编辑", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CreatorSection(chat: Chat, members: List<Member>) {
    val creator = members.firstOrNull { it.uid == chat.creator } ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(Tk.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = Tk.colors.secondaryText, modifier = Modifier.size(Tk.dimens.iconSize))
        Spacer(Modifier.width(Tk.spacing.md))
        Text("群主", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(Tk.spacing.md))
        Text(
            creator.displayName(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Tk.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
