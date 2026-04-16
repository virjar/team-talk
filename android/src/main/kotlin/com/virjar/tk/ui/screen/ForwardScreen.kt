package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.ConversationDto
import com.virjar.tk.dto.FriendDto
import com.virjar.tk.viewmodel.ContactsViewModel
import com.virjar.tk.util.AppLog
import com.virjar.tk.viewmodel.ConversationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(
    conversationVm: ConversationViewModel,
    contactsVm: ContactsViewModel,
    onForward: (channelId: String, channelType: Int) -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val conversations by conversationVm.state.collectAsState()
    val contactsState by contactsVm.contactsState.collectAsState()

    LaunchedEffect(Unit) {
        try { conversationVm.refresh() } catch (e: Exception) { AppLog.e("Forward", "refresh failed", e) }
        try { contactsVm.loadFriends() } catch (e: Exception) { AppLog.e("Forward", "loadFriends failed", e) }
    }

    val filteredConversations = conversations.conversations.filter {
        it.channelName.contains(searchQuery, ignoreCase = true) || it.channelId.contains(searchQuery)
    }
    val filteredFriends = contactsState.friends.filter {
        it.friendName.contains(searchQuery, ignoreCase = true) || it.friendUid.contains(searchQuery)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forward to...") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )

            LazyColumn {
                if (filteredConversations.isNotEmpty()) {
                    item {
                        Text(
                            "Recent Chats",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(filteredConversations, key = { it.channelId }) { conv ->
                        ForwardConversationItem(
                            conversation = conv,
                            onClick = { onForward(conv.channelId, conv.channelType) },
                        )
                    }
                }

                if (filteredFriends.isNotEmpty()) {
                    item {
                        Text(
                            "Friends",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(filteredFriends, key = { "friend_${it.friendUid}" }) { friend ->
                        ForwardFriendItem(
                            friend = friend,
                            onClick = {
                                // Personal channel uses pattern p:sorted(uids)
                                val channelId = "p:" + listOf(friend.uid, friend.friendUid).sorted().joinToString(":")
                                onForward(channelId, 1)
                            },
                        )
                    }
                }

                if (filteredConversations.isEmpty() && filteredFriends.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No results", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardConversationItem(
    conversation: ConversationDto,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(conversation.channelName.ifEmpty { conversation.channelId.take(20) })
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ForwardFriendItem(
    friend: FriendDto,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(friend.remark.ifEmpty { friend.friendName.ifEmpty { friend.friendUid.take(12) } })
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
