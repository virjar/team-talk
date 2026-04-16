package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.protocol.payload.FileBody
import com.virjar.tk.protocol.payload.VideoBody
import com.virjar.tk.protocol.payload.ReplyBody
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.util.ImageCache
import com.virjar.tk.util.buildFileUrl
import com.virjar.tk.util.formatDuration
import com.virjar.tk.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FileMessageContent(payload: FileBody, onDownload: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = "File",
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                payload.fileName.ifEmpty { "File" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatFileSize(payload.fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDownload != null) {
            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun VideoMessageContent(payload: VideoBody, imageBaseUrl: String, onDownload: (() -> Unit)? = null, onPlay: (() -> Unit)? = null) {
    Column(modifier = Modifier.width(220.dp)) {
        // Thumbnail area with play button overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // Try to load cover image
            if (!payload.coverUrl.isNullOrEmpty()) {
                val coverUrl = buildFileUrl(imageBaseUrl, payload.coverUrl!!)
                var coverBitmap by remember(coverUrl) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(coverUrl) {
                    withContext(Dispatchers.IO) {
                        coverBitmap = ImageCache.loadOrFetch(coverUrl)
                    }
                }
                coverBitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "Video thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // Play icon overlay
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.small,
                color = Color.Black.copy(alpha = 0.5f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        modifier = Modifier.size(28.dp),
                        tint = Color.White,
                    )
                }
            }

            // Duration badge (bottom-end)
            if (payload.duration > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        formatDuration(payload.duration),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White,
                    )
                }
            }
        }

        // Info bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.OndemandVideo,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (payload.size > 0) formatFileSize(payload.size) else "Video",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (onDownload != null) {
                IconButton(onClick = onDownload, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun ReplyMessageContent(
    payload: ReplyBody,
    isMe: Boolean,
    imageBaseUrl: String,
    replyLookup: ((String) -> Message?)? = null,
) {
    // 通过 replyLookup 查找原消息获取预览文本，fallback 到类型标签
    val previewText = replyLookup?.invoke(payload.replyToMessageId)?.let {
        Message.extractPreviewText(it.body)
    } ?: when (payload.replyToPacketType.toInt()) {
        21 -> "[Image]"
        22 -> "[Voice]"
        23 -> "[Video]"
        24 -> "[File]"
        else -> ""
    }

    Column(modifier = Modifier.padding(10.dp)) {
        // Quoted block
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    (payload.replyToSenderName ?: "").ifEmpty { payload.replyToSenderUid.take(12) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    previewText.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            payload.text,
            color = if (isMe) MaterialTheme.colorScheme.onPrimary else Color(0xFF666666),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
