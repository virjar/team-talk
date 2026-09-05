package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.ChatAvatar
import com.virjar.tk.app.ui.component.UnreadBadge
import com.virjar.tk.app.ui.platform.primaryClickWithContextLongPress
import com.virjar.tk.app.ui.platform.secondaryClick
import com.virjar.tk.app.ui.theme.Tk
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class ConversationMuteMenuPresentation(
    val targetMuted: Boolean,
    val label: String,
    val testTag: String,
)

internal fun conversationMuteMenuPresentation(conversation: Conversation) = ConversationMuteMenuPresentation(
    targetMuted = !conversation.isMuted,
    label = if (conversation.isMuted) "关闭免打扰" else "开启免打扰",
    testTag = "conv.mute.${conversation.chatId.take(12)}",
)

data class ConversationIdentityPresentation(
    val name: String?,
    val avatar: Attachment?,
)

fun currentConversationPeerUser(conversation: Conversation, peerUser: User?): User? =
    peerUser?.takeIf { user ->
        conversation.peerRevision?.let { snapshotRevision -> user.revision >= snapshotRevision } == true
    }

/** 已加载的规范化用户是权威来源，即使其头像被显式清除也是如此。 */
fun conversationIdentityPresentation(
    conversation: Conversation,
    peerUser: User?,
): ConversationIdentityPresentation = currentConversationPeerUser(conversation, peerUser)?.let { currentPeer ->
    ConversationIdentityPresentation(
        name = currentPeer.name.ifBlank { currentPeer.username },
        avatar = currentPeer.avatar,
    )
} ?: run {
    ConversationIdentityPresentation(conversation.chatName, conversation.chatAvatar)
}

/**
 * 会话列表（共享组件，规格见 doc/05-clients/design-system.md）。
 *
 * @param selectedChatId 当前打开的会话（选中态高亮）
 * @param onMuteClick 右键或长按菜单「开启/关闭免打扰」（chatId, muted）
 * @param onMarkRead 右键菜单「标记已读」：本地水位线置顶（chatId, lastSeq）
 */
@Composable
fun ConversationListScreen(
    conversations: List<Conversation>,
    onConversationClick: (String) -> Unit,
    onPinClick: ((String, Boolean) -> Unit)? = null,
    onMuteClick: ((String, Boolean) -> Unit)? = null,
    selectedChatId: String? = null,
    onMarkRead: ((String, Long) -> Unit)? = null,
    peerUsers: Map<String, User> = emptyMap(),
) {
    // 首次收藏前不占用会话列表；收藏后与普通会话一起排序，置顶仅由用户决定。
    val sorted = conversations
        .filter { it.chatType != ChatType.SAVED.code || it.lastSeq > 0L }
        .sortedWith(
            compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.lastMsgTimestamp ?: 0L },
        )
    if (sorted.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无会话",
                style = MaterialTheme.typography.bodySmall,
                color = Tk.colors.metaText,
                modifier = Modifier.testTag("conv.empty"),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Tk.spacing.xs),
    ) {
        items(sorted, key = { it.chatId }) { conv ->
            val peerUser = conv.peerUid?.let(peerUsers::get)
            ConversationItem(
                conversation = conv,
                peerUser = peerUser,
                selected = conv.chatId == selectedChatId,
                onClick = { onConversationClick(conv.chatId) },
                onPinToggle = onPinClick?.let { { onPinClick(conv.chatId, !conv.isPinned) } },
                onMuteToggle = onMuteClick?.let { callback ->
                    { muted -> callback(conv.chatId, muted) }
                },
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
    peerUser: User?,
    selected: Boolean,
    onClick: () -> Unit,
    onPinToggle: (() -> Unit)?,
    onMuteToggle: ((Boolean) -> Unit)?,
    onMarkRead: (() -> Unit)?,
) {
    // 桌面 hover 态（触屏设备不触发，API 兼容）
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    val muteMenu = conversationMuteMenuPresentation(conversation)
    val identity = conversationIdentityPresentation(conversation, peerUser)
    val displayName = identity.name

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
            // Android 在同一个手势节点中处理单击和长按，Desktop 仍由右键打开菜单。
            .primaryClickWithContextLongPress(
                onClick = onClick,
                onLongPress = { menuExpanded = true },
            )
            .secondaryClick { menuExpanded = true }
            .testTag(
                if (conversation.chatType == ChatType.SAVED.code) "conv.saved.entry"
                else "conv.item.${conversation.chatId.take(12)}",
            ),
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
                chatName = displayName,
                avatar = identity.avatar,
                size = Tk.dimens.listAvatar.value.toInt(),
            )

            Spacer(Modifier.width(Tk.spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                // 行1：名称 + 免打扰/置顶图标 + 时间 + 未读徽标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName ?: conversation.chatId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Tk.spacing.sm))

                    if (conversation.isMuted) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeOff,
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

        // 右键/长按菜单：置顶、免打扰与已读状态
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
            if (onMuteToggle != null) {
                DropdownMenuItem(
                    text = { Text(muteMenu.label) },
                    onClick = {
                        onMuteToggle(muteMenu.targetMuted)
                        menuExpanded = false
                    },
                    modifier = Modifier.testTag(muteMenu.testTag),
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
        com.virjar.tk.protocol.MessageType.RICH_TEXT.code -> text
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
