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
}
