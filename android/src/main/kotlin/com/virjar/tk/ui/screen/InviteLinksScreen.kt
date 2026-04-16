package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.InviteLinkDto
import com.virjar.tk.repository.ChannelRepository
import com.virjar.tk.ui.component.QrCodeContentDialog
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteLinksScreen(
    channelId: String,
    channelRepo: ChannelRepository,
    onBack: () -> Unit,
) {
    var links by remember { mutableStateOf<List<InviteLinkDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var qrLink by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadLinks() {
        isLoading = true
        error = ""
        try {
            links = channelRepo.getInviteLinks(channelId)
        } catch (e: Exception) {
            AppLog.e("InviteLinks", "loadLinks failed", e)
            error = e.message ?: "Failed to load"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(channelId) { loadLinks() }

    // QR code dialog
    qrLink?.let { url ->
        QrCodeContentDialog(
            content = url,
            title = "Invite Link QR Code",
            subtitle = "Scan to join group",
            onDismiss = { qrLink = null },
        )
    }

    // Create link dialog
    if (showCreateDialog) {
        CreateInviteLinkDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, maxUses, expiresIn ->
                scope.launch {
                    try {
                        channelRepo.createInviteLink(channelId, name, maxUses, expiresIn)
                        showCreateDialog = false
                        loadLinks()
                    } catch (e: Exception) {
                        AppLog.e("InviteLinks", "createLink failed", e)
                        error = e.message ?: "Failed to create"
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite Links") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Link")
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { scope.launch { loadLinks() } }) { Text("Retry") }
                    }
                }
            }
            links.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No active invite links", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Create Link")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(links) { link ->
                        InviteLinkItem(
                            link = link,
                            onRevoke = {
                                scope.launch {
                                    try {
                                        channelRepo.revokeInviteLink(channelId, link.token)
                                        loadLinks()
                                    } catch (e: Exception) {
                                        AppLog.e("InviteLinks", "revoke failed", e)
                                    }
                                }
                            },
                            onShowQr = { qrLink = link.url },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteLinkItem(
    link: InviteLinkDto,
    onRevoke: () -> Unit,
    onShowQr: () -> Unit,
) {
    var showRevokeDialog by remember { mutableStateOf(false) }

    if (showRevokeDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text("Revoke Link") },
            text = { Text("Are you sure you want to revoke this invite link? It cannot be used afterwards.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeDialog = false
                        onRevoke()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) { Text("Cancel") }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    link.name ?: "Link ${link.token.take(6)}...",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Token: ${link.token}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val usesText = if (link.maxUses != null) {
                "Uses: ${link.useCount} / ${link.maxUses}"
            } else {
                "Uses: ${link.useCount} / unlimited"
            }
            Text(usesText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (link.expiresAt != null) {
                val remaining = link.expiresAt!! - System.currentTimeMillis()
                val expiryText = if (remaining > 0) {
                    val hours = remaining / 3600000
                    val minutes = (remaining % 3600000) / 60000
                    "Expires in ${hours}h ${minutes}m"
                } else {
                    "Expired"
                }
                Text(expiryText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Never expires", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val dateStr = formatTimestamp(link.createdAt)
            Text("Created: $dateStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onShowQr) {
                    Icon(Icons.Default.QrCode2, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("QR Code")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = { showRevokeDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Revoke")
                }
            }
        }
    }
}

@Composable
private fun CreateInviteLinkDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String?, maxUses: Int?, expiresIn: Long?) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var maxUsesText by remember { mutableStateOf(TextFieldValue("")) }
    var selectedExpiry by remember { mutableStateOf(0) }
    var isCreating by remember { mutableStateOf(false) }

    val expiryOptions = listOf("Never", "1 hour", "24 hours", "7 days")
    val expiryMs = listOf(null, 3600000L, 86400000L, 604800000L)

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("Create Invite Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Link name (optional)") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = maxUsesText,
                    onValueChange = { maxUsesText = it },
                    label = { Text("Max uses (optional, leave empty for unlimited)") },
                    singleLine = true,
                )

                Text("Expires:", style = MaterialTheme.typography.labelMedium)
                expiryOptions.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedExpiry == index,
                            onClick = { selectedExpiry = index },
                        )
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isCreating = true
                    val maxUses = maxUsesText.text.trim().toIntOrNull()
                    onCreate(
                        name.text.trim().ifBlank { null },
                        maxUses,
                        expiryMs[selectedExpiry],
                    )
                },
                enabled = !isCreating,
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel") }
        },
    )
}

private fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}
