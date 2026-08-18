package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.SentimentSatisfied
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.body.isMarkdownTextBody
import com.virjar.tk.body.markdownContentOrNull
import com.virjar.tk.body.plainTextContentOrNull
import com.virjar.tk.ui.component.input.AttachmentPanel
import com.virjar.tk.ui.component.input.AutoCompleteItem
import com.virjar.tk.ui.component.input.AutoCompleteOverlay
import com.virjar.tk.ui.component.input.MentionVisualTransformation
import com.virjar.tk.ui.component.input.SlashCommands
import com.virjar.tk.ui.component.input.detectMentionQuery
import com.virjar.tk.ui.component.input.detectSlashQuery
import com.virjar.tk.ui.component.input.expandSlashCommand
import com.virjar.tk.ui.component.input.EmojiPanel
import com.virjar.tk.ui.component.input.FormatKey
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.isEdgeToEdgeMedia
import com.virjar.tk.ui.platform.contextLongPress
import com.virjar.tk.ui.platform.secondaryClick
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
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
    /** @ 补全候选（群成员/私聊对方）；null=禁用 @ 补全 */
    mentionCandidates: List<com.virjar.tk.model.User>? = null,
    /** 文本气泡可鼠标拖选复制（桌面 true；Android 走长按菜单「复制」，避免选择拦截长按菜单） */
    selectableText: Boolean = false,
) {
    // 统一入口：media 优先，回退到独立 lambda
    val effectiveAttachClick = media?.onAttachClick ?: onAttachClick
    val effectivePickImage = media?.onPickImage ?: onPickImage
    val effectivePickFile = media?.onPickFile ?: onPickFile
    val effectiveVoiceRecord = media?.onVoiceRecord ?: onVoiceRecord
    val effectiveMediaClick = media?.onMediaClick ?: onMediaClick
    val effectiveImageContent = media?.imageContent ?: imageContent
    val effectiveVideoContent = media?.videoContent ?: videoContent
    val effectiveMentionClick = media?.onMentionClick
    val effectiveUrlClick = media?.onUrlClick
    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var menuMessage by remember { mutableStateOf<Message?>(null) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    // WYSIWYG 富文本编辑状态（fork 源码引入，见 richeditor/FORK.md）
    val richState = rememberRichTextState()
    var showEmoji by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }

    // 会话切换：恢复草稿（markdown 双向：setMarkdown/toMarkdown）
    LaunchedEffect(chatId, initialDraft) {
        richState.setMarkdown(initialDraft.orEmpty())
    }

    // @ / / 补全查询（从光标上下文推导；emoji/attach 面板打开时抑制）
    val inputView = TextFieldValue(richState.annotatedString.text, richState.selection)
    val mentionQuery = if (!showEmoji && !showAttach) detectMentionQuery(inputView) else null
    val slashQuery = if (!showEmoji && !showAttach && mentionQuery == null) detectSlashQuery(inputView) else null

    /** 选中 mention 候选：替换 @query 为完整链接语法（编辑器内以 markdown 链接样式呈现） */
    fun pickMention(user: com.virjar.tk.model.User) {
        val q = mentionQuery ?: return
        val syntax = "@[${user.name.ifBlank { user.username ?: user.uid }}](mention://${user.uid}) "
        richState.replaceRange(q.atIndex, inputView.selection.min, syntax)
        inputFocus.requestFocus()
    }

    /** 选中 / 指令候选：把行首不完整 token（如 /s）回填为完整命令 + 空格；发送时再展开 */
    fun pickSlash(cmd: String) {
        val text = inputView.text
        val pos = inputView.selection.min
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (pos == 0) 0 else it + 1 }
        val tokenEnd = text.indexOf(' ', lineStart).let { if (it < 0) text.length else it }
        richState.replaceRange(lineStart, tokenEnd, "$cmd ")
        inputFocus.requestFocus()
    }
    var voiceMode by rememberSaveable { mutableStateOf(false) }

    val isPersonal = ChatType.fromCode(chatType) == ChatType.PERSONAL

    // Save draft on dispose
    DisposableEffect(chatId) {
        onDispose { onDraftChange?.invoke(richState.toMarkdown()) }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // 当前会话收到的小文件即使尚未滚动到可见区域，也要静默下载。
    // 大文件只初始化状态，等待用户点击气泡。
    val fileDownloads = media?.fileDownloads
    LaunchedEffect(fileDownloads, messages) {
        if (fileDownloads != null) {
            messages.asSequence()
                .filter { it.sendStatus != Message.SEND_STATUS_UPLOADING }
                .mapNotNull { it.body as? FileBody }
                .filter { it.attachment.path.isNotBlank() }
                .forEach { body ->
                    fileDownloads.ensure(body.attachment)
                    if (body.attachment.size <= com.virjar.tk.ui.component.FileDownloadController.AUTO_DOWNLOAD_LIMIT &&
                        fileDownloads.states[body.attachment.path] is com.virjar.tk.ui.component.FileDownloadState.Idle
                    ) {
                        fileDownloads.download(body.attachment)
                    }
                }
        }
    }

    // 编辑时预填输入
    LaunchedEffect(editingMessage) {
        editingMessage?.let { msg ->
            richState.setMarkdown(msg.body.markdownContentOrNull().orEmpty())
        }
    }

    // ── 发送动作（按钮与 Cmd/Ctrl+Enter 共用）──
    val sendAction: () -> Unit = {
        // WYSIWYG 编辑器导出 markdown（粗体等样式已编码为 **xx** 语法）
        // Markdown 是权威源。不能 trim：开头缩进、空行和结尾换行都可能有语义。
        val rawText = richState.toMarkdown()
        // / 指令展开（/shrug 等）；未注册指令原样透传（服务端/bot 二期解析）
        val inputText = if (rawText.startsWith("/")) expandSlashCommand(rawText) ?: rawText else rawText
        if (inputText.isNotBlank()) {
            if (editingMessage != null) {
                val edited = editingMessage!!.copy(
                    messageType = MessageType.RICH_TEXT.code,
                    body = buildRichTextBody(inputText),
                )
                viewModel.editMessage(edited)
                editingMessage = null
                richState.clear()
                onDraftChange?.invoke("")
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
                    // 普通文本是 Markdown 的自然子集；所有新文本统一走 RICH_TEXT，
                    // 彻底消除发送/编辑/SDK 在 TEXT 与 RICH_TEXT 之间的行为分叉。
                    Message(
                        chatId = chatId,
                        clientMsgId = UUID.randomUUID().toString(),
                        senderUid = myUid,
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = System.currentTimeMillis(),
                        body = buildRichTextBody(inputText),
                    )
                }
                viewModel.sendMessage(message)
                richState.clear()
                onDraftChange?.invoke("") // 发送即清草稿（泄漏：发送后列表仍显示草稿并回填）
                replyingTo = null
            }
        }
    }

    val boldStyle = remember { androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
    val italicStyle = remember { androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
    val strikeStyle = remember { androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) }
    val toggleBold: () -> Unit = {
        richState.toggleSpanStyle(boldStyle)
        inputFocus.requestFocus()
        Unit
    }
    val toggleItalic: () -> Unit = {
        richState.toggleSpanStyle(italicStyle)
        inputFocus.requestFocus()
        Unit
    }
    val toggleStrike: () -> Unit = {
        richState.toggleSpanStyle(strikeStyle)
        inputFocus.requestFocus()
        Unit
    }
    val toggleCode: () -> Unit = {
        // Code 是 RichSpanStyle，不能用 fontFamily=Monospace 冒充；后者不会序列化为反引号。
        richState.toggleCodeSpan()
        inputFocus.requestFocus()
        Unit
    }

    // 文件下载控制器注入（FileCard 消费；null = 回退旧 onMediaClick 路径）
    androidx.compose.runtime.CompositionLocalProvider(
        com.virjar.tk.ui.component.LocalFileDownloads provides fileDownloads,
    ) {
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
                                    onMentionClick = effectiveMentionClick,
                                    onUrlClick = effectiveUrlClick,
                                    selectableText = selectableText,
                                    menuEpoch = if (menuMessage?.clientMsgId == msg.clientMsgId) msg.hashCode() else 0,
                                    onLongClick = { menuMessage = msg },
                                    onMediaClick = effectiveMediaClick,
                                    imageContent = effectiveImageContent,
                                    videoContent = effectiveVideoContent,
                                    modifier = Modifier,
                                    menuExpanded = menuMessage?.clientMsgId == msg.clientMsgId,
                                    onMenuDismiss = { menuMessage = null },
                                    menuItems = {
                                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                        DropdownMenuItem(
                                            text = { Text("复制") },
                                            onClick = {
                                                clipboard.setText(
                                                    androidx.compose.ui.text.AnnotatedString(
                                                        msg.body.plainTextContentOrNull()
                                                            ?: com.virjar.tk.util.MessagePreview.preview(msg, flagsAware = false)
                                                    )
                                                )
                                                menuMessage = null
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("回复") },
                                            onClick = { replyingTo = msg; menuMessage = null },
                                        )
                                        if (isMe && msg.body.isMarkdownTextBody()) {
                                            DropdownMenuItem(
                                                text = { Text("编辑") },
                                                onClick = {
                                                    editingMessage = msg
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

                // @ 补全层（内嵌展开于输入行上方）：按名字/uid 过滤候选，排除自己
                mentionQuery?.let { q ->
                    val candidates = (mentionCandidates ?: emptyList())
                        .filter { it.uid != myUid }
                        .filter { u ->
                            val name = u.name.ifBlank { u.username ?: "" }
                            q.text.isEmpty() || name.contains(q.text, ignoreCase = true) || u.uid.contains(q.text)
                        }
                    if (candidates.isNotEmpty()) {
                        AutoCompleteOverlay(
                            title = "提及成员",
                            items = candidates.take(5).map { u ->
                                AutoCompleteItem(
                                    label = u.name.ifBlank { u.username ?: u.uid },
                                    hint = "@" + (u.username ?: u.uid),
                                    payload = u.uid,
                                )
                            },
                            onPick = { item -> candidates.find { it.uid == item.payload }?.let { pickMention(it) } },
                        )
                    }
                }

                // / 指令补全层
                slashQuery?.let { q ->
                    val matched = SlashCommands.filter { it.command.startsWith(q.text) }
                    if (matched.isNotEmpty() && q.text.length <= "/shrug".length) {
                        AutoCompleteOverlay(
                            title = "指令",
                            items = matched.map { AutoCompleteItem(it.command, it.desc, it.command) },
                            onPick = { item -> pickSlash(item.payload) },
                        )
                    }
                }

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
                        TextButton(onClick = { editingMessage = null; richState.clear() }) { Text("取消") }
                    }
                }

                // 工具行（输入框下方，飞书范式）：表情/格式键/语音 居左；＋附件 居右。
                // 替代旧的"图标平铺+AlertDialog 文字菜单"（曾是最丑的一层）。
                val effectivePickVideo = media?.onPickVideo
                val hasAttachment = effectivePickImage != null || effectivePickFile != null || effectivePickVideo != null
                run {
                    val insertEmoji: (String) -> Unit = { emoji ->
                        richState.insertAtCaret(emoji)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 表情面板（锚定按钮上方弹出）
                        Box {
                            IconButton(
                                onClick = { showEmoji = !showEmoji },
                                modifier = Modifier.testTag("chat.emoji"),
                            ) {
                                Icon(
                                    Icons.Filled.SentimentSatisfied,
                                    contentDescription = "表情",
                                    tint = if (showEmoji) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
                                )
                            }
                            if (showEmoji) {
                                EmojiPanel(
                                    onPick = {
                                        insertEmoji(it)
                                        inputFocus.requestFocus()
                                    },
                                    onDismiss = { showEmoji = false },
                                )
                            }
                        }

                        // WYSIWYG 格式键（B/I/S/代码）：直改样式
                        FormatKey("B", (richState.currentSpanStyle.fontWeight?.weight ?: 400) > 400, toggleBold, "chat.fmt.bold")
                        FormatKey("I", richState.currentSpanStyle.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic, toggleItalic, "chat.fmt.italic")
                        FormatKey("S", richState.currentSpanStyle.textDecoration?.contains(androidx.compose.ui.text.style.TextDecoration.LineThrough) == true, toggleStrike, "chat.fmt.strike")
                        FormatKey("</>", richState.isCodeSpan, toggleCode, "chat.fmt.code")

                        // 语音/键盘切换
                        if (effectiveVoiceRecord != null) {
                            IconButton(onClick = { voiceMode = !voiceMode }, modifier = Modifier.testTag("chat.voiceMode")) {
                                Icon(
                                    if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.KeyboardVoice,
                                    contentDescription = if (voiceMode) "键盘" else "语音",
                                    tint = Tk.colors.secondaryText,
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // ＋附件宫格弹层（图片/视频/文件）
                        if (hasAttachment) {
                            Box {
                                IconButton(
                                    onClick = { showAttach = true },
                                    modifier = Modifier.testTag("chat.attach"),
                                ) {
                                    Icon(Icons.Filled.AddCircle, contentDescription = "附件", tint = Tk.colors.secondaryText)
                                }
                                if (showAttach) {
                                    AttachmentPanel(
                                        onPickImage = { showAttach = false; effectivePickImage?.invoke() },
                                        onPickVideo = effectivePickVideo?.let { pick -> { showAttach = false; pick() } },
                                        onPickFile = { showAttach = false; effectivePickFile?.invoke() },
                                        onDismiss = { showAttach = false },
                                    )
                                }
                            }
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
                        // WYSIWYG 富文本编辑区：所见即所得（粗体实时渲染），导出走 toMarkdown
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Box {
                                BasicRichTextEditor(
                                    state = richState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 72.dp, max = 200.dp)
                                        .testTag("chat.input")
                                        .focusRequester(inputFocus)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                            val command = event.isMetaPressed || event.isCtrlPressed
                                            when {
                                                // 富文本编辑器默认 Enter 换行；显式快捷键才发送。
                                                event.key == Key.Enter && command -> {
                                                    sendAction()
                                                    true
                                                }
                                                event.key == Key.B && command -> {
                                                    toggleBold()
                                                    true
                                                }
                                                event.key == Key.I && command -> {
                                                    toggleItalic()
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                        .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = androidx.compose.material3.LocalContentColor.current,
                                    ),
                                    minLines = 3,
                                    maxLines = 8,
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                )
                                // 占位符（编辑器空态时）
                                if (richState.annotatedString.text.isEmpty()) {
                                    Text(
                                        if (editingMessage != null) "编辑消息…" else "输入消息…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Tk.colors.metaText,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                                            .testTag("chat.input.hint"),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(Tk.spacing.sm))
                    Button(
                        onClick = sendAction,
                        modifier = Modifier
                            .testTag("chat.send")
                            .height(Tk.dimens.inputMinHeight),
                        enabled = richState.annotatedString.text.isNotBlank(),
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
    } // CompositionLocalProvider(LocalFileDownloads)
}

// ── 消息渲染常量 ──

/** 连续消息阈值：同一人 5 分钟内的消息视为连续，隐藏头像和昵称 */
private const val CONTINUATION_THRESHOLD_MS = 5 * 60 * 1000L

/** 时间分隔阈值：消息间隔超过 5 分钟显示时间标签 */
private const val TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L

/**
 * 格式化聊天时间：当天显示 HH:mm，非当天显示 MM-dd HH:mm。
 */
internal fun formatChatTime(timestamp: Long): String {
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
internal fun resolveDisplayName(uid: String, resolveSender: ((uid: String) -> User?)?): String {
    val user = resolveSender?.invoke(uid)
    return user?.name?.ifBlank { null }
        ?: user?.username?.ifBlank { null }
        ?: uid.take(8)
}
