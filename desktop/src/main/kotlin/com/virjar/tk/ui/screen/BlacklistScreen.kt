package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.BlacklistDto
import com.virjar.tk.repository.ContactRepository
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlacklistScreen(
    contactRepo: ContactRepository,
    onBack: () -> Unit,
) {
    var blacklist by remember { mutableStateOf<List<BlacklistDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<BlacklistDto?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadBlacklist() {
        isLoading = true
        error = ""
        try {
            blacklist = contactRepo.getBlacklist()
        } catch (e: Exception) {
            AppLog.e("Blacklist", "loadBlacklist failed", e)
            error = e.message ?: "Failed to load blacklist"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadBlacklist() }

    // Remove from blacklist confirmation
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove from Blacklist") },
            text = { Text("Unblock ${target.name.ifEmpty { target.uid.take(12) }}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                contactRepo.removeBlacklist(target.uid)
                                loadBlacklist()
                            } catch (e: Exception) {
                                AppLog.e("Blacklist", "removeBlacklist failed", e)
                            }
                        }
                        removeTarget = null
                    },
                ) { Text("Unblock") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blacklist") },
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
            blacklist.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No blocked users", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(blacklist, key = { it.uid }) { item ->
                        ListItem(
                            headlineContent = {
                                Text(item.name.ifEmpty { item.uid.take(12) })
                            },
                            supportingContent = {
                                if (item.username.isNotEmpty()) {
                                    Text("@${item.username}")
                                }
                            },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Person, null,
                                            Modifier.size(20.dp),
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                OutlinedButton(onClick = { removeTarget = item }) {
                                    Text("Unblock")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
