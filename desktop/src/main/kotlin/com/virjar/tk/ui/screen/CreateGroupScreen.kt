package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.FriendDto
import com.virjar.tk.repository.ChannelRepository
import com.virjar.tk.viewmodel.ContactsViewModel
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    contactsVm: ContactsViewModel,
    channelRepo: ChannelRepository,
    onBack: () -> Unit,
    onGroupCreated: (channelId: String) -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var selectedUids by remember { mutableStateOf(setOf<String>()) }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val contactsState by contactsVm.contactsState.collectAsState()

    LaunchedEffect(Unit) { contactsVm.loadFriends() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (groupName.isNotBlank() && selectedUids.isNotEmpty()) {
                                scope.launch {
                                    isCreating = true
                                    error = ""
                                    try {
                                        val channel = channelRepo.createGroup(groupName, selectedUids.toList())
                                        onGroupCreated(channel.channelId)
                                    } catch (e: Exception) {
                                        AppLog.e("CreateGroup", "createGroup failed", e)
                                        error = e.message ?: "Failed to create group"
                                    } finally {
                                        isCreating = false
                                    }
                                }
                            }
                        },
                        enabled = groupName.isNotBlank() && selectedUids.isNotEmpty() && !isCreating,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Create")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group Name") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
            )

            Text(
                "Select members (${selectedUids.size} selected)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (isCreating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }

            LazyColumn {
                items(contactsState.friends, key = { it.friendUid }) { friend ->
                    val isSelected = friend.friendUid in selectedUids
                    ListItem(
                        headlineContent = {
                            val name = when {
                                friend.remark.isNotEmpty() -> friend.remark
                                friend.friendName.isNotEmpty() -> friend.friendName
                                else -> friend.friendUid.take(12)
                            }
                            Text(name)
                        },
                        supportingContent = {
                            val sub = when {
                                friend.remark.isNotEmpty() && friend.friendName.isNotEmpty() -> friend.friendName
                                friend.friendUsername.isNotEmpty() -> "@${friend.friendUsername}"
                                else -> null
                            }
                            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        },
                        leadingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedUids = if (checked) {
                                        selectedUids + friend.friendUid
                                    } else {
                                        selectedUids - friend.friendUid
                                    }
                                },
                            )
                        },
                        modifier = Modifier.clickable {
                            selectedUids = if (isSelected) {
                                selectedUids - friend.friendUid
                            } else {
                                selectedUids + friend.friendUid
                            }
                        },
                    )
                }
            }
        }
    }
}
