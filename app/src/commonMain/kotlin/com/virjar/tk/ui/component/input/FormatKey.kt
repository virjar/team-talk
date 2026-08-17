package com.virjar.tk.ui.component.input

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

/**
 * WYSIWYG 格式键（B/I/S/代码）：对选区直改 SpanStyle（发送时编码为 markdown 语法）。
 */
@Composable
internal fun FormatKey(
    label: String,
    style: androidx.compose.ui.text.SpanStyle,
    onApply: (androidx.compose.ui.text.SpanStyle) -> Unit,
    testTag: String,
) {
    TextButton(
        onClick = { onApply(style) },
        modifier = Modifier.height(32.dp).width(40.dp).testTag(testTag),
        contentPadding = PaddingValues(horizontal = Tk.spacing.sm),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = Tk.colors.secondaryText,
        )
    }
}
