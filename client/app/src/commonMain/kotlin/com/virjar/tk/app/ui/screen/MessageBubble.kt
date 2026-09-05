package com.virjar.tk.app.ui.screen

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
import androidx.compose.ui.platform.testTag
import com.virjar.tk.app.ui.platform.contextLongPress
import com.virjar.tk.app.ui.platform.secondaryClick
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.shared.client.OutgoingFailureCode
import androidx.compose.runtime.key
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.component.hasReadyEdgeToEdgeMedia
import com.virjar.tk.app.ui.theme.Tk

@Composable
internal fun MessageBubble(
    msg: Message,
    isMe: Boolean,
    isContinuation: Boolean,
    showSenderName: Boolean,
    showReadIndicator: Boolean,
    /** 本条消息的表情回应组（chips、本人 uid、picker 开合与选择回调）。 */
    reactions: MessageReactions = MessageReactions(),
    peerReadSeq: Long = 0,
    /** 会话级正文展示上下文：发送者解析、正文导航、媒体交互与渲染槽。 */
    content: MessageContentContext,
    selectableText: Boolean = false,
    /** 桌面右键菜单会话 ID（非 null=菜单正开，SelectionContainer 以此为 key 重建清空选区——微信右键不选词） */
    menuEpoch: Int = 0,
    onLongClick: () -> Unit,
    menuExpanded: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    menuItems: @Composable ColumnScope.() -> Unit = {},
    outgoingFailureCode: OutgoingFailureCode? = null,
    modifier: Modifier = Modifier,
) {
    val resolveSender = content.resolveSender
    val acknowledgedMessageTag = msg.serverSeq.takeIf { it > 0 }?.let { "chat.message.seq.$it" }
    Row(
        modifier = modifier
            .then(acknowledgedMessageTag?.let { Modifier.testTag(it) } ?: Modifier)
            .fillMaxWidth()
            .padding(vertical = if (isContinuation) Tk.spacing.xs / 2f else Tk.spacing.xs),
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
                    avatar = user?.avatar,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 2.dp, start = Tk.spacing.xs),
                ) {
                    Text(
                        "${resolveDisplayName(msg.senderUid, resolveSender)}  ${formatChatTime(msg.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tk.colors.metaText,
                    )
                    if (resolveSender?.invoke(msg.senderUid)?.role == UserRole.BOT) {
                        Spacer(Modifier.width(5.dp))
                        RoleChip("机器人")
                    }
                }
            }
            Surface(
                color = if (isMe) Tk.colors.bubbleOutgoing else Tk.colors.bubbleIncoming,
                contentColor = if (isMe) Tk.colors.bubbleOutgoingContent else MaterialTheme.colorScheme.onSurface,
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
                    .then(acknowledgedMessageTag?.let { Modifier.testTag("$it.body") } ?: Modifier)
                    .contextLongPress(onLongClick)
                    .secondaryClick(onLongClick),
            ) {
                if (msg.hasReadyEdgeToEdgeMedia()) {
                    // 贴边媒体（图片/视频/贴纸）：无气泡内边距，媒体自身即气泡面（微信/飞书范式）
                    Box(modifier = Modifier.widthIn(max = Tk.dimens.bubbleMaxWidth)) {
                        com.virjar.tk.app.ui.component.MessageBodyRenderer(
                            message = msg,
                            isMe = isMe,
                            onMediaClick = content.onMediaClick,
                            onEmbeddedMediaClick = content.onEmbeddedMediaClick,
                            onMessageLongClick = onLongClick,
                            imageContent = content.imageContent,
                            voicePlayback = content.voicePlayback,
                            onMentionClick = content.onMentionClick,
                            onUrlClick = content.onUrlClick,
                            resolveSender = content.resolveSender,
                        )
                    }
                } else {
                    val bubbleContent: @Composable () -> Unit = {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                                .widthIn(max = Tk.dimens.bubbleMaxWidth - (Tk.spacing.md * 2))
                        ) {
                            com.virjar.tk.app.ui.component.MessageBodyRenderer(
                                message = msg,
                                isMe = isMe,
                                onMediaClick = content.onMediaClick,
                                onEmbeddedMediaClick = content.onEmbeddedMediaClick,
                                onMessageLongClick = onLongClick,
                                imageContent = content.imageContent,
                                voicePlayback = content.voicePlayback,
                                onMentionClick = content.onMentionClick,
                                onUrlClick = content.onUrlClick,
                                resolveSender = content.resolveSender,
                            )
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
            if (reactions.groups.isNotEmpty() && msg.serverSeq > 0L) {
                com.virjar.tk.app.ui.component.ReactionChips(
                    groups = reactions.groups,
                    myUid = reactions.myUid,
                    onToggle = reactions.onToggle,
                    onMore = reactions.onOpenPicker,
                )
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
            if (isMe && msg.sendStatus == Message.SEND_STATUS_FAILED) {
                Text(
                    text = outgoingFailureText(msg, outgoingFailureCode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 1.dp, end = Tk.spacing.xs)
                        .testTag("chat.message.failed.${msg.clientMsgId.take(12)}"),
                )
            }
            // 长按/右键菜单：挂在气泡同级（Column 内），Compose 自动以气泡为锚点定位
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                content = menuItems,
            )
            if (reactions.pickerVisible && msg.serverSeq > 0L) {
                com.virjar.tk.app.ui.component.ReactionPickerPopup(
                    onPick = { emoji ->
                        reactions.onPick(emoji)
                        reactions.onDismissPicker()
                    },
                    onDismiss = reactions.onDismissPicker,
                )
            }
        }

        // 自己头像（右）
        if (isMe) {
            if (isContinuation) {
                Spacer(Modifier.width(Tk.dimens.chatAvatar))
            } else {
                val user = resolveSender?.invoke(msg.senderUid)
                AvatarPlaceholder(
                    name = user?.name ?: user?.username ?: msg.senderUid,
                    avatar = user?.avatar,
                    modifier = Modifier.padding(start = Tk.spacing.sm),
                    size = Tk.dimens.chatAvatar.value.toInt(),
                )
            }
        }
    }
}

private fun outgoingFailureText(message: Message, code: OutgoingFailureCode?): String = when {
    code == null -> "发送失败，正在确认安全恢复方式；可丢弃本地记录"
    canEditAndResendFailedMessage(message, isMe = true, code) ->
        "发送失败：${code.publicMessage}，可编辑重发或丢弃"
    code.allowsFreshClientMsgIdReplacement -> "发送失败：${code.publicMessage}；可丢弃本地记录"
    else -> "发送状态待确认：${code.publicMessage}；为避免重复，仅可丢弃本地记录"
}
