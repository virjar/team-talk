package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.client.LocalUserContext
import com.virjar.tk.audio.PlaybackState
import com.virjar.tk.audio.VoicePlayer
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.protocol.payload.ImageBody
import com.virjar.tk.protocol.payload.VoiceBody
import com.virjar.tk.util.buildFileUrl
import com.virjar.tk.util.decodeToImageBitmap
import com.virjar.tk.util.formatDuration
import com.virjar.tk.util.formatFileSize
import com.virjar.tk.ui.theme.extendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TextMessageContent(payload: TextBody, isMe: Boolean) {
    Column(modifier = Modifier.padding(10.dp)) {
        Text(
            payload.text,
            color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.extendedColors.bubbleOtherText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ImageMessageContent(payload: ImageBody, imageBaseUrl: String, onImageClick: ((String) -> Unit)? = null) {
    val fullUrl = buildFileUrl(imageBaseUrl, payload.url)
    val thumbnailUrl = payload.thumbnailUrl
    val thumbnailFullUrl = if (!thumbnailUrl.isNullOrEmpty()) buildFileUrl(imageBaseUrl, thumbnailUrl) else ""
    val imageCache = LocalUserContext.current!!.imageCache

    // Load thumbnail first (if available), then full image
    var imageBitmap by remember(fullUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(fullUrl) {
        withContext(Dispatchers.IO) {
            // Try thumbnail first
            if (thumbnailFullUrl.isNotEmpty()) {
                val thumb = imageCache.loadOrFetch(thumbnailFullUrl)
                if (thumb != null && imageBitmap == null) {
                    imageBitmap = thumb
                }
            }
            // Then load full image
            val full = imageCache.loadOrFetch(fullUrl)
            if (full != null) {
                imageBitmap = full
            }
        }
    }

    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Image",
            modifier = Modifier.widthIn(max = 240.dp).then(
                if (onImageClick != null) Modifier.clickable { onImageClick(fullUrl) }
                else Modifier
            ),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Box(
            modifier = Modifier.width(200.dp).height(150.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun VoiceMessageContent(payload: VoiceBody, imageBaseUrl: String, voicePlayer: VoicePlayer) {
    val fullUrl = buildFileUrl(imageBaseUrl, payload.url)
    val playbackState by voicePlayer.state.collectAsState()
    val currentPlayingUrl by voicePlayer.currentUrl.collectAsState()
    val currentPosition by voicePlayer.currentPosition.collectAsState()
    val totalDuration by voicePlayer.duration.collectAsState()

    val isPlaying = playbackState == PlaybackState.PLAYING && currentPlayingUrl == fullUrl

    Row(
        modifier = Modifier.padding(10.dp).clickable {
            if (isPlaying) {
                voicePlayer.stop()
            } else {
                voicePlayer.play(fullUrl)
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (isPlaying && totalDuration > 0) {
                LinearProgressIndicator(
                    progress = { (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                // Static waveform-style bars
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(20) { i ->
                        val height = (4 + (i * 17 % 12)).dp
                        Box(
                            modifier = Modifier.width(2.dp).height(height)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (payload.duration > 0) {
                    Text(
                        if (isPlaying) formatDuration(currentPosition / 1000) else formatDuration(payload.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (payload.size > 0) {
                    Text(
                        formatFileSize(payload.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
fun RevokeMessageContent() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "This message was recalled",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
fun SystemMessageContent(messageType: Int) {
    val text = when (messageType) {
        20 -> "Group created"
        21 -> "Group info updated"
        22 -> "Group deleted"
        23 -> "A member was added"
        24 -> "A member was removed"
        else -> "System message"
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
