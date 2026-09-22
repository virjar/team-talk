package com.virjar.tk.app.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 内测反馈 T052：`<br>` 系标签渲染为换行，不再把文档打回未建模源码块。 */
class LineBreakHtmlCapabilityTest {

    @Test
    fun lineBreakOnlyHtmlIsWhitelisted() {
        assertTrue(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<br>"))
        assertTrue(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<br/>"))
        assertTrue(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<br />"))
        assertTrue(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<BR>"))
        assertTrue(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<br>\n<br/>"))
        assertTrue(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<br>\n<br />\n<br>"))
    }

    @Test
    fun otherHtmlStillRequiresSourceMode() {
        assertFalse(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<div>"))
        assertFalse(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<br>text"))
        assertFalse(RichEditorMarkdownCapability.isLineBreakOnlyHtml("<script>alert(1)</script>"))
        assertFalse(RichEditorMarkdownCapability.isLineBreakOnlyHtml(""))

        val capability = RichEditorMarkdownCapability.inspect("第一行\n<br>\n第二行")
        assertFalse(capability.requiresSourceMode)

        val withRealHtml = RichEditorMarkdownCapability.inspect("第一行\n<div>x</div>")
        assertTrue(withRealHtml.requiresSourceMode)
    }

    @Test
    fun lineBreakHeadedBlockWithPlainTextRendersAsMarkdown() {
        // 块首 <br> 后跟普通文本行：CommonMark 把整块归为 HTML 块，但编辑器与预览都按
        // 换行 + Markdown 渲染，不必打回未建模源码块（内测文档「<br>\nhaode，half俄\n好的」）。
        assertTrue(RichEditorMarkdownCapability.isLineBreakHeadedBlock("<br>\nhaode，half俄\n好的"))
        assertFalse(RichEditorMarkdownCapability.isLineBreakHeadedBlock("haode，half俄\n好的"))
        assertFalse(RichEditorMarkdownCapability.isLineBreakHeadedBlock("<br>\n<span>仍是真的 HTML</span>"))

        val capability = RichEditorMarkdownCapability.inspect("<br>\nhaode，half俄\n好的")
        assertFalse(capability.requiresSourceMode)

        val withRealHtml = RichEditorMarkdownCapability.inspect("<br>\n<span>x</span>")
        assertTrue(withRealHtml.requiresSourceMode)
    }
}
