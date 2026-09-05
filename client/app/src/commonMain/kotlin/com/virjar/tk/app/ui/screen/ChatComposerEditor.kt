package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.component.CHAT_PREVIEW_TEST_TAG
import com.virjar.tk.app.ui.component.CHAT_VOICE_MODE_TEST_TAG
import com.virjar.tk.app.ui.component.CHAT_VOICE_RECORD_TEST_TAG
import com.virjar.tk.app.ui.component.FileCardWithDownload
import com.virjar.tk.app.ui.component.ImageThumbCard
import com.virjar.tk.app.ui.component.RichMessageText
import com.virjar.tk.app.ui.component.input.AttachmentPanel
import com.virjar.tk.app.ui.component.input.EmojiPanel
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.acceptsChatSourceInput
import com.virjar.tk.app.ui.component.rich.replaceComposerRange
import com.virjar.tk.app.ui.component.rich.wrapComposerSelection
import com.virjar.tk.app.ui.theme.Tk

@Composable
internal fun ComposerEditor(
    compact: Boolean,
    composerMode: ChatComposerMode,
    richState: RichTextState,
    sourceInput: TextFieldValue,
    onSourceInputChange: (TextFieldValue) -> Unit,
    onVisualTextChange: () -> Unit,
    inputFocus: FocusRequester,
    sourceFocus: FocusRequester,
    embeddedAssets: List<EmbeddedAsset>,
    media: ChatMediaConfig,
    editingSessionActive: Boolean,
    sendAction: () -> Unit,
    toggleBold: () -> Unit,
    toggleItalic: () -> Unit,
    onMentionClick: ((uid: String) -> Unit)?,
    onUrlClick: ((String) -> Unit)?,
) {
    when (composerMode) {
        ChatComposerMode.VISUAL -> Box {
            BasicRichTextEditor(
                state = richState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 48.dp else 72.dp, max = if (compact) 160.dp else 200.dp)
                    .testTag("chat.input")
                    .focusRequester(inputFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val command = event.isMetaPressed || event.isCtrlPressed
                        when {
                            event.key == Key.V && command && media.onPasteEmbeddedAsset?.invoke() == true -> true
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
                minLines = if (compact) 1 else 3,
                maxLines = if (compact) 6 else 8,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                onUserTextChange = onVisualTextChange,
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
            Modifier.fillMaxWidth().heightIn(min = 96.dp, max = if (compact) 200.dp else 240.dp),
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
                        if (acceptsChatSourceInput(candidate)) onSourceInputChange(candidate)
                    },
                    modifier = Modifier.fillMaxSize()
                        .testTag("chat.input.source")
                        .focusRequester(sourceFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val command = event.isMetaPressed || event.isCtrlPressed
                            when {
                                event.key == Key.V && command && media.onPasteEmbeddedAsset?.invoke() == true -> true
                                event.key == Key.Enter && command -> {
                                    sendAction()
                                    true
                                }
                                event.key == Key.B && command -> {
                                    onSourceInputChange(sourceInput.wrapComposerSelection("**"))
                                    true
                                }
                                event.key == Key.I && command -> {
                                    onSourceInputChange(sourceInput.wrapComposerSelection("_"))
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
            Modifier.fillMaxWidth().heightIn(min = 96.dp, max = if (compact) 200.dp else 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm)
                .testTag(CHAT_PREVIEW_TEST_TAG),
        ) {
            if (sourceInput.text.isBlank()) Text(
                "暂无可预览内容",
                style = MaterialTheme.typography.bodyMedium,
                color = Tk.colors.metaText,
            ) else SelectionContainer {
                RichMessageText(
                    content = sourceInput.text,
                    onUrlClick = onUrlClick,
                    onMentionClick = onMentionClick,
                    embeddedAssets = com.virjar.tk.app.ui.component.rich.EmbeddedAssetRenderScope(embeddedAssets),
                    embeddedAssetContent = { asset, presentation, modifier ->
                        when (presentation) {
                            EmbeddedAssetPresentation.IMAGE -> ImageThumbCard(
                                attachment = asset.thumbnail ?: asset.attachment,
                                imageContent = media.imageContent,
                                imgWidth = asset.width,
                                imgHeight = asset.height,
                                onClick = null,
                                modifier = modifier,
                            )
                            EmbeddedAssetPresentation.FILE -> FileCardWithDownload(
                                controller = media.fileDownloads,
                                attachment = asset.attachment,
                                modifier = modifier,
                            )
                        }
                    },
                )
            }
        }
    }
}
@Composable
internal fun ComposerEmojiAction(
    composerMode: ChatComposerMode,
    showEmoji: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    richState: RichTextState,
    sourceInput: TextFieldValue,
    onSourceInputChange: (TextFieldValue) -> Unit,
    onVisualTextChange: () -> Unit,
    inputFocus: FocusRequester,
    sourceFocus: FocusRequester,
    compact: Boolean = false,
) {
    Box {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.then(if (compact) Modifier.size(44.dp) else Modifier).testTag("chat.emoji"),
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
                        onSourceInputChange(
                            sourceInput.replaceComposerRange(
                                sourceInput.selection.min,
                                sourceInput.selection.max,
                                it,
                            ),
                        )
                        sourceFocus.requestFocus()
                    } else {
                        val previousText = richState.annotatedString.text
                        richState.insertAtCaret(it)
                        if (richState.annotatedString.text != previousText) onVisualTextChange()
                        inputFocus.requestFocus()
                    }
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
internal fun ComposerVoiceModeAction(
    voiceMode: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.then(if (compact) Modifier.size(44.dp) else Modifier)
            .testTag(CHAT_VOICE_MODE_TEST_TAG),
    ) {
        Icon(
            if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.KeyboardVoice,
            contentDescription = if (voiceMode) "键盘" else "语音",
            tint = Tk.colors.secondaryText,
        )
    }
}

@Composable
internal fun ComposerAttachmentAction(
    showAttach: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onPickImage: (() -> Unit)?,
    onPickVideo: (() -> Unit)?,
    onPickFile: (() -> Unit)?,
    onPasteAsset: (() -> Boolean)?,
    compact: Boolean = false,
    onPickDocument: (() -> Unit)? = null,
    onPickGroupFile: (() -> Unit)? = null,
) {
    Box {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.then(if (compact) Modifier.size(44.dp) else Modifier).testTag("chat.attach"),
        ) {
            Icon(
                Icons.Filled.AddCircle,
                contentDescription = "附件",
                tint = if (showAttach) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
            )
        }
        if (showAttach) {
            AttachmentPanel(
                onPickImage = {
                    onDismiss()
                    onPickImage?.invoke()
                },
                onPickVideo = onPickVideo?.let { pick ->
                    {
                        onDismiss()
                        pick()
                    }
                },
                onPickFile = {
                    onDismiss()
                    onPickFile?.invoke()
                },
                onPasteAsset = onPasteAsset?.let { paste ->
                    {
                        if (paste()) onDismiss()
                    }
                },
                onPickDocument = onPickDocument?.let { pick ->
                    {
                        onDismiss()
                        pick()
                    }
                },
                onPickGroupFile = onPickGroupFile?.let { pick ->
                    {
                        onDismiss()
                        pick()
                    }
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
internal fun VoiceRecordSurface(
    onVoiceRecord: ((Boolean) -> Unit)?,
    onVoiceRecordCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var isRecording by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.height(48.dp).testTag(CHAT_VOICE_RECORD_TEST_TAG)
            .pointerInput(onVoiceRecord, onVoiceRecordCancel) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var started = false
                    var sent = false
                    try {
                        isRecording = true
                        started = true
                        onVoiceRecord?.invoke(true)
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            sent = true
                            onVoiceRecord?.invoke(false)
                        }
                    } finally {
                        isRecording = false
                        if (started && !sent) onVoiceRecordCancel?.invoke() ?: onVoiceRecord?.invoke(false)
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
}

@Composable
internal fun PendingComposerAssets(
    pendingAssetJobs: List<PendingAssetJob>,
    onRetryPendingAsset: (PendingAssetJob) -> Unit,
    onDiscardPendingAsset: (PendingAssetJob) -> Unit,
) {
    PendingAssetRows(
        jobs = pendingAssetJobs,
        testTagPrefix = "chat",
        onRetry = onRetryPendingAsset,
        onDiscard = onDiscardPendingAsset,
        modifier = Modifier.padding(horizontal = Tk.spacing.md),
    )
}
