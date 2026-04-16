package com.virjar.tk.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberFilePicker(
    onFileSelected: (ByteArray, String) -> Unit,
    fileType: FilePickerType,
): () -> Unit {
    // Android implementation requires Activity context for ActivityResultContracts.
    // For now, returns a no-op launcher.
    // TODO: Implement with rememberLauncherForActivityResult when integrating Android UI.
    return remember { { } }
}
