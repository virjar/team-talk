package com.virjar.tk.shared.repository

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Existing Desktop/Android private-directory checks remain owned by those platforms. */
internal interface NioChatAssetSpoolFiles {
    val directory: Path
    fun createFile(name: String): Path
    fun requireFile(path: Path): BasicFileAttributes
    fun forceDirectory()
}
internal expect fun nioChatAssetSpoolFiles(dataDir: File, directories: List<String>): NioChatAssetSpoolFiles

internal actual fun chatAssetSpoolStorage(dataDir: File, directories: List<String>): ChatAssetSpoolStorage =
    NioChatAssetSpoolStorage(nioChatAssetSpoolFiles(dataDir, directories))

private class NioChatAssetSpoolStorage(private val files: NioChatAssetSpoolFiles) : ChatAssetSpoolStorage {
    override val directoryKey: String = files.directory.toString()
    override fun entries(limit: Int): List<String> = Files.newDirectoryStream(files.directory).use { stream ->
        val names = mutableListOf<String>()
        for (path in stream) {
            check(names.size < limit) { "Chat asset spool contains too many files" }
            names += path.fileName.toString()
        }
        names
    }
    override fun inspect(name: String): ChatAssetFileSnapshot {
        val attributes = files.requireFile(files.directory.resolve(name))
        return ChatAssetFileSnapshot(attributes.size(), listOf(attributes.fileKey(), attributes.lastModifiedTime()))
    }
    override fun create(name: String) { files.createFile(name) }
    override fun openWriter(name: String): ChatAssetWriter {
        val channel = FileChannel.open(files.directory.resolve(name), WRITE, NOFOLLOW_LINKS)
        return object : ChatAssetWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val buffer = ByteBuffer.wrap(bytes, offset, length)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            override fun sync() { channel.force(true) }
            override fun close() = channel.close()
        }
    }
    override fun openReader(name: String): ChatAssetReader {
        val channel = FileChannel.open(files.directory.resolve(name), READ, NOFOLLOW_LINKS)
        return object : ChatAssetReader {
            override fun read(bytes: ByteArray): Int = channel.read(ByteBuffer.wrap(bytes))
            override fun rewind() { channel.position(0) }
            override fun close() = channel.close()
        }
    }
    override fun publish(partial: String, ready: String) {
        val destination = files.directory.resolve(ready)
        check(!Files.exists(destination, NOFOLLOW_LINKS)) { "Private attachment source already exists" }
        Files.move(files.directory.resolve(partial), destination, ATOMIC_MOVE)
    }
    override fun delete(name: String) {
        val path = files.directory.resolve(name)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        files.requireFile(path)
        Files.delete(path)
    }
    override fun forceDirectory() = files.forceDirectory()
}

internal actual fun createChatAssetDigest(): ChatAssetDigest = object : ChatAssetDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
    override fun finish(): String = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    override fun close() = Unit
}
