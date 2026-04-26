package com.virjar.tk.ui.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberFilePicker(
    onFileSelected: (ByteArray, String) -> Unit,
    fileType: FilePickerType,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentCallback = rememberUpdatedState(onFileSelected)

    val mimeType = when (fileType) {
        FilePickerType.Image -> "image/*"
        FilePickerType.Video -> "video/*"
        FilePickerType.Any -> "*/*"
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArrayOutputStream()
                        input.copyTo(buffer)
                        buffer.toByteArray()
                    }
                }
                val fileName = withContext(Dispatchers.IO) {
                    resolveFileName(context, uri)
                }
                if (bytes != null && fileName != null) {
                    currentCallback.value(bytes, fileName)
                }
            }
        }
    }

    return remember { { launcher.launch(mimeType) } }
}

private fun resolveFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path?.substringAfterLast('/')
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    cursor.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) return it.getString(nameIndex)
        }
    }
    return uri.path?.substringAfterLast('/') ?: "unknown"
}
