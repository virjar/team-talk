package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.TextBody
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.isEdgeToEdgeMedia
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.viewmodel.ChatViewModel
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 共享聊天面板。包含消息列表和输入栏，不含 Scaffold/TopAppBar。
 * 视觉规格：doc/04-ui-design/components.md §1.3/§1.4。
 *
 * @param chatType 1=私聊 2=群聊（私聊不显示对方昵称行；已读回执仅私聊）
 * @param resolveSender 通过 uid 解析发送者 User（取昵称/头像），平台注入 LocalCache.getUser
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPanel(
    chatId: String,
    chatName: String,
    viewModel: ChatViewModel,
    myUid: String,
    modifier: Modifier = Modifier,
    chatType: Int = ChatType.PERSONAL.code,
    resolveSender: ((uid: String) -> User?)? = null,
    onForward: ((Message) -> Unit)? = null,
    initialDraft: String? = null,
    onDraftChange: ((String) -> Unit)? = null,
    /**
     * 平台媒体能力配置。提供时替代 onAttachClick/onPickImage/onPickFile/onVoiceRecord/
     * onMediaClick/imageContent/videoContent 这 7 个分散参数。
     * 为 null 时回退到下面的独立 lambda（向后兼容，不推荐）。
     */
    media: com.virjar.tk.ui.bridge.ChatMediaConfig? = null,
    // 以下参数为向后兼容，推荐使用 media 参数
    onAttachClick: (() -> Unit)? = null,
    onPickImage: (() -> Unit)? = null,
    onPickFile: (() -> Unit)? = null,
    onVoiceRecord: ((Boolean) -> Unit)? = null,
    onMediaClick: ((Message) -> Unit)? = null,
    imageContent: (@Composable (String, Modifier) -> Unit)? = null,
    videoContent: (@Composable (String, Modifier) -> Unit)? = null,
    peerReadSeq: Long = 0,
    /** 语音应用内播放控制器（null 时语音点击回退 onMediaClick 链路） */
    voicePlayback: com.virjar.tk.ui.component.VoicePlaybackController? = null,
) {
    // 统一入口：media 优先，回退到独立 lambda
    val effectiveAttachClick = media?.onAttachClick ?: onAttachClick
    val effectivePickImage = media?.onPickImage ?: onPickImage
    val effectivePickFile = media?.onPickFile ?: onPickFile
    val effectiveVoiceRecord = media?.onVoiceRecord ?: onVoiceRecord
    val effectiveMediaClick = media?.onMediaClick ?: onMediaClick
    val effectiveImageContent = media?.imageContent ?: imageContent
    val effectiveVideoContent = media?.videoContent ?: videoContent
    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var menuMessage by remember { mutableStateOf<Message?>(null) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    var inputText by rememberSaveable { mutableStateOf(initialDraft ?: "") }
    var voiceMode by rememberSaveable { mutableStateOf(false) }

    val isPersonal = ChatType.fromCode(chatType) == ChatType.PERSONAL

    // Save draft on dispose
    DisposableEffect(chatId) {
        onDispose { onDraftChange?.invoke(inputText) }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // 编辑时预填输入
    LaunchedEffect(editingMessage) {
        editingMessage?.let { msg ->
            inputText = (msg.body as? TextBody)?.text ?: ""
        }
    }

    // ── 发送动作（按钮与 Enter 共用）──
    val sendAction: () -> Unit = {
        if (inputText.isNotBlank()) {
            if (editingMessage != null) {
                val edited = editingMessage!!.copy(body = TextBody(inputText))
                viewModel.editMessage(edited)
                editingMessage = null
                inputText = ""
            } else {
                val target = replyingTo
                val message = if (target != null) {
                    // 回复消息：ReplyBody = 引用卡片信息 + 回复正文
                    val replyToMsgId = if (target.serverSeq != 0L) target.serverSeq.toString() else target.clientMsgId
                    val replySenderName = resolveSender?.invoke(target.senderUid)?.name ?: target.senderUid
                    val snippet = com.virjar.tk.util.MessagePreview.preview(target).take(50)
                    Message(
                        chatId = chatId,
                        clientMsgId = UUID.randomUUID().toString(),
                        senderUid = myUid,
                        messageType = MessageType.REPLY.code,
                        timestamp = System.currentTimeMillis(),
                        body = ReplyBody(
                            replyToMsgId = replyToMsgId,
                            replyToSenderUid = target.senderUid,
                            replyToSenderName = replySenderName,
                            replySnippet = snippet,
                            content = inputText,
                        ),
                    )
                } else {
                    Message(
                        chatId = chatId,
                        clientMsgId = UUID.randomUUID().toString(),
                        senderUid = myUid,
                        messageType = MessageType.TEXT.code,
                        timestamp = System.currentTimeMillis(),
                        body = TextBody(inputText),
                    )
                }
                viewModel.sendMessage(message)
                inputText = ""
                replyingTo = null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 消息列表 ──
            if (loading && messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Tk.spacing.md),
                    reverseLayout = true,
                ) {
                    items(messages.size) { index ->
                        val msg = messages[index]
                        val isMe = msg.senderUid == myUid

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
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = Tk.spacing.xs),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    "${if (isMe) "你" else resolveDisplayName(msg.senderUid, resolveSender)} 撤回了一条消息",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Tk.colors.metaText,
                                )
                            }
                        } else {
                            Column {
                                // 时间分隔：裸文字（飞书范式，无胶囊底）
                                if (showTimeSeparator) {
                                    Row(
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
                                    peerReadSeq = peerReadSeq,
                                    resolveSender = resolveSender,
                                    voicePlayback = voicePlayback,
                                    onLongClick = { menuMessage = msg },
                                    onMediaClick = effectiveMediaClick,
                                    imageContent = effectiveImageContent,
                                    videoContent = effectiveVideoContent,
                                    modifier = Modifier,
                                    menuExpanded = menuMessage?.clientMsgId == msg.clientMsgId,
                                    onMenuDismiss = { menuMessage = null },
                                    menuItems = {
                                        DropdownMenuItem(
                                            text = { Text("回复") },
                                            onClick = { replyingTo = msg; menuMessage = null },
                                        )
                                        if (isMe && msg.body is TextBody) {
                                            DropdownMenuItem(
                                                text = { Text("编辑") },
                                                onClick = {
                                                    editingMessage = msg
                                                    inputText = (msg.body as TextBody).text
                                                    menuMessage = null
                                                },
                                            )
                                        }
                                        val canRevoke = isMe && (System.currentTimeMillis() - msg.timestamp < 2 * 60 * 1000)
                                        if (canRevoke) {
                                            DropdownMenuItem(
                                                text = { Text("撤回") },
                                                onClick = {
                                                    viewModel.revokeMessage(msg.serverSeq)
                                                    menuMessage = null
                                                },
                                            )
                                        }
                                        if (onForward != null) {
                                            DropdownMenuItem(
                                                text = { Text("转发") },
                                                onClick = {
                                                    onForward.invoke(msg)
                                                    menuMessage = null
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── 输入区（白底 + 顶部分隔线，规格 §1.4）──
            Column {
                HorizontalDivider(color = Tk.colors.divider)

                // 回复/编辑上下文条
                replyingTo?.let { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "回复 ${com.virjar.tk.util.MessagePreview.preview(msg).take(20)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { replyingTo = null }) { Text("取消") }
                    }
                }
                editingMessage?.let {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("编辑消息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingMessage = null; inputText = "" }) { Text("取消") }
                    }
                }

                // 工具行：左对齐（替代原 SpaceEvenly 分散布局）
                if (effectiveAttachClick != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.xs),
                    ) {
                        IconButton(onClick = { voiceMode = !voiceMode }, modifier = Modifier.testTag("chat.voiceMode")) {
                            Icon(
                                if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.KeyboardVoice,
                                contentDescription = if (voiceMode) "键盘" else "语音",
                                tint = Tk.colors.secondaryText,
                            )
                        }
                        IconButton(onClick = { effectivePickImage?.invoke() }, modifier = Modifier.testTag("chat.pickImage")) {
                            Icon(Icons.Filled.Image, contentDescription = "图片", tint = Tk.colors.secondaryText)
                        }
                        IconButton(onClick = { effectivePickFile?.invoke() }, modifier = Modifier.testTag("chat.pickFile")) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "文件", tint = Tk.colors.secondaryText)
                        }
                        IconButton(onClick = effectiveAttachClick) {
                            Icon(Icons.Filled.Add, contentDescription = "更多", tint = Tk.colors.secondaryText)
                        }
                    }
                }

                // 输入行：文本/语音 + 发送按钮
                Row(
                    modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (voiceMode) {
                        // 语音模式：按住说话
                        var isRecording by remember { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pressed = event.changes.any { it.pressed }
                                            if (pressed && !isRecording) {
                                                isRecording = true
                                                effectiveVoiceRecord?.invoke(true)
                                            } else if (!pressed && isRecording) {
                                                isRecording = false
                                                effectiveVoiceRecord?.invoke(false)
                                            }
                                        }
                                    }
                                },
                            shape = MaterialTheme.shapes.small,
                            color = Tk.colors.bubbleIncoming,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    if (isRecording) "松开发送" else "按住说话",
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat.input")
                                .onPreviewKeyEvent { event ->
                                    // Enter 发送 / Shift+Enter 换行（桌面硬件键盘；Android 走 IME Send）
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Enter && !event.isShiftPressed
                                    ) {
                                        sendAction()
                                        true
                                    } else {
                                        false
                                    }
                                },
                            placeholder = {
                                Text(
                                    if (editingMessage != null) "编辑消息…" else "输入消息…",
                                    color = Tk.colors.metaText,
                                )
                            },
                            maxLines = 6,
                            shape = MaterialTheme.shapes.small,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendAction() }),
                        )
                    }
                    Spacer(Modifier.width(Tk.spacing.sm))
                    Button(
                        onClick = sendAction,
                        modifier = Modifier
                            .testTag("chat.send")
                            .height(Tk.dimens.inputMinHeight),
                        enabled = inputText.isNotBlank(),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = Tk.spacing.lg),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(Tk.dimens.iconSize - 2.dp),
                        )
                        Spacer(Modifier.width(Tk.spacing.xs))
                        Text(if (editingMessage != null) "保存" else "发送")
                    }
                }
            }
        }

        // ── 错误 Snackbar ──
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// ── 消息渲染常量 ──

/** 连续消息阈值：同一人 5 分钟内的消息视为连续，隐藏头像和昵称 */
private const val CONTINUATION_THRESHOLD_MS = 5 * 60 * 1000L

/** 时间分隔阈值：消息间隔超过 5 分钟显示时间标签 */
private const val TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L

/**
 * 格式化聊天时间：当天显示 HH:mm，非当天显示 MM-dd HH:mm。
 */
private fun formatChatTime(timestamp: Long): String {
    val now = Date()
    val msg = Date(timestamp)
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val isToday = dayFmt.format(now) == dayFmt.format(msg)
    return if (isToday) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(msg)
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(msg)
    }
}

/**
 * 解析发送者显示名。
 * fallback 链：User.name → User.username → uid.take(8)
 */
private fun resolveDisplayName(uid: String, resolveSender: ((uid: String) -> User?)?): String {
    val user = resolveSender?.invoke(uid)
    return user?.name?.ifBlank { null }
        ?: user?.username?.ifBlank { null }
        ?: uid.take(8)
}

/**
 * 单条消息气泡（含头像、昵称、气泡内容）。规格：doc/04-ui-design/components.md §1.3。
 *
 * 双方都显示头像（自己右侧）；飞书扁平气泡：对方灰底无阴影、自己蓝底白字；
 * 指向角（靠头像一侧）4dp，其余 8dp，替代气泡尾巴。
 *
 * @param isContinuation 是否是连续消息（同一人短时间多次发送）——隐藏头像和昵称
 * @param showSenderName 是否显示发送者昵称行（群聊对方显示，私聊对方不显示）
 * @param showReadIndicator 是否显示已读水位线指示（仅私聊、我方最后一条已送达消息）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: Message,
    isMe: Boolean,
    isContinuation: Boolean,
    showSenderName: Boolean,
    showReadIndicator: Boolean,
    peerReadSeq: Long = 0,
    resolveSender: ((uid: String) -> User?)?,
    voicePlayback: com.virjar.tk.ui.component.VoicePlaybackController? = null,
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
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                ),
            ) {
                if (msg.body.isEdgeToEdgeMedia()) {
                    // 贴边媒体（图片/视频/贴纸）：无气泡内边距，媒体自身即气泡面（微信/飞书范式）
                    Box(modifier = Modifier.widthIn(max = Tk.dimens.bubbleMaxWidth)) {
                        com.virjar.tk.ui.component.MessageBodyRenderer(msg, isMe, onMediaClick, imageContent, videoContent, voicePlayback)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                            .widthIn(max = Tk.dimens.bubbleMaxWidth - (Tk.spacing.md * 2))
                    ) {
                        com.virjar.tk.ui.component.MessageBodyRenderer(msg, isMe, onMediaClick, imageContent, videoContent, voicePlayback)
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
