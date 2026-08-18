package com.virjar.tk.ui.screen

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 锁定聊天工具栏依赖的 RichTextState → Markdown 契约。 */
class ChatComposerFormatTest {

    @Test
    fun `多行内容保留换行`() {
        val state = RichTextState().setText("第一行\n第二行\n\n第四行")
        val markdown = state.toMarkdown()

        assertTrue("第一行\n第二行" in markdown)
        assertTrue("\n\n第四行" in markdown)
    }

    @Test
    fun `工具栏样式序列化为 Markdown`() {
        val state = RichTextState().setText("bold italic strike code")
        state.selection = TextRange(0, 4)
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        state.selection = TextRange(5, 11)
        state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        state.selection = TextRange(12, 18)
        state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        state.selection = TextRange(19, 23)
        state.toggleCodeSpan()

        assertEquals("**bold** *italic* ~~strike~~ `code`", state.toMarkdown())
    }
}
