package com.virjar.tk.ui.component.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.ui.theme.extendedColors

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

/** Common emoji categories for the picker */
private val EMOJI_LIST = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😊",
    "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋",
    "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🫡",
    "🤐", "🤨", "😐", "😑", "😶", "🫥", "😏", "😒", "🙄", "😬",
    "🤥", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢",
    "🤮", "🥵", "🥶", "🥴", "😵", "🤯", "🤠", "🥳", "🥸", "😎",
    "👍", "👎", "👊", "✊", "🤛", "🤜", "👏", "🙌", "👐", "🤲",
    "🤝", "🙏", "✌️", "🤞", "🫰", "🤟", "🤘", "👌", "🤌", "🤏",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️",
    "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "♥️", "🫶",
    "🔥", "⭐", "🌟", "✨", "💫", "🎉", "🎊", "🎈", "🎁", "🏆",
    "💯", "✅", "❌", "⭕", "❗", "❓", "⚠️", "🚀", "💪", "👀",
)

@Composable
fun ChatInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isSending: Boolean,
    replyingTo: Message?,
    onClearReply: () -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onPickVideo: () -> Unit = {},
    editingMessage: Message? = null,
    onClearEdit: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    isRecording: Boolean = false,
    recordingDuration: Int = 0,
    recordingAmplitude: Float = 0f,
    onSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
) {
    var showAttachMenu by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    Column {
        // Recording panel replaces normal input when recording
        if (isRecording) {
            RecordingPanel(
                duration = recordingDuration,
                amplitude = recordingAmplitude,
                onCancel = onCancelRecording,
                onSend = onSendRecording,
            )
            return@Column
        }
        // Edit preview bar
        editingMessage?.let { editMsg ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Edit message",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            ((editMsg.body as? TextBody)?.text ?: "").take(50),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onClearEdit,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel edit", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Reply preview bar
        replyingTo?.let { replyMsg ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Reply to ${(replyMsg.senderUid ?: "").take(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            ((replyMsg.body as? TextBody)?.text ?: "").take(50),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = onClearReply,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Emoji picker panel
        if (showEmojiPicker) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(10),
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(EMOJI_LIST) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    onInputTextChange(inputText + emoji)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 20.sp)
                        }
                    }
                }
            }
        }

        // Input row
        Surface(shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { showAttachMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Attach")
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Image") },
                            onClick = {
                                showAttachMenu = false
                                onPickImage()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("File") },
                            onClick = {
                                showAttachMenu = false
                                onPickFile()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Video") },
                            onClick = {
                                showAttachMenu = false
                                onPickVideo()
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        onInputTextChange(it)
                        if (showEmojiPicker) showEmojiPicker = false
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.extendedColors.inputBorder,
                        unfocusedBorderColor = MaterialTheme.extendedColors.inputBorder,
                    ),
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { showEmojiPicker = !showEmojiPicker }) {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = "Emoji",
                        tint = if (showEmojiPicker) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.mutedIcon,
                    )
                }
                // Send button / Mic button
                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        enabled = !isSending,
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.extendedColors.mutedIcon,
                            )
                        }
                    }
                } else {
                    // Empty input: show mic button to start recording
                    IconButton(
                        onClick = onStartRecording,
                        enabled = !isSending,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Record voice",
                            tint = MaterialTheme.extendedColors.mutedIcon,
                        )
                    }
                }
            }
        }
    }
}
