package com.mohamedrejeb.richeditor.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState

// [TT] BasicTextField has no inlineContent slot. Render a host-supplied image marker while
// keeping the original one-character atomic span, offset mapping, and Markdown untouched.
// The host presents authenticated thumbnails beside the input; no image URL is decoded here.
internal class ImageEditorVisualTransformation(
    private val state: RichTextState,
    private val marker: Char,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = state.visualTransformation.filter(text)
        val imageOffsets = state.styledRichSpanList
            .filter { it.richSpanStyle is RichSpanStyle.Image }
            .map { it.textRange.min }
            .filter { it in transformed.text.text.indices }
        if (imageOffsets.isEmpty()) return transformed
        val characters = transformed.text.text.toCharArray()
        imageOffsets.forEach { characters[it] = marker }
        return TransformedText(
            text = AnnotatedString(
                text = characters.concatToString(),
                spanStyles = transformed.text.spanStyles + imageOffsets.map { offset ->
                    AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.SemiBold), offset, offset + 1)
                },
                paragraphStyles = transformed.text.paragraphStyles,
            ),
            offsetMapping = transformed.offsetMapping,
        )
    }
}
