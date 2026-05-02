package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.util.isSecondaryButtonPressed
import com.virjar.tk.audio.VoicePlayer
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.ui.theme.extendedColors
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.protocol.payload.FileBody
import com.virjar.tk.util.copyToClipboard
import com.virjar.tk.util.formatMessageTimeShort

/** Check if a message type is a centered system/revoke message (no bubble, no menu). */
private fun isCenteredMessageType(msg: Message): Boolean {
    val code = msg.packetType.code.toInt()
    return code == 30 || code in 90..98
}

@Composable
fun MessageBubble(
    msg: Message,
    senderName: String = "",
    isMe: Boolean,
    isGroup: Boolean,
    readSeq: Long,
    imageBaseUrl: String,
    onRevoke: () -> Unit,
    onDelete: () -> Unit = {},
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onForward: () -> Unit = {},
    onEdit: () -> Unit = {},
    onImageClick: ((String) -> Unit)? = null,
    onFileDownload: (() -> Unit)? = null,
    onVideoPlay: ((String) -> Unit)? = null,
    voicePlayer: VoicePlayer,
    replyLookup: ((String) -> Message?)? = null,
) {
    // Centered messages (revoke / system) render without bubble
    if (isCenteredMessageType(msg)) {
        MessageContentRenderer(
            msg = msg,
            isMe = isMe,
            imageBaseUrl = imageBaseUrl,
            voicePlayer = voicePlayer,
            replyLookup = replyLookup,
        )
        return
    }

    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
    ) {
        if (isGroup && !isMe && senderName.isNotEmpty()) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.extendedColors.messageTimestamp,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }

        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true },
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.isSecondaryButtonPressed()) {
                                showMenu = true
                            }
                        }
                    }
                },
        ) {
            MessageContentRenderer(
                msg = msg,
                isMe = isMe,
                imageBaseUrl = imageBaseUrl,
                onImageClick = onImageClick,
                onFileDownload = onFileDownload,
                onVideoPlay = onVideoPlay,
                voicePlayer = voicePlayer,
                replyLookup = replyLookup,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            modifier = Modifier.padding(start = 4.dp, top = 1.dp),
        ) {
            Text(
                text = formatMessageTimeShort(msg.timestamp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            // "edited" 标记
            val isEdited = Message.isEdited(msg.flags)
            if (isEdited) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "edited",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
            if (isMe) {
                Spacer(modifier = Modifier.width(2.dp))
                MessageStatusIcon(seq = msg.serverSeq, readSeq = readSeq)
            }
        }
    }

    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false },
    ) {
        // Copy: only for text-bearing message types (TEXT=20, REPLY=27)
        val msgTypeCode = msg.packetType.code.toInt()
        if (msgTypeCode == 20 || msgTypeCode == 27) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    showMenu = false
                    val text = when (val b = msg.body) {
                        is TextBody -> b.text
                        is com.virjar.tk.protocol.payload.ReplyBody -> b.text
                        else -> ""
                    }
                    copyToClipboard(text)
                    onCopy()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Reply") },
            onClick = {
                showMenu = false
                onReply()
            },
        )
        DropdownMenuItem(
            text = { Text("Forward") },
            onClick = {
                showMenu = false
                onForward()
            },
        )
        if (isMe) {
            // Edit: only for TEXT messages (type=20)
            if (msgTypeCode == 20) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Revoke") },
                onClick = {
                    showMenu = false
                    onRevoke()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Delete (local)") },
            onClick = {
                showMenu = false
                onDelete()
            },
        )
    }
}

@Composable
private fun MessageStatusIcon(seq: Long, readSeq: Long) {
    val size = 12.dp
    val checkColor = when {
        seq <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        seq > readSeq -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.extendedColors.messageLink
    }

    when {
        seq <= 0 -> {
            Icon(Icons.Default.Schedule, contentDescription = "Sending", modifier = Modifier.size(size), tint = checkColor)
        }
        seq <= readSeq -> {
            Icon(Icons.Default.DoneAll, contentDescription = "Read", modifier = Modifier.size(size), tint = checkColor)
        }
        else -> {
            Icon(Icons.Default.Check, contentDescription = "Sent", modifier = Modifier.size(size), tint = checkColor)
        }
    }
}
