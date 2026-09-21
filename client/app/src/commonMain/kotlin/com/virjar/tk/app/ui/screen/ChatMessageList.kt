package com.virjar.tk.app.ui.screen

import com.virjar.tk.shared.platform.platformCurrentTimeMillis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import kotlinx.coroutines.delay
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
import com.virjar.tk.app.ui.component.messageExportableAttachment

@Composable
internal fun ChatMessageList(
    loading: Boolean,
    messages: List<Message>,
    state: LazyListState,
    hasMore: Boolean,
    loadingOlder: Boolean,
    highlightedServerSeq: Long? = null,
    /** 「跳至未读」锚点（内测 T063）：打开会话时水位之后最早一条的 serverSeq。 */
    unreadAnchorServerSeq: Long? = null,
    /** 「有人@我」锚点（内测 T065）：窗口内最新一条提到我的他人消息 serverSeq。 */
    mentionAnchorServerSeq: Long? = null,
    outgoingFailureCodes: Map<String, OutgoingFailureCode> = emptyMap(),
    reactions: Map<Long, List<MessageReactionGroup>> = emptyMap(),
    onToggleReaction: (serverSeq: Long, emoji: String) -> Unit = { _, _ -> },
    onPickReaction: (serverSeq: Long, emoji: String) -> Unit = { _, _ -> },
    /** 非 null 且消息已确认时，长按/右键菜单提供"收藏"（CLIENT-08）。 */
    onSaveMessage: ((Message) -> Unit)? = null,
    /** 非 null 时媒体消息菜单提供"保存到设备"（导出附件，T007）。 */
    onSaveToDevice: ((Message) -> Unit)? = null,
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
    /** 头像点击打开发送者用户详情；复用平台的资料入口（T004）。 */
    onAvatarClick: ((uid: String) -> Unit)? = null,
    /** 长按/右键头像把该成员以 mention 插入输入框（T026）；非 null 时仅对他人消息生效。 */
    onAvatarMention: ((uid: String) -> Unit)? = null,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.LaunchedEffect(messages) { onWindowReactionsConverge() }

    if (loading && messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        // 内测 T027：离开最新消息且有新消息时提示，点击回到底部。
        val newestSeq = messages.firstOrNull()?.serverSeq ?: 0L
        var seenNewestSeq by remember { mutableStateOf<Long?>(null) }
        val atLatest by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 } }
        LaunchedEffect(newestSeq, atLatest) {
            if (atLatest || seenNewestSeq == null) seenNewestSeq = newestSeq
        }
        val seen = seenNewestSeq
        val showNewPill = !loading && seen != null && newestSeq > seen && !atLatest
        // 内测 T063/T065：未读/被@ 悬浮跳转。目标行在视口上方（被淹没）时显示胶囊；
        // 点击定位并短暂高亮，目标自然可见或已点击即消费，本次打开不再出现。
        var consumedUnreadJump by remember { mutableStateOf(false) }
        var consumedMentionJump by remember { mutableStateOf(false) }
        var jumpHighlightSeq by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(jumpHighlightSeq) {
            if (jumpHighlightSeq != null) {
                delay(2_500)
                jumpHighlightSeq = null
            }
        }
        // 视口最旧可见 index：布局信息只能在协程里读取（produceState），在组合期读
        // layoutInfo 会与 LazyColumn 的测量/锚定互相失效，冷开大未读时把消息整段
        // 挤出可视区（内测 T063 面板空白的直接原因）。
        val viewportOldestIndex by androidx.compose.runtime.produceState(-1, state) {
            androidx.compose.runtime.snapshotFlow {
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }.collect { value = it }
        }
        val unreadJumpIndex = unreadAnchorServerSeq
            ?.let { seq -> messages.indexOfFirst { it.serverSeq == seq } }
            ?.takeIf { it >= 0 }
        val mentionJumpIndex = mentionAnchorServerSeq
            ?.let { seq -> messages.indexOfFirst { it.serverSeq == seq } }
            ?.takeIf { it >= 0 }
        // 目标自然进入视口即视为已展示（「至少一屏未读」由此自然成立：不满一屏时
        // 打开即命中消费，胶囊永不出现）。
        LaunchedEffect(unreadJumpIndex, viewportOldestIndex) {
            if (unreadJumpIndex != null && unreadJumpIndex <= viewportOldestIndex) consumedUnreadJump = true
        }
        LaunchedEffect(mentionJumpIndex, viewportOldestIndex) {
            if (mentionJumpIndex != null && mentionJumpIndex <= viewportOldestIndex) consumedMentionJump = true
        }
        val showUnreadJumpPill = !consumedUnreadJump && unreadJumpIndex != null &&
            unreadJumpIndex > viewportOldestIndex && !loading
        val showMentionJumpPill = !consumedMentionJump && mentionJumpIndex != null &&
            mentionJumpIndex > viewportOldestIndex && !loading
        val listScope = rememberCoroutineScope()
        Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = Tk.spacing.md),
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
                // 跳转高亮优先于搜索/定位高亮（T065：点击胶囊后短暂标出目标行）。
                val effectiveHighlightSeq = jumpHighlightSeq ?: highlightedServerSeq
                val focusModifier = if (msg.serverSeq == effectiveHighlightSeq) {
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

                // 连续消息判断（reverseLayout: index+1 是时间更早的消息）。
                // 撤回行替换了原气泡且不带头像槽，前一条被撤回时必须重新展示头像，
                // 否则该发送者的头像组随撤回整段消失。
                val prevMsg = messages.getOrNull(index + 1)
                val isContinuation = prevMsg != null
                    && prevMsg.senderUid == msg.senderUid
                    && (msg.timestamp - prevMsg.timestamp) < CONTINUATION_THRESHOLD_MS
                    && (msg.flags and Message.FLAG_REVOKED) == 0
                    && (prevMsg.flags and Message.FLAG_REVOKED) == 0

                // 时间分隔判断（reverseLayout: index-1 是时间更晚的消息）
                val nextMsg = messages.getOrNull(index - 1)
                val showTimeSeparator = nextMsg == null
                    || (nextMsg.timestamp - msg.timestamp) > TIME_SEPARATOR_THRESHOLD_MS

                // 私聊我方消息逐条渲染送达/已读状态（单勾双钩），不再只挂最后一条
                val showReadIndicator = isPersonal && isMe

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
                            showReadIndicator = showReadIndicator,
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
                            onAvatarClick = onAvatarClick,
                            onAvatarMention = onAvatarMention,
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
                                onSaveToDevice = onSaveToDevice,
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
        // 悬浮胶囊统一渲染（互不遮挡时纵向堆叠）：跳至未读 / 有人@我 / 有新消息。
        // 必须作为消息列表 Box 的 matchParentSize 覆盖层；绝不能作为聊天 Column 的
        // 直接子项——fillMaxSize 会在 weight(1f) 测量前吃光整列高度，把消息列表压成
        // 零高（T063「面板空白」根因，即用户原文「不应该影响聊天页面本身布局」）。
        val jumpPills = buildList {
            if (showUnreadJumpPill && unreadJumpIndex != null && unreadAnchorServerSeq != null) {
                add(
                    Triple<String, String, () -> Unit>("跳至未读", "chat.jumpUnreadPill") {
                        consumedUnreadJump = true
                        jumpHighlightSeq = unreadAnchorServerSeq
                        listScope.launch { state.scrollToItem(unreadJumpIndex) }
                    },
                )
            }
            if (showMentionJumpPill && mentionJumpIndex != null && mentionAnchorServerSeq != null) {
                add(
                    Triple<String, String, () -> Unit>("有人@我", "chat.jumpMentionPill") {
                        consumedMentionJump = true
                        jumpHighlightSeq = mentionAnchorServerSeq
                        listScope.launch { state.scrollToItem(mentionJumpIndex) }
                    },
                )
            }
            if (showNewPill) {
                add(
                    Triple<String, String, () -> Unit>("有新消息", "chat.newMessagesPill") {
                        listScope.launch { state.animateScrollToItem(0) }
                    },
                )
            }
        }
        if (jumpPills.isNotEmpty()) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Tk.spacing.xs),
                    modifier = Modifier.padding(bottom = Tk.spacing.sm),
                ) {
                    jumpPills.forEach { (label, tag, onClick) ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable(onClick = onClick)
                                .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs)
                                .testTag(tag),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
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
    onSaveToDevice: ((Message) -> Unit)?,
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
        (isSavedChat || platformCurrentTimeMillis() - msg.timestamp < 2 * 60 * 1000)
    // "收藏"进入保存的消息；与"保存到设备"（导出文件）是两个动作，文案必须区分（T007）。
    if (onSaveMessage != null && msg.serverSeq > 0L) {
        DropdownMenuItem(
            text = { Text("收藏") },
            onClick = {
                onSaveMessage(msg)
                onMenuMessageChange(null)
            },
            modifier = Modifier.testTag("chat.save.${msg.clientMsgId.take(12)}"),
        )
    }
    if (onSaveToDevice != null && messageExportableAttachment(msg) != null) {
        DropdownMenuItem(
            text = { Text("保存到设备") },
            onClick = {
                onSaveToDevice(msg)
                onMenuMessageChange(null)
            },
            modifier = Modifier.testTag("chat.export.${msg.clientMsgId.take(12)}"),
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
