package com.virjar.tk.util

import java.awt.EventQueue
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

actual fun saveFileToDisk(bytes: ByteArray, fileName: String): Boolean {
    var saved = false
    try {
        val file = if (EventQueue.isDispatchThread()) {
            showSaveDialog(fileName)
        } else {
            var result: File? = null
            EventQueue.invokeAndWait { result = showSaveDialog(fileName) }
            result
        }
        if (file != null) {
            file.writeBytes(bytes)
            saved = true
        }
    } catch (_: Exception) {
        // User cancelled or dialog failed
    }
    return saved
}

private fun showSaveDialog(fileName: String): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Save File"
    chooser.selectedFile = File(fileName)
    val ext = fileName.substringAfterLast('.', "")
    if (ext.isNotEmpty()) {
        chooser.fileFilter = FileNameExtensionFilter(".$ext files", ext)
    }
    val returnVal = chooser.showSaveDialog(null)
    return if (returnVal == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
