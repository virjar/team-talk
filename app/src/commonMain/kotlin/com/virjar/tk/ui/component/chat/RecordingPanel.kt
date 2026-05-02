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
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.extendedColors
import com.virjar.tk.util.formatDuration

@Composable
fun RecordingPanel(
    duration: Int,
    amplitude: Float,
    maxDuration: Int = 60,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val recColor = MaterialTheme.extendedColors.recordingActive

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
            Box(
                modifier = Modifier.size(10.dp).alpha(blinkAlpha)
                    .background(recColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.titleMedium,
                color = recColor,
            )
            Spacer(modifier = Modifier.width(12.dp))

            LinearProgressIndicator(
                progress = { (duration.toFloat() / maxDuration).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(4.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))

            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.extendedColors.mutedIcon)
            }

            IconButton(onClick = onSend, enabled = duration >= 1) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (duration >= 1) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.disabledControl,
                )
            }
        }
    }
}
