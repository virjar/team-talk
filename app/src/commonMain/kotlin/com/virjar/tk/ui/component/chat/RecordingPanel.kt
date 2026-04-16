package com.virjar.tk.ui.component.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.util.formatDuration

private val RecColor = Color(0xFFE53935)

@Composable
fun RecordingPanel(
    duration: Int,
    amplitude: Float,
    maxDuration: Int = 60,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    // Blinking animation for the recording dot
    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Blinking red dot
            Box(
                modifier = Modifier.size(10.dp).alpha(blinkAlpha)
                    .background(RecColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))

            // Duration
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.titleMedium,
                color = RecColor,
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { (duration.toFloat() / maxDuration).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(4.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Cancel button
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF999999))
            }

            // Send button
            IconButton(onClick = onSend, enabled = duration >= 1) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (duration >= 1) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
                )
            }
        }
    }
}
