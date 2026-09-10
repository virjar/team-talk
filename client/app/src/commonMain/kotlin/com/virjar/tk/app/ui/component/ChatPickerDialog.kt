package com.virjar.tk.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Conversation

/**
 * 会话选择器（内测 T023）：文档工作台把文档分享进任一会话时挑选目标。
 * 列表与聊天列表同一投影；点击即回调，不做发送。
 */
@Composable
internal fun ChatPickerDialog(
    conversations: List<Conversation>,
    title: String,
    onPick: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(conversations, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) conversations
        else conversations.filter {
            (it.chatName.orEmpty()).contains(trimmed, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("分享到会话") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("搜索会话") },
                    modifier = Modifier.fillMaxWidth().testTag("share.chat.query"),
                )
                Spacer(Modifier.padding(top = Tk.spacing.xs))
                if (filtered.isEmpty()) {
                    Text(
                        "没有匹配的会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tk.colors.metaText,
                        modifier = Modifier.padding(vertical = Tk.spacing.sm),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filtered, key = { it.chatId }) { conversation ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("share.chat.pick.${conversation.chatId.take(12)}")
                                    .clickable { onPick(conversation) }
                                    .padding(vertical = Tk.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    conversationLabel(conversation),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(Tk.spacing.sm))
                                Text(
                                    when (ChatType.fromCode(conversation.chatType)) {
                                        ChatType.GROUP -> "群组"
                                        ChatType.SAVED -> "保存的消息"
                                        ChatType.PERSONAL -> "私聊"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Tk.colors.metaText,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun conversationLabel(conversation: Conversation): String =
    conversation.chatName?.trim()?.takeIf(String::isNotEmpty)
        ?: conversation.peerUid?.take(8)
        ?: conversation.chatId.take(8)
