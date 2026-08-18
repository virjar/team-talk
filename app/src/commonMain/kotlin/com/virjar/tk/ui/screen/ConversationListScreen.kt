package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Conversation
import com.virjar.tk.ui.component.ChatAvatar
import com.virjar.tk.ui.component.UnreadBadge
import com.virjar.tk.ui.platform.contextLongPress
import com.virjar.tk.ui.platform.secondaryClick
import com.virjar.tk.ui.theme.Tk
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 会话列表（共享组件，规格见 doc/05-clients/design-system.md）。
 *
 * @param selectedChatId 当前打开的会话（选中态高亮）
 * @param onMarkRead 右键菜单「标记已读」：本地水位线置顶（chatId, lastSeq）
 */
@Composable
fun ConversationListScreen(
    conversations: List<Conversation>,
    onConversationClick: (String) -> Unit,
    onPinClick: ((String, Boolean) -> Unit)? = null,
    selectedChatId: String? = null,
    onMarkRead: ((String, Long) -> Unit)? = null,
) {
    if (conversations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无会话",
                style = MaterialTheme.typography.bodySmall,
                color = Tk.colors.metaText,
            )
        }
        return
    }
    // 置顶在前，然后按时间倒序
    val sorted = conversations.sortedWith(
        compareByDescending<Conversation> { it.isPinned }
            .thenByDescending { it.lastMsgTimestamp ?: 0L }
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Tk.spacing.xs),
    ) {
        items(sorted, key = { it.chatId }) { conv ->
            ConversationItem(
                conversation = conv,
                selected = conv.chatId == selectedChatId,
                onClick = { onConversationClick(conv.chatId) },
                onPinToggle = onPinClick?.let { { onPinClick(conv.chatId, !conv.isPinned) } },
                onMarkRead = if (conv.unreadCount > 0 && onMarkRead != null) {
                    { onMarkRead(conv.chatId, conv.lastSeq) }
                } else null,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onPinToggle: (() -> Unit)?,
    onMarkRead: (() -> Unit)?,
) {
    // 桌面 hover 态（触屏设备不触发，API 兼容）
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    val bg = when {
        selected -> Tk.colors.selected
        hovered -> Tk.colors.hover
        conversation.isPinned -> Tk.colors.pinnedBg
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    // 圆角选中/hover 高亮（内缩 8dp，飞书范式）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tk.spacing.sm)
            .padding(vertical = 1.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .hoverable(hoverInteraction)
            .clickable(onClick = onClick)
            // 长按弹菜单平台分流（F29）：Android 长按；桌面右键
            .contextLongPress { menuExpanded = true }
            .secondaryClick { menuExpanded = true }
            .testTag("conv.item.${conversation.chatId.take(12)}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tk.dimens.listItemHeight)
                .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(
                chatType = conversation.chatType,
                chatName = conversation.chatName,
                size = Tk.dimens.listAvatar.value.toInt(),
            )

            Spacer(Modifier.width(Tk.spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                // 行1：名称 + 免打扰/置顶图标 + 时间 + 未读徽标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.chatName ?: conversation.chatId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))

                    if (conversation.isMuted) {
                        Icon(
                            Icons.Filled.VolumeOff,
                            contentDescription = "免打扰",
                            modifier = Modifier.size(13.dp),
                            tint = Tk.colors.pinIcon,
                        )
                        Spacer(Modifier.width(Tk.spacing.xs))
                    }
                    if (conversation.isPinned && onPinToggle != null) {
                        // 置顶图钉：低调灰、可点击（保留 e2e testTag）
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier
                                .size(13.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable(onClick = onPinToggle)
                                .testTag("conv.pin.${conversation.chatId.take(12)}"),
                            tint = Tk.colors.pinIcon,
                        )
                        Spacer(Modifier.width(Tk.spacing.xs))
                    }
                    Text(
                        text = conversation.lastMsgTimestamp?.let { formatListTime(it) } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tk.colors.metaText,
                        maxLines = 1,
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(Modifier.width(Tk.spacing.sm))
                        UnreadBadge(conversation.unreadCount)
                    }
                }
                Spacer(Modifier.height(2.dp))
                // 行2：草稿（红）或最后一条消息预览
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
                            color = Tk.colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 右键/长按菜单：置顶切换 + 标记已读
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (onPinToggle != null) {
                DropdownMenuItem(
                    text = { Text(if (conversation.isPinned) "取消置顶" else "置顶") },
                    onClick = { onPinToggle(); menuExpanded = false },
                )
            }
            if (onMarkRead != null) {
                DropdownMenuItem(
                    text = { Text("标记已读") },
                    onClick = { onMarkRead(); menuExpanded = false },
                )
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

/**
 * 会话列表时间格式：今天 HH:mm；昨天「昨天」；7 天内「周X」；跨年 yyyy/MM/dd；其余 MM/dd。
 */
internal fun formatListTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }

    val sameDay = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    // 昨天判定：now 减一天后同日
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天"

    if (now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)) {
        val diffDays = (now.timeInMillis - msg.timeInMillis) / (24 * 3600 * 1000L)
        if (diffDays < 7) {
            val weekdays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            return weekdays[msg.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY]
        }
        return SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
    }
    return SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(timestamp))
}
