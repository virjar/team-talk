package com.virjar.tk.util

/**
 * Platform-specific file saving. Shows a save dialog (desktop) or saves to downloads (android).
 * @return true if save succeeded
 */
expect fun saveFileToDisk(bytes: ByteArray, fileName: String): Boolean
