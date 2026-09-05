package com.virjar.tk.app.ui.screen

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.model.RichTextState

internal data class ChatComposerFormattingActions(
    val toggleBold: () -> Unit,
    val toggleItalic: () -> Unit,
)

/** 让视觉编辑器的格式回调留在 ChatPanel 编排体之外。 */
internal fun chatComposerFormattingActions(
    richState: RichTextState,
    voiceMode: Boolean,
    inputFocus: FocusRequester,
): ChatComposerFormattingActions = ChatComposerFormattingActions(
    toggleBold = {
        if (!voiceMode) {
            richState.toggleSpanStyle(CHAT_BOLD_STYLE)
            inputFocus.requestFocus()
        }
    },
    toggleItalic = {
        if (!voiceMode) {
            richState.toggleSpanStyle(CHAT_ITALIC_STYLE)
            inputFocus.requestFocus()
        }
    },
)

private val CHAT_BOLD_STYLE = SpanStyle(fontWeight = FontWeight.Bold)
private val CHAT_ITALIC_STYLE = SpanStyle(fontStyle = FontStyle.Italic)
