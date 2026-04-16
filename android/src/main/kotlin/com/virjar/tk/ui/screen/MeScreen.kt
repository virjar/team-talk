package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.ThemeMode
import com.virjar.tk.dto.UserDto
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.QrCodeDialog
import com.virjar.tk.ui.component.buildAvatarUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(
    currentUser: UserDto?,
    imageBaseUrl: String = "",
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onToggleTheme: (ThemeMode) -> Unit = {},
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit = {},
    onBlacklist: () -> Unit = {},
    onDeviceManagement: () -> Unit = {},
    onQrCode: () -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQrCode by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Me") })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Profile card
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        url = buildAvatarUrl(imageBaseUrl, currentUser?.avatar ?: ""),
                        name = currentUser?.name ?: "",
                        size = 64.dp,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            currentUser?.name ?: "Unknown",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (currentUser?.username != null) {
                            Text(
                                "@${currentUser.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "UID: ${currentUser?.uid?.take(12) ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Edit profile
            ListItem(
                headlineContent = { Text("Edit Profile") },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.clickable(onClick = onEditProfile),
            )

            // Change password
            ListItem(
                headlineContent = { Text("Change Password") },
                leadingContent = { Icon(Icons.Default.Lock, null) },
                modifier = Modifier.clickable(onClick = onChangePassword),
            )

            // Blacklist
            ListItem(
                headlineContent = { Text("Blacklist") },
                leadingContent = { Icon(Icons.Default.Block, null) },
                modifier = Modifier.clickable(onClick = onBlacklist),
            )

            // Device Management
            ListItem(
                headlineContent = { Text("Devices") },
                leadingContent = { Icon(Icons.Default.Devices, null) },
                modifier = Modifier.clickable(onClick = onDeviceManagement),
            )

            // QR Code
            ListItem(
                headlineContent = { Text("My QR Code") },
                leadingContent = { Icon(Icons.Default.QrCode2, null) },
                modifier = Modifier.clickable(onClick = { showQrCode = true }),
            )

            // Theme
            ListItem(
                headlineContent = { Text("Theme") },
                leadingContent = { Icon(Icons.Default.Settings, null) },
                trailingContent = {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        Text(
                            when (themeMode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            modifier = Modifier.clickable { expanded = true },
                            color = MaterialTheme.colorScheme.primary,
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("System") },
                                onClick = { onToggleTheme(ThemeMode.SYSTEM); expanded = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Light") },
                                onClick = { onToggleTheme(ThemeMode.LIGHT); expanded = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Dark") },
                                onClick = { onToggleTheme(ThemeMode.DARK); expanded = false },
                            )
                        }
                    }
                },
            )

            HorizontalDivider()

            // Logout
            ListItem(
                headlineContent = { Text("Logout", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(onClick = onLogout),
            )
        }
    }

    // QR Code dialog
    if (showQrCode && currentUser != null) {
        QrCodeDialog(
            uid = currentUser.uid,
            name = currentUser.name,
            onDismiss = { showQrCode = false },
        )
    }
}
