package com.mohamedrejeb.richeditor.model

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RichTextStateSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Suppress("UNCHECKED_CAST")
    private val saver = RichTextState.Saver as Saver<RichTextState, Any>

    @Test
    fun emptyDraftRestoresThroughHtmlCallbacks() {
        val restored = saveAndRestore(RichTextState())
        assertEquals("", restored.toMarkdown())
        assertEquals(TextRange.Zero, restored.selection)
    }

    @Test
    fun repeatedNavigationRestorePreservesDraftImagesFormattingAndSelection() {
        val markdown = "草稿 **加粗** ![图片.png](teamtalk-asset://asset/11111111-1111-4111-8111-111111111111) " +
            "[附件.txt](teamtalk-asset://asset/22222222-2222-4222-8222-222222222222)"
        var state = RichTextState().apply {
            setMarkdown(markdown)
            selection = TextRange(1, 4)
        }
        val expectedMarkdown = state.toMarkdown()
        val expectedHtml = state.toHtml()
        repeat(2) {
            state = saveAndRestore(state)
            assertEquals(expectedMarkdown, state.toMarkdown())
            assertEquals(expectedHtml, state.toHtml())
            assertEquals(TextRange(1, 4), state.selection)
        }
    }

    @Test
    fun imageFormattingNewlineSeparatesFollowingInlineContent() {
        val state = RichTextState().apply {
            setHtml("<p><img src=\"image.png\" alt=\"图片\">\n  <a href=\"file.txt\">附件</a></p>")
        }
        assertEquals("![图片](image.png) [附件](file.txt)", state.toMarkdown())
        assertEquals(state.toMarkdown(), saveAndRestore(state).toMarkdown())
    }

    private fun saveAndRestore(state: RichTextState): RichTextState {
        val saved = assertNotNull(with(saver) { scope.save(state) })
        return assertNotNull(saver.restore(saved))
    }
}
