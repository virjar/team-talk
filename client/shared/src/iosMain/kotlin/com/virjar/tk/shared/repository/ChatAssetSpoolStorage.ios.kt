@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.iosPrivateDirectory
import com.virjar.tk.shared.client.requireIosRegularFile
import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.posix.*

internal actual fun chatAssetSpoolStorage(dataDir: PlatformFile, directories: List<String>): ChatAssetSpoolStorage =
    IosChatAssetSpoolStorage(iosPrivateDirectory(dataDir, directories))

/** POSIX handles and CoreCrypto remain native; no quota, ownership or lifecycle policy is duplicated. */
private class IosChatAssetSpoolStorage(private val directory: PlatformFile) : ChatAssetSpoolStorage {
    override val directoryKey: String = directory.canonicalPath
    private fun file(name: String) = PlatformFile(directory, name)
    override fun entries(limit: Int): List<String> {
        val stream = checkNotNull(opendir(directory.path)) { "Cannot scan private attachment storage" }
        try {
            val names = mutableListOf<String>()
            while (true) {
                val entry = readdir(stream) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                check(names.size < limit) { "Chat asset spool contains too many files" }
                names += name
            }
            return names
        } finally { closedir(stream) }
    }
    override fun inspect(name: String): ChatAssetFileSnapshot = memScoped {
        val info = alloc<stat>()
        check(lstat(file(name).path, info.ptr) == 0) { "Cannot inspect private attachment" }
        require(info.st_mode.toInt() and S_IFMT == S_IFREG) { "Invalid private attachment" }
        ChatAssetFileSnapshot(info.st_size, listOf(info.st_dev.toLong(), info.st_ino.toLong(), info.st_mtimespec.tv_sec, info.st_mtimespec.tv_nsec))
    }
    override fun create(name: String) {
        val file = file(name)
        check(file.createNewFile()) { "Cannot create private attachment" }
        check(chmod(file.path, 384u) == 0) { "Cannot protect private attachment" }
    }
    override fun openWriter(name: String): ChatAssetWriter {
        val descriptor = open(file(name).path, O_WRONLY or O_NOFOLLOW)
        check(descriptor >= 0) { "Cannot write private attachment" }
        return object : ChatAssetWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length == 0) return
                bytes.usePinned { pinned ->
                    var position = offset
                    while (position < offset + length) {
                        val count = platform.posix.write(descriptor, pinned.addressOf(position), (offset + length - position).toULong())
                        if (count < 0 && errno == EINTR) continue
                        check(count > 0) { "Cannot write private attachment" }
                        position += count.toInt()
                    }
                }
            }
            override fun sync() { check(fsync(descriptor) == 0) }
            override fun close() { platform.posix.close(descriptor) }
        }
    }
    override fun openReader(name: String): ChatAssetReader {
        val descriptor = open(file(name).path, O_RDONLY or O_NOFOLLOW)
        check(descriptor >= 0) { "Cannot read private attachment" }
        return object : ChatAssetReader {
            override fun read(bytes: ByteArray): Int = bytes.usePinned { pinned ->
                var count: Long
                do { count = platform.posix.read(descriptor, pinned.addressOf(0), bytes.size.toULong()) } while (count < 0 && errno == EINTR)
                check(count >= 0) { "Cannot read private attachment" }
                if (count == 0L) -1 else count.toInt()
            }
            override fun rewind() { check(lseek(descriptor, 0, SEEK_SET) == 0L) }
            override fun close() { platform.posix.close(descriptor) }
        }
    }
    override fun publish(partial: String, ready: String) {
        val destination = file(ready)
        check(!destination.exists()) { "Private attachment source already exists" }
        destination.atomicReplaceWith(file(partial))
    }
    override fun delete(name: String) {
        val file = file(name)
        if (!file.exists()) return
        requireIosRegularFile(file)
        check(file.delete()) { "Cannot remove private attachment" }
    }
    override fun forceDirectory() = directory.syncToDisk()
}

internal actual fun createChatAssetDigest(): ChatAssetDigest = IosChatAssetDigest()
private class IosChatAssetDigest : ChatAssetDigest {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>()
    init { check(CC_SHA256_Init(context.ptr) == 1) }
    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length != 0) bytes.usePinned { check(CC_SHA256_Update(context.ptr, it.addressOf(offset), length.toUInt()) == 1) }
    }
    override fun finish(): String = ByteArray(CC_SHA256_DIGEST_LENGTH).also { bytes ->
        bytes.usePinned { check(CC_SHA256_Final(it.addressOf(0).reinterpret(), context.ptr) == 1) }
    }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    override fun close() = nativeHeap.free(context)
}
