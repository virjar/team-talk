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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    state: ConversationListState,
    isTcpConnected: Boolean = true,
    imageBaseUrl: String = "",
    onSelectConversation: (channelId: String, channelType: Int, channelName: String) -> Unit,
    onDeleteConversation: (channelId: String) -> Unit,
    onTogglePin: (channelId: String, pinned: Boolean) -> Unit,
    onToggleMute: (channelId: String, muted: Boolean) -> Unit,
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TeamTalk")
                        if (!isTcpConnected) {
                            Spacer(Modifier.width(8.dp))
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
                        }
                    }
                },
                actions = {
                    if (onSearchMessages != null) {
                        IconButton(onClick = onSearchMessages) {
                            Icon(Icons.Default.Search, contentDescription = "Search Messages", tint = Color(0xFF999999))
                        }
                    }
                },
            )
        }
    ) { padding ->
        when {
            state.isLoading && state.conversations.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            sortedConversations.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No conversations yet", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a friend to start chatting", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
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
