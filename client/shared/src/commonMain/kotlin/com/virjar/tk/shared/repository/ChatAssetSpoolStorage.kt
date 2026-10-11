package com.virjar.tk.shared.repository

import com.virjar.tk.shared.platform.PlatformFile

/** 私有 spool IO；名称是由公共 spool 选取的单段组件，绝不是用户路径。 */
internal interface ChatAssetSpoolStorage {
    /** 规范的账号目录 identity，由各自独立重新打开的存储实例共享。 */
    val directoryKey: String
    fun entries(limit: Int): List<String>
    fun inspect(name: String): ChatAssetFileSnapshot
    fun create(name: String)
    fun openWriter(name: String): ChatAssetWriter
    fun openReader(name: String): ChatAssetReader
    fun publish(partial: String, ready: String)
    /** 缺失的条目视为已删除；仍存在的条目必须还是私有的普通文件。 */
    fun delete(name: String)
    fun forceDirectory()
}
internal data class ChatAssetFileSnapshot(val size: Long, val identity: Any)
internal interface ChatAssetWriter : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun sync()
}
internal interface ChatAssetReader : AutoCloseable {
    /** EOF 时返回 -1；缓冲区归调用方所有，可立即复用。 */
    fun read(bytes: ByteArray): Int
    fun rewind()
}
internal interface ChatAssetDigest : AutoCloseable {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun finish(): String
}
internal expect fun chatAssetSpoolStorage(dataDir: PlatformFile, directories: List<String>): ChatAssetSpoolStorage
internal expect fun createChatAssetDigest(): ChatAssetDigest
