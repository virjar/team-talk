package com.virjar.tk.ui.component

import androidx.compose.runtime.Composable

enum class FilePickerType { Image, Video, Any }

/**
 * Platform-specific file picker.
 * Returns a launcher function that shows the file dialog when called.
 * @param onFileSelected callback receiving (fileBytes, fileName)
 * @param fileType filter type for the file dialog
 */
@Composable
expect fun rememberFilePicker(
    onFileSelected: (ByteArray, String) -> Unit,
    fileType: FilePickerType = FilePickerType.Image,
): () -> Unit
