package com.virjar.tk.ui.component.conversation

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.util.isSecondaryButtonPressed
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.dto.ConversationDto
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.OnlineIndicator
import com.virjar.tk.ui.component.buildAvatarUrl
import com.virjar.tk.util.formatRelativeTime
import com.virjar.tk.ui.theme.extendedColors

@Composable
fun ConversationItemWithMenu(
    conv: ConversationDto,
    onlineStatus: Map<String, Boolean>,
    imageBaseUrl: String = "",
    avatarUrl: String = "",
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        ConversationItem(
            conv = conv,
            onlineStatus = onlineStatus,
            imageBaseUrl = imageBaseUrl,
            avatarUrl = avatarUrl,
            onClick = onClick,
            onLongClick = { showMenu = true },
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (conv.isPinned) "Unpin" else "Pin") },
                leadingIcon = {
                    Icon(
                        if (conv.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showMenu = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text(if (conv.isMuted) "Unmute" else "Mute") },
                leadingIcon = {
                    Icon(
                        if (conv.isMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showMenu = false
                    onToggleMute()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
fun ConversationItem(
    conv: ConversationDto,
    onlineStatus: Map<String, Boolean>,
    imageBaseUrl: String = "",
    avatarUrl: String = "",
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val displayName = conv.channelName.ifEmpty {
        formatChannelName(conv.channelId, conv.channelType)
    }
    val lastMessagePreview = if (conv.draft.isNotEmpty()) {
        "[Draft] ${conv.draft}"
    } else {
        conv.lastMessage
    }

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (conv.isPinned) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conv.draft.isNotEmpty())
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.extendedColors.mutedIcon,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conv.lastMsgTimestamp > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatRelativeTime(conv.lastMsgTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.isMuted) {
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = "Muted",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (conv.unreadCount > 0) {
                    if (conv.isMuted) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                conv.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Badge { Text(conv.unreadCount.toString()) }
                    }
                }
            }
        },
        leadingContent = {
            val isPersonal = conv.channelType == ChannelType.PERSONAL.code
            val isOnline = isPersonal && isPeerOnline(conv.channelId, onlineStatus)
            val displayName = conv.channelName.ifEmpty {
                formatChannelName(conv.channelId, conv.channelType)
            }

            Box {
                Avatar(
                    url = buildAvatarUrl(imageBaseUrl, avatarUrl),
                    name = displayName,
                    isGroup = !isPersonal,
                    size = 48.dp,
                )
                if (isOnline) {
                    OnlineIndicator(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
        },
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.isSecondaryButtonPressed()) {
                            onLongClick()
                        }
                    }
                }
            },
    )
}

private fun formatChannelName(channelId: String, channelType: Int): String {
    return when (channelType) {
        ChannelType.PERSONAL.code -> {
            val parts = channelId.split(":")
            if (parts.size >= 3) "Chat" else channelId
        }
        ChannelType.GROUP.code -> channelId.removePrefix("group:").take(20)
        else -> channelId.take(20)
    }
}

private fun isPeerOnline(channelId: String, onlineStatus: Map<String, Boolean>): Boolean {
    val parts = channelId.split(":")
    if (parts.size < 3) return false
    return parts.drop(1).any { uid -> onlineStatus[uid] == true }
}
