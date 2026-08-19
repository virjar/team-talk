package com.virjar.tk.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

class ChatComposerMarkdownTest {

    @Test
    fun `可逆的行内 Markdown 可以进入可视编辑`() {
        assertTrue(canUseChatVisualEditor("**粗体**、[链接](https://im.virjar.com)\n\n- 列表"))
    }

    @Test
    fun `高级 Markdown 保持在源码和预览模式`() {
        assertFalse(canUseChatVisualEditor("```kotlin\nprintln(1)\n```"))
        assertFalse(canUseChatVisualEditor("| 名称 | 状态 |\n| --- | --- |\n| TeamTalk | OK |"))
        assertFalse(canUseChatVisualEditor("> 引用\n\n- [x] 已完成"))
        assertFalse(canUseChatVisualEditor("<script>alert(1)</script>"))
        assertFalse(canUseChatVisualEditor("a".repeat(MAX_CHAT_VISUAL_MARKDOWN_LENGTH + 1)))
    }

    @Test
    fun `源码输入遵守消息正文上限且绝不截断`() {
        val limit = com.virjar.tk.body.MessageBodyPolicy.MAX_MARKDOWN_LENGTH
        assertTrue(acceptsChatSourceInput(TextFieldValue("a".repeat(limit))))
        assertFalse(acceptsChatSourceInput(TextFieldValue("a".repeat(limit + 1))))
    }

    @Test
    fun `源码替换只影响选区并把光标放到插入内容之后`() {
        val value = TextFieldValue("第一行\n/s\n第三行", TextRange(5, 7))
            .replaceComposerRange(4, 6, "/shrug ")

        assertEquals("第一行\n/shrug \n第三行", value.text)
        assertEquals(TextRange(11), value.selection)
    }

    @Test
    fun `源码快捷键包裹选区且空选区光标留在标记中`() {
        val selected = TextFieldValue("你好 TeamTalk", TextRange(3, 11))
            .wrapComposerSelection("**")
        assertEquals("你好 **TeamTalk**", selected.text)
        assertEquals(TextRange(5, 13), selected.selection)

        val collapsed = TextFieldValue("你好", TextRange(2))
            .wrapComposerSelection("_")
        assertEquals("你好__", collapsed.text)
        assertEquals(TextRange(3), collapsed.selection)
    }

    @Test
    fun `可视编辑未改动时保留原始 Markdown 写法`() {
        val baseline = ChatVisualMarkdownBaseline(
            originalMarkdown = "_italic_\n\n* item",
            normalizedMarkdown = "*italic*\n\n- item",
        )

        assertEquals("_italic_\n\n* item", baseline.snapshot("*italic*\n\n- item"))
        assertEquals("*italic edited*\n\n- item", baseline.snapshot("*italic edited*\n\n- item"))
    }
}
