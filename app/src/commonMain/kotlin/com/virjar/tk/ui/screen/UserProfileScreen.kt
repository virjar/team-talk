package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.User
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.ScreenHeader

enum class UserProfilePresentation {
    FullPage,
    CompactDialog,
}

@Composable
fun UserProfileScreen(
    user: User?,
    isFriend: Boolean,
    hasPendingApply: Boolean,
    onAddFriend: () -> Unit,
    onSendMessage: () -> Unit,
    onCreateGroup: (() -> Unit)? = null,
    onDeleteFriend: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "用户资料", onBack = onBack)
        UserProfileContent(
            user = user,
            isFriend = isFriend,
            hasPendingApply = hasPendingApply,
            onAddFriend = onAddFriend,
            onSendMessage = onSendMessage,
            onCreateGroup = onCreateGroup,
            onDeleteFriend = onDeleteFriend,
            presentation = UserProfilePresentation.FullPage,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * 用户资料的内容事实源。Android 用全屏版；Desktop 用紧凑弹窗版，避免为相同业务动作
 * 复制两套状态和 testTag。
 */
@Composable
fun UserProfileContent(
    user: User?,
    isFriend: Boolean,
    hasPendingApply: Boolean,
    onAddFriend: () -> Unit,
    onSendMessage: () -> Unit,
    onCreateGroup: (() -> Unit)? = null,
    onDeleteFriend: (() -> Unit)? = null,
    presentation: UserProfilePresentation = UserProfilePresentation.FullPage,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除好友") },
            text = { Text("确定要删除该好友吗？") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDeleteFriend?.invoke() }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    if (user != null) {
        Column(modifier = modifier) {
            when (presentation) {
                UserProfilePresentation.FullPage -> FullPageProfileHero(user)
                UserProfilePresentation.CompactDialog -> CompactProfileHero(user)
            }

            HorizontalDivider()

            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = if (presentation == UserProfilePresentation.CompactDialog) 0.dp else 16.dp,
                    vertical = 16.dp,
                ),
            ) {
                when {
                    isFriend -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = onSendMessage,
                                modifier = Modifier.weight(1f).testTag("profile.sendMessage"),
                            ) { Text("发消息") }
                            if (onCreateGroup != null) {
                                OutlinedButton(
                                    onClick = onCreateGroup,
                                    modifier = Modifier.weight(1f).testTag("profile.createGroup"),
                                ) { Text("发起群聊") }
                            }
                        }
                        if (onDeleteFriend != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = if (presentation == UserProfilePresentation.CompactDialog) {
                                    Modifier.align(Alignment.CenterHorizontally).testTag("profile.deleteFriend")
                                } else {
                                    Modifier.fillMaxWidth().testTag("profile.deleteFriend")
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("删除好友") }
                        }
                    }
                    hasPendingApply -> {
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().testTag("profile.applied"),
                            enabled = false,
                        ) { Text("已申请") }
                    }
                    else -> {
                        Button(
                            onClick = onAddFriend,
                            modifier = Modifier.fillMaxWidth().testTag("profile.addFriend"),
                        ) { Text("添加好友") }
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.then(
                if (presentation == UserProfilePresentation.CompactDialog) Modifier.height(220.dp)
                else Modifier.fillMaxSize()
            ),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun FullPageProfileHero(user: User) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AvatarPlaceholder(name = user.name, size = 80)
        Spacer(Modifier.height(16.dp))
        Text(user.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "@${user.username}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "UID: ${user.uid.take(16)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompactProfileHero(user: User) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPlaceholder(name = user.name, size = 64)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                user.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "@${user.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "UID: ${user.uid.take(16)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
