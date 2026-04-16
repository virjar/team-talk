package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.audio.VoicePlayer
import com.virjar.tk.protocol.PacketType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import com.virjar.tk.protocol.payload.*

/**
 * Render a forwarded message: shows a [Forwarded] tag, then renders the original content.
 */
@Composable
fun ForwardMessageContent(payload: ForwardBody, isMe: Boolean, imageBaseUrl: String, onImageClick: ((String) -> Unit)? = null, onFileDownload: (() -> Unit)? = null, onVideoPlay: ((String) -> Unit)? = null, voicePlayer: VoicePlayer) {
    Column {
        // Forwarded label
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
        ) {
            Text(
                "[Forwarded]",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        // Render original content — reconstruct inner body from forwardPayload JSON string
        val innerBody = remember(payload.forwardPayload) {
            try {
                val fp = payload.forwardPayload
                if (fp != null) {
                    val packetType = PacketType.fromCode(payload.forwardPacketType) ?: return@remember null
                    val json = Json.parseToJsonElement(fp).jsonObject
                    Message.bodyFromJson(packetType, json)
                } else null
            } catch (_: Exception) { null }
        }
        if (innerBody != null) {
            InnerBodyRenderer(
                body = innerBody,
                isMe = isMe,
                imageBaseUrl = imageBaseUrl,
                onImageClick = onImageClick,
                onFileDownload = onFileDownload,
                onVideoPlay = onVideoPlay,
                voicePlayer = voicePlayer,
            )
        } else {
            TextMessageContent(TextBody("", emptyList()), isMe)
        }
    }
}

/**
 * Render a merge-forward message (type=29): shows a summary card with title and message count.
 */
@Composable
fun MergeForwardMessageContent(payload: MergeForwardBody, imageBaseUrl: String) {
    Surface(
        modifier = Modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Text(
                payload.title ?: "Chat Record",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "${payload.messages.size} messages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Render an inner body (used by ForwardMessageContent to render the forwarded content).
 */
@Composable
private fun InnerBodyRenderer(
    body: MessageBody,
    isMe: Boolean,
    imageBaseUrl: String,
    onImageClick: ((String) -> Unit)? = null,
    onFileDownload: (() -> Unit)? = null,
    onVideoPlay: ((String) -> Unit)? = null,
    voicePlayer: VoicePlayer,
) {
    when (body) {
        is TextBody -> TextMessageContent(body, isMe)
        is ImageBody -> ImageMessageContent(body, imageBaseUrl, onImageClick)
        is VoiceBody -> VoiceMessageContent(body, imageBaseUrl, voicePlayer)
        is VideoBody -> VideoMessageContent(body, imageBaseUrl, onFileDownload, onPlay = {
            val url = com.virjar.tk.util.buildFileUrl(imageBaseUrl, body.url)
            if (url.isNotEmpty()) onVideoPlay?.invoke(url)
        })
        is FileBody -> FileMessageContent(body, onFileDownload)
        else -> TextMessageContent(TextBody("", emptyList()), isMe)
    }
}

/**
 * Dispatch message content rendering based on message type.
 */
@Composable
fun MessageContentRenderer(
    msg: Message,
    isMe: Boolean,
    imageBaseUrl: String,
    onImageClick: ((String) -> Unit)? = null,
    onFileDownload: (() -> Unit)? = null,
    onVideoPlay: ((String) -> Unit)? = null,
    voicePlayer: VoicePlayer,
    replyLookup: ((String) -> Message?)? = null,
) {
    val body = msg.body
    when (body) {
        is TextBody -> TextMessageContent(body, isMe)
        is ImageBody -> ImageMessageContent(body, imageBaseUrl, onImageClick)
        is VoiceBody -> VoiceMessageContent(body, imageBaseUrl, voicePlayer)
        is VideoBody -> VideoMessageContent(body, imageBaseUrl, onFileDownload, onPlay = {
            val url = com.virjar.tk.util.buildFileUrl(imageBaseUrl, body.url)
            if (url.isNotEmpty()) onVideoPlay?.invoke(url)
        })
        is FileBody -> FileMessageContent(body, onFileDownload)
        is ReplyBody -> ReplyMessageContent(body, isMe, imageBaseUrl, replyLookup)
        is ForwardBody -> ForwardMessageContent(body, isMe, imageBaseUrl, onImageClick, onFileDownload, onVideoPlay, voicePlayer)
        is MergeForwardBody -> MergeForwardMessageContent(body, imageBaseUrl)
        is RevokeBody -> RevokeMessageContent()
        is StickerBody -> TextMessageContent(TextBody("[Sticker]", emptyList()), isMe)
        is ReactionBody -> TextMessageContent(TextBody(body.emoji, emptyList()), isMe)
        is InteractiveBody -> TextMessageContent(TextBody(body.title, emptyList()), isMe)
        is RichBody -> TextMessageContent(TextBody(body.segments.mapNotNull { it.text }.joinToString(" "), emptyList()), isMe)
        is LocationBody -> TextMessageContent(TextBody("[Location]", emptyList()), isMe)
        is CardBody -> TextMessageContent(TextBody("[Card] ${body.name}", emptyList()), isMe)
        is EditBody -> TextMessageContent(TextBody(body.newContent, emptyList()), isMe)
        is TypingBody -> TextMessageContent(TextBody("[Typing]", emptyList()), isMe)
        else -> TextMessageContent(TextBody("...", emptyList()), isMe)
    }
}
