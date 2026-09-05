package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildMentionMarkdown
import com.virjar.tk.protocol.body.markdownContentOrNull
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.UiResultHandoff
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSnapshot
import com.virjar.tk.app.ui.bridge.reduce
import com.virjar.tk.app.ui.component.input.detectMentionQuery
import com.virjar.tk.app.ui.component.input.detectSlashQuery
import com.virjar.tk.app.ui.component.input.expandSlashCommand
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.ChatVisualMarkdownBaseline
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.canUseChatVisualEditor
import com.virjar.tk.app.ui.component.rich.projectEmbeddedAssetManifest
import com.virjar.tk.app.ui.component.rich.referencedPendingAssetJobs
import com.virjar.tk.app.ui.component.rich.replaceComposerRange
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.app.viewmodel.MessageFocusState
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.UserFeedbackReporter
import java.util.UUID
import kotlinx.coroutines.delay

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
    onSaveMessage: ((Message) -> Unit)? = null,
    /** LocalCache 的普通草稿；null 为会话未加载，空字符串为已知清空。 */
    cachedDraft: String? = null,
    onDraftChange: ((String) -> Unit)? = null,
    draftLifecycleBridge: ChatDraftLifecycleBridge,
    /** 已排队的输入处理器执行时重新检查已认证的展示。 */
    actionAdmission: UiActionAdmission,
    /** 会话持有的回复/编辑与编辑器 UI 状态续存；不是草稿后端。 */
    composerContextStore: ChatComposerContextStore,
    /** 平台媒体能力的唯一入口；平台壳负责构造，聊天 UI 不再维护平行回调。 */
    media: ChatMediaConfig,
    peerReadSeq: Long = 0,
    /** 当前认证会话的语音应用内播放控制器。 */
    voicePlayback: com.virjar.tk.app.ui.component.VoicePlaybackController,
    /** @ 补全候选（群成员/私聊对方）；null=禁用 @ 补全 */
    mentionCandidates: List<com.virjar.tk.protocol.model.User>? = null,
    /** 文本气泡可鼠标拖选复制（桌面 true；Android 走长按菜单「复制」，避免选择拦截长按菜单） */
    selectableText: Boolean = false,
    /** 只有真正可见/前台化的聊天才能消费未读消息或发布输入状态。 */
    chatForegroundActive: Boolean,
    /** 可选的搜索结果身份；平台导航传递它，共享 UI 消费它。 */
    messageFocusTarget: MessageFocusTarget? = null,
    /** 区分重复导航到同一条消息的情况，而不改变其身份。 */
    messageFocusRequestId: Long = 0L,
    telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
) {
    val uiResultScope = rememberCoroutineScope()
    val uiResultHandoff = remember(uiResultScope) { UiResultHandoff(uiResultScope) }
    val admittedMedia = rememberAdmittedChatMedia(media, actionAdmission)
    val admittedVoicePlayback = rememberAdmittedVoicePlayback(voicePlayback, actionAdmission)
    val effectiveMentionClick = admittedMedia.onMentionClick
    val effectiveUrlClick = admittedMedia.onUrlClick
    // 会话级正文展示上下文：整个会话内不变，作为单一参数下传消息列表与气泡。
    val messageContent = rememberMessageContentContext(
        resolveSender = resolveSender,
        admittedVoicePlayback = admittedVoicePlayback,
        admittedMedia = admittedMedia,
    )
    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val loadingOlder by viewModel.loadingOlder.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val messageFocusState by viewModel.messageFocusState.collectAsState()
    val outgoingFailureCodes by viewModel.outgoingFailureCodes.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackReporter = remember(telemetry) { UserFeedbackReporter(telemetry) }
    // Desktop 在 A -> B 切换时复用此组合槽位。给状态设置 key 可防止另一个会话的位置
    // 成为本聊天的初始锚点。
    val messageListState = key(chatId) { rememberLazyListState() }

    val visibleTypingUid = chatTypingPresentationUid(chatId, viewModel, chatForegroundActive)

    val highlightedServerSeq = rememberMessageFocus(
        viewModel = viewModel,
        target = messageFocusTarget?.takeIf { target -> target.chatId == chatId },
        requestId = messageFocusRequestId,
        state = messageFocusState,
        messages = messages,
        messageListState = messageListState,
        actionAdmission = actionAdmission,
    )

    ChatLatestScrollEffect(
        chatId = chatId,
        messages = messages,
        messageListState = messageListState,
        suppressInitialAnchor = messageFocusTarget != null,
        suppressLatestFollow = messageFocusTarget != null &&
            (messageFocusState == MessageFocusState.Idle ||
                messageFocusState.isLoadingOrAwaitingPosition()),
    )

    ChatReadEffects(
        viewModel = viewModel,
        chatId = chatId,
        messages = messages,
        readReceiptsEnabled = chatForegroundActive,
        messageListState = messageListState,
        hasMore = hasMore,
        loading = loading,
        suppressHistoryPaging = messageFocusState.isLoadingOrAwaitingPosition(),
        actionAdmission = actionAdmission,
    )
    // 同一会话内 A -> B -> A 的导航本身不会保留 A 的 rememberSaveable 条目。每次新组合都
    // 从会话 store 播种；Android SavedState 恢复仍然优先，因为下面每个字段都保持其既有的
    // rememberSaveable/Saver 契约。
    val restoredComposerContext = remember(chatId, composerContextStore) {
        composerContextStore.restore(chatId)
    }
    val initialComposerMarkdown = restoredComposerContext?.markdown ?: cachedDraft.orEmpty()
    val initialComposerSelection = restoredComposerContext?.let { context ->
        TextRange(
            context.selectionStart.coerceIn(0, initialComposerMarkdown.length),
            context.selectionEnd.coerceIn(0, initialComposerMarkdown.length),
        )
    } ?: TextRange(initialComposerMarkdown.length)

    var menuMessage by remember(chatId) { mutableStateOf<Message?>(null) }
    val failedMessageDiscard = remember(chatId, viewModel, uiResultHandoff, actionAdmission) {
        ChatFailedMessageDiscardState(viewModel, uiResultHandoff, actionAdmission)
    }
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
    val editingFailedMessage = editingMessage?.takeIf { message ->
        message.serverSeq == 0L && message.sendStatus == Message.SEND_STATUS_FAILED
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
    var embeddedAssetSnapshot by remember(chatId) {
        mutableStateOf(
            EmbeddedAssetImportSnapshot(assets = restoredComposerContext?.assets.orEmpty()),
        )
    }
    val composerModeState = rememberSaveable(chatId) {
        mutableStateOf(restoredComposerContext?.mode ?: ChatComposerMode.VISUAL)
    }
    var composerMode by composerModeState
    val draftSync = remember(chatId) { ChatDraftSync(cachedDraft, restoredComposerContext) }
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

    fun loadComposerMarkdown(
        markdown: String,
        assets: List<EmbeddedAsset> = emptyList(),
    ) {
        sourceInput = TextFieldValue(markdown, TextRange(markdown.length))
        embeddedAssetSnapshot = EmbeddedAssetImportSnapshot(
            assets = runCatching { projectEmbeddedAssetManifest(markdown, assets) }
                .getOrDefault(emptyList()),
        )
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

    fun publishUserTextChange() {
        viewModel.onUserTextChanged(chatForegroundActive)
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
            previousCachedDraft = draftSync.previousCachedDraft,
            lastPublishedDraft = draftSync.lastPublishedDraft,
            assets = runCatching {
                projectEmbeddedAssetManifest(composerMarkdownSnapshot(), embeddedAssetSnapshot.assets)
            }.getOrDefault(emptyList()),
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
            assetImportOwnerId = UUID.randomUUID().toString(),
            suspendedMarkdown = composerMarkdownSnapshot(),
            suspendedAssets = runCatching {
                projectEmbeddedAssetManifest(composerMarkdownSnapshot(), embeddedAssetSnapshot.assets)
            }.getOrDefault(emptyList()),
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
            embeddedAssetSnapshot = EmbeddedAssetImportSnapshot()
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
        embeddedAssetSnapshot = EmbeddedAssetImportSnapshot(assets = suspended.suspendedAssets)
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
        if (
            hasReferencedIncompleteEmbeddedAssetJob(
                composerMarkdownSnapshot(),
                embeddedAssetSnapshot.jobs,
            )
        ) {
            viewModel.onError("附件仍在上传或等待处理，请完成或删除后再编辑消息")
            return
        }
        if (!editingSessionActive) editingSession = captureEditingSession(message.clientMsgId)
        savedReplyTarget = SavedChatReplyTarget()
    }

    fun cancelEditing() {
        if (editingSaving) return
        restoreSuspendedComposer()
    }

    fun placeEmbeddedAssetReference(event: EmbeddedAssetImportEvent) {
        val placement = placeChatEmbeddedAssetReference(
            event = event,
            currentMarkdown = composerMarkdownSnapshot(),
            sourceInput = sourceInput,
            composerMode = composerMode,
        ) ?: return
        composerMode = applyChatEmbeddedAssetPlacement(placement, richState) { sourceInput = it }
        showEmoji = false
        showAttach = false
        restoreComposerFocus = true
    }

    val embeddedAssetImports = admittedMedia.embeddedAssetImports
    val embeddedAssetImportOwnerKey = chatEmbeddedAssetImportOwnerKey(chatId, editingSession)
    DisposableEffect(embeddedAssetImportOwnerKey, embeddedAssetImports, actionAdmission) {
        val registration = embeddedAssetImports?.bind(
            ownerKey = embeddedAssetImportOwnerKey,
            sink = EmbeddedAssetImportEventSink { event ->
                actionAdmission.runIfOpen {
                    embeddedAssetSnapshot = embeddedAssetSnapshot.reduce(event)
                    placeEmbeddedAssetReference(event)
                    persistComposerContext()
                }
            },
        )
        onDispose { registration?.close() }
    }

    // 首次进入会话使用 cachedDraft；Android 重建时 TextFieldValue/Mode 由 Saver 恢复。
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

    // callback holder 必须按 chatId 隔离。同一 composition slot 从 A 切到 B 时，
    // A 的 onDispose 仍应写回 A，不能读到 B 刚更新进去的 callback。
    val latestDraftChangeState = key(chatId) { rememberUpdatedState(onDraftChange) }

    fun publishOrdinaryDraft(markdown: String) =
        draftSync.publish(markdown, latestDraftChangeState.value)
    // 只替换未被本机改动的外部镜像。空字符串也是一次更新，不能只处理首次非空 hydrate。
    LaunchedEffect(chatId, composerReady, cachedDraft) {
        if (!composerReady || cachedDraft == null) return@LaunchedEffect
        val markdown = composerMarkdownSnapshot()
        val replacement = draftSync.receive(
            cachedDraft = cachedDraft,
            currentMarkdown = markdown,
            hasLocalContext = editingSessionActive || savedReplyTarget.clientMsgId.isNotEmpty() ||
                embeddedAssetSnapshot.assets.isNotEmpty() || embeddedAssetSnapshot.jobs.isNotEmpty() ||
                durableChatDraftMirrorPayload(markdown) != markdown,
        ) ?: return@LaunchedEffect
        val previousMode = composerMode
        resetChatComposerState(richState)
        loadComposerMarkdown(replacement)
        if (previousMode != ChatComposerMode.VISUAL) composerMode = previousMode
        persistComposerContext()
    }

    // 可视编辑的 saveable 镜像 + 草稿防抖。离开页面时 DisposableEffect 仍会立即 flush。
    val richTextSignal = richState.annotatedString
    LaunchedEffect(composerReady, composerMode, sourceInput.text, richTextSignal, editingSessionActive) {
        // “编辑已发消息”是临时会话，不得覆盖普通聊天草稿。
        if (!composerReady || editingSessionActive) return@LaunchedEffect
        val draft = composerMarkdownSnapshot()
        if (composerMode == ChatComposerMode.VISUAL && sourceInput.text != draft) {
            sourceInput = TextFieldValue(draft, TextRange(draft.length))
        }
        if (draft == draftSync.lastPublishedDraft) return@LaunchedEffect
        delay(350)
        publishOrdinaryDraft(draft)
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
        embeddedAssetSnapshot = EmbeddedAssetImportSnapshot()
        visualBaseline = ChatVisualMarkdownBaseline("", "")
        composerMode = ChatComposerMode.VISUAL
        draftSync.publish("", latestDraftChangeState.value, force = true)
        if (requestFocus && !voiceMode) restoreComposerFocus = true
    }

    /** 选中 mention 候选：编辑器显示 @姓名，导出时仍是服务端权威的 mention Markdown。 */
    fun pickMention(user: com.virjar.tk.protocol.model.User) {
        if (voiceMode || composerMode == ChatComposerMode.PREVIEW) return
        val q = mentionQuery ?: return
        val previousMarkdown = composerMarkdownSnapshot()
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
        if (composerMarkdownSnapshot() != previousMarkdown) publishUserTextChange()
    }

    /** 选中 / 指令候选：把行首不完整 token（如 /s）回填为完整命令 + 空格；发送时再展开 */
    fun pickSlash(cmd: String) {
        if (voiceMode || composerMode == ChatComposerMode.PREVIEW) return
        val text = inputView.text
        val pos = inputView.selection.min
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (pos == 0) 0 else it + 1 }
        val previousMarkdown = composerMarkdownSnapshot()
        if (composerMode == ChatComposerMode.MARKDOWN) {
            sourceInput = sourceInput.replaceComposerRange(lineStart, pos, "$cmd ")
            sourceFocus.requestFocus()
        } else {
            richState.replaceRange(lineStart, pos, "$cmd ")
            inputFocus.requestFocus()
        }
        if (composerMarkdownSnapshot() != previousMarkdown) publishUserTextChange()
    }
    val isPersonal = ChatType.fromCode(chatType) == ChatType.PERSONAL

    fun captureFinalComposerFrame() {
        // 消息编辑只保存挂起的普通草稿，不把临时编辑正文发布出去。
        val draft = finalChatDraftSnapshot(
            editingSession = editingSession,
            composerReady = composerReadyState.value,
            preHydrationSource = sourceInputState.value.text,
            composerMode = composerModeState.value,
            visualMarkdown = {
                visualBaselineState.value.snapshot(richState.toMarkdown())
            },
            sourceMarkdown = sourceInputState.value.text,
        )
        try {
            publishOrdinaryDraft(draft)
        } finally {
            // 同步保存最终帧及本次发布值；快速返回不重发旧草稿，发布失败也不丢正文。
            persistComposerContext()
        }
    }
    val finalFrameCapture = remember(chatId) {
        ChatDraftCaptureHandle(::captureFinalComposerFrame)
    }
    SideEffect { finalFrameCapture.action = ::captureFinalComposerFrame }
    DisposableEffect(chatId, composerContextStore, draftLifecycleBridge) {
        val registration = draftLifecycleBridge.register(finalFrameCapture::capture)
        onDispose { draftLifecycleBridge.captureAndUnregister(registration) }
    }

    ChatFeedbackEffect(error, feedbackReporter, snackbarHostState, actionAdmission, viewModel::clearError)

    // 当前会话收到的小文件即使尚未滚动到可见区域，也要静默下载。
    // 大文件只初始化状态，等待用户点击气泡。
    val fileDownloads = admittedMedia.fileDownloads
    ChatFileDownloadEffect(messages, admittedMedia, actionAdmission)

    // 编辑时预填输入
    LaunchedEffect(editingSession.editingClientMsgId, editingMessage) {
        if (editingSessionActive && !editingSession.targetLoaded) editingMessage?.let { msg ->
            loadComposerMarkdown(
                markdown = msg.body.markdownContentOrNull().orEmpty(),
                assets = (msg.body as? RichTextBody)?.assets.orEmpty(),
            )
            editingSession = editingSession.copy(targetLoaded = true)
        }
    }

    // ── 发送动作（按钮与 Cmd/Ctrl+Enter 共用）──
    fun validatedMessageOrReport(message: Message) = canonicalizeChatMessageOrReport(message, viewModel::onError)
    val performSend: () -> Unit = sendAction@{
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
            val embeddedAssets = admitChatEmbeddedAssetsOrReport(inputText, embeddedAssetSnapshot, viewModel::onError) ?: return@sendAction
            if (editingSessionActive) {
                if (editingSaving) return@sendAction
                val editing = editingMessage ?: return@sendAction
                val editedBody = buildChatRichTextBodyOrReport(inputText, embeddedAssets, viewModel::onError) ?: return@sendAction
                val edited = validatedMessageOrReport(editing.copy(
                    messageType = MessageType.RICH_TEXT.code,
                    body = editedBody,
                )) ?: return@sendAction
                editingSaving = true
                val onSaved: (Boolean) -> Unit = { succeeded ->
                    // ChatViewModel 把乐观缓存/RPC 工作放在其 worker 与本地数据调度器上。
                    // 只有最终的 UI 发布才跨入此 owner 作用域。
                    uiResultHandoff.deliver(succeeded, actionAdmission) { editSucceeded ->
                        editingSaving = false
                        if (editSucceeded && editingSession.editingClientMsgId == editing.clientMsgId) {
                            restoreSuspendedComposer()
                        }
                    }
                }
                if (editingFailedMessage != null) {
                    val replacement = validatedMessageOrReport(
                        edited.copy(
                            clientMsgId = UUID.randomUUID().toString(),
                            serverSeq = 0L,
                            timestamp = System.currentTimeMillis(),
                            sendStatus = Message.SEND_STATUS_SENDING,
                            uploadProgress = 0f,
                        ),
                    ) ?: run {
                        editingSaving = false
                        return@sendAction
                    }
                    viewModel.replaceFailedMessage(
                        failedClientMsgId = editing.clientMsgId,
                        replacement = replacement,
                        onResult = onSaved,
                    )
                } else {
                    viewModel.editMessage(edited, onSaved)
                }
            } else {
                // 回复目标可能正等待 messages 流恢复，禁止把本应是回复的内容静默降级成普通消息。
                if (savedReplyTarget.clientMsgId.isNotEmpty() && replyingTo == null) return@sendAction
                val message = buildOutgoingChatMessageOrReport(
                    chatId = chatId,
                    myUid = myUid,
                    inputText = inputText,
                    embeddedAssets = embeddedAssets,
                    replyingTo = replyingTo,
                    resolveSender = resolveSender,
                    reportError = viewModel::onError,
                    buildRichTextBody = { m, a -> buildChatRichTextBodyOrReport(m, a, viewModel::onError) },
                ) ?: return@sendAction
                val validated = validatedMessageOrReport(message) ?: return@sendAction
                viewModel.sendMessage(validated)
                resetComposer()
                savedReplyTarget = SavedChatReplyTarget()
                persistComposerContext()
            }
        }
    }
    val sendAction = actionAdmission.guard(performSend)

    val (toggleBold, toggleItalic) = chatComposerFormattingActions(richState, voiceMode, inputFocus)

    fun changeComposerMode(target: ChatComposerMode) = changeChatComposerMode(
        target, voiceMode, composerMode, composerMarkdownSnapshot(), sourceInput,
        ::enterVisualMarkdown, { sourceInput = it }, { composerMode = it },
        { restoreComposerFocus = true }, { showEmoji = false },
    )

    fun discardPendingAsset(job: PendingAssetJob) = discardChatPendingAsset(
        job, composerMarkdownSnapshot(), sourceInput, editingSessionActive,
        updateEditor = { updated ->
            sourceInput = updated
            composerMode = ChatComposerMode.MARKDOWN
            showEmoji = false
            showAttach = false
            restoreComposerFocus = true
        },
        persistSessionContext = ::persistComposerContext,
        persistOrdinaryDraft = ::publishOrdinaryDraft,
        publishUserTextChange = ::publishUserTextChange,
        cancelUpload = { embeddedAssetImports?.cancel(it) },
        reportError = viewModel::onError,
    )
    val composerMarkdown = composerMarkdownSnapshot()
    val composerEmbeddedAssets = remember(composerMarkdown, embeddedAssetSnapshot.assets) {
        runCatching {
            projectEmbeddedAssetManifest(composerMarkdown, embeddedAssetSnapshot.assets)
        }.getOrDefault(emptyList())
    }
    val composerPendingAssetJobs = remember(composerMarkdown, embeddedAssetSnapshot.jobs) {
        referencedPendingAssetJobs(composerMarkdown, embeddedAssetSnapshot.jobs)
    }

    // 文件下载控制器注入（FileCard 消费）。
    androidx.compose.runtime.CompositionLocalProvider(
        com.virjar.tk.app.ui.component.LocalFileDownloads provides fileDownloads,
    ) {
    Box(
        modifier = modifier.fillMaxSize().testTag(
            messageFocusSemanticsTag(messageFocusTarget, messageFocusState),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatMessageList(
                loading = loading,
                messages = messages,
                state = messageListState,
                hasMore = hasMore,
                loadingOlder = loadingOlder,
                highlightedServerSeq = highlightedServerSeq,
                outgoingFailureCodes = outgoingFailureCodes,
                reactions = viewModel.reactions.collectAsState().value,
                onToggleReaction = actionAdmission.guard(viewModel::toggleReaction),
                onPickReaction = actionAdmission.guard(viewModel::pickReaction),
                onSaveMessage = onSaveMessage?.let { actionAdmission.guard(it) },
                isSavedChat = chatType == 3, // ChatType.SAVED：saved 会话内不提供保存入口
                onWindowReactionsConverge = viewModel::refreshReactionsForWindow,
                myUid = myUid,
                isPersonal = isPersonal,
                peerReadSeq = peerReadSeq,
                content = messageContent,
                selectableText = selectableText,
                menuMessage = menuMessage,
                onMenuMessageChange = actionAdmission.guard { message: Message? ->
                    menuMessage = message
                },
                voiceMode = voiceMode,
                editingSessionActive = editingSessionActive,
                onCancelEditing = actionAdmission.guard(::cancelEditing),
                onReply = actionAdmission.guard { message: Message ->
                    savedReplyTarget = SavedChatReplyTarget(message.clientMsgId)
                },
                onBeginEditing = actionAdmission.guard(::beginEditing),
                onDiscardFailed = actionAdmission.guard(failedMessageDiscard::request),
                onRevoke = actionAdmission.guard(viewModel::revokeMessage),
                onForward = onForward?.let { actionAdmission.guard(it) },
                onLoadOlder = actionAdmission.guard(viewModel::loadOlder),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            ChatTypingIndicator(visibleTypingUid, resolveSender)

            ChatComposer(
                myUid = myUid,
                mentionQuery = mentionQuery,
                mentionCandidates = mentionCandidates,
                onPickMention = actionAdmission.guard(::pickMention),
                slashQuery = slashQuery,
                onPickSlash = actionAdmission.guard(::pickSlash),
                voiceMode = voiceMode,
                onVoiceModeChange = actionAdmission.guard { enabled: Boolean -> voiceMode = enabled },
                replyTargetClientMsgId = savedReplyTarget.clientMsgId,
                replyingTo = replyingTo,
                onCancelReply = actionAdmission.guard {
                    savedReplyTarget = SavedChatReplyTarget()
                    persistComposerContext()
                },
                editingSessionActive = editingSessionActive,
                editingMessage = editingMessage,
                editingFailedMessage = editingFailedMessage != null,
                editingSaving = editingSaving,
                onCancelEditing = actionAdmission.guard(::cancelEditing),
                media = admittedMedia,
                showEmoji = showEmoji,
                onShowEmojiChange = actionAdmission.guard { visible: Boolean -> showEmoji = visible },
                showAttach = showAttach,
                onShowAttachChange = actionAdmission.guard { visible: Boolean -> showAttach = visible },
                richState = richState,
                composerMode = composerMode,
                onComposerModeChange = actionAdmission.guard(::changeComposerMode),
                sourceInput = sourceInput,
                onSourceInputChange = actionAdmission.guard { value: TextFieldValue ->
                    val textChanged = value.text != sourceInput.text
                    sourceInput = value
                    if (textChanged) publishUserTextChange()
                },
                onVisualTextChange = actionAdmission.guard(::publishUserTextChange),
                inputFocus = inputFocus,
                sourceFocus = sourceFocus,
                embeddedAssets = composerEmbeddedAssets,
                pendingAssetJobs = composerPendingAssetJobs,
                onRetryPendingAsset = actionAdmission.guard { job ->
                    retryChatPendingAsset(job, embeddedAssetImports, viewModel::onError)
                },
                onDiscardPendingAsset = actionAdmission.guard(::discardPendingAsset),
                onRestoreComposerFocus = actionAdmission.guard { restoreComposerFocus = true },
                sendAction = sendAction,
                toggleBold = actionAdmission.guard(toggleBold),
                toggleItalic = actionAdmission.guard(toggleItalic),
                onMentionClick = effectiveMentionClick,
                onUrlClick = effectiveUrlClick,
            )
        }

        ChatPanelOverlays(snackbarHostState, failedMessageDiscard)
    }
    } // CompositionLocalProvider(LocalFileDownloads)
}
