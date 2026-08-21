package com.virjar.tk.client

import java.io.File

internal actual fun privateAtomicTextFileStore(
    dataDir: File,
    privateDirectories: List<String>,
    fileName: String,
): PrivateAtomicTextFileStore {
    val delegate = JvmPrivateDataDirectory.openExisting(dataDir).atomicTextFile(
        privateDirectories = privateDirectories,
        fileName = fileName,
    )
    return object : PrivateAtomicTextFileStore {
        override fun existsNonEmpty(): Boolean = delegate.existsNonEmpty()

        override fun readText(): String? = delegate.readText()

        override fun replaceText(content: String) = delegate.replaceText(content)

        override fun delete(): Boolean = delegate.delete()
    }
}
