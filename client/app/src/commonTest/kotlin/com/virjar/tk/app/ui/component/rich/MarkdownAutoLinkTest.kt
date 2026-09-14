package com.virjar.tk.app.ui.component.rich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownAutoLinkTest {
    private val invite = "https://im.example.test/invite#d11e1122-1234-4567-89ab-123456789abc"

    @Test
    fun `pasted invitation is clickable with its complete fragment`() {
        val block = assertIs<MdBlock.Paragraph>(MdParser.parse(invite).single())
        assertEquals(MdSpan.Link(invite, invite), block.spans.single())
        val sentence = assertIs<MdBlock.Paragraph>(MdParser.parse("加入群聊 $invite 后联系我").single())
        assertEquals(listOf(MdSpan.Link(invite, invite)), sentence.spans.filterIsInstance<MdSpan.Link>())
    }

    @Test
    fun `explicit and angle links stay intact while code stays literal`() {
        assertEquals(MdSpan.Link("加入群", invite),
            assertIs<MdBlock.Paragraph>(MdParser.parse("[加入群]($invite)").single()).spans.single())
        assertEquals(MdSpan.Link(invite, invite),
            assertIs<MdBlock.Paragraph>(MdParser.parse("<$invite>").single()).spans.single())
        val code = assertIs<MdBlock.Paragraph>(MdParser.parse("`$invite`").single())
        assertTrue(code.spans.none { it is MdSpan.Link })
        assertEquals(invite, assertIs<MdSpan.Styled>(code.spans.single()).text)
        assertEquals(invite, assertIs<MdBlock.CodeFence>(MdParser.parse("```\n$invite\n```").single()).code)
    }

    @Test
    fun `ordinary web addresses remain links without losing trailing prose`() {
        val paragraph = assertIs<MdBlock.Paragraph>(MdParser.parse("See https://example.test/page, then www.example.test.").single())
        assertEquals(listOf(
            MdSpan.Link("https://example.test/page", "https://example.test/page"),
            MdSpan.Link("www.example.test", "https://www.example.test"),
        ), paragraph.spans.filterIsInstance<MdSpan.Link>())
    }
}
