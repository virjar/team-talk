package com.mohamedrejeb.richeditor.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasicRichTextEditorUserChangeTest {
    @Test
    fun `selection-only update is not a user text change`() {
        val before = TextFieldValue("hello", TextRange(0))
        val selectionOnly = TextFieldValue("hello", TextRange(4))

        assertFalse(isUserTextChange(before, selectionOnly))
        assertTrue(isUserTextChange(before, selectionOnly.copy(text = "hello!")))
    }
}
