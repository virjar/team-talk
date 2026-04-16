package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.DeviceDto
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

private fun deviceFlagLabel(flag: Int): String = when (flag) {
    1 -> "Android"
    2 -> "iOS"
    3 -> "Desktop"
    4 -> "Web"
    else -> "Unknown"
}

private fun formatTimestamp(ts: Long): String {
    if (ts <= 0) return "N/A"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm")
    return sdf.format(Date(ts))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagementScreen(
    devices: List<DeviceDto>,
    currentDeviceId: String,
    isLoading: Boolean,
    onKick: (deviceId: String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var kickTarget by remember { mutableStateOf<DeviceDto?>(null) }

    kickTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            title = { Text("Kick Device") },
            text = {
                val name = target.deviceName.ifEmpty { deviceFlagLabel(target.deviceFlag) }
                Text("Remove \"$name\" from this account? It will be disconnected immediately.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onKick(target.deviceId)
                        kickTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Kick") }
            },
            dismissButton = {
                TextButton(onClick = { kickTarget = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
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
            devices.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No devices found", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(devices, key = { it.deviceId }) { device ->
                        val isCurrent = device.deviceId == currentDeviceId
                        val displayName = device.deviceName.ifEmpty { deviceFlagLabel(device.deviceFlag) }

                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(displayName)
                                    if (isCurrent) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "(Current)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                Column {
                                    if (device.deviceModel.isNotEmpty()) {
                                        Text(device.deviceModel)
                                    }
                                    Text(
                                        "${deviceFlagLabel(device.deviceFlag)} · Last login: ${formatTimestamp(device.lastLogin)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(8.dp),
                                        shape = CircleShape,
                                        color = if (device.isOnline) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ) {}
                                    Spacer(Modifier.width(8.dp))
                                    if (!isCurrent) {
                                        OutlinedButton(onClick = { kickTarget = device }) {
                                            Text("Kick")
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
