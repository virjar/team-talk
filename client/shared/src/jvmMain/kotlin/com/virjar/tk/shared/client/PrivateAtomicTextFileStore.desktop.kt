package com.virjar.tk.shared.client

import java.io.File

internal actual fun privateAtomicTextFileStore(
    dataDir: File,
    privateDirectories: List<String>,
    fileName: String,
    replacementTemporaryFileName: String?,
): PrivateAtomicTextFileStore {
    val delegate = JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(
        privateDirectories = privateDirectories,
        fileName = fileName,
        replacementTemporaryFileName = replacementTemporaryFileName,
    )
    return object : PrivateAtomicTextFileStore {
        override fun existsNonEmpty(): Boolean = delegate.existsNonEmpty()

        override fun readText(maxBytes: Long): String? = delegate.readText(maxBytes)

        override fun replaceText(content: String, maxBytes: Long) = delegate.replaceText(content, maxBytes)

        override fun cleanupPendingReplacement(): Boolean = delegate.cleanupPendingReplacement()

        override fun delete(): Boolean = delegate.delete()
    }
}
