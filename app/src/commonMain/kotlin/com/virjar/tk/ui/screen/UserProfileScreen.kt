package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.User
import com.virjar.tk.ui.component.ScreenHeader

@Composable
fun UserProfileScreen(
    user: User?,
    isFriend: Boolean,
    hasPendingApply: Boolean,
    onAddFriend: () -> Unit,
    onSendMessage: () -> Unit,
    onDeleteFriend: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "用户资料", onBack = onBack)

        // 删除确认弹窗
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            user.name.take(1),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(user.name, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("@${user.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("UID: ${user.uid.take(16)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            Column(modifier = Modifier.padding(16.dp)) {
                when {
                    isFriend -> {
                        FilledTonalButton(
                            onClick = onSendMessage,
                            modifier = Modifier.fillMaxWidth().testTag("profile.sendMessage"),
                        ) { Text("发消息") }
                        if (onDeleteFriend != null) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.fillMaxWidth().testTag("profile.deleteFriend"),
                                colors = ButtonDefaults.outlinedButtonColors(
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
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
