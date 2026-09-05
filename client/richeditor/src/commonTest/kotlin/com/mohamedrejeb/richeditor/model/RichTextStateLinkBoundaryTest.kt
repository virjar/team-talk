package com.mohamedrejeb.richeditor.model

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalRichTextApi::class)
class RichTextStateLinkBoundaryTest {

    @Test
    fun `complete link selection drives toolbar state and mutations`() {
        val state = linkedState()
        state.selection = TextRange(LINK_START, LINK_END)

        assertTrue(state.isLink)
        assertEquals("Link", state.selectedLinkText)
        assertEquals(ORIGINAL_URL, state.selectedLinkUrl)

        state.updateLink(UPDATED_URL)
        assertEquals(UPDATED_URL, state.selectedLinkUrl)

        state.removeLink()
        assertFalse(state.isLink)
        assertEquals(null, state.selectedLinkUrl)
    }

    @Test
    fun `caret at first link character finds link but caret after link does not`() {
        val state = linkedState()
        state.selection = TextRange(LINK_START)

        assertTrue(state.isLink)
        assertEquals("Link", state.selectedLinkText)
        assertEquals(ORIGINAL_URL, state.selectedLinkUrl)

        state.updateLink(UPDATED_URL)
        assertEquals(UPDATED_URL, state.selectedLinkUrl)

        state.selection = TextRange(LINK_END)
        assertFalse(state.isLink)
        assertEquals(null, state.selectedLinkUrl)
    }

    private fun linkedState() = RichTextState().apply {
        setText("Before Link After")
        addLinkToTextRange(ORIGINAL_URL, TextRange(LINK_START, LINK_END))
    }

    private companion object {
        const val LINK_START = 7
        const val LINK_END = 11
        const val ORIGINAL_URL = "https://example.test/original"
        const val UPDATED_URL = "https://example.test/updated"
    }
}
