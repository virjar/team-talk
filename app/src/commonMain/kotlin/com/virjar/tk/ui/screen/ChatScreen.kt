package com.virjar.tk.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.buildMentionMarkdown
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.body.isMarkdownTextBody
import com.virjar.tk.body.markdownContentOrNull
import com.virjar.tk.body.plainTextContentOrNull
import com.virjar.tk.ui.component.input.AttachmentPanel
import com.virjar.tk.ui.component.input.AutoCompleteItem
import com.virjar.tk.ui.component.input.AutoCompleteOverlay
import com.virjar.tk.ui.component.input.SlashCommands
import com.virjar.tk.ui.component.input.detectMentionQuery
import com.virjar.tk.ui.component.input.detectSlashQuery
import com.virjar.tk.ui.component.input.expandSlashCommand
import com.virjar.tk.ui.component.input.EmojiPanel
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.RichMessageText
import com.virjar.tk.ui.component.isEdgeToEdgeMedia
import com.virjar.tk.ui.bridge.ChatMediaConfig
import com.virjar.tk.ui.platform.contextLongPress
import com.virjar.tk.ui.platform.secondaryClick
import com.virjar.tk.ui.component.rich.RichTextFormattingToolbar
import com.virjar.tk.ui.component.rich.RichTextToolbarMode
import com.virjar.tk.ui.component.rich.ChatComposerMode
import com.virjar.tk.ui.component.rich.ChatComposerModeSwitcher
import com.virjar.tk.ui.component.rich.acceptsChatSourceInput
import com.virjar.tk.ui.component.rich.ChatVisualMarkdownBaseline
import com.virjar.tk.ui.component.rich.canUseChatVisualEditor
import com.virjar.tk.ui.component.rich.replaceComposerRange
import com.virjar.tk.ui.component.rich.wrapComposerSelection
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.viewmodel.ChatViewModel
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * 共享聊天面板。包含消息列表和输入栏，不含 Scaffold/TopAppBar。
 * 视觉与内容规格：doc/05-clients/design-system.md、doc/05-clients/rich-content.md。
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
    /** Session-owned continuation for reply/edit and composer UI state; not a draft backend. */
    composerContextStore: ChatComposerContextStore,
    /** 平台媒体能力的唯一入口；平台壳负责构造，聊天 UI 不再维护平行回调。 */
    media: ChatMediaConfig,
    peerReadSeq: Long = 0,
    /** 语音应用内播放控制器（null 时语音点击回退 onMediaClick 链路） */
    voicePlayback: com.virjar.tk.ui.component.VoicePlaybackController? = null,
    /** @ 补全候选（群成员/私聊对方）；null=禁用 @ 补全 */
    mentionCandidates: List<com.virjar.tk.model.User>? = null,
    /** 文本气泡可鼠标拖选复制（桌面 true；Android 走长按菜单「复制」，避免选择拦截长按菜单） */
    selectableText: Boolean = false,
    /** Only an actually visible/foreground chat may consume unread messages. */
    readReceiptsEnabled: Boolean,
) {
    val effectivePickImage = media.onPickImage
    val effectivePickFile = media.onPickFile
    val effectiveVoiceRecord = media.onVoiceRecord
    val effectiveVoiceModeEntered = media.onVoiceModeEntered
    val effectiveVoiceRecordCancel = media.onVoiceRecordCancel
    val effectiveMediaClick = media.onMediaClick
    val effectiveImageContent = media.imageContent
    val effectiveVideoContent = media.videoContent
    val effectiveMentionClick = media.onMentionClick
    val effectiveUrlClick = media.onUrlClick
    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingOlder by viewModel.loadingOlder.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val messageListState = rememberLazyListState()

    // A session keeps the current ChatViewModel alive while Android navigates back to the
    // conversation list. Re-entering that same chat therefore does not run the ViewModel init
    // block again. Tie the read receipt to the actually visible panel instead: opening the panel
    // clears an existing badge, and a newer message received while it remains visible advances
    // the read watermark as well. Only confirmed server messages participate.
    val latestVisibleServerSeq = messages.maxOfOrNull(Message::serverSeq)?.coerceAtLeast(0L) ?: 0L
    val visibleReadTarget = visibleChatReadTarget(readReceiptsEnabled, latestVisibleServerSeq)
    LaunchedEffect(viewModel, chatId, visibleReadTarget) {
        visibleReadTarget?.let(viewModel::markRead)
    }

    // reverseLayout puts index 0 at the bottom; reaching the highest message index means the user
    // has scrolled to the visual top. Keep a manual button below as a deterministic fallback.
    LaunchedEffect(messageListState, messages.size, hasMore, loading) {
        snapshotFlow { messageListState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1 }
            .map { lastVisible ->
                !loading && messages.isNotEmpty() && hasMore &&
                    lastVisible >= (messages.lastIndex - 1).coerceAtLeast(0)
            }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadOlder() }
    }
    // Same-session A -> B -> A navigation does not retain rememberSaveable entries for A by
    // itself. Seed each new composition from the session store; Android SavedState restoration
    // still wins because every field below keeps its existing rememberSaveable/Saver contract.
    val restoredComposerContext = remember(chatId, composerContextStore) {
        composerContextStore.restore(chatId)
    }
    val initialComposerMarkdown = restoredComposerContext?.markdown ?: initialDraft.orEmpty()
    val initialComposerSelection = restoredComposerContext?.let { context ->
        TextRange(
            context.selectionStart.coerceIn(0, initialComposerMarkdown.length),
            context.selectionEnd.coerceIn(0, initialComposerMarkdown.length),
        )
    } ?: TextRange(initialComposerMarkdown.length)

    var menuMessage by remember(chatId) { mutableStateOf<Message?>(null) }
    var savedReplyTarget by rememberSaveable(chatId, stateSaver = SavedChatReplyTargetSaver) {
        mutableStateOf(restoredComposerContext?.replyTarget ?: SavedChatReplyTarget())
    }
    // Message 属于 messages 流，不进入 Saver；流刷新或 Activity 重建后按稳定 clientMsgId 重绑定。
    val replyingTo = savedReplyTarget.bind(messages)
    val editingSessionSaver = remember(chatId, composerContextStore) {
        savedChatEditingSessionSaver(chatId, composerContextStore)
    }
    var editingSession by rememberSaveable(chatId, stateSaver = editingSessionSaver) {
        mutableStateOf(restoredComposerContext?.editingSession ?: SavedChatEditingSession())
    }
    val editingSessionActive = editingSession.editingClientMsgId.isNotEmpty()
    val editingMessage = editingSession.editingClientMsgId.takeIf { it.isNotEmpty() }?.let { editingId ->
        messages.firstOrNull { it.clientMsgId == editingId }
    }
    var editingSaving by remember(chatId) { mutableStateOf(false) }
    // WYSIWYG 富文本编辑状态（fork 源码引入，见 richeditor/FORK.md）
    // 按 chatId 隔离，确保切换会话时旧 DisposableEffect 仍能读取旧会话的最后一帧草稿。
    val richState = key(chatId) { rememberRichTextState() }
    val sourceInputSaver = remember(chatId, composerContextStore) {
        chatSourceInputSaver(chatId, composerContextStore)
    }
    val sourceInputState = rememberSaveable(chatId, stateSaver = sourceInputSaver) {
        mutableStateOf(TextFieldValue(initialComposerMarkdown, initialComposerSelection))
    }
    var sourceInput by sourceInputState
    val composerModeState = rememberSaveable(chatId) {
        mutableStateOf(restoredComposerContext?.mode ?: ChatComposerMode.VISUAL)
    }
    var composerMode by composerModeState
    // Baseline is bookkeeping, not user data. Saving it would duplicate a 100k source draft in
    // the Activity Bundle; after recreation it can safely be rebuilt from the durable draft.
    val draftBaselineState = remember(chatId) { mutableStateOf(initialDraft.orEmpty()) }
    val visualBaselineState = rememberSaveable(chatId, stateSaver = ChatVisualMarkdownBaselineSaver) {
        mutableStateOf(
            restoredComposerContext?.visualBaseline
                ?: ChatVisualMarkdownBaseline(originalMarkdown = "", normalizedMarkdown = ""),
        )
    }
    var visualBaseline by visualBaselineState
    val composerHydratedState = rememberSaveable(chatId) { mutableStateOf(false) }
    var composerHydrated by composerHydratedState
    val composerReadyState = remember(chatId) { mutableStateOf(false) }
    var composerReady by composerReadyState
    var showEmoji by remember(chatId) { mutableStateOf(false) }
    var showAttach by remember(chatId) { mutableStateOf(false) }
    var voiceMode by rememberSaveable(chatId) { mutableStateOf(false) }
    var restoreComposerFocus by remember(chatId) { mutableStateOf(false) }
    val inputFocus = remember(chatId) { FocusRequester() }
    val sourceFocus = remember(chatId) { FocusRequester() }

    fun enterVisualMarkdown(
        markdown: String,
        preservedBaseline: ChatVisualMarkdownBaseline? = null,
        selection: TextRange? = null,
    ) {
        richState.setMarkdown(markdown)
        val normalized = richState.toMarkdown()
        visualBaseline = preservedBaseline
            ?.takeIf { it.snapshot(normalized) == markdown }
            ?: ChatVisualMarkdownBaseline(
                originalMarkdown = markdown,
                normalizedMarkdown = normalized,
            )
        selection?.let { restored ->
            val length = richState.annotatedString.length
            richState.selection = TextRange(
                restored.start.coerceIn(0, length),
                restored.end.coerceIn(0, length),
            )
        }
        composerMode = ChatComposerMode.VISUAL
    }

    fun loadComposerMarkdown(markdown: String) {
        sourceInput = TextFieldValue(markdown, TextRange(markdown.length))
        if (canUseChatVisualEditor(markdown)) {
            enterVisualMarkdown(markdown)
        } else {
            // 高级 Markdown 由用户主动在源码模式编辑；不送进 WYSIWYG 做有损规范化。
            composerMode = ChatComposerMode.MARKDOWN
        }
    }

    fun composerMarkdownSnapshot(): String = when (composerMode) {
        ChatComposerMode.VISUAL -> visualBaseline.snapshot(richState.toMarkdown())
        ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> sourceInput.text
    }

    fun captureComposerContext(): ChatComposerContext {
        val selection = when (composerMode) {
            ChatComposerMode.VISUAL -> richState.selection
            ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> sourceInput.selection
        }
        return ChatComposerContext(
            replyTarget = savedReplyTarget,
            editingSession = editingSession,
            markdown = composerMarkdownSnapshot(),
            mode = composerMode,
            selectionStart = selection.start,
            selectionEnd = selection.end,
            visualBaseline = visualBaseline,
        )
    }

    fun persistComposerContext() {
        composerContextStore.save(chatId, captureComposerContext())
    }

    fun captureEditingSession(editingClientMsgId: String): SavedChatEditingSession {
        val selection = when (composerMode) {
            ChatComposerMode.VISUAL -> richState.selection
            ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> sourceInput.selection
        }
        return SavedChatEditingSession(
            editingClientMsgId = editingClientMsgId,
            targetLoaded = false,
            suspendedMarkdown = composerMarkdownSnapshot(),
            suspendedMode = composerMode,
            selectionStart = selection.start,
            selectionEnd = selection.end,
            replyingClientMsgId = savedReplyTarget.clientMsgId,
        )
    }

    fun restoreSuspendedComposer() {
        val suspended = editingSession.takeIf { it.editingClientMsgId.isNotEmpty() }
        editingSession = SavedChatEditingSession()
        if (suspended == null) {
            resetChatComposerState(richState)
            sourceInput = TextFieldValue("")
            visualBaseline = ChatVisualMarkdownBaseline("", "")
            composerMode = ChatComposerMode.VISUAL
            savedReplyTarget = SavedChatReplyTarget()
            persistComposerContext()
            return
        }
        val sourceSelection = TextRange(
            suspended.selectionStart.coerceIn(0, suspended.suspendedMarkdown.length),
            suspended.selectionEnd.coerceIn(0, suspended.suspendedMarkdown.length),
        )
        sourceInput = TextFieldValue(suspended.suspendedMarkdown, sourceSelection)
        when (suspended.suspendedMode) {
            ChatComposerMode.VISUAL -> {
                enterVisualMarkdown(suspended.suspendedMarkdown)
                val visualLength = richState.annotatedString.length
                richState.selection = TextRange(
                    suspended.selectionStart.coerceIn(0, visualLength),
                    suspended.selectionEnd.coerceIn(0, visualLength),
                )
            }
            ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> composerMode = suspended.suspendedMode
        }
        savedReplyTarget = SavedChatReplyTarget(suspended.replyingClientMsgId)
        restoreComposerFocus = suspended.suspendedMode != ChatComposerMode.PREVIEW
        persistComposerContext()
    }

    fun beginEditing(message: Message) {
        if (editingSaving) return
        if (!editingSessionActive) editingSession = captureEditingSession(message.clientMsgId)
        savedReplyTarget = SavedChatReplyTarget()
    }

    fun cancelEditing() {
        if (editingSaving) return
        restoreSuspendedComposer()
    }

    // 首次进入会话使用 initialDraft；Android 重建时 TextFieldValue/Mode 由 Saver 恢复。
    // 只在每个 ChatPanel 实例挂载时恢复 WYSIWYG 内部状态，不让后到的草稿回显覆盖用户输入。
    LaunchedEffect(chatId) {
        if (!composerHydratedState.value) {
            val restoredMarkdown = sourceInputState.value.text
            val restoredMode = composerModeState.value
            when {
                restoredMode == ChatComposerMode.VISUAL && canUseChatVisualEditor(restoredMarkdown) -> {
                    enterVisualMarkdown(
                        markdown = restoredMarkdown,
                        preservedBaseline = restoredComposerContext?.visualBaseline,
                        selection = sourceInputState.value.selection,
                    )
                }
                restoredMode == ChatComposerMode.VISUAL -> composerMode = ChatComposerMode.MARKDOWN
                else -> composerMode = restoredMode
            }
            composerHydrated = true
        }
        // Activity 重建时 RichTextState 自身由 Saver 恢复；不要再用可能落后一帧的
        // source 镜像覆盖它。模式与原始 Markdown baseline 也由各自 Saver 恢复。
        composerReady = true
    }

    // 会话列表尚未加载时 initialDraft 可能晚于 ChatPanel 到达。只在编辑器仍为空且
    // 从未产生本地草稿时接受这次 hydrate，避免后到回显覆盖已输入内容。
    LaunchedEffect(initialDraft) {
        val external = initialDraft
        if (composerReady && !external.isNullOrEmpty() &&
            draftBaselineState.value.isEmpty() && composerMarkdownSnapshot().isEmpty()
        ) {
            loadComposerMarkdown(external)
            draftBaselineState.value = external
        }
    }

    // 可视编辑的 saveable 镜像 + 草稿防抖。离开页面时 DisposableEffect 仍会立即 flush。
    // callback holder 必须按 chatId 隔离。同一 composition slot 从 A 切到 B 时，
    // A 的 onDispose 仍应写回 A，不能读到 B 刚更新进去的 callback。
    val latestDraftChangeState = key(chatId) { rememberUpdatedState(onDraftChange) }
    val richTextSignal = richState.annotatedString
    LaunchedEffect(composerReady, composerMode, sourceInput.text, richTextSignal, editingSessionActive) {
        // “编辑已发消息”是临时会话，不得覆盖普通聊天草稿。
        if (!composerReady || editingSessionActive) return@LaunchedEffect
        val draft = composerMarkdownSnapshot()
        if (composerMode == ChatComposerMode.VISUAL && sourceInput.text != draft) {
            sourceInput = TextFieldValue(draft, TextRange(draft.length))
        }
        if (draft == draftBaselineState.value) return@LaunchedEffect
        delay(350)
        latestDraftChangeState.value?.invoke(draft)
        draftBaselineState.value = draft
    }

    // @ / / 补全查询（从光标上下文推导；语音模式或浮层打开时抑制）
    val inputView = when (composerMode) {
        ChatComposerMode.VISUAL -> TextFieldValue(richState.annotatedString.text, richState.selection)
        ChatComposerMode.MARKDOWN -> sourceInput
        ChatComposerMode.PREVIEW -> TextFieldValue("")
    }
    val mentionQuery = if (!voiceMode && !showEmoji && !showAttach) detectMentionQuery(inputView) else null
    val slashQuery = if (!voiceMode && !showEmoji && !showAttach && mentionQuery == null) detectSlashQuery(inputView) else null

    // 切回键盘或切换编辑模式后，等目标编辑器挂载再恢复焦点。
    LaunchedEffect(voiceMode, composerMode, restoreComposerFocus) {
        if (!voiceMode && restoreComposerFocus) {
            withFrameNanos { }
            when (composerMode) {
                ChatComposerMode.VISUAL -> inputFocus.requestFocus()
                ChatComposerMode.MARKDOWN -> sourceFocus.requestFocus()
                ChatComposerMode.PREVIEW -> Unit
            }
            restoreComposerFocus = false
        }
    }

    /** 清空正文、撤销/重做栈和外部草稿；焦点只在编辑器挂载时恢复。 */
    fun resetComposer(requestFocus: Boolean = true) {
        resetChatComposerState(richState)
        sourceInput = TextFieldValue("")
        visualBaseline = ChatVisualMarkdownBaseline("", "")
        composerMode = ChatComposerMode.VISUAL
        draftBaselineState.value = ""
        onDraftChange?.invoke("")
        if (requestFocus && !voiceMode) restoreComposerFocus = true
    }

    /** 选中 mention 候选：编辑器显示 @姓名，导出时仍是服务端权威的 mention Markdown。 */
    fun pickMention(user: com.virjar.tk.model.User) {
        if (voiceMode || composerMode == ChatComposerMode.PREVIEW) return
        val q = mentionQuery ?: return
        val displayName = user.name.ifBlank { user.username.ifBlank { user.uid } }
        if (composerMode == ChatComposerMode.MARKDOWN) {
            val syntax = buildMentionMarkdown(displayName, user.uid) + " "
            sourceInput = sourceInput.replaceComposerRange(q.atIndex, inputView.selection.min, syntax)
            sourceFocus.requestFocus()
        } else {
            val displayText = "@$displayName "
            richState.replaceRange(q.atIndex, inputView.selection.min, displayText)
            richState.addLinkToTextRange(
                url = "mention://${user.uid}",
                textRange = TextRange(q.atIndex + 1, q.atIndex + 1 + displayName.length),
            )
            richState.selection = TextRange(q.atIndex + displayText.length)
            inputFocus.requestFocus()
        }
    }

    /** 选中 / 指令候选：把行首不完整 token（如 /s）回填为完整命令 + 空格；发送时再展开 */
    fun pickSlash(cmd: String) {
        if (voiceMode || composerMode == ChatComposerMode.PREVIEW) return
        val text = inputView.text
        val pos = inputView.selection.min
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (pos == 0) 0 else it + 1 }
        if (composerMode == ChatComposerMode.MARKDOWN) {
            sourceInput = sourceInput.replaceComposerRange(lineStart, pos, "$cmd ")
            sourceFocus.requestFocus()
        } else {
            richState.replaceRange(lineStart, pos, "$cmd ")
            inputFocus.requestFocus()
        }
    }
    val isPersonal = ChatType.fromCode(chatType) == ChatType.PERSONAL

    // Save draft on dispose
    DisposableEffect(chatId, composerContextStore) {
        // sourceInputState/composerModeState 都按 chatId 创建；旧会话 dispose 不会误读新会话状态。
        onDispose {
            // This write is synchronous and precedes any async draft mirroring, so switching
            // A -> B -> A restores reply/edit/source context even when no frame is rendered in B.
            composerContextStore.save(chatId, captureComposerContext())
            // 编辑已发消息时，当前输入区属于临时编辑会话；真正的普通草稿已被
            // 挂起在 suspendedComposer，离开页面也必须把它落盘，不能静默丢弃。
            val draft = if (editingSession.editingClientMsgId.isNotEmpty()) {
                editingSession.suspendedMarkdown
            } else {
                if (!composerReadyState.value) {
                    sourceInputState.value.text
                } else when (composerModeState.value) {
                    ChatComposerMode.VISUAL -> visualBaselineState.value.snapshot(richState.toMarkdown())
                    ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> sourceInputState.value.text
                }
            }
            if (draft != draftBaselineState.value) latestDraftChangeState.value?.invoke(draft)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    // 当前会话收到的小文件即使尚未滚动到可见区域，也要静默下载。
    // 大文件只初始化状态，等待用户点击气泡。
    val fileDownloads = media.fileDownloads
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
    LaunchedEffect(editingSession.editingClientMsgId, editingMessage) {
        if (editingSessionActive && !editingSession.targetLoaded) editingMessage?.let { msg ->
            loadComposerMarkdown(msg.body.markdownContentOrNull().orEmpty())
            editingSession = editingSession.copy(targetLoaded = true)
        }
    }

    // ── 发送动作（按钮与 Cmd/Ctrl+Enter 共用）──
    fun validatedMessageOrReport(message: Message): Message? = try {
        canonicalizeChatMessageForSend(message)
    } catch (error: IllegalArgumentException) {
        viewModel.onError("发送失败: ${error.message ?: "消息内容不合法"}")
        null
    }

    val sendAction: () -> Unit = sendAction@{
        // 语音模式没有挂载编辑器，也不能发送不可见的文字草稿。
        if (voiceMode) return@sendAction
        // 可视编辑与源码编辑最终都只发送同一份权威 Markdown。
        // Markdown 是权威源。不能 trim：开头缩进、空行和结尾换行都可能有语义。
        val rawText = composerMarkdownSnapshot()
        // 只在普通可视输入中展开 /shrug 等快捷指令。源码和编辑已发消息必须字节语义透传。
        val inputText = if (!editingSessionActive && composerMode == ChatComposerMode.VISUAL && rawText.startsWith("/")) {
            expandSlashCommand(rawText) ?: rawText
        } else rawText
        if (inputText.isNotBlank()) {
            if (editingSessionActive) {
                if (editingSaving) return@sendAction
                val editing = editingMessage ?: return@sendAction
                val edited = validatedMessageOrReport(editing.copy(
                    messageType = MessageType.RICH_TEXT.code,
                    body = buildRichTextBody(inputText),
                )) ?: return@sendAction
                editingSaving = true
                viewModel.editMessage(edited) { succeeded ->
                    editingSaving = false
                    if (succeeded && editingSession.editingClientMsgId == editing.clientMsgId) {
                        restoreSuspendedComposer()
                    }
                }
            } else {
                // 回复目标可能正等待 messages 流恢复，禁止把本应是回复的内容静默降级成普通消息。
                if (savedReplyTarget.clientMsgId.isNotEmpty() && replyingTo == null) return@sendAction
                val target = replyingTo
                val message = if (target != null) {
                    // 回复消息：ReplyBody = 引用卡片信息 + 回复正文
                    val replyToMsgId = target.confirmedReplyToMsgIdOrNull() ?: run {
                        viewModel.onError("发送失败: 回复目标尚未被服务器确认")
                        return@sendAction
                    }
                    val replySenderName = resolveDisplayNameOrNull(target.senderUid, resolveSender)
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
                    // 普通文本是 Markdown 的自然子集；所有文本统一走 RICH_TEXT。
                    Message(
                        chatId = chatId,
                        clientMsgId = UUID.randomUUID().toString(),
                        senderUid = myUid,
                        messageType = MessageType.RICH_TEXT.code,
                        timestamp = System.currentTimeMillis(),
                        body = buildRichTextBody(inputText),
                    )
                }
                val validated = validatedMessageOrReport(message) ?: return@sendAction
                viewModel.sendMessage(validated)
                resetComposer()
                savedReplyTarget = SavedChatReplyTarget()
                persistComposerContext()
            }
        }
    }

    val boldStyle = remember { androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
    val italicStyle = remember { androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
    val toggleBold: () -> Unit = toggleBold@{
        if (voiceMode) return@toggleBold
        richState.toggleSpanStyle(boldStyle)
        inputFocus.requestFocus()
        Unit
    }
    val toggleItalic: () -> Unit = toggleItalic@{
        if (voiceMode) return@toggleItalic
        richState.toggleSpanStyle(italicStyle)
        inputFocus.requestFocus()
        Unit
    }

    fun changeComposerMode(target: ChatComposerMode) {
        if (voiceMode || target == composerMode) return
        val markdown = composerMarkdownSnapshot()
        when (target) {
            ChatComposerMode.VISUAL -> {
                if (!canUseChatVisualEditor(markdown)) return
                enterVisualMarkdown(markdown)
                restoreComposerFocus = true
            }
            ChatComposerMode.MARKDOWN -> {
                sourceInput = TextFieldValue(markdown, TextRange(markdown.length))
                composerMode = ChatComposerMode.MARKDOWN
                restoreComposerFocus = true
            }
            ChatComposerMode.PREVIEW -> {
                sourceInput = TextFieldValue(
                    markdown,
                    TextRange(
                        sourceInput.selection.start.coerceIn(0, markdown.length),
                        sourceInput.selection.end.coerceIn(0, markdown.length),
                    ),
                )
                composerMode = ChatComposerMode.PREVIEW
                showEmoji = false
            }
        }
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
                    state = messageListState,
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
                                        if (!voiceMode) {
                                            if (msg.confirmedReplyToMsgIdOrNull() != null) {
                                                DropdownMenuItem(
                                                    text = { Text("回复") },
                                                    onClick = {
                                                        if (editingSessionActive) cancelEditing()
                                                        savedReplyTarget = SavedChatReplyTarget(msg.clientMsgId)
                                                        menuMessage = null
                                                    },
                                                )
                                            }
                                            if (isMe && msg.serverSeq > 0L && msg.body.isMarkdownTextBody()) {
                                                DropdownMenuItem(
                                                    text = { Text("编辑") },
                                                    onClick = {
                                                        beginEditing(msg)
                                                        menuMessage = null
                                                    },
                                                )
                                            }
                                        }
                                        val canRevoke = isMe && msg.serverSeq > 0L &&
                                            (System.currentTimeMillis() - msg.timestamp < 2 * 60 * 1000)
                                        if (canRevoke) {
                                            DropdownMenuItem(
                                                text = { Text("撤回") },
                                                onClick = {
                                                    viewModel.revokeMessage(msg.serverSeq)
                                                    menuMessage = null
                                                },
                                            )
                                        }
                                        if (onForward != null && msg.serverSeq > 0L) {
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
                                        onClick = viewModel::loadOlder,
                                        modifier = Modifier.testTag("chat.history.loadMore"),
                                    ) { Text("加载更早消息") }
                                }
                            }
                        }
                    }
                }
            }

            // ── 输入区（白底 + 顶部分隔线，规格 §1.4）──
            Column(modifier = Modifier.testTag("chat.composer")) {
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

                // 回复/编辑上下文条属于文字编辑器；语音模式下不暴露对不可见草稿的操作入口。
                if (!voiceMode) {
                    if (savedReplyTarget.clientMsgId.isNotEmpty()) {
                        val msg = replyingTo
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                msg?.let {
                                    "回复 ${com.virjar.tk.util.MessagePreview.preview(it).take(20)}"
                                } ?: "正在恢复回复消息…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    savedReplyTarget = SavedChatReplyTarget()
                                    persistComposerContext()
                                },
                            ) { Text("取消") }
                        }
                    }
                    if (editingSessionActive) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("编辑消息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = ::cancelEditing,
                                enabled = !editingSaving,
                            ) { Text("取消") }
                        }
                    }
                }

                // 工具行（输入框下方，飞书范式）：表情/格式键/语音 居左；＋附件 居右。
                // 替代旧的"图标平铺+AlertDialog 文字菜单"（曾是最丑的一层）。
                val effectivePickVideo = media.onPickVideo
                // 编辑已发消息只允许修改文本 body，不制造“附件会并入原消息”的错觉。
                val hasAttachment = !editingSessionActive &&
                    (effectivePickImage != null || effectivePickFile != null || effectivePickVideo != null)
                run {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!voiceMode) {
                            // 预览态不接受输入；可视/源码态都允许插入表情。
                            if (composerMode != ChatComposerMode.PREVIEW) Box {
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
                                            if (composerMode == ChatComposerMode.MARKDOWN) {
                                                sourceInput = sourceInput.replaceComposerRange(
                                                    sourceInput.selection.min,
                                                    sourceInput.selection.max,
                                                    it,
                                                )
                                                sourceFocus.requestFocus()
                                            } else {
                                                richState.insertAtCaret(it)
                                                inputFocus.requestFocus()
                                            }
                                        },
                                        onDismiss = { showEmoji = false },
                                    )
                                }
                            }

                            if (composerMode == ChatComposerMode.VISUAL) {
                                // 聊天只保留高频格式；高级结构通过无损 Markdown 源码编写。
                                RichTextFormattingToolbar(
                                    state = richState,
                                    mode = RichTextToolbarMode.MESSAGE,
                                    onRequestFocus = { inputFocus.requestFocus() },
                                    modifier = Modifier.weight(1f),
                                    testTagPrefix = "chat.fmt",
                                )
                                TextButton(
                                    onClick = { changeComposerMode(ChatComposerMode.MARKDOWN) },
                                    modifier = Modifier.height(36.dp).testTag("chat.composer.mode.source"),
                                    contentPadding = PaddingValues(horizontal = Tk.spacing.sm),
                                ) {
                                    Text("MD", style = MaterialTheme.typography.labelLarge)
                                }
                            } else {
                                ChatComposerModeSwitcher(
                                    mode = composerMode,
                                    visualEnabled = canUseChatVisualEditor(sourceInput.text),
                                    onModeChange = ::changeComposerMode,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        // 语音/键盘切换
                        if (effectiveVoiceRecord != null) {
                            IconButton(
                                onClick = {
                                    if (voiceMode) {
                                        restoreComposerFocus = true
                                        voiceMode = false
                                    } else {
                                        showEmoji = false
                                        showAttach = false
                                        effectiveVoiceModeEntered?.invoke()
                                        voiceMode = true
                                    }
                                },
                                modifier = Modifier.testTag("chat.voiceMode"),
                            ) {
                                Icon(
                                    if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.KeyboardVoice,
                                    contentDescription = if (voiceMode) "键盘" else "语音",
                                    tint = Tk.colors.secondaryText,
                                )
                            }
                        }
                        if (voiceMode) Spacer(Modifier.weight(1f))

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
                                .pointerInput(effectiveVoiceRecord, effectiveVoiceRecordCancel) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var started = false
                                        var sent = false
                                        try {
                                            isRecording = true
                                            started = true
                                            effectiveVoiceRecord?.invoke(true)
                                            val up = waitForUpOrCancellation()
                                            if (up != null) {
                                                // 只有明确抬手才发送；pointer cancel、控件离开组合、
                                                // 导航销毁都会进入 finally 的丢弃路径。
                                                sent = true
                                                effectiveVoiceRecord?.invoke(false)
                                            }
                                        } finally {
                                            isRecording = false
                                            if (started && !sent) {
                                                effectiveVoiceRecordCancel?.invoke()
                                                    ?: effectiveVoiceRecord?.invoke(false)
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
                        // 可视编辑、Markdown 源码和最终气泡预览共享同一个正文区域。
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            when (composerMode) {
                                ChatComposerMode.VISUAL -> Box {
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
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                    )
                                    if (richState.annotatedString.text.isEmpty()) Text(
                                        if (editingSessionActive) "编辑消息…" else "输入消息…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Tk.colors.metaText,
                                        modifier = Modifier.align(Alignment.TopStart)
                                            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                                            .testTag("chat.input.hint"),
                                    )
                                }

                                ChatComposerMode.MARKDOWN -> Column(
                                    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp),
                                ) {
                                    Text(
                                        "Markdown 源码 · 原样发送",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Box(Modifier.weight(1f).fillMaxWidth()) {
                                        BasicTextField(
                                            value = sourceInput,
                                            onValueChange = { candidate ->
                                                // 与 SDK/服务端正文预算保持一致；拒绝超限粘贴而不静默截断源码。
                                                if (acceptsChatSourceInput(candidate)) sourceInput = candidate
                                            },
                                            modifier = Modifier.fillMaxSize()
                                                .testTag("chat.input.source")
                                                .focusRequester(sourceFocus)
                                                .onPreviewKeyEvent { event ->
                                                    if (event.type != KeyEventType.KeyDown) {
                                                        return@onPreviewKeyEvent false
                                                    }
                                                    val command = event.isMetaPressed || event.isCtrlPressed
                                                    when {
                                                        event.key == Key.Enter && command -> {
                                                            sendAction()
                                                            true
                                                        }
                                                        event.key == Key.B && command -> {
                                                            sourceInput = sourceInput.wrapComposerSelection("**")
                                                            true
                                                        }
                                                        event.key == Key.I && command -> {
                                                            sourceInput = sourceInput.wrapComposerSelection("_")
                                                            true
                                                        }
                                                        else -> false
                                                    }
                                                }
                                                .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = androidx.compose.material3.LocalContentColor.current,
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                        )
                                        if (sourceInput.text.isEmpty()) Text(
                                            if (editingSessionActive) "输入消息 Markdown…" else "输入 Markdown；支持标题、引用、代码块、表格…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Tk.colors.metaText,
                                            modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                                                .testTag("chat.input.source.hint"),
                                        )
                                    }
                                }

                                ChatComposerMode.PREVIEW -> Box(
                                    Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp)
                                        .verticalScroll(rememberScrollState())
                                        .testTag("chat.preview")
                                        .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
                                ) {
                                    if (sourceInput.text.isBlank()) Text(
                                        "暂无可预览内容",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Tk.colors.metaText,
                                    ) else SelectionContainer {
                                        RichMessageText(
                                            content = sourceInput.text,
                                            onUrlClick = effectiveUrlClick,
                                            onMentionClick = effectiveMentionClick,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!voiceMode) {
                        Spacer(Modifier.width(Tk.spacing.sm))
                        Button(
                            onClick = sendAction,
                            modifier = Modifier
                                .testTag("chat.send")
                                .height(Tk.dimens.inputMinHeight),
                            enabled = (when (composerMode) {
                                ChatComposerMode.VISUAL -> richState.annotatedString.text.isNotBlank()
                                ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> sourceInput.text.isNotBlank()
                            }) && !editingSaving &&
                                (!editingSessionActive || editingMessage != null) &&
                                (savedReplyTarget.clientMsgId.isEmpty() ||
                                    replyingTo?.confirmedReplyToMsgIdOrNull() != null),
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = Tk.spacing.lg),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(Tk.dimens.iconSize - 2.dp),
                            )
                            Spacer(Modifier.width(Tk.spacing.xs))
                            Text(if (editingSaving) "保存中…" else if (editingSessionActive) "保存" else "发送")
                        }
                    }
                }
            }
        }

        // ── 错误 Snackbar ──
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
    } // CompositionLocalProvider(LocalFileDownloads)
}

internal fun visibleChatReadTarget(
    readReceiptsEnabled: Boolean,
    latestVisibleServerSeq: Long,
): Long? = latestVisibleServerSeq.takeIf { readReceiptsEnabled && it > 0L }

// ── 消息渲染常量 ──

/** 清理聊天正文时同步丢弃撤销/重做历史，避免撤销恢复已发送或已取消的内容。 */
internal fun resetChatComposerState(state: RichTextState) {
    state.clear()
    state.history.clear()
}

/** UI 发送前与 SDK/服务端执行同一份消息类型、正文和资源预算校验。 */
internal fun canonicalizeChatMessageForSend(message: Message): Message =
    MessageBodyPolicy.canonicalize(message)

/** 回复协议只引用服务端序号；本地临时 clientMsgId 不得进入 replyToMsgId。 */
internal fun Message.confirmedReplyToMsgIdOrNull(): String? =
    serverSeq.takeIf { it > 0L }?.toString()

private val ChatVisualMarkdownBaselineSaver = listSaver<ChatVisualMarkdownBaseline, Any>(
    save = { baseline -> listOf(baseline.originalMarkdown, baseline.normalizedMarkdown) },
    restore = { values ->
        ChatVisualMarkdownBaseline(
            originalMarkdown = values[0] as String,
            normalizedMarkdown = values[1] as String,
        )
    },
)

/** Activity 重建只保存稳定标识；Message 实例始终来自当前会话的 messages 流。 */
internal data class SavedChatReplyTarget(val clientMsgId: String = "") {
    internal fun bind(messages: List<Message>): Message? =
        clientMsgId.takeIf(String::isNotEmpty)?.let { targetId ->
            messages.firstOrNull { it.clientMsgId == targetId }
        }
}

internal val SavedChatReplyTargetSaver = listSaver<SavedChatReplyTarget, String>(
    save = { target -> listOf(target.clientMsgId) },
    restore = { values -> SavedChatReplyTarget(values.firstOrNull().orEmpty()) },
)

/**
 * 编辑已发消息时的可恢复会话。这里只保存平台 Saver 支持的稳定值；目标消息和回复消息
 * 均用 clientMsgId 在当前消息流中重新绑定，Activity 重建不会把被编辑正文误当普通草稿。
 */
internal data class SavedChatEditingSession(
    val editingClientMsgId: String = "",
    val targetLoaded: Boolean = false,
    val suspendedMarkdown: String = "",
    val suspendedMode: ChatComposerMode = ChatComposerMode.VISUAL,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val replyingClientMsgId: String = "",
)

/** Keep normal rotation restore convenient while bounding every inline SavedState string. */
internal const val MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH = 8_192

internal fun chatSourceInputSaver(chatId: String, store: ChatComposerContextStore) =
    listSaver<TextFieldValue, Any>(
        save = { value ->
            val inline = value.text.length <= MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH
            val textOrToken = if (inline) {
                store.discardText(chatId, RetainedTextSlot.SOURCE_INPUT)
                value.text
            } else {
                store.retainText(chatId, RetainedTextSlot.SOURCE_INPUT, value.text)
            }
            listOf(inline, textOrToken, value.selection.start, value.selection.end)
        },
        restore = { values ->
            val inline = values[0] as Boolean
            val textOrToken = values[1] as String
            val text = if (inline) {
                textOrToken
            } else {
                store.restoreText(chatId, RetainedTextSlot.SOURCE_INPUT, textOrToken)
                    ?: store.restore(chatId)?.markdown.orEmpty()
            }
            TextFieldValue(
                text = text,
                selection = TextRange(
                    (values[2] as Int).coerceIn(0, text.length),
                    (values[3] as Int).coerceIn(0, text.length),
                ),
            )
        },
    )

internal fun savedChatEditingSessionSaver(chatId: String, store: ChatComposerContextStore) =
    listSaver<SavedChatEditingSession, Any>(
        save = { session ->
            val inline = session.suspendedMarkdown.length <= MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH
            val textOrToken = if (inline) {
                store.discardText(chatId, RetainedTextSlot.SUSPENDED_DRAFT)
                session.suspendedMarkdown
            } else {
                store.retainText(chatId, RetainedTextSlot.SUSPENDED_DRAFT, session.suspendedMarkdown)
            }
            listOf(
                session.editingClientMsgId,
                session.targetLoaded,
                inline,
                textOrToken,
                session.suspendedMode.name,
                session.selectionStart,
                session.selectionEnd,
                session.replyingClientMsgId,
            )
        },
        restore = { values ->
            val editingClientMsgId = values[0] as String
            val inline = values[2] as Boolean
            val textOrToken = values[3] as String
            val suspendedMarkdown = if (inline) {
                textOrToken
            } else {
                store.restoreText(chatId, RetainedTextSlot.SUSPENDED_DRAFT, textOrToken)
                    ?: store.restore(chatId)
                        ?.editingSession
                        ?.takeIf { it.editingClientMsgId == editingClientMsgId }
                        ?.suspendedMarkdown
                        .orEmpty()
            }
            SavedChatEditingSession(
                editingClientMsgId = editingClientMsgId,
                targetLoaded = values[1] as Boolean,
                suspendedMarkdown = suspendedMarkdown,
                suspendedMode = ChatComposerMode.entries.firstOrNull { it.name == values[4] as String }
                    ?: ChatComposerMode.VISUAL,
                selectionStart = (values[5] as Int).coerceIn(0, suspendedMarkdown.length),
                selectionEnd = (values[6] as Int).coerceIn(0, suspendedMarkdown.length),
                replyingClientMsgId = values[7] as String,
            )
        },
    )

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
    return resolveDisplayNameOrNull(uid, resolveSender) ?: uid.take(8)
}

/** Returns null when the current chat's sender directory has not resolved this uid yet. */
internal fun resolveDisplayNameOrNull(uid: String, resolveSender: ((uid: String) -> User?)?): String? {
    val user = resolveSender?.invoke(uid)
    return user?.name?.trim()?.takeIf(String::isNotEmpty)
        ?: user?.username?.trim()?.takeIf(String::isNotEmpty)
}
