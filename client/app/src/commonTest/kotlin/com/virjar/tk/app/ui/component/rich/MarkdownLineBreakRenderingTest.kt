package com.virjar.tk.app.ui.component.rich

import kotlin.test.*

class MarkdownLineBreakRenderingTest {
    @Test fun editorEmptyParagraphsAndInlineBreaksNeverLeakTags() {
        listOf("第一行\n\n<br>\n\n第二行", "<br>\n<br />\n第二行", "第一行<br/>第二行", "<BR><br />").forEach { source ->
            val blocks = MdParser.parse(source)
            assertFalse(blocks.any { it is MdBlock.Raw }, source)
            val text = blocks.joinToString("\n", transform = ::text)
            assertFalse(text.contains("<br", ignoreCase = true), source)
            assertTrue(text.contains('\n'), source)
        }
    }

    @Test fun standaloneBreakDoesNotConsumeFollowingMarkdown() {
        val blocks = MdParser.parse("<br>\n**后续粗体**")
        assertTrue(blocks.filterIsInstance<MdBlock.Paragraph>().flatMap { it.spans }
            .any { it is MdSpan.Styled && it.bold && it.text == "后续粗体" })
    }

    @Test fun standaloneBreakPreservesFollowingIndentedCodeAndFencedCode() {
        for (indent in listOf("    ", "\t")) {
            for (newline in listOf("\n", "\r\n")) {
                val source = "<br>$newline${indent}<br>"
                val prefix = RichEditorMarkdownCapability.lineBreakHtmlPrefix(source)
                assertEquals(1, prefix.count)
                assertEquals("${indent}<br>", source.substring(prefix.endOffset))
                assertFalse(RichEditorMarkdownCapability.isLineBreakOnlyHtml(source))
                assertTrue(RichEditorMarkdownCapability.inspect(source).requiresSourceMode)
                val code = MdParser.parse(source).filterIsInstance<MdBlock.CodeFence>().single()
                assertTrue(code.code.contains("<br>"), source)
            }
        }
        val fenced = MdParser.parse("<br>\n```html\n<br>\n```").filterIsInstance<MdBlock.CodeFence>().single()
        assertEquals("<br>", fenced.code)
        assertEquals("\n\n", assertIs<MdSpan.Text>(assertIs<MdBlock.Paragraph>(
            MdParser.parse("<br>\n   <br />").single()).spans.single()).text)
    }

    @Test fun eachBreakConsumesSharedRenderBudgetAndOverflowPreservesExactSource() {
        for (separator in listOf("", "\n")) {
            val source = ("<br>" + separator).repeat(16_000)
            assertEquals(source, assertIs<MdBlock.Raw>(MdParser.parse(source).single()).source)
        }
        val normal = "<br>".repeat(100)
        val paragraph = assertIs<MdBlock.Paragraph>(MdParser.parse(normal).single())
        assertEquals("\n".repeat(100), text(paragraph))
    }

    @Test fun literalCodeEscapesAndOtherHtmlArePreserved() {
        assertEquals("<br>", assertIs<MdBlock.CodeFence>(MdParser.parse("```html\n<br>\n```").single()).code)
        val inline = assertIs<MdBlock.Paragraph>(MdParser.parse("`<br>`").single())
        assertTrue(inline.spans.any { it is MdSpan.Styled && it.code && it.text == "<br>" })
        assertTrue(MdParser.parse("\\<br>").joinToString("", transform = ::text).contains("<br>"))
        assertIs<MdBlock.Raw>(MdParser.parse("<div>literal</div>").single())
        assertTrue(MdParser.parse("first<br class=\"x\">last").joinToString("", transform = ::text).contains("<br"))
    }

    private fun text(block: MdBlock): String = when (block) {
        is MdBlock.Paragraph -> block.spans.joinToString("") { when (it) {
            is MdSpan.Text -> it.text
            is MdSpan.Styled -> it.text
            else -> it.toString()
        } }
        is MdBlock.Raw -> block.source
        else -> block.toString()
    }
}
