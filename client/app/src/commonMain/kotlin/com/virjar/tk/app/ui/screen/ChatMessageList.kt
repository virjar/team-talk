package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.body.isMarkdownTextBody
import com.virjar.tk.protocol.body.plainTextContentOrNull
import com.virjar.tk.shared.client.OutgoingFailureCode
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.app.ui.platform.rememberClipboardTextWriter
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.ui.component.MessagePreview

@Composable
internal fun ChatMessageList(
    loading: Boolean,
    messages: List<Message>,
    state: LazyListState,
    hasMore: Boolean,
    loadingOlder: Boolean,
    highlightedServerSeq: Long? = null,
    outgoingFailureCodes: Map<String, OutgoingFailureCode> = emptyMap(),
    reactions: Map<Long, List<MessageReactionGroup>> = emptyMap(),
    onToggleReaction: (serverSeq: Long, emoji: String) -> Unit = { _, _ -> },
    onPickReaction: (serverSeq: Long, emoji: String) -> Unit = { _, _ -> },
    /** 非 null 且消息已确认时，长按/右键菜单提供"保存"（CLIENT-08）。 */
    onSaveMessage: ((Message) -> Unit)? = null,
    /** saved 会话内部不再提供"保存"入口，避免自引用。 */
    isSavedChat: Boolean = false,
    /** 消息集变化时收敛一次权威回应快照（VM 内按窗口下界去重）。 */
    onWindowReactionsConverge: () -> Unit = {},
    myUid: String,
    isPersonal: Boolean,
    peerReadSeq: Long,
    /** 会话级正文展示上下文：发送者解析、正文导航、媒体交互与渲染槽。 */
    content: MessageContentContext,
    selectableText: Boolean,
    menuMessage: Message?,
    onMenuMessageChange: (Message?) -> Unit,
    voiceMode: Boolean,
    editingSessionActive: Boolean,
    onCancelEditing: () -> Unit,
    onReply: (Message) -> Unit,
    onBeginEditing: (Message) -> Unit,
    onDiscardFailed: (Message) -> Unit,
    onRevoke: (Long) -> Unit,
    onForward: ((Message) -> Unit)?,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.LaunchedEffect(messages) { onWindowReactionsConverge() }

    if (loading && messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = Tk.spacing.md),
            state = state,
            reverseLayout = true,
        ) {
            items(
                count = messages.size,
                // clientMsgId 只在单个聊天内唯一。把作用域纳入 key，使 A -> B 的宿主切换
                // 不会在无关的消息身份下保留条目组合。
                key = { index -> messages[index].let { "${it.chatId}\u0000${it.clientMsgId}" } },
                contentType = { index -> messages[index].messageType },
            ) { index ->
                val msg = messages[index]
                val isMe = msg.senderUid == myUid
                val focusModifier = if (msg.serverSeq == highlightedServerSeq) {
                    Modifier
                        .testTag("chat.message.focused.${msg.serverSeq}")
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 4.dp)
                } else {
                    Modifier
                }

                // 连续消息判断（reverseLayout: index+1 是时间更早的消息）
                val prevMsg = messages.getOrNull(index + 1)
                val isContinuation = prevMsg != null
                    && prevMsg.senderUid == msg.senderUid
                    && (msg.timestamp - prevMsg.timestamp) < CONTINUATION_THRESHOLD_MS
                    && (msg.flags and Message.FLAG_REVOKED) == 0

                // 时间分隔判断（reverseLayout: index-1 是时间更晚的消息）
                val nextMsg = messages.getOrNull(index - 1)
                val showTimeSeparator = nextMsg == null
                    || (nextMsg.timestamp - msg.timestamp) > TIME_SEPARATOR_THRESHOLD_MS

                // 是否是我最后一条已送达消息（已读水位线指示只挂在这里，飞书范式）
                val isMyLastMsg = isMe
                    && msg.serverSeq > 0
                    && (nextMsg == null || nextMsg.senderUid != myUid)

                // 撤回消息走系统提示（居中裸文字），不走气泡
                if (msg.flags and Message.FLAG_REVOKED != 0) {
                    androidx.compose.foundation.layout.Row(
                        modifier = focusModifier.fillMaxWidth().padding(vertical = Tk.spacing.xs),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${if (isMe) "你" else resolveDisplayName(msg.senderUid, content.resolveSender)} 撤回了一条消息",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tk.colors.metaText,
                        )
                    }
                } else {
                    var reactionPickerVisible by remember(msg.clientMsgId) { mutableStateOf(false) }
                    Column {
                        // 时间分隔：裸文字（飞书范式，无胶囊底）
                        if (showTimeSeparator) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = Tk.spacing.sm),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    formatChatTime(msg.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Tk.colors.metaText,
                                )
                            }
                        }

                        MessageBubble(
                            msg = msg,
                            isMe = isMe,
                            isContinuation = isContinuation,
                            showSenderName = !isPersonal && !isMe,
                            showReadIndicator = isPersonal && isMyLastMsg,
                            reactions = MessageReactions(
                                groups = if (msg.serverSeq > 0L) reactions[msg.serverSeq].orEmpty() else emptyList(),
                                myUid = myUid,
                                onToggle = { emoji -> onToggleReaction(msg.serverSeq, emoji) },
                                pickerVisible = reactionPickerVisible,
                                onOpenPicker = { reactionPickerVisible = true },
                                onDismissPicker = { reactionPickerVisible = false },
                                onPick = { emoji -> onPickReaction(msg.serverSeq, emoji) },
                            ),
                            peerReadSeq = peerReadSeq,
                            content = content,
                            selectableText = selectableText,
                            menuEpoch = if (menuMessage?.clientMsgId == msg.clientMsgId) msg.hashCode() else 0,
                            onLongClick = { onMenuMessageChange(msg) },
                            modifier = focusModifier,
                            menuExpanded = menuMessage?.clientMsgId == msg.clientMsgId,
                            onMenuDismiss = { onMenuMessageChange(null) },
                            outgoingFailureCode = outgoingFailureCodes[msg.clientMsgId],
                            menuItems = messageMenuItems(
                                msg = msg,
                                isMe = isMe,
                                outgoingFailureCode = outgoingFailureCodes[msg.clientMsgId],
                                voiceMode = voiceMode,
                                editingSessionActive = editingSessionActive,
                                onMenuMessageChange = onMenuMessageChange,
                                onCancelEditing = onCancelEditing,
                                onReply = onReply,
                                onBeginEditing = onBeginEditing,
                                onDiscardFailed = onDiscardFailed,
                                onRevoke = onRevoke,
                                onForward = onForward,
                                onToggleReaction = onToggleReaction,
                                onOpenReactionPicker = {
                                    onMenuMessageChange(null)
                                    reactionPickerVisible = true
                                },
                                onSaveMessage = onSaveMessage?.takeIf { !isSavedChat },
                                isSavedChat = isSavedChat,
                            ),
                        )
                    }
                }
            }
            if (hasMore || loadingOlder) {
                item(key = "history-loader") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(Tk.spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loadingOlder) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).testTag("chat.history.loading"),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            TextButton(
                                onClick = onLoadOlder,
                                modifier = Modifier.testTag("chat.history.loadMore"),
                            ) { Text("加载更早消息") }
                        }
                    }
                }
            }
        }
    }
}

private fun messageMenuItems(
    msg: Message,
    isMe: Boolean,
    outgoingFailureCode: OutgoingFailureCode?,
    voiceMode: Boolean,
    editingSessionActive: Boolean,
    onMenuMessageChange: (Message?) -> Unit,
    onCancelEditing: () -> Unit,
    onReply: (Message) -> Unit,
    onBeginEditing: (Message) -> Unit,
    onDiscardFailed: (Message) -> Unit,
    onRevoke: (Long) -> Unit,
    onForward: ((Message) -> Unit)?,
    onToggleReaction: (serverSeq: Long, emoji: String) -> Unit,
    onOpenReactionPicker: () -> Unit,
    onSaveMessage: ((Message) -> Unit)?,
    isSavedChat: Boolean,
): @Composable ColumnScope.() -> Unit = {
    val copyText = rememberClipboardTextWriter()
    if (msg.serverSeq > 0L) {
        // 快捷回应栏：已确认消息直接从菜单选择 emoji；chips 点击负责取消
        com.virjar.tk.app.ui.component.ReactionQuickBar(
            onPick = { emoji ->
                onToggleReaction(msg.serverSeq, emoji)
                onMenuMessageChange(null)
            },
            onMore = onOpenReactionPicker,
        )
    }
    DropdownMenuItem(
        text = { Text("复制") },
        onClick = {
            copyText(
                msg.body.plainTextContentOrNull()
                    ?: MessagePreview.preview(msg, flagsAware = false),
            )
            onMenuMessageChange(null)
        },
    )
    if (!voiceMode) {
        if (msg.confirmedReplyToMsgIdOrNull() != null) {
            DropdownMenuItem(
                text = { Text("回复") },
                onClick = {
                    if (editingSessionActive) onCancelEditing()
                    onReply(msg)
                    onMenuMessageChange(null)
                },
            )
        }
        if (canEditAndResendFailedMessage(msg, isMe, outgoingFailureCode)) {
            DropdownMenuItem(
                text = { Text("编辑并重发") },
                onClick = {
                    if (editingSessionActive) onCancelEditing()
                    onBeginEditing(msg)
                    onMenuMessageChange(null)
                },
                modifier = Modifier.testTag("chat.failed.recover.${msg.clientMsgId.take(12)}"),
            )
        } else if (isMe && msg.serverSeq > 0L && msg.body.isMarkdownTextBody()) {
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = {
                    if (editingSessionActive) onCancelEditing()
                    onBeginEditing(msg)
                    onMenuMessageChange(null)
                },
            )
        }
    }
    if (canDiscardFailedMessage(msg, isMe)) {
        DropdownMenuItem(
            text = { Text("丢弃失败消息") },
            onClick = {
                onDiscardFailed(msg)
                onMenuMessageChange(null)
            },
            modifier = Modifier.testTag("chat.failed.discard.${msg.clientMsgId.take(12)}"),
        )
    }
    // 撤回时限是防扰动的私聊/群聊 UI 规则；"保存的消息"是自有副本，随时可删除。
    val canRevoke = isMe && msg.serverSeq > 0L &&
        (isSavedChat || System.currentTimeMillis() - msg.timestamp < 2 * 60 * 1000)
    if (onSaveMessage != null && msg.serverSeq > 0L) {
        DropdownMenuItem(
            text = { Text("保存") },
            onClick = {
                onSaveMessage(msg)
                onMenuMessageChange(null)
            },
            modifier = Modifier.testTag("chat.save.${msg.clientMsgId.take(12)}"),
        )
    }
    if (canRevoke) {
        DropdownMenuItem(
            text = { Text("撤回") },
            onClick = {
                onRevoke(msg.serverSeq)
                onMenuMessageChange(null)
            },
        )
    }
    if (onForward != null && msg.serverSeq > 0L) {
        DropdownMenuItem(
            text = { Text("转发") },
            onClick = {
                onForward.invoke(msg)
                onMenuMessageChange(null)
            },
        )
    }
}

internal fun canDiscardFailedMessage(message: Message, isMe: Boolean): Boolean =
    isMe && message.serverSeq == 0L && message.sendStatus == Message.SEND_STATUS_FAILED

internal fun canEditAndResendFailedMessage(
    message: Message,
    isMe: Boolean,
    failureCode: OutgoingFailureCode?,
): Boolean = canDiscardFailedMessage(message, isMe) &&
    message.body.isMarkdownTextBody() &&
    failureCode?.allowsFreshClientMsgIdReplacement == true

/** 连续消息阈值：同一人 5 分钟内的消息视为连续，隐藏头像和昵称 */
private const val CONTINUATION_THRESHOLD_MS = 5 * 60 * 1000L

/** 时间分隔阈值：消息间隔超过 5 分钟显示时间标签 */
private const val TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L
