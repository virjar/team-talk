package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.FriendDto
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.OnlineIndicator
import com.virjar.tk.ui.component.buildAvatarUrl
import com.virjar.tk.viewmodel.ContactsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    state: ContactsState,
    imageBaseUrl: String = "",
    onSearchClick: () -> Unit,
    onFriendAppliesClick: () -> Unit,
    onFriendClick: (uid: String, name: String) -> Unit,
    onCreateGroupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // Quick actions
                    item {
                        ListItem(
                            headlineContent = { Text("New Friends") },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            },
                            modifier = Modifier.clickable(onClick = onFriendAppliesClick),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Create Group") },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.GroupAdd, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                }
                            },
                            modifier = Modifier.clickable(onClick = onCreateGroupClick),
                        )
                    }

                    if (state.friends.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No contacts yet", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    } else {
                        item {
                            Text(
                                "Friends (${state.friends.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(state.friends, key = { "${it.uid}_${it.friendUid}" }) { friend ->
                            FriendItem(
                                friend = friend,
                                avatarUrl = state.friendAvatarMap[friend.friendUid] ?: "",
                                imageBaseUrl = imageBaseUrl,
                                isOnline = state.onlineStatus[friend.friendUid] == true,
                                onClick = {
                                    val displayName = when {
                                        friend.remark.isNotEmpty() -> friend.remark
                                        friend.friendName.isNotEmpty() -> friend.friendName
                                        else -> friend.friendUid.take(12)
                                    }
                                    onFriendClick(friend.friendUid, displayName)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendItem(friend: FriendDto, avatarUrl: String, imageBaseUrl: String, isOnline: Boolean, onClick: () -> Unit) {
    val displayName = when {
        friend.remark.isNotEmpty() -> friend.remark
        friend.friendName.isNotEmpty() -> friend.friendName
        else -> friend.friendUid.take(12)
    }
    val subtitle = when {
        friend.remark.isNotEmpty() && friend.friendName.isNotEmpty() -> friend.friendName
        friend.friendUsername.isNotEmpty() -> "@${friend.friendUsername}"
        else -> null
    }

    ListItem(
        headlineContent = { Text(displayName) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = {
            Box {
                Avatar(
                    url = buildAvatarUrl(imageBaseUrl, avatarUrl),
                    name = displayName,
                    size = 40.dp,
                )
                if (isOnline) {
                    OnlineIndicator(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
