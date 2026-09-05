package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.component.MessagePreview
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.app.ui.component.input.AutoCompleteItem
import com.virjar.tk.app.ui.component.input.AutoCompleteOverlay
import com.virjar.tk.app.ui.component.input.MentionQuery
import com.virjar.tk.app.ui.component.input.SlashCommands
import com.virjar.tk.app.ui.component.input.SlashQuery
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.theme.Tk

@Composable
internal fun ChatComposer(
    myUid: String,
    mentionQuery: MentionQuery?,
    mentionCandidates: List<User>?,
    onPickMention: (User) -> Unit,
    slashQuery: SlashQuery?,
    onPickSlash: (String) -> Unit,
    voiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    replyTargetClientMsgId: String,
    replyingTo: Message?,
    onCancelReply: () -> Unit,
    editingSessionActive: Boolean,
    editingMessage: Message?,
    editingFailedMessage: Boolean,
    editingSaving: Boolean,
    onCancelEditing: () -> Unit,
    media: ChatMediaConfig,
    showEmoji: Boolean,
    onShowEmojiChange: (Boolean) -> Unit,
    showAttach: Boolean,
    onShowAttachChange: (Boolean) -> Unit,
    richState: RichTextState,
    composerMode: ChatComposerMode,
    onComposerModeChange: (ChatComposerMode) -> Unit,
    sourceInput: TextFieldValue,
    onSourceInputChange: (TextFieldValue) -> Unit,
    onVisualTextChange: () -> Unit,
    inputFocus: FocusRequester,
    sourceFocus: FocusRequester,
    embeddedAssets: List<EmbeddedAsset>,
    pendingAssetJobs: List<PendingAssetJob>,
    onRetryPendingAsset: (PendingAssetJob) -> Unit,
    onDiscardPendingAsset: (PendingAssetJob) -> Unit,
    onRestoreComposerFocus: () -> Unit,
    sendAction: () -> Unit,
    toggleBold: () -> Unit,
    toggleItalic: () -> Unit,
    onMentionClick: ((uid: String) -> Unit)?,
    onUrlClick: ((String) -> Unit)?,
) {
    val effectivePickImage = media.embeddedAssetImports?.let { imports ->
        { imports.select(EmbeddedAssetPresentation.IMAGE) }
    }
    val effectivePickFile = media.embeddedAssetImports?.let { imports ->
        { imports.select(EmbeddedAssetPresentation.FILE) }
    }
    val effectiveVoiceRecord = media.onVoiceRecord
    val effectiveVoiceModeEntered = media.onVoiceModeEntered
    val effectiveVoiceRecordCancel = media.onVoiceRecordCancel
    var showFormatting by remember(richState) { mutableStateOf(false) }

    fun toggleEmojiPanel() {
        val next = !showEmoji
        if (next) {
            onShowAttachChange(false)
            showFormatting = false
        }
        onShowEmojiChange(next)
    }

    fun toggleAttachmentPanel() {
        val next = !showAttach
        if (next) {
            onShowEmojiChange(false)
            showFormatting = false
        }
        onShowAttachChange(next)
    }

    fun toggleFormattingPanel() {
        val next = !showFormatting
        if (next) {
            onShowEmojiChange(false)
            onShowAttachChange(false)
        }
        showFormatting = next
    }

    fun enterVoiceMode() {
        onShowEmojiChange(false)
        onShowAttachChange(false)
        showFormatting = false
        effectiveVoiceModeEntered?.invoke()
        onVoiceModeChange(true)
    }

    fun leaveVoiceMode() {
        onRestoreComposerFocus()
        onVoiceModeChange(false)
    }

    // 输入器只按当前可用宽度响应；不把 Android/Desktop 变成两套产品语义。
    BoxWithConstraints(modifier = Modifier.testTag("chat.composer")) {
        val layout = chatComposerLayout(maxWidth)
        Column {
        HorizontalDivider(color = Tk.colors.divider)

        // @ 补全层（内嵌展开于输入行上方）：按名字/uid 过滤候选，排除自己
        mentionQuery?.let { q ->
            val candidates = (mentionCandidates ?: emptyList())
                .filter { it.uid != myUid }
                .filter { u ->
                    val name = u.name.ifBlank { u.username.ifBlank { u.uid } }
                    q.text.isEmpty() || name.contains(q.text, ignoreCase = true) || u.uid.contains(q.text)
                }
            if (candidates.isNotEmpty()) {
                AutoCompleteOverlay(
                    title = "提及成员",
                    items = candidates.take(5).map { u ->
                        AutoCompleteItem(
                            label = u.name.ifBlank { u.username.ifBlank { u.uid } },
                            hint = "@" + u.username.ifBlank { u.uid },
                            payload = u.uid,
                        )
                    },
                    onPick = { item -> candidates.find { it.uid == item.payload }?.let { onPickMention(it) } },
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
                    onPick = { item -> onPickSlash(item.payload) },
                )
            }
        }

        // 回复/编辑上下文条属于文字编辑器；语音模式下不暴露对不可见草稿的操作入口。
        if (!voiceMode) {
            if (replyTargetClientMsgId.isNotEmpty()) {
                val msg = replyingTo
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        msg?.let {
                            "回复 ${MessagePreview.preview(it).take(20)}"
                        } ?: "正在恢复回复消息…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelReply) { Text("取消") }
                }
            }
            if (editingSessionActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs)
                        .then(
                            if (editingFailedMessage) {
                                Modifier.testTag("chat.failed.recovery.context")
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (editingFailedMessage) "编辑失败消息并重发" else "编辑消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onCancelEditing,
                        enabled = !editingSaving,
                    ) { Text("取消") }
                }
            }
        }

        val effectivePickVideo = media.onPickVideo
        // 编辑已发消息只允许修改文本 body，不制造“附件会并入原消息”的错觉。
        val hasAttachment = !editingSessionActive &&
            (effectivePickImage != null || effectivePickFile != null || effectivePickVideo != null)
        if (layout == ChatComposerLayout.WIDE) {
            WideComposerToolbar(
                voiceMode = voiceMode,
                editingSessionActive = editingSessionActive,
                composerMode = composerMode,
                onComposerModeChange = onComposerModeChange,
                richState = richState,
                sourceInput = sourceInput,
                sourceFocus = sourceFocus,
                inputFocus = inputFocus,
                showEmoji = showEmoji,
                onToggleEmoji = ::toggleEmojiPanel,
                onDismissEmoji = { onShowEmojiChange(false) },
                onSourceInputChange = onSourceInputChange,
                onVisualTextChange = onVisualTextChange,
                hasVoice = effectiveVoiceRecord != null,
                onVoiceClick = if (voiceMode) ::leaveVoiceMode else ::enterVoiceMode,
                hasAttachment = hasAttachment,
                showAttach = showAttach,
                onToggleAttach = ::toggleAttachmentPanel,
                onDismissAttach = { onShowAttachChange(false) },
                onPickImage = effectivePickImage,
                onPickVideo = effectivePickVideo,
                onPickFile = effectivePickFile,
                onPickDocument = media.onPickDocument,
                onPickGroupFile = media.onPickGroupFile,
                onPasteAsset = media.onPasteEmbeddedAsset,
            )
        }

        PendingComposerAssets(
            pendingAssetJobs = pendingAssetJobs,
            onRetryPendingAsset = onRetryPendingAsset,
            onDiscardPendingAsset = onDiscardPendingAsset,
        )

        val sendEnabled = (when (composerMode) {
            ChatComposerMode.VISUAL -> richState.annotatedString.text.isNotBlank()
            ChatComposerMode.MARKDOWN, ChatComposerMode.PREVIEW -> sourceInput.text.isNotBlank()
        }) && !editingSaving &&
            (!editingSessionActive || editingMessage != null) &&
            (replyTargetClientMsgId.isEmpty() || replyingTo?.confirmedReplyToMsgIdOrNull() != null)

        when (layout) {
            ChatComposerLayout.COMPACT -> CompactChatComposer(
                voiceMode = voiceMode,
                editingSessionActive = editingSessionActive,
                composerMode = composerMode,
                showFormatting = showFormatting,
                onToggleFormatting = ::toggleFormattingPanel,
                onComposerModeChange = { target ->
                    showFormatting = false
                    onComposerModeChange(target)
                },
                richState = richState,
                sourceInput = sourceInput,
                onSourceInputChange = onSourceInputChange,
                onVisualTextChange = onVisualTextChange,
                inputFocus = inputFocus,
                sourceFocus = sourceFocus,
                embeddedAssets = embeddedAssets,
                media = media,
                editingSaving = editingSaving,
                editingFailedMessage = editingFailedMessage,
                sendEnabled = sendEnabled,
                sendAction = sendAction,
                toggleBold = toggleBold,
                toggleItalic = toggleItalic,
                onMentionClick = onMentionClick,
                onUrlClick = onUrlClick,
                showEmoji = showEmoji,
                onToggleEmoji = ::toggleEmojiPanel,
                onDismissEmoji = { onShowEmojiChange(false) },
                hasVoice = effectiveVoiceRecord != null,
                onVoiceClick = if (voiceMode) ::leaveVoiceMode else ::enterVoiceMode,
                onVoiceRecord = effectiveVoiceRecord,
                onVoiceRecordCancel = effectiveVoiceRecordCancel,
                hasAttachment = hasAttachment,
                showAttach = showAttach,
                onToggleAttach = ::toggleAttachmentPanel,
                onDismissAttach = { onShowAttachChange(false) },
                onPickImage = effectivePickImage,
                onPickVideo = effectivePickVideo,
                onPickFile = effectivePickFile,
                onPickDocument = media.onPickDocument,
                onPickGroupFile = media.onPickGroupFile,
                onPasteAsset = media.onPasteEmbeddedAsset,
            )
            ChatComposerLayout.WIDE -> WideComposerInput(
                voiceMode = voiceMode,
                composerMode = composerMode,
                richState = richState,
                sourceInput = sourceInput,
                onSourceInputChange = onSourceInputChange,
                onVisualTextChange = onVisualTextChange,
                inputFocus = inputFocus,
                sourceFocus = sourceFocus,
                embeddedAssets = embeddedAssets,
                media = media,
                editingSessionActive = editingSessionActive,
                editingSaving = editingSaving,
                editingFailedMessage = editingFailedMessage,
                sendEnabled = sendEnabled,
                sendAction = sendAction,
                toggleBold = toggleBold,
                toggleItalic = toggleItalic,
                onVoiceRecord = effectiveVoiceRecord,
                onVoiceRecordCancel = effectiveVoiceRecordCancel,
                onMentionClick = onMentionClick,
                onUrlClick = onUrlClick,
            )
        }
        }
    }
}
