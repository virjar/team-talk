package com.virjar.tk.util

actual fun saveFileToDisk(bytes: ByteArray, fileName: String): Boolean {
    // Android implementation requires Activity context for MediaStore or SAF.
    // TODO: Implement with Activity context when integrating Android UI.
    return false
}
