package com.virjar.tk.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.UserDto
import com.virjar.tk.repository.FileRepository
import com.virjar.tk.repository.UserRepository
import com.virjar.tk.ui.component.Avatar
import com.virjar.tk.ui.component.rememberFilePicker
import com.virjar.tk.ui.component.buildAvatarUrl
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    userDto: UserDto?,
    userRepo: UserRepository,
    fileRepo: FileRepository?,
    imageBaseUrl: String = "",
    onBack: () -> Unit,
    onProfileUpdated: (UserDto) -> Unit,
) {
    var name by remember { mutableStateOf(userDto?.name ?: "") }
    var sex by remember { mutableStateOf(userDto?.sex ?: 0) }
    var avatarPath by remember { mutableStateOf(userDto?.avatar ?: "") }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // File picker for avatar
    val launchAvatarPicker = rememberFilePicker(onFileSelected = { bytes, fileName ->
        scope.launch {
            try {
                val repo = fileRepo ?: throw RuntimeException("FileRepository not configured")
                val path = repo.uploadImage(bytes, fileName).path
                avatarPath = path
                saved = false
            } catch (e: Exception) {
                AppLog.e("EditProfile", "upload avatar failed", e)
                error = e.message ?: "Failed to upload avatar"
            }
        }
    })

    val hasChanges = name != (userDto?.name ?: "") ||
            sex != (userDto?.sex ?: 0) ||
            avatarPath != (userDto?.avatar ?: "")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (saved) {
                        Icon(Icons.Default.Check, contentDescription = "Saved", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isSaving = true
                                    error = ""
                                    saved = false
                                    try {
                                        val updated = userRepo.updateProfile(
                                            name = name.ifBlank { null },
                                            sex = sex,
                                            avatar = avatarPath.ifBlank { null },
                                        )
                                        onProfileUpdated(updated)
                                        saved = true
                                    } catch (e: Exception) {
                                        AppLog.e("EditProfile", "updateProfile failed", e)
                                        error = e.message ?: "Failed to update"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = name.isNotBlank() && !isSaving && hasChanges,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save")
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (error.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // Avatar with edit overlay
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(
                    url = buildAvatarUrl(imageBaseUrl, avatarPath),
                    name = name,
                    size = 96.dp,
                )
                if (fileRepo != null) {
                    FilledTonalButton(
                        onClick = { launchAvatarPicker() },
                        modifier = Modifier.size(32.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Change avatar",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; saved = false },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))

            Text("Gender", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = sex == 0,
                    onClick = { sex = 0; saved = false },
                    label = { Text("Not set") },
                )
                FilterChip(
                    selected = sex == 1,
                    onClick = { sex = 1; saved = false },
                    label = { Text("Male") },
                )
                FilterChip(
                    selected = sex == 2,
                    onClick = { sex = 2; saved = false },
                    label = { Text("Female") },
                )
            }

            Spacer(Modifier.height(32.dp))

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Read-only info
            Text("Account Info", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (userDto?.username != null) {
                Text("Username: @${userDto.username}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
            Text("UID: ${userDto?.uid?.take(12) ?: ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        }
    }
}
