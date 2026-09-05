package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.ChatComposerModeSwitcher
import com.virjar.tk.app.ui.component.rich.RichTextFormattingToolbar
import com.virjar.tk.app.ui.component.rich.RichTextToolbarMode
import com.virjar.tk.app.ui.component.rich.canUseChatVisualEditor
import com.virjar.tk.app.ui.theme.Tk

@Composable
internal fun WideComposerToolbar(
    voiceMode: Boolean,
    editingSessionActive: Boolean,
    composerMode: ChatComposerMode,
    onComposerModeChange: (ChatComposerMode) -> Unit,
    richState: RichTextState,
    sourceInput: TextFieldValue,
    sourceFocus: FocusRequester,
    inputFocus: FocusRequester,
    showEmoji: Boolean,
    onToggleEmoji: () -> Unit,
    onDismissEmoji: () -> Unit,
    onSourceInputChange: (TextFieldValue) -> Unit,
    onVisualTextChange: () -> Unit,
    hasVoice: Boolean,
    onVoiceClick: () -> Unit,
    hasAttachment: Boolean,
    showAttach: Boolean,
    onToggleAttach: () -> Unit,
    onDismissAttach: () -> Unit,
    onPickImage: (() -> Unit)?,
    onPickVideo: (() -> Unit)?,
    onPickDocument: (() -> Unit)? = null,
    onPickGroupFile: (() -> Unit)? = null,
    onPickFile: (() -> Unit)?,
    onPasteAsset: (() -> Boolean)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!voiceMode) {
            if (composerMode != ChatComposerMode.PREVIEW) {
                ComposerEmojiAction(
                    composerMode = composerMode,
                    showEmoji = showEmoji,
                    onToggle = onToggleEmoji,
                    onDismiss = onDismissEmoji,
                    richState = richState,
                    sourceInput = sourceInput,
                    onSourceInputChange = onSourceInputChange,
                    onVisualTextChange = onVisualTextChange,
                    inputFocus = inputFocus,
                    sourceFocus = sourceFocus,
                )
            }
            if (composerMode == ChatComposerMode.VISUAL) {
                RichTextFormattingToolbar(
                    state = richState,
                    mode = RichTextToolbarMode.MESSAGE,
                    onRequestFocus = { inputFocus.requestFocus() },
                    modifier = Modifier.weight(1f),
                    testTagPrefix = "chat.fmt",
                )
                TextButton(
                    onClick = { onComposerModeChange(ChatComposerMode.MARKDOWN) },
                    modifier = Modifier.height(36.dp).testTag("chat.composer.mode.source"),
                    contentPadding = PaddingValues(horizontal = Tk.spacing.sm),
                ) {
                    Text("MD", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                ChatComposerModeSwitcher(
                    mode = composerMode,
                    visualEnabled = canUseChatVisualEditor(sourceInput.text),
                    onModeChange = onComposerModeChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (hasVoice && (!editingSessionActive || voiceMode)) {
            ComposerVoiceModeAction(voiceMode = voiceMode, onClick = onVoiceClick)
        }
        if (voiceMode) Spacer(Modifier.weight(1f))
        if (hasAttachment) {
            ComposerAttachmentAction(
                showAttach = showAttach,
                onToggle = onToggleAttach,
                onDismiss = onDismissAttach,
                onPickImage = onPickImage,
                onPickVideo = onPickVideo,
                onPickFile = onPickFile,
                onPickDocument = onPickDocument,
                onPickGroupFile = onPickGroupFile,
                onPasteAsset = onPasteAsset,
            )
        }
    }
}
@Composable
internal fun CompactChatComposer(
    voiceMode: Boolean,
    editingSessionActive: Boolean,
    composerMode: ChatComposerMode,
    showFormatting: Boolean,
    onToggleFormatting: () -> Unit,
    onComposerModeChange: (ChatComposerMode) -> Unit,
    richState: RichTextState,
    sourceInput: TextFieldValue,
    onSourceInputChange: (TextFieldValue) -> Unit,
    onVisualTextChange: () -> Unit,
    inputFocus: FocusRequester,
    sourceFocus: FocusRequester,
    embeddedAssets: List<EmbeddedAsset>,
    media: ChatMediaConfig,
    editingSaving: Boolean,
    editingFailedMessage: Boolean,
    sendEnabled: Boolean,
    sendAction: () -> Unit,
    toggleBold: () -> Unit,
    toggleItalic: () -> Unit,
    onMentionClick: ((uid: String) -> Unit)?,
    onUrlClick: ((String) -> Unit)?,
    showEmoji: Boolean,
    onToggleEmoji: () -> Unit,
    onDismissEmoji: () -> Unit,
    hasVoice: Boolean,
    onVoiceClick: () -> Unit,
    onVoiceRecord: ((Boolean) -> Unit)?,
    onVoiceRecordCancel: (() -> Unit)?,
    hasAttachment: Boolean,
    showAttach: Boolean,
    onToggleAttach: () -> Unit,
    onDismissAttach: () -> Unit,
    onPickImage: (() -> Unit)?,
    onPickVideo: (() -> Unit)?,
    onPickDocument: (() -> Unit)? = null,
    onPickGroupFile: (() -> Unit)? = null,
    onPickFile: (() -> Unit)?,
    onPasteAsset: (() -> Boolean)?,
) {
    if (voiceMode) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tk.spacing.sm),
        ) {
            if (hasVoice) ComposerVoiceModeAction(voiceMode = true, onClick = onVoiceClick, compact = true)
            VoiceRecordSurface(
                onVoiceRecord = onVoiceRecord,
                onVoiceRecordCancel = onVoiceRecordCancel,
                modifier = Modifier.weight(1f),
            )
            if (hasAttachment) {
                ComposerAttachmentAction(
                    showAttach = showAttach,
                    onToggle = onToggleAttach,
                    onDismiss = onDismissAttach,
                    onPickImage = onPickImage,
                    onPickVideo = onPickVideo,
                    onPickFile = onPickFile,
                onPickDocument = onPickDocument,
                onPickGroupFile = onPickGroupFile,
                    onPasteAsset = onPasteAsset,
                    compact = true,
                )
            }
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column {
            ComposerEditor(
                compact = true,
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
                sendAction = sendAction,
                toggleBold = toggleBold,
                toggleItalic = toggleItalic,
                onMentionClick = onMentionClick,
                onUrlClick = onUrlClick,
            )
            if (showFormatting) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (composerMode == ChatComposerMode.VISUAL) {
                    RichTextFormattingToolbar(
                        state = richState,
                        mode = RichTextToolbarMode.MESSAGE,
                        onRequestFocus = { inputFocus.requestFocus() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.xs),
                        testTagPrefix = "chat.fmt",
                    )
                }
                ChatComposerModeSwitcher(
                    mode = composerMode,
                    visualEnabled = canUseChatVisualEditor(sourceInput.text),
                    onModeChange = onComposerModeChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.sm),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Tk.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (composerMode != ChatComposerMode.PREVIEW) {
                    ComposerEmojiAction(
                        composerMode = composerMode,
                        showEmoji = showEmoji,
                        onToggle = onToggleEmoji,
                        onDismiss = onDismissEmoji,
                        richState = richState,
                        sourceInput = sourceInput,
                        onSourceInputChange = onSourceInputChange,
                        onVisualTextChange = onVisualTextChange,
                        inputFocus = inputFocus,
                        sourceFocus = sourceFocus,
                        compact = true,
                    )
                }
                IconButton(
                    onClick = onToggleFormatting,
                    modifier = Modifier.size(44.dp).testTag(
                        if (showFormatting) "chat.composer.format.close" else "chat.fmt.more",
                    ),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = if (showFormatting) "收起格式" else "格式",
                        tint = if (showFormatting) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
                    )
                }
                if (hasVoice && !editingSessionActive) {
                    ComposerVoiceModeAction(voiceMode = false, onClick = onVoiceClick, compact = true)
                }
                if (hasAttachment) {
                    ComposerAttachmentAction(
                        showAttach = showAttach,
                        onToggle = onToggleAttach,
                        onDismiss = onDismissAttach,
                        onPickImage = onPickImage,
                        onPickVideo = onPickVideo,
                        onPickFile = onPickFile,
                onPickDocument = onPickDocument,
                onPickGroupFile = onPickGroupFile,
                        onPasteAsset = onPasteAsset,
                        compact = true,
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = sendAction,
                    modifier = Modifier.size(44.dp).testTag("chat.send"),
                    enabled = sendEnabled,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = when {
                            editingSaving && editingFailedMessage -> "重发中"
                            editingSaving -> "保存中"
                            editingFailedMessage -> "重发"
                            editingSessionActive -> "保存"
                            else -> "发送"
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun WideComposerInput(
    voiceMode: Boolean,
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
    editingSaving: Boolean,
    editingFailedMessage: Boolean,
    sendEnabled: Boolean,
    sendAction: () -> Unit,
    toggleBold: () -> Unit,
    toggleItalic: () -> Unit,
    onVoiceRecord: ((Boolean) -> Unit)?,
    onVoiceRecordCancel: (() -> Unit)?,
    onMentionClick: ((uid: String) -> Unit)?,
    onUrlClick: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier.padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (voiceMode) {
            VoiceRecordSurface(
                onVoiceRecord = onVoiceRecord,
                onVoiceRecordCancel = onVoiceRecordCancel,
                modifier = Modifier.weight(1f),
            )
        } else {
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                ComposerEditor(
                    compact = false,
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
                    sendAction = sendAction,
                    toggleBold = toggleBold,
                    toggleItalic = toggleItalic,
                    onMentionClick = onMentionClick,
                    onUrlClick = onUrlClick,
                )
            }
            Spacer(Modifier.width(Tk.spacing.sm))
            Button(
                onClick = sendAction,
                modifier = Modifier.testTag("chat.send").height(Tk.dimens.inputMinHeight),
                enabled = sendEnabled,
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = Tk.spacing.lg),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(Tk.dimens.iconSize - 2.dp),
                )
                Spacer(Modifier.width(Tk.spacing.xs))
                Text(
                    when {
                        editingSaving && editingFailedMessage -> "重发中…"
                        editingSaving -> "保存中…"
                        editingFailedMessage -> "重发"
                        editingSessionActive -> "保存"
                        else -> "发送"
                    },
                )
            }
        }
    }
}
