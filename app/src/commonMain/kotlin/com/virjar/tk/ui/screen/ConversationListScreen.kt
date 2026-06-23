package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Conversation
import com.virjar.tk.ui.component.ChatAvatar

@Composable
fun ConversationListScreen(
    conversations: List<Conversation>,
    onConversationClick: (String) -> Unit,
    onPinClick: ((String, Boolean) -> Unit)? = null,
) {
    if (conversations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无会话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // 置顶在前，然后按时间倒序
    val sorted = conversations.sortedWith(
        compareByDescending<Conversation> { it.isPinned }
            .thenByDescending { it.lastMsgTimestamp ?: 0L }
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sorted, key = { it.chatId }) { conv ->
            ConversationItem(
                conversation = conv,
                onClick = { onConversationClick(conv.chatId) },
                onLongClick = onPinClick?.let { { onPinClick(conv.chatId, !conv.isPinned) } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .testTag("conv.item.${conversation.chatId.take(12)}")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像
        ChatAvatar(
            chatType = conversation.chatType,
            chatName = conversation.chatName,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 置顶标识
                if (conversation.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "已置顶",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = conversation.chatName ?: conversation.chatId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                        Text(
                            if (conversation.unreadCount > 99) "99+" else "${conversation.unreadCount}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                // 可见的置顶按钮（提升可发现性和可测试性）
                if (onLongClick != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onLongClick,
                        modifier = Modifier.size(28.dp).testTag("conv.pin.${conversation.chatId.take(12)}"),
                    ) {
                        Icon(
                            imageVector = if (conversation.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (conversation.isPinned) "取消置顶" else "置顶",
                            modifier = Modifier.size(16.dp),
                            tint = if (conversation.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            if (conversation.draft != null) {
                Text(
                    text = "[草稿] ${conversation.draft}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                conversation.lastMessage?.let { msg ->
                    Text(
                        text = lastMessagePreview(msg, conversation.lastMessageType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun lastMessagePreview(text: String, type: Int?): String {
    if (type == null) return text
    return when (type) {
        com.virjar.tk.protocol.MessageType.TEXT.code -> text
        com.virjar.tk.protocol.MessageType.FILE.code -> "[文件] $text"
        com.virjar.tk.protocol.MessageType.VOICE.code -> "[语音]"
        com.virjar.tk.protocol.MessageType.IMAGE.code -> "[图片]"
        com.virjar.tk.protocol.MessageType.VIDEO.code -> "[视频]"
        com.virjar.tk.protocol.MessageType.LOCATION.code -> "[位置]"
        com.virjar.tk.protocol.MessageType.CARD.code -> "[名片]"
        com.virjar.tk.protocol.MessageType.STICKER.code -> "[表情]"
        com.virjar.tk.protocol.MessageType.FORWARD.code -> "[转发]"
        com.virjar.tk.protocol.MessageType.MERGE_FORWARD.code -> "[合并转发]"
        com.virjar.tk.protocol.MessageType.REVOKE.code -> "撤回了一条消息"
        com.virjar.tk.protocol.MessageType.TYPING.code -> "正在输入..."
        else -> text
    }
}
