package com.mohamedrejeb.richeditor.parser.markdown

import androidx.compose.ui.text.SpanStyle
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalRichTextApi::class)
class RichTextStateMarkdownLinkRoundTripTest {

    @Test
    fun `addLink round trip preserves literal label and destination punctuation`() {
        val label = "literal * _ [x] \\ tail"
        val url = "https://example.test/a(b)/c\\d?x=(1)"
        val state = RichTextState()
        state.addLink(text = label, url = url)

        val markdown = state.toMarkdown()
        assertTrue(markdown.contains("\\*"), markdown)
        assertTrue(markdown.contains("\\_"), markdown)
        assertTrue(markdown.contains("\\[x\\]"), markdown)
        assertTrue(markdown.contains("a\\(b\\)"), markdown)
        assertTrue(markdown.contains("c\\\\d"), markdown)

        val restored = RichTextState().setMarkdown(markdown)
        assertEquals(label, restored.toText())
        val restoredLink = restored.getRichSpanByTextIndex(0, ignoreCustomFiltering = true)
            ?.fullStyle as? RichSpanStyle.Link
        assertEquals(url, restoredLink?.url)
        assertEquals(markdown, restored.toMarkdown())
    }

    @Test
    fun `plain editor text keeps markdown punctuation literal after round trip`() {
        val plain = "literal * _ [brackets] \\ path"
        val markdown = RichTextState().apply { setText(plain) }.toMarkdown()
        val restored = RichTextState().setMarkdown(markdown)

        assertEquals(plain, restored.toText())
        val span = restored.getRichSpanByTextIndex(
            textIndex = plain.indexOf('*'),
            ignoreCustomFiltering = true,
        )
        assertTrue(span?.fullStyle is RichSpanStyle.Default)
        assertEquals(SpanStyle(), span.fullSpanStyle)
        assertEquals(markdown, restored.toMarkdown())
    }

    @Test
    fun `plain paragraph block markers remain literal after round trip`() {
        listOf(
            "# literal heading",
            "> literal quote",
            "- literal bullet",
            "+ literal bullet",
            "1. literal numbered item",
            "2) literal numbered item",
            "---",
            "===",
        ).forEach { plain ->
            val markdown = RichTextState().apply { setText(plain) }.toMarkdown()
            val restored = RichTextState().setMarkdown(markdown)

            assertEquals(plain, restored.toText(), "markdown=$markdown")
            assertTrue(
                restored.richParagraphList.single().headingStyle ==
                    com.mohamedrejeb.richeditor.model.HeadingStyle.Normal,
            )
            assertTrue(
                restored.richParagraphList.single().type is
                    com.mohamedrejeb.richeditor.paragraph.type.DefaultParagraph,
                "plain text became a block: $markdown",
            )
            assertEquals(markdown, restored.toMarkdown())
        }
    }
}
