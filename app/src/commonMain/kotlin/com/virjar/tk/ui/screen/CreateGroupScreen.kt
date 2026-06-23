package com.virjar.tk.ui.screen

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Contact
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.ScreenHeader
import kotlinx.coroutines.launch

/**
 * 创建群组页面。
 *
 * 设计语言对齐登录/设置页：顶部渐变头部承载群头像+群名输入，
 * 中部已选成员预览条（头像横滑，可点删除），底部联系人列表（选中态主题色高亮）。
 */
@Composable
fun CreateGroupScreen(
    contacts: List<Contact>,
    onCreateGroup: suspend (name: String, memberUids: List<String>) -> Result<String>,
    onBack: (() -> Unit)? = null,
) {
    var groupName by remember { mutableStateOf("") }
    var selectedUids by remember { mutableStateOf(setOf<String>()) }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 已选联系人（保持选择顺序，用于预览条）
    val selectedContacts = remember(contacts, selectedUids) {
        contacts.filter { it.friendUid in selectedUids }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "创建群组",
            onBack = onBack,
            trailing = {
                TextButton(
                    onClick = {
                        if (groupName.isNotBlank() && selectedUids.isNotEmpty()) {
                            scope.launch {
                                isCreating = true
                                error = null
                                onCreateGroup(groupName, selectedUids.toList())
                                    .onFailure { error = it.message }
                                isCreating = false
                            }
                        }
                    },
                    enabled = groupName.isNotBlank() && selectedUids.isNotEmpty() && !isCreating,
                    modifier = Modifier.testTag("group.create"),
                ) { Text("创建") }
            },
        )

        // ── 渐变头部：群头像 + 群名输入 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primaryContainer,
                        )
                    )
                )
                .padding(bottom = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 群头像（渐变底 + Group 图标）
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(38.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 群名输入框（透明背景，融入渐变）
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = { Text("群聊名称", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .testTag("group.name"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.15f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                        unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                        cursorColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        // ── 错误提示 / 进度条 ──
        AnimatedVisibility(error != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (isCreating) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        // ── 已选成员预览条（头像横滑，点 × 删除）──
        if (selectedContacts.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(selectedContacts, key = { it.friendUid }) { contact ->
                    val displayName = contact.remark ?: contact.user?.name ?: contact.friendUid
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(56.dp),
                    ) {
                        Box {
                            AvatarPlaceholder(name = displayName, size = 44)
                            // 删除按钮
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                                    .clickable { selectedUids = selectedUids - contact.friendUid },
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
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // ── 联系人列表标题 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "选择成员",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    "${selectedUids.size}/${contacts.size}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // ── 联系人列表（选中态：主题色边框 + 勾选图标）──
        if (contacts.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "暂无好友，无法创建群组",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(contacts, key = { it.friendUid }) { contact ->
                    val isSelected = contact.friendUid in selectedUids
                    val displayName = contact.remark ?: contact.user?.name ?: contact.friendUid
                    val subName = if (contact.remark != null && contact.user?.name != null)
                        contact.user?.username else null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedUids = if (isSelected) selectedUids - contact.friendUid
                                else selectedUids + contact.friendUid
                            }
                            .testTag("group.member.${contact.friendUid.take(8)}")
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 选中态：头像加主题色边框
                        Box {
                            AvatarPlaceholder(
                                name = displayName,
                                size = 44,
                                modifier = Modifier.then(
                                    if (isSelected) Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    ) else Modifier
                                ),
                            )
                        }
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
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
