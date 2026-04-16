package com.virjar.tk.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A small green dot indicating online status, overlaid on avatars.
 */
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
            color = Color(0xFF4CAF50),
        ) {}
    }
}
