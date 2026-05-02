package com.virjar.tk.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.extendedColors

@Composable
fun OnlineIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(12.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Surface(
            modifier = Modifier.size(10.dp).padding(1.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.extendedColors.onlineIndicator,
        ) {}
    }
}
