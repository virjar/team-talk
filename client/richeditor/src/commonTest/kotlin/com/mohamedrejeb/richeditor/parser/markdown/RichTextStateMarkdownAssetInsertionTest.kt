package com.mohamedrejeb.richeditor.parser.markdown

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals

class RichTextStateMarkdownAssetInsertionTest {

    @Test
    fun `canonical assets insert after visual selection and round trip in order`() {
        val image =
            "![photo.png](teamtalk-asset://asset/11111111-1111-4111-8111-111111111111)"
        val file =
            "[report.pdf](teamtalk-asset://asset/22222222-2222-4222-8222-222222222222)"
        val state = RichTextState().setMarkdown("before target after")
        state.selection = TextRange(start = 7, end = 13)

        state.insertMarkdownAfterSelection(image)
        state.insertMarkdownAfterSelection(file)

        val expected = "before target$image$file after"
        assertEquals(expected, state.toMarkdown())
        assertEquals(expected, RichTextState().setMarkdown(state.toMarkdown()).toMarkdown())
    }
}
