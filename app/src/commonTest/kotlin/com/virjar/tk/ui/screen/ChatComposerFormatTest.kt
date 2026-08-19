package com.virjar.tk.ui.screen

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `链接和两种列表能够写回 Markdown`() {
        val link = RichTextState().setText("官网")
        link.selection = TextRange(0, 2)
        link.addLinkToSelection("https://im.virjar.com")

        val bullets = RichTextState().setText("第一项")
        bullets.selection = TextRange(0, 3)
        bullets.toggleUnorderedList()

        val numbered = RichTextState().setText("第二项")
        numbered.selection = TextRange(0, 3)
        numbered.toggleOrderedList()

        assertEquals("[官网](https://im.virjar.com)", link.toMarkdown())
        assertTrue("- 第一项" in bullets.toMarkdown(), bullets.toMarkdown())
        assertTrue("1. 第二项" in numbered.toMarkdown(), numbered.toMarkdown())
    }

    @Test
    fun `mention 在编辑器中只显示姓名但导出权威语法`() {
        val state = RichTextState().setText("@zhang")
        state.replaceRange(0, 6, "@张三 ")
        state.addLinkToTextRange("mention://uid-zhang", TextRange(1, 3))
        state.selection = TextRange(4)

        assertEquals("@张三 ", state.annotatedString.text)
        assertEquals("@[张三](mention://uid-zhang) ", state.toMarkdown())
    }

    @Test
    fun `重置输入框同时清正文和撤销重做历史`() {
        val withUndo = RichTextState().setText("已发送")
        withUndo.selection = TextRange(withUndo.annotatedString.text.length)
        withUndo.insertAtCaret("消息")
        assertTrue(withUndo.history.canUndo)

        resetChatComposerState(withUndo)

        assertEquals("", withUndo.annotatedString.text)
        assertEquals("", withUndo.toMarkdown())
        assertFalse(withUndo.history.canUndo)
        assertFalse(withUndo.history.canRedo)
        assertFalse(withUndo.history.undo())

        val withRedo = RichTextState().setText("取消编辑")
        withRedo.selection = TextRange(withRedo.annotatedString.text.length)
        withRedo.insertAtCaret("内容")
        assertTrue(withRedo.history.undo())
        assertTrue(withRedo.history.canRedo)

        resetChatComposerState(withRedo)

        assertEquals("", withRedo.annotatedString.text)
        assertFalse(withRedo.history.canUndo)
        assertFalse(withRedo.history.canRedo)
        assertFalse(withRedo.history.redo())
    }
}
