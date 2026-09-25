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
import com.virjar.tk.protocol.model.ChatType
import kotlinx.coroutines.launch

/** 会话工具窗口内的可用动作；清单由会话类型计算（[chatToolActionsFor]），UI 只渲染。 */
enum class ChatToolActionKind {
    HISTORY_SEARCH,
    CREATE_GROUP,
    CLEAR_HISTORY,
}

/**
 * 按会话类型计算可用动作：
 * - 群聊：已在群里，"发起群聊"与当前会话无关，不提供；
 * - 系统账号会话（服务号/文件传输助手）：系统账号不能入群，不提供"发起群聊"；
 * - 私聊（真人）：提供"发起群聊"并预选当前对方；
 * - 保存的消息：没有对端，不提供"发起群聊"。
 */
fun chatToolActionsFor(chatType: ChatType, peerUid: String?): List<ChatToolActionKind> = buildList {
    add(ChatToolActionKind.HISTORY_SEARCH)
    if (chatType == ChatType.PERSONAL && !isSystemAccountUid(peerUid)) {
        add(ChatToolActionKind.CREATE_GROUP)
    }
    add(ChatToolActionKind.CLEAR_HISTORY)
}

/**
 * 系统账号（服务号/文件传输助手等固定系统会话的对端）以 `sys_` 前缀约定；
 * 与 [com.virjar.tk.app.navigation.feature.chat] 拉起的系统会话 uid 保持一致。
 */
fun isSystemAccountUid(uid: String?): Boolean = uid != null && uid.startsWith("sys_")

private data class ChatToolActionSpec(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

private val CHAT_TOOL_ACTION_SPECS: Map<ChatToolActionKind, ChatToolActionSpec> = mapOf(
    ChatToolActionKind.HISTORY_SEARCH to ChatToolActionSpec(
        icon = Icons.Filled.Search,
        title = "搜索聊天记录",
        subtitle = "按关键词、类型、发送人和时间查找",
    ),
    ChatToolActionKind.CREATE_GROUP to ChatToolActionSpec(
        icon = Icons.Filled.GroupAdd,
        title = "发起群聊",
        subtitle = "与当前好友和其他联系人创建群聊",
    ),
    ChatToolActionKind.CLEAR_HISTORY to ChatToolActionSpec(
        icon = Icons.Filled.DeleteOutline,
        title = "清空聊天记录",
        subtitle = "删除本设备的聊天记录，不影响对方",
    ),
)

/**
 * 会话工具页（聊天头部「···」进入的独立窗口根页）。
 *
 * 组件只渲染 [actions] 声明的动作；清单由 [chatToolActionsFor] 按会话类型计算，
 * 宿主通过 [onAction] 承接跳转类动作（搜索/建群）。「清空聊天记录」的就地确认
 * 与执行由本页内部处理（[onClearHistory]）。
 * 清空聊天记录只影响本机：SDK 在删除本地投影的同时记录清空水位，防止历史拉取
 * 把已清空消息重新落库；对端与其他设备不受影响。
 *
 * @param actions 经会话类型过滤后的动作清单
 * @param onAction 承接 HISTORY_SEARCH / CREATE_GROUP；CLEAR_HISTORY 不会回调
 * @param onClearHistory 执行清空；返回 null 表示成功，否则为可展示的失败原因
 * @param onFinished 清空成功后的收尾（宿主通常关闭窗口）
 */
@Composable
fun ChatToolsScreen(
    chatName: String,
    isGroup: Boolean,
    actions: List<ChatToolActionKind>,
    onAction: (ChatToolActionKind) -> Unit,
    onClearHistory: suspend () -> String?,
    onFinished: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var confirmVisible by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "聊天工具", onBack = onBack)

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

        actions.forEachIndexed { index, kind ->
            val spec = checkNotNull(CHAT_TOOL_ACTION_SPECS[kind]) { "unknown chat tool action: $kind" }
            if (index > 0) HorizontalDivider(color = Tk.colors.divider)
            ChatToolsActionRow(
                icon = spec.icon,
                title = spec.title,
                subtitle = spec.subtitle,
                testTag = when (kind) {
                    ChatToolActionKind.HISTORY_SEARCH -> "chatTools.historySearch"
                    ChatToolActionKind.CREATE_GROUP -> "chatTools.createGroup"
                    ChatToolActionKind.CLEAR_HISTORY -> "chatTools.clearHistory"
                },
                tint = if (kind == ChatToolActionKind.CLEAR_HISTORY) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = {
                    when (kind) {
                        ChatToolActionKind.CLEAR_HISTORY -> {
                            errorText = null
                            confirmVisible = true
                        }
                        else -> onAction(kind)
                    }
                },
            )
        }
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
