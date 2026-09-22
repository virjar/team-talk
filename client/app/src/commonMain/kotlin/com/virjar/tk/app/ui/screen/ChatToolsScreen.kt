package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.theme.Tk
import kotlinx.coroutines.launch

/**
 * 会话工具页（聊天头部「···」进入的独立窗口根页）。
 *
 * 当前承载聊天记录搜索、发起群聊与清空聊天记录；后续会话级操作在此追加。
 * 清空聊天记录只影响本机：SDK 在删除本地投影的同时记录清空水位，防止历史拉取
 * 把已清空消息重新落库；对端与其他设备不受影响。
 *
 * @param onOpenHistorySearch 进入聊天记录搜索（搜索全部内容 + 分类浏览）
 * @param onClearHistory 执行清空；返回 null 表示成功，否则为可展示的失败原因
 * @param onFinished 清空成功后的收尾（宿主通常关闭窗口）
 */
@Composable
fun ChatToolsScreen(
    chatName: String,
    isGroup: Boolean,
    onOpenHistorySearch: () -> Unit,
    onCreateGroup: () -> Unit,
    onClearHistory: suspend () -> String?,
    onBack: (() -> Unit)? = null,
    onFinished: (() -> Unit)? = null,
) {
    var confirmVisible by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "会话设置", onBack = onBack)

        Text(
            text = if (isGroup) "群聊「$chatName」" else chatName.ifEmpty { "当前会话" },
            style = MaterialTheme.typography.titleSmall,
            color = Tk.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm)
                .testTag("chatTools.context"),
        )

        ChatToolsActionRow(
            icon = Icons.Filled.Search,
            title = "搜索聊天记录",
            subtitle = "按关键词、类型、发送人和时间查找",
            testTag = "chatTools.historySearch",
            onClick = onOpenHistorySearch,
        )
        HorizontalDivider(color = Tk.colors.divider)
        ChatToolsActionRow(
            icon = Icons.Filled.GroupAdd,
            title = "发起群聊",
            subtitle = if (isGroup) "创建一个新的群聊" else "与当前好友和其他联系人创建群聊",
            testTag = "chatTools.createGroup",
            onClick = onCreateGroup,
        )
        HorizontalDivider(color = Tk.colors.divider)
        ChatToolsActionRow(
            icon = Icons.Filled.DeleteOutline,
            title = "清空聊天记录",
            subtitle = "删除本设备的聊天记录，不影响对方",
            testTag = "chatTools.clearHistory",
            tint = MaterialTheme.colorScheme.error,
            onClick = { errorText = null; confirmVisible = true },
        )
        errorText?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.sm)
                    .testTag("chatTools.error"),
            )
        }
    }

    if (confirmVisible) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmVisible = false },
            title = { Text("清空聊天记录") },
            text = {
                Text(
                    "将清空「${chatName.ifEmpty { "当前会话" }}」在本设备的全部聊天记录，" +
                        "不影响对方的聊天记录。此操作不可恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        scope.launch {
                            val failure = onClearHistory()
                            clearing = false
                            if (failure == null) {
                                confirmVisible = false
                                onFinished?.invoke()
                            } else {
                                errorText = failure
                            }
                        }
                    },
                    modifier = Modifier.testTag("chatTools.clearHistory.confirm"),
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !clearing,
                    onClick = { confirmVisible = false },
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ChatToolsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Tk.spacing.lg, vertical = Tk.spacing.md)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Tk.spacing.md))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Tk.colors.metaText,
            )
        }
    }
}
