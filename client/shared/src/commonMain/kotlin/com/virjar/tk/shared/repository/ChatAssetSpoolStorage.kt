package com.virjar.tk.shared.repository

import com.virjar.tk.shared.platform.PlatformFile

/** Private spool IO; names are single components chosen by the common spool, never user paths. */
internal interface ChatAssetSpoolStorage {
    /** Canonical account directory identity, shared by independently reopened stores. */
    val directoryKey: String
    fun entries(limit: Int): List<String>
    fun inspect(name: String): ChatAssetFileSnapshot
    fun create(name: String)
    fun openWriter(name: String): ChatAssetWriter
    fun openReader(name: String): ChatAssetReader
    fun publish(partial: String, ready: String)
    /** Missing entries are already deleted; every present entry must still be a private regular file. */
    fun delete(name: String)
    fun forceDirectory()
}
internal data class ChatAssetFileSnapshot(val size: Long, val identity: Any)
internal interface ChatAssetWriter : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun sync()
}
internal interface ChatAssetReader : AutoCloseable {
    /** Returns -1 at EOF; buffers belong to the caller and may be reused immediately. */
    fun read(bytes: ByteArray): Int
    fun rewind()
}
internal interface ChatAssetDigest : AutoCloseable {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun finish(): String
}
internal expect fun chatAssetSpoolStorage(dataDir: PlatformFile, directories: List<String>): ChatAssetSpoolStorage
internal expect fun createChatAssetDigest(): ChatAssetDigest
