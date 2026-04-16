package com.virjar.tk.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberFilePicker(
    onFileSelected: (ByteArray, String) -> Unit,
    fileType: FilePickerType,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(fileType) {
        {
            scope.launch {
                try {
                    var selectedFile: File? = null
                    withContext(Dispatchers.IO) {
                        EventQueue.invokeAndWait {
                            val chooser = JFileChooser()
                            when (fileType) {
                                FilePickerType.Image -> {
                                    chooser.fileFilter = FileNameExtensionFilter(
                                        "Images", "jpg", "jpeg", "png", "gif", "webp", "bmp"
                                    )
                                    chooser.dialogTitle = "Select Image"
                                }
                                FilePickerType.Video -> {
                                    chooser.fileFilter = FileNameExtensionFilter(
                                        "Videos", "mp4", "avi", "mkv", "mov", "webm", "wmv", "flv"
                                    )
                                    chooser.dialogTitle = "Select Video"
                                }
                                FilePickerType.Any -> {
                                    chooser.dialogTitle = "Select File"
                                }
                            }
                            val returnVal = chooser.showOpenDialog(null)
                            if (returnVal == JFileChooser.APPROVE_OPTION) {
                                selectedFile = chooser.selectedFile
                            }
                        }
                    }
                    val file = selectedFile
                    if (file != null) {
                        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                        onFileSelected(bytes, file.name)
                    }
                } catch (_: Exception) {
                    // User cancelled or dialog failed — ignore silently
                }
            }
        }
    }
}
