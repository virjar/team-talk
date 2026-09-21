@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.platform

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*

actual class PlatformFile {
    private val value: String
    actual constructor(pathname: String) { value = pathname }
    actual constructor(parent: PlatformFile?, child: String) : this(parent?.getPath(), child)
    actual constructor(parent: String?, child: String) : this(if (parent.isNullOrEmpty()) child else "$parent/$child")
    actual fun getPath(): String = value
    actual fun getName(): String = value.trimEnd('/').substringAfterLast('/')
    actual fun getParent(): String? = value.trimEnd('/').let { if ('/' !in it) null else it.substringBeforeLast('/').ifEmpty { "/" } }
    actual fun getParentFile(): PlatformFile? = getParent()?.let(::PlatformFile)
    actual fun getAbsolutePath(): String = if (value.startsWith('/')) value else NSFileManager.defaultManager.currentDirectoryPath + "/" + value
    actual fun getAbsoluteFile(): PlatformFile = PlatformFile(getAbsolutePath())
    actual fun getCanonicalPath(): String = (getAbsolutePath() as NSString).stringByResolvingSymlinksInPath
    actual fun getCanonicalFile(): PlatformFile = PlatformFile(getCanonicalPath())
    actual fun exists(): Boolean = memScoped { val info = alloc<stat>(); lstat(value, info.ptr) == 0 }
    actual fun isFile(): Boolean = kind(S_IFREG)
    actual fun isDirectory(): Boolean = kind(S_IFDIR)
    private fun kind(expected: Int): Boolean = memScoped { val info = alloc<stat>(); stat(value, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == expected }
    actual fun mkdirs(): Boolean = !exists() && NSFileManager.defaultManager.createDirectoryAtPath(value, true, null, null)
    actual fun mkdir(): Boolean = platform.posix.mkdir(value, 448u) == 0
    actual fun createNewFile(): Boolean { val fd = open(value, O_WRONLY or O_CREAT or O_EXCL, 384); if (fd < 0) return false; close(fd); return true }
    actual fun delete(): Boolean = if (isDirectory() && !isSymbolicLink()) rmdir(value) == 0 else unlink(value) == 0
    actual fun renameTo(destination: PlatformFile): Boolean = rename(value, destination.value) == 0
    actual fun length(): Long = memScoped { val info = alloc<stat>(); if (stat(value, info.ptr) == 0) info.st_size else 0L }
    actual fun lastModified(): Long = memScoped { val info = alloc<stat>(); if (stat(value, info.ptr) == 0) info.st_mtimespec.tv_sec * 1000L + info.st_mtimespec.tv_nsec / 1_000_000L else 0L }
    actual fun setLastModified(time: Long): Boolean = NSFileManager.defaultManager.setAttributes(mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(time / 1000.0)), value, null)
    actual fun listFiles(): Array<PlatformFile>? = list()?.map { PlatformFile(this, it) }?.toTypedArray()
    actual fun list(): Array<String>? = NSFileManager.defaultManager.contentsOfDirectoryAtPath(value, null)?.map { it as String }?.toTypedArray()
    actual fun getUsableSpace(): Long = (NSFileManager.defaultManager.attributesOfFileSystemForPath(getAbsolutePath(), null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is PlatformFile && value == other.value
    override fun hashCode(): Int = value.hashCode()
}
actual fun PlatformFile.readBytes(): ByteArray {
    val data = checkNotNull(NSData.dataWithContentsOfFile(path)) { "Unable to read local file" }
    require(data.length <= Int.MAX_VALUE.toULong()) { "File is too large for a memory read" }
    return ByteArray(data.length.toInt()).also { bytes -> if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
}
actual fun PlatformFile.writeBytes(bytes: ByteArray) {
    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    check(data.writeToFile(path, false)) { "Unable to write local file" }
}
actual fun PlatformFile.appendText(text: String) {
    val bytes = text.encodeToByteArray()
    val fd = open(path, O_WRONLY or O_CREAT or O_APPEND, 384)
    check(fd >= 0) { "Unable to open local file" }
    try { bytes.usePinned { pinned -> var offset = 0; while (offset < bytes.size) {
        val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).toULong())
        check(count > 0) { "Unable to append local file" }; offset += count.toInt()
    } } } finally { close(fd) }
}
actual fun PlatformFile.deleteRecursively(): Boolean {
    if (!exists()) return true
    if (isDirectory && !isSymbolicLink()) listFiles()?.forEach { if (!it.deleteRecursively()) return false }
    return delete()
}
actual fun PlatformFile.isSymbolicLink(): Boolean = memScoped { val info = alloc<stat>(); lstat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFLNK }
actual fun PlatformFile.atomicReplaceWith(source: PlatformFile) { check(rename(source.path, path) == 0) { "Atomic file replacement failed" } }
actual fun PlatformFile.syncToDisk() { val fd = open(path, O_RDONLY); check(fd >= 0); try { check(fsync(fd) == 0) } finally { close(fd) } }
actual fun PlatformFile.copyTo(target: PlatformFile, overwrite: Boolean): PlatformFile {
    val input = open(path, O_RDONLY or O_NOFOLLOW)
    check(input >= 0) { "Unable to open copy source" }
    try {
        val output = open(target.path, O_WRONLY or O_CREAT or O_NOFOLLOW or (if (overwrite) O_TRUNC else O_EXCL), 384)
        check(output >= 0) { "Unable to create copy destination" }
        try {
            val buffer = ByteArray(64 * 1024)
            buffer.usePinned { pinned ->
                while (true) {
                    val count = read(input, pinned.addressOf(0), buffer.size.toULong())
                    check(count >= 0) { "Unable to read copy source" }
                    if (count == 0L) break
                    var offset = 0
                    while (offset < count) {
                        val written = write(output, pinned.addressOf(offset), (count - offset).toULong())
                        check(written > 0) { "Unable to write copy destination" }
                        offset += written.toInt()
                    }
                }
            }
        } finally { close(output) }
    } finally { close(input) }
    return target
}
