package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.FriendDto
import com.virjar.tk.dto.UserDto
import com.virjar.tk.repository.ContactRepository
import com.virjar.tk.repository.UserRepository
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    uid: String,
    userRepo: UserRepository,
    contactRepo: ContactRepository,
    myUid: String,
    onBack: () -> Unit,
    onStartChat: (channelId: String, channelType: Int, channelName: String) -> Unit,
    onSendApply: (String) -> Unit,
    onDeleteFriend: (String) -> Unit = {},
    onUpdateRemark: (String, String) -> Unit = { _, _ -> },
    onAddBlacklist: (String) -> Unit = {},
    onRemoveBlacklist: (String) -> Unit = {},
    isBlacklisted: Boolean = false,
) {
    var user by remember { mutableStateOf<UserDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }
    var applySent by remember { mutableStateOf(false) }
    var isOnline by remember { mutableStateOf(false) }
    var friendInfo by remember { mutableStateOf<FriendDto?>(null) }
    var showRemarkDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var remarkInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uid) {
        isLoading = true
        errorMsg = ""
        try {
            user = userRepo.getUser(uid)
            val status = userRepo.getOnlineStatus(listOf(uid))
            isOnline = status[uid] == true
            // Check if this user is already a friend
            try {
                val friends = contactRepo.getFriends()
                friendInfo = friends.find { it.friendUid == uid }
            } catch (e: Exception) {
                AppLog.e("UserProfile", "getFriends failed", e)
            }
        } catch (e: Exception) {
            AppLog.e("UserProfile", "loadUser failed", e)
            errorMsg = e.message ?: "Failed to load user"
        } finally {
            isLoading = false
        }
    }

    // Remark edit dialog
    if (showRemarkDialog) {
        AlertDialog(
            onDismissRequest = { showRemarkDialog = false },
            title = { Text("Edit Remark") },
            text = {
                OutlinedTextField(
                    value = remarkInput,
                    onValueChange = { remarkInput = it },
                    label = { Text("Remark") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateRemark(uid, remarkInput)
                    showRemarkDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRemarkDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Delete friend confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Friend") },
            text = { Text("Are you sure you want to delete this friend?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFriend(uid)
                        showDeleteDialog = false
                        friendInfo = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { paddingVal ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingVal)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMsg.isNotEmpty() -> Text(
                    errorMsg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center),
                )
                user != null -> {
                    val u = user!!
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Surface(
                            modifier = Modifier.size(96.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person, null,
                                    Modifier.size(48.dp),
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(u.name, style = MaterialTheme.typography.headlineSmall)
                        if (u.username != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("@${u.username}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        // Online status indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outlineVariant,
                            ) {}
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isOnline) "Online" else "Offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("UID: ${u.uid.take(12)}", style = MaterialTheme.typography.bodySmall)

                        if (uid != myUid) {
                            Spacer(Modifier.height(32.dp))
                            if (friendInfo != null) {
                                // Already a friend: show Remark + Message + Delete
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            remarkInput = friendInfo!!.remark
                                            showRemarkDialog = true
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Remark")
                                    }
                                    Button(
                                        onClick = {
                                            val channelId = "p:" + listOf(myUid, uid).sorted().joinToString(":")
                                            val peerName = u.name
                                            onStartChat(channelId, 1, peerName)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Message")
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { showDeleteDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text("Delete Friend")
                                }
                                Spacer(Modifier.height(8.dp))
                                if (isBlacklisted) {
                                    OutlinedButton(
                                        onClick = { onRemoveBlacklist(uid) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Unblock")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onAddBlacklist(uid) },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) {
                                        Text("Block")
                                    }
                                }
                            } else {
                                // Not a friend: show add friend + message
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch { onSendApply(uid) }
                                            applySent = true
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !applySent,
                                    ) {
                                        Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (applySent) "Sent" else "Add Friend")
                                    }
                                    Button(
                                        onClick = {
                                            val channelId = "p:" + listOf(myUid, uid).sorted().joinToString(":")
                                            val peerName = u.name
                                            onStartChat(channelId, 1, peerName)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Message")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
