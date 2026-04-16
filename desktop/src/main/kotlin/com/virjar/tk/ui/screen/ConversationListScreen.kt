package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.ConversationDto
import com.virjar.tk.ui.component.conversation.ConversationItemWithMenu
import com.virjar.tk.viewmodel.ConversationListState

@Composable
fun ConversationListScreen(
    state: ConversationListState,
    isTcpConnected: Boolean = true,
    imageBaseUrl: String = "",
    onSelectConversation: (channelId: String, channelType: Int, channelName: String) -> Unit,
    onDeleteConversation: (channelId: String) -> Unit,
    onTogglePin: (channelId: String, pinned: Boolean) -> Unit,
    onToggleMute: (channelId: String, muted: Boolean) -> Unit,
    onRefresh: () -> Unit,
    onLogout: (() -> Unit)? = null,
    onSearchMessages: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Sort conversations: pinned first, then by timestamp descending
    val sortedConversations = remember(state.conversations) {
        state.conversations.sortedWith(
            compareByDescending<ConversationDto> { it.isPinned }
                .thenByDescending { it.lastMsgTimestamp }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Compact toolbar: connection status + search button
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isTcpConnected) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        "Disconnected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (onSearchMessages != null) {
                IconButton(onClick = onSearchMessages) {
                    Icon(Icons.Default.Search, contentDescription = "Search Messages", tint = Color(0xFF999999))
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = !state.isLoading,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF999999))
                }
            }
        }

        when {
            state.isLoading && state.conversations.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            sortedConversations.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No conversations yet", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a friend to start chatting", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = onRefresh) {
                            Text("Refresh")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sortedConversations, key = { it.channelId }) { conv ->
                        ConversationItemWithMenu(
                            conv = conv,
                            onlineStatus = state.onlineStatus,
                            imageBaseUrl = imageBaseUrl,
                            avatarUrl = state.avatarMap[conv.channelId] ?: "",
                            onClick = {
                                val name = conv.channelName.ifEmpty { conv.channelId.take(30) }
                                onSelectConversation(conv.channelId, conv.channelType, name)
                            },
                            onDelete = { onDeleteConversation(conv.channelId) },
                            onTogglePin = { onTogglePin(conv.channelId, !conv.isPinned) },
                            onToggleMute = { onToggleMute(conv.channelId, !conv.isMuted) },
                        )
                    }
                }
            }
        }
    }
}
