package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.dto.ChannelDto
import com.virjar.tk.repository.ChannelRepository
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.buildAvatarUrl
import com.virjar.tk.ui.component.rememberFilePicker
import com.virjar.tk.ui.component.ConfirmDialog
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

// ────────────────────────────────────────────────────────────────
// GroupDetailScreen
// ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    channelId: String,
    channelType: ChannelType,
    channelRepo: ChannelRepository,
    myUid: String,
    myRole: Int = 0,
    onBack: () -> Unit,
    onViewMembers: () -> Unit,
    onInviteMembers: () -> Unit,
    onInviteLinks: () -> Unit = {},
    onLeaveGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    imageBaseUrl: String = "",
    fileRepo: com.virjar.tk.repository.FileRepository? = null,
) {
    var channel by remember { mutableStateOf<ChannelDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditNoticeDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var mutedAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val isOwner = myRole >= 2
    val isAdmin = myRole >= 1

    // Avatar picker
    val launchAvatarPicker = rememberFilePicker(onFileSelected = { bytes, fileName ->
        scope.launch {
            try {
                val repo = fileRepo ?: return@launch
                repo.uploadImage(bytes, fileName)
                channel = channelRepo.uploadGroupAvatar(channelId, bytes, fileName)
            } catch (e: Exception) {
                AppLog.e("Group", "uploadAvatar failed", e)
            }
        }
    })

    LaunchedEffect(channelId) {
        isLoading = true
        try {
            channel = channelRepo.getChannel(channelId)
            mutedAll = channel?.mutedAll ?: false
        } catch (e: Exception) {
            AppLog.e("Group", "loadChannel failed", e)
            error = e.message ?: "Failed to load"
        } finally {
            isLoading = false
        }
    }

    // Edit name dialog (admin+)
    if (showEditNameDialog) {
        var newName by remember { mutableStateOf(channel?.name ?: "") }
        var isSaving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Edit Group Name") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            try {
                                channel = channelRepo.updateChannel(channelId, newName.ifBlank { null })
                                showEditNameDialog = false
                            } catch (e: Exception) {
                                AppLog.e("Group", "updateChannelName failed", e)
                            }
                            isSaving = false
                        }
                    },
                    enabled = newName.isNotBlank() && !isSaving,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Edit notice dialog (admin+)
    if (showEditNoticeDialog) {
        var newNotice by remember { mutableStateOf(channel?.notice ?: "") }
        var isSaving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showEditNoticeDialog = false },
            title = { Text("Edit Announcement") },
            text = {
                OutlinedTextField(
                    value = newNotice,
                    onValueChange = { newNotice = it },
                    label = { Text("Announcement") },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            try {
                                channel = channelRepo.updateChannel(channelId, notice = newNotice)
                                showEditNoticeDialog = false
                            } catch (e: Exception) {
                                AppLog.e("Group", "updateChannelNotice failed", e)
                            }
                            isSaving = false
                        }
                    },
                    enabled = !isSaving,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditNoticeDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Leave group confirmation
    if (showLeaveDialog) {
        ConfirmDialog(
            title = "Leave Group",
            message = "Are you sure you want to leave this group?",
            confirmLabel = "Leave",
            onConfirm = {
                showLeaveDialog = false
                onLeaveGroup()
            },
            onDismiss = { showLeaveDialog = false },
        )
    }

    // Delete group confirmation (owner only)
    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Group",
            message = "Are you sure you want to delete this group? This action cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteDialog = false
                onDeleteGroup()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            else -> {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    // Group name with edit icon (admin+)
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(channel?.name ?: "Group", style = MaterialTheme.typography.titleMedium)
                                if (isAdmin) {
                                    IconButton(onClick = { showEditNameDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Name", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            Text("${channel?.memberCount ?: 0} members")
                        },
                        leadingContent = {
                            Box(
                                contentAlignment = Alignment.BottomEnd,
                                modifier = if (fileRepo != null && isAdmin) Modifier.combinedClickable(
                                    onClick = { launchAvatarPicker() }
                                ) else Modifier,
                            ) {
                                Avatar(
                                    url = buildAvatarUrl(imageBaseUrl, channel?.avatar ?: ""),
                                    name = channel?.name ?: "Group",
                                    isGroup = true,
                                    size = 48.dp,
                                )
                                if (fileRepo != null && isAdmin) {
                                    Surface(
                                        modifier = Modifier.size(20.dp),
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    ) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            contentDescription = "Change avatar",
                                            modifier = Modifier.size(12.dp).padding(2.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                        },
                    )

                    HorizontalDivider()

                    // View members
                    ListItem(
                        headlineContent = { Text("Group Members") },
                        leadingContent = {
                            Icon(Icons.Default.People, null)
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.clickable { onViewMembers() },
                    )

                    // Invite members
                    ListItem(
                        headlineContent = { Text("Invite Members") },
                        leadingContent = {
                            Icon(Icons.Default.PersonAdd, null)
                        },
                        modifier = Modifier.clickable { onInviteMembers() },
                    )

                    // Invite links (admin+ only)
                    if (isAdmin) {
                        ListItem(
                            headlineContent = { Text("Invite Links") },
                            supportingContent = { Text("Share link to join group") },
                            leadingContent = {
                                Icon(Icons.Default.Link, null)
                            },
                            modifier = Modifier.clickable { onInviteLinks() },
                        )
                    }

                    HorizontalDivider()

                    // Mute all toggle (admin+ only)
                    if (isAdmin) {
                        ListItem(
                            headlineContent = { Text("Mute All Members") },
                            supportingContent = {
                                Text("Only admins can send messages when enabled")
                            },
                            leadingContent = {
                                Icon(Icons.Default.NotificationsOff, null)
                            },
                            trailingContent = {
                                Switch(
                                    checked = mutedAll,
                                    onCheckedChange = { newValue ->
                                        scope.launch {
                                            try {
                                                channelRepo.setMutedAll(channelId, newValue)
                                                mutedAll = newValue
                                            } catch (e: Exception) {
                                                AppLog.e("Group", "setMutedAll failed", e)
                                            }
                                        }
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                    }

                    // Announcement (admin+ can edit)
                    ListItem(
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Announcement", modifier = Modifier.weight(1f))
                                if (isAdmin) {
                                    IconButton(onClick = { showEditNoticeDialog = true }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            val noticeText = channel?.notice ?: ""
                            if (noticeText.isNotEmpty()) {
                                Text(noticeText, maxLines = 3)
                            } else {
                                Text("No announcement", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Default.Notifications, null)
                        },
                    )

                    HorizontalDivider()

                    // Danger zone
                    Spacer(Modifier.weight(1f))

                    if (isOwner) {
                        // Owner: Delete group
                        Button(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Group")
                        }
                    } else {
                        // Non-owner: Leave group
                        OutlinedButton(
                            onClick = { showLeaveDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Leave Group")
                        }
                    }
                }
            }
        }
    }
}
