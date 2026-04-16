package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun InvalidContentFallback(label: String) {
    Text(
        "[$label]",
        modifier = Modifier.padding(10.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}
