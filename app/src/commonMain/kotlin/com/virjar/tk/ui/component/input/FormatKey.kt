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
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

/**
 * WYSIWYG 格式键。选中态既反馈当前光标样式，也让“先点格式、再输入”可理解。
 */
@Composable
internal fun FormatKey(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp).width(40.dp).testTag(testTag),
        contentPadding = PaddingValues(horizontal = Tk.spacing.sm),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else androidx.compose.ui.graphics.Color.Transparent,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else Tk.colors.secondaryText,
        )
    }
}
