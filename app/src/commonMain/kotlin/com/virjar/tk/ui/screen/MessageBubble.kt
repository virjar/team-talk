package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.virjar.tk.ui.platform.contextLongPress
import com.virjar.tk.ui.platform.secondaryClick
import androidx.compose.ui.unit.dp
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import androidx.compose.runtime.key
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.isEdgeToEdgeMedia
import com.virjar.tk.ui.component.VoicePlaybackController
import com.virjar.tk.ui.theme.Tk

@Composable
internal fun MessageBubble(
    msg: Message,
    isMe: Boolean,
    isContinuation: Boolean,
    showSenderName: Boolean,
    showReadIndicator: Boolean,
    peerReadSeq: Long = 0,
    resolveSender: ((uid: String) -> User?)?,
    voicePlayback: com.virjar.tk.ui.component.VoicePlaybackController? = null,
    onMentionClick: ((uid: String) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null,
    selectableText: Boolean = false,
    /** 桌面右键菜单会话 ID（非 null=菜单正开，SelectionContainer 以此为 key 重建清空选区——微信右键不选词） */
    menuEpoch: Int = 0,
    onLongClick: () -> Unit,
    onMediaClick: ((Message) -> Unit)?,
    imageContent: (@Composable (String, Modifier) -> Unit)?,
    videoContent: (@Composable (String, Modifier) -> Unit)?,
    menuExpanded: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    menuItems: @Composable ColumnScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (isContinuation) 1.dp else 2.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // 对方头像（左）
        if (!isMe) {
            if (isContinuation) {
                Spacer(Modifier.width(Tk.dimens.chatAvatar))
            } else {
                val user = resolveSender?.invoke(msg.senderUid)
                AvatarPlaceholder(
                    name = user?.name ?: user?.username ?: msg.senderUid,
                    modifier = Modifier.padding(end = Tk.spacing.sm),
                    size = Tk.dimens.chatAvatar.value.toInt(),
                )
            }
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = Tk.dimens.bubbleMaxWidth),
        ) {
            // 昵称行（群聊对方、非连续消息才显示）：「张三 14:32」
            if (!isMe && showSenderName && !isContinuation) {
                Text(
                    "${resolveDisplayName(msg.senderUid, resolveSender)}  ${formatChatTime(msg.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tk.colors.metaText,
                    modifier = Modifier.padding(bottom = 2.dp, start = Tk.spacing.xs),
                )
            }
            Surface(
                color = if (isMe) Tk.colors.bubbleOutgoing else Tk.colors.bubbleIncoming,
                contentColor = if (isMe) Color.White else MaterialTheme.colorScheme.onSurface,
                // 飞书扁平气泡：无阴影无 tonalElevation
                shape = RoundedCornerShape(
                    topStart = if (!isMe && !isContinuation) 4.dp else 8.dp,
                    topEnd = if (isMe && !isContinuation) 4.dp else 8.dp,
                    bottomStart = if (isMe) 8.dp else if (isContinuation) 8.dp else 4.dp,
                    bottomEnd = if (isMe) if (isContinuation) 8.dp else 4.dp else 8.dp,
                ),
                // 长按弹菜单平台分流（F29）：Android 长按；桌面无长按（拖选文字不被误判），
                // 菜单只走右键 secondaryClick
                modifier = Modifier
                    .contextLongPress(onLongClick)
                    .secondaryClick(onLongClick),
            ) {
                if (msg.body.isEdgeToEdgeMedia()) {
                    // 贴边媒体（图片/视频/贴纸）：无气泡内边距，媒体自身即气泡面（微信/飞书范式）
                    Box(modifier = Modifier.widthIn(max = Tk.dimens.bubbleMaxWidth)) {
                        com.virjar.tk.ui.component.MessageBodyRenderer(msg, isMe, onMediaClick, imageContent, videoContent, voicePlayback, onMentionClick, onUrlClick)
                    }
                } else {
                    val bubbleContent: @Composable () -> Unit = {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                                .widthIn(max = Tk.dimens.bubbleMaxWidth - (Tk.spacing.md * 2))
                        ) {
                            com.virjar.tk.ui.component.MessageBodyRenderer(msg, isMe, onMediaClick, imageContent, videoContent, voicePlayback, onMentionClick, onUrlClick)
                        }
                    }
                    if (selectableText) {
                        // key(menuEpoch)：右键菜单打开瞬间重建容器，清空右键产生的选词
                        // （微信范式：右键只弹菜单不选词；拖选选区随菜单打开一并清空）
                        key(menuEpoch) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                bubbleContent()
                            }
                        }
                    } else {
                        bubbleContent()
                    }
                }
            }
            // 已读水位线指示：私聊中我方最后一条消息下方（飞书「已读/未读」文字范式）
            if (showReadIndicator) {
                val isRead = peerReadSeq > 0 && msg.serverSeq <= peerReadSeq
                Text(
                    text = if (isRead) "已读" else "未读",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRead) Tk.colors.secondaryText else Tk.colors.metaText,
                    modifier = Modifier.padding(top = 1.dp, end = Tk.spacing.xs),
                )
            }
            // 长按/右键菜单：挂在气泡同级（Column 内），Compose 自动以气泡为锚点定位
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                content = menuItems,
            )
        }

        // 自己头像（右）
        if (isMe) {
            if (isContinuation) {
                Spacer(Modifier.width(Tk.dimens.chatAvatar))
            } else {
                val user = resolveSender?.invoke(msg.senderUid)
                AvatarPlaceholder(
                    name = user?.name ?: user?.username ?: msg.senderUid,
                    modifier = Modifier.padding(start = Tk.spacing.sm),
                    size = Tk.dimens.chatAvatar.value.toInt(),
                )
            }
        }
    }
}
