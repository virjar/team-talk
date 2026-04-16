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
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.ChannelMemberDto
import com.virjar.tk.repository.ChannelRepository
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.buildAvatarUrl
import com.virjar.tk.ui.component.ConfirmDialog
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

// ────────────────────────────────────────────────────────────────
// GroupMembersScreen
// ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersScreen(
    channelId: String,
    channelRepo: ChannelRepository,
    myUid: String,
    onBack: () -> Unit,
    imageBaseUrl: String = "",
) {
    var members by remember { mutableStateOf<List<ChannelMemberDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<ChannelMemberDto?>(null) }
    var actionTarget by remember { mutableStateOf<ChannelMemberDto?>(null) }
    var showMuteDialog by remember { mutableStateOf(false) }
    var muteTarget by remember { mutableStateOf<ChannelMemberDto?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadMembers() {
        isLoading = true
        try {
            members = channelRepo.getMembers(channelId)
        } catch (e: Exception) {
            AppLog.e("Group", "loadMembers failed", e)
            error = e.message ?: "Failed to load"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(channelId) { loadMembers() }

    val myMember = members.find { it.uid == myUid }
    val myRole = myMember?.role ?: 0
    val isAdmin = myRole >= 1
    val isOwner = myRole >= 2

    // Remove member confirmation
    removeTarget?.let { target ->
        ConfirmDialog(
            title = "Remove Member",
            message = "Remove ${target.nickname.ifEmpty { target.uid.take(12) }} from the group?",
            confirmLabel = "Remove",
            onConfirm = {
                scope.launch {
                    try {
                        channelRepo.removeMembers(channelId, listOf(target.uid))
                        loadMembers()
                    } catch (e: Exception) {
                        AppLog.e("Group", "removeMember failed", e)
                    }
                }
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }

    // Action dropdown menu
    actionTarget?.let { target ->
        val targetRole = target.role
        DropdownMenu(
            expanded = true,
            onDismissRequest = { actionTarget = null },
        ) {
            // Set/remove admin (owner only)
            if (isOwner && targetRole < 2) {
                DropdownMenuItem(
                    text = { Text(if (targetRole == 1) "Remove Admin" else "Set as Admin") },
                    onClick = {
                        val newRole = if (targetRole == 1) 0 else 1
                        scope.launch {
                            try {
                                channelRepo.setMemberRole(channelId, target.uid, newRole)
                                loadMembers()
                            } catch (e: Exception) {
                                AppLog.e("Group", "setMemberRole failed", e)
                            }
                        }
                        actionTarget = null
                    },
                )
            }
            // Mute/unmute (admin+)
            if (isAdmin && targetRole < 1) {
                DropdownMenuItem(
                    text = { Text("Mute Member") },
                    onClick = {
                        muteTarget = target
                        showMuteDialog = true
                        actionTarget = null
                    },
                )
            }
            // Kick (admin+, cannot kick admin/owner)
            if (isAdmin && target.uid != myUid && (isOwner || targetRole < 1)) {
                DropdownMenuItem(
                    text = { Text("Kick from Group") },
                    onClick = {
                        removeTarget = target
                        actionTarget = null
                    },
                )
            }
        }
    }

    // Mute duration dialog
    if (showMuteDialog && muteTarget != null) {
        val durations = listOf(
            "10 minutes" to 600L,
            "1 hour" to 3600L,
            "1 day" to 86400L,
            "Permanent" to 0L,
        )
        var selectedDuration by remember { mutableStateOf(600L) }
        AlertDialog(
            onDismissRequest = { showMuteDialog = false; muteTarget = null },
            title = { Text("Mute Member") },
            text = {
                Column {
                    durations.forEach { (label, seconds) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = selectedDuration == seconds,
                                onClick = { selectedDuration = seconds },
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = muteTarget ?: return@TextButton
                    scope.launch {
                        try {
                            channelRepo.muteMember(channelId, target.uid, selectedDuration)
                            loadMembers()
                        } catch (e: Exception) {
                            AppLog.e("Group", "muteMember failed", e)
                        }
                    }
                    showMuteDialog = false
                    muteTarget = null
                }) { Text("Mute") }
            },
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false; muteTarget = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Members (${members.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error.isNotEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
            members.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No members")
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(members, key = { it.uid }) { member ->
                        val displayName = member.nickname.ifEmpty {
                            member.user?.name ?: member.uid.take(12)
                        }
                        val roleLabel = when (member.role) {
                            2 -> " (Owner)"
                            1 -> " (Admin)"
                            else -> ""
                        }
                        ListItem(
                            headlineContent = { Text(displayName + roleLabel) },
                            leadingContent = {
                                Avatar(
                                    url = buildAvatarUrl(imageBaseUrl, member.user?.avatar ?: ""),
                                    name = displayName,
                                    size = 40.dp,
                                )
                            },
                            trailingContent = {
                                if (member.uid != myUid && isAdmin) {
                                    Box {
                                        IconButton(onClick = { actionTarget = member }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
