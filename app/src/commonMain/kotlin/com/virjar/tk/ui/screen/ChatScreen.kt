package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.TextBody
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.viewmodel.ChatViewModel
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 共享聊天面板。包含消息列表和输入栏，不含 Scaffold/TopAppBar。
 *
 * @param chatType 1=私聊 2=群聊（私聊不显示对方昵称行）
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 消息列表 ──
            if (loading && messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
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

                        // 撤回消息走系统提示（居中），不走气泡
                        if (msg.flags and Message.FLAG_REVOKED != 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                                    Text("消息已撤回", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                }
                            }
                        } else {
                            Column {
                                // 时间分隔标签
                                if (showTimeSeparator) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                                            Text(
                                                formatChatTime(msg.timestamp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }

                                MessageBubble(
                                    msg = msg,
                                    isMe = isMe,
                                    isContinuation = isContinuation,
                                    showSenderName = ChatType.fromCode(chatType) == ChatType.GROUP && !isMe,
                                    peerReadSeq = peerReadSeq,
                                    resolveSender = resolveSender,
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

            // ── 输入栏 ──
            Surface(tonalElevation = 2.dp) {
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    // 回复/编辑上下文栏
                    replyingTo?.let { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("编辑消息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editingMessage = null; inputText = "" }) { Text("取消") }
                        }
                    }
                    // 文本/语音输入行
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
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
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        if (isRecording) "松开发送" else "按住说话",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            // 切换回键盘
                            IconButton(onClick = { voiceMode = false }) {
                                Icon(Icons.Filled.Keyboard, contentDescription = "键盘", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f).testTag("chat.input"),
                                placeholder = { if (editingMessage != null) Text("编辑消息...") else Text("输入消息...") },
                                maxLines = 3,
                                shape = RoundedCornerShape(12.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                            onClick = {
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
                            },
                            modifier = Modifier.testTag("chat.send"),
                            enabled = inputText.isNotBlank(),
                        ) { Text(if (editingMessage != null) "保存" else "发送") }
                        }
                    }

                    // ── 功能工具栏（Material Icons，替代 emoji）──
                    if (effectiveAttachClick != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            IconButton(onClick = { voiceMode = !voiceMode }, modifier = Modifier.testTag("chat.voiceMode")) {
                                Icon(
                                    if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.KeyboardVoice,
                                    contentDescription = if (voiceMode) "键盘" else "语音",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { effectivePickImage?.invoke() }, modifier = Modifier.testTag("chat.pickImage")) {
                                Icon(Icons.Filled.Image, contentDescription = "图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { effectivePickFile?.invoke() }, modifier = Modifier.testTag("chat.pickFile")) {
                                Icon(Icons.Filled.AttachFile, contentDescription = "文件", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = effectiveAttachClick) {
                                Icon(Icons.Filled.Add, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
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
 * 单条消息气泡（含头像、昵称、气泡内容）。
 *
 * @param isContinuation 是否是连续消息（同一人短时间多次发送）——隐藏头像和昵称
 * @param showSenderName 是否显示发送者昵称（群聊对方显示，私聊对方不显示）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: Message,
    isMe: Boolean,
    isContinuation: Boolean,
    showSenderName: Boolean,
    peerReadSeq: Long = 0,
    resolveSender: ((uid: String) -> User?)?,
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (isContinuation) 1.dp else 2.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // 对方头像（我的消息无头像；连续消息留空占位保持对齐）
        if (!isMe) {
            if (isContinuation) {
                Spacer(Modifier.width(36.dp))
            } else {
                val user = resolveSender?.invoke(msg.senderUid)
                AvatarPlaceholder(
                    name = user?.name ?: user?.username ?: msg.senderUid,
                    modifier = Modifier.padding(end = 8.dp),
                    size = 36,
                )
            }
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            // 昵称行（群聊对方、非连续消息才显示）
            if (!isMe && showSenderName && !isContinuation) {
                Text(
                    resolveDisplayName(msg.senderUid, resolveSender),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Surface(
                color = if (isMe) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isMe) 0.dp else 1.dp,
                shadowElevation = if (isMe) 0.dp else 1.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 12.dp, topEnd = 12.dp,
                    bottomStart = if (isMe) 12.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 12.dp,
                ),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                ),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 300.dp)) {
                    com.virjar.tk.ui.component.MessageBodyRenderer(msg, isMe, onMediaClick, imageContent, videoContent)
                    // 已读回执：自己的消息显示发送/已读状态
                    if (isMe && msg.serverSeq > 0) {
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isRead = peerReadSeq > 0 && msg.serverSeq <= peerReadSeq
                            Text(
                                text = if (isRead) "✓✓" else "✓",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isRead) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // 长按菜单：挂在气泡同级（Column 内），Compose 自动以气泡为锚点定位
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onMenuDismiss,
                content = menuItems,
            )
        }
    }
}
