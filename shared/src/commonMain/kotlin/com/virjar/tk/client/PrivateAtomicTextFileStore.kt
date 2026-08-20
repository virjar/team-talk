package com.virjar.tk.client

import java.io.File

/**
 * Minimal private-file primitive shared by crash ownership now and reusable by other authenticated
 * headless/client stores later. Implementations fail closed on unsafe existing paths.
 */
internal interface PrivateAtomicTextFileStore {
    fun existsNonEmpty(): Boolean
    fun readText(): String?
    fun replaceText(content: String)
    fun delete(): Boolean
}

internal expect fun privateAtomicTextFileStore(
    dataDir: File,
    privateDirectories: List<String>,
    fileName: String,
): PrivateAtomicTextFileStore
