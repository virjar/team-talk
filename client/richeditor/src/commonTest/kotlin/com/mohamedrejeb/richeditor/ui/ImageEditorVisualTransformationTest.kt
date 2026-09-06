package com.mohamedrejeb.richeditor.ui

import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageEditorVisualTransformationTest {
    @Test
    fun `image markers preserve source caret positions and adjacent literal replacement characters`() {
        val image = "![photo.png](teamtalk-asset://asset/11111111-1111-4111-8111-111111111111)"
        val state = RichTextState().setMarkdown("before $image **after** �")
        val original = state.annotatedString
        val markdown = state.toMarkdown()

        val shown = ImageEditorVisualTransformation(state, '图').filter(original)

        assertEquals("before 图 after �", shown.text.text)
        assertEquals(original, state.annotatedString)
        assertEquals(markdown, state.toMarkdown())
        assertEquals(original.length, shown.text.length)
        assertTrue(shown.text.spanStyles.containsAll(original.spanStyles))
        for (offset in 0..original.length) {
            assertEquals(offset, shown.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, shown.offsetMapping.transformedToOriginal(offset))
        }

        val imageOffset = shown.text.text.indexOf('图')
        state.replaceRange(imageOffset, imageOffset + 1, "")
        assertTrue("teamtalk-asset://" !in state.toMarkdown())
        assertTrue("after" in state.toMarkdown())
    }
}
