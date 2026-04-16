package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.repository.ChannelRepository
import com.virjar.tk.viewmodel.ContactsViewModel
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

// ────────────────────────────────────────────────────────────────
// InviteMembersScreen
// ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteMembersScreen(
    channelId: String,
    contactsVm: ContactsViewModel,
    channelRepo: ChannelRepository,
    onBack: () -> Unit,
) {
    var selectedUids by remember { mutableStateOf(setOf<String>()) }
    var existingMemberUids by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isInviting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var inviteSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val contactsState by contactsVm.contactsState.collectAsState()

    LaunchedEffect(Unit) {
        contactsVm.loadFriends()
        try {
            val members = channelRepo.getMembers(channelId)
            existingMemberUids = members.map { it.uid }.toSet()
        } catch (e: Exception) {
            AppLog.e("Group", "loadExistingMembers failed", e)
        }
    }

    val availableFriends = contactsState.friends.filter { it.friendUid !in existingMemberUids }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite Members") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (inviteSuccess) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Check, contentDescription = "Done")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                if (selectedUids.isNotEmpty()) {
                                    scope.launch {
                                        isInviting = true
                                        error = ""
                                        try {
                                            channelRepo.addMembers(channelId, selectedUids.toList())
                                            inviteSuccess = true
                                        } catch (e: Exception) {
                                            AppLog.e("Group", "inviteMembers failed", e)
                                            error = e.message ?: "Failed to invite"
                                        } finally {
                                            isInviting = false
                                        }
                                    }
                                }
                            },
                            enabled = selectedUids.isNotEmpty() && !isInviting,
                        ) { Text("Invite") }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error.isNotEmpty()) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            if (inviteSuccess) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Members invited!", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onBack) { Text("Done") }
                    }
                }
            } else if (isInviting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (availableFriends.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No friends available to invite", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text(
                    "${selectedUids.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn {
                    items(availableFriends, key = { it.friendUid }) { friend ->
                        val isSelected = friend.friendUid in selectedUids
                        val name = when {
                            friend.remark.isNotEmpty() -> friend.remark
                            friend.friendName.isNotEmpty() -> friend.friendName
                            else -> friend.friendUid.take(12)
                        }
                        ListItem(
                            headlineContent = { Text(name) },
                            leadingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedUids = if (checked) selectedUids + friend.friendUid
                                        else selectedUids - friend.friendUid
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                selectedUids = if (isSelected) selectedUids - friend.friendUid
                                else selectedUids + friend.friendUid
                            },
                        )
                    }
                }
            }
        }
    }
}
