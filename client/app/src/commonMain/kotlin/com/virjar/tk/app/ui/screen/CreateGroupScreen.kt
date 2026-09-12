package com.virjar.tk.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.virjar.tk.app.ui.component.GroupAvatarCollage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.shared.client.PendingGroupCreationCommand
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.theme.Tk
import kotlinx.coroutines.launch

/**
 * 创建群组页面。
 *
 * 使用中性信息表面承载群头像与名称，品牌色只用于焦点和选中状态；
 * 中部是已选成员预览条，底部是联系人列表。可从用户资料页预选发起人。
 */
@Composable
fun CreateGroupScreen(
    contacts: List<Contact>,
    pendingGroupCreation: PendingGroupCreationCommand?,
    groupCreationDraftLoaded: Boolean,
    groupCreationDraftError: String?,
    onCreateGroup: suspend (name: String, memberUids: List<String>) -> Result<String>,
    onDiscardPendingGroupCreation: suspend () -> Boolean,
    onBack: (() -> Unit)? = null,
    initialSelectedUids: Set<String> = emptySet(),
    /** 组织同事搜索（内测反馈 T046）：非好友但组织内的成员可搜索加入候选。 */
    onSearchUsers: (suspend (String) -> List<User>)? = null,
) {
    var groupName by remember { mutableStateOf("") }
    var selectedUids by remember { mutableStateOf(emptySet<String>()) }
    var draftInitialized by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var isDiscarding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val compactDesktop = Tk.dimens.headerHeight < 56.dp
    val inputsEnabled = groupCreationDraftLoaded && groupCreationDraftError == null &&
        !isCreating && !isDiscarding

    // 搜索选人（T046）：非好友同事经用户搜索进入候选；已选的非好友保存在 addedUsers
    // 以支撑预览条与列表渲染（好友仍以 contacts 为准）。
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var addedUsers by remember { mutableStateOf<Map<String, User>>(emptyMap()) }
    val friendUids = remember(contacts) { contacts.mapTo(HashSet()) { it.friendUid } }
    LaunchedEffect(searchQuery, onSearchUsers) {
        val query = searchQuery.trim()
        if (onSearchUsers == null || query.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(250)
        val result = runCatching { onSearchUsers(query) }.getOrDefault(emptyList())
            .filter { it.uid !in friendUids }
        searchResults = result
        addedUsers = addedUsers + result.associateBy { it.uid }
        searching = false
    }

    // LocalCache 恢复发生在 Main 之外。在完整的冻结命令被恢复或证明不存在之前不要接纳编辑，
    // 否则快速点击可能覆盖它。
    LaunchedEffect(groupCreationDraftLoaded) {
        if (groupCreationDraftLoaded && !draftInitialized) {
            groupName = pendingGroupCreation?.name.orEmpty()
            selectedUids = pendingGroupCreation?.targetMemberUids?.toSet() ?: initialSelectedUids
            draftInitialized = true
        }
    }

    // 已选联系人（保持选择顺序，用于预览条）
    val selectedContacts = remember(contacts, selectedUids) {
        contacts.filter { it.friendUid in selectedUids }
    }
    // 已选的非好友（T046）：仅用于预览条与草稿头像渲染
    val selectedNonFriends = remember(addedUsers, selectedUids) {
        addedUsers.values.filter { it.uid in selectedUids }
    }
    val draftMemberUsers = remember(selectedContacts, selectedNonFriends) {
        selectedContacts.mapNotNull { it.user } + selectedNonFriends
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "创建群组",
            onBack = onBack,
            trailing = {
                TextButton(
                    onClick = {
                        if (inputsEnabled && groupName.isNotBlank() && selectedUids.isNotEmpty()) {
                            isCreating = true
                            scope.launch {
                                try {
                                    error = null
                                    onCreateGroup(groupName, selectedUids.toList())
                                        .onFailure { error = it.message }
                                } finally {
                                    isCreating = false
                                }
                            }
                        }
                    },
                    enabled = inputsEnabled && groupName.isNotBlank() && selectedUids.isNotEmpty(),
                    modifier = Modifier.testTag("group.create"),
                ) { Text("创建") }
            },
        )

        // ── 建群上下文：Desktop 使用横向任务表单；Android 保留触控端纵向引导 ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            if (compactDesktop) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupDraftAvatar(size = 48.dp, iconSize = 24.dp, members = draftMemberUsers, groupName = groupName)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        placeholder = { Text("群聊名称") },
                        enabled = inputsEnabled,
                        modifier = Modifier.weight(1f).testTag("group.name"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GroupDraftAvatar(size = 64.dp, iconSize = 32.dp, members = draftMemberUsers, groupName = groupName)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        placeholder = { Text("群聊名称") },
                        enabled = inputsEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("group.name"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }

        pendingGroupCreation?.let { pending ->
            val stillMatches = groupName.trim() == pending.name &&
                selectedUids.toList().sorted() == pending.targetMemberUids
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (stillMatches) {
                            "已恢复上次未确认的建群操作，直接创建会安全重试"
                        } else {
                            "内容已修改，再次创建将使用新的操作标识"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(
                        enabled = inputsEnabled,
                        onClick = {
                            isDiscarding = true
                            scope.launch {
                                try {
                                    error = null
                                    if (!onDiscardPendingGroupCreation()) {
                                        error = "放弃待创建群组失败"
                                    }
                                } finally {
                                    isDiscarding = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("group.pending.discard"),
                    ) { Text("放弃") }
                }
            }
        }

        // ── 错误提示 / 进度条 ──
        val visibleError = groupCreationDraftError ?: error
        AnimatedVisibility(visibleError != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                visibleError ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!groupCreationDraftLoaded || isCreating || isDiscarding) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        // ── 已选成员预览条（头像横滑，点 × 删除）──
        if (selectedContacts.isNotEmpty() || selectedNonFriends.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(selectedContacts, key = { it.friendUid }) { contact ->
                    val displayName = contactDisplayName(contact.user, contact.remark, contact.friendUid)
                    SelectedMemberChip(
                        displayName = displayName,
                        avatar = contact.user?.avatar,
                        enabled = inputsEnabled,
                        onRemove = { selectedUids = selectedUids - contact.friendUid },
                    )
                }
                items(selectedNonFriends, key = { it.uid }) { user ->
                    SelectedMemberChip(
                        displayName = user.name.ifBlank { user.uid.take(8) },
                        avatar = user.avatar,
                        enabled = inputsEnabled,
                        onRemove = { selectedUids = selectedUids - user.uid },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // ── 组织同事搜索（T046）──
        if (onSearchUsers != null) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索组织内同事（非好友也可加入）") },
                enabled = inputsEnabled,
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                        }
                    } else if (searching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .testTag("group.search"),
            )
        }

        // ── 联系人列表标题 ──
        val searchActive = searchQuery.trim().isNotEmpty() && onSearchUsers != null
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (searchActive) "搜索结果" else "选择成员",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    "${selectedUids.size}/${contacts.size + addedUsers.size}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // ── 成员候选列表（搜索态显示组织同事结果；选中态：主题色边框 + 勾选图标）──
        when {
            searchActive -> when {
                searching && searchResults.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("搜索中…", style = MaterialTheme.typography.bodyMedium) }
                searchResults.isEmpty() -> Box(
                    Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "未找到组织成员，可尝试姓名或账号关键词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(searchResults, key = { it.uid }) { user ->
                        MemberCandidateRow(
                            uid = user.uid,
                            displayName = user.name.ifBlank { user.uid.take(8) },
                            subName = user.username,
                            avatar = user.avatar,
                            isSelected = user.uid in selectedUids,
                            inputsEnabled = inputsEnabled,
                            onToggle = {
                                selectedUids = if (user.uid in selectedUids) selectedUids - user.uid
                                else selectedUids + user.uid
                            },
                        )
                    }
                }
            }
            contacts.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (onSearchUsers != null) "暂无好友，可通过上方搜索添加组织内同事"
                    else "暂无好友，无法创建群组",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(contacts, key = { it.friendUid }) { contact ->
                    val isSelected = contact.friendUid in selectedUids
                    val displayName = contactDisplayName(contact.user, contact.remark, contact.friendUid)
                    val subName = if (contact.remark != null && contact.user?.name != null)
                        contact.user?.username else null

                    MemberCandidateRow(
                        uid = contact.friendUid,
                        displayName = displayName,
                        subName = subName,
                        avatar = contact.user?.avatar,
                        isSelected = isSelected,
                        inputsEnabled = inputsEnabled,
                        onToggle = {
                            selectedUids = if (isSelected) selectedUids - contact.friendUid
                            else selectedUids + contact.friendUid
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberCandidateRow(
    uid: String,
    displayName: String,
    subName: String?,
    avatar: Attachment?,
    isSelected: Boolean,
    inputsEnabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = inputsEnabled, onClick = onToggle)
            .testTag("group.member.${uid.take(8)}")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中态：头像加主题色边框
        AvatarPlaceholder(
            name = displayName,
            avatar = avatar,
            size = 44,
            modifier = Modifier.then(
                if (isSelected) Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) else Modifier
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (subName != null) {
                Text(
                    "@$subName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 选中态：勾选图标（替代裸 Checkbox）
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

@Composable
private fun SelectedMemberChip(
    displayName: String,
    avatar: Attachment?,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp),
    ) {
        Box {
            AvatarPlaceholder(name = displayName, avatar = avatar, size = 44)
            // 删除按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            displayName.take(4),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun GroupDraftAvatar(
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    members: List<User> = emptyList(),
    groupName: String? = null,
) {
    if (members.isNotEmpty()) {
        GroupAvatarCollage(chatName = groupName ?: "群聊", members = members, size = size.value.toInt())
        return
    }
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(size * 0.28f),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
