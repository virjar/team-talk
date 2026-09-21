@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.client.*
import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.CoreCrypto.*
import platform.posix.*

actual fun createChatAssetSpool(dataDir: PlatformFile, owner: AccountDataOwner, quotaBytes: Long, maxEntries: Int): ChatAssetSpool =
    IosChatAssetSpool(iosPrivateDirectory(dataDir, chatAssetSpoolDirectories(owner)), quotaBytes, maxEntries)

private object IosSpoolCoordination {
    val lock = PlatformLock()
    val reservations = mutableMapOf<String, Long>()
    val readers = mutableMapOf<String, Int>()
    val pendingDeletes = mutableSetOf<String>()
}

private class IosChatAssetSpool(
    private val directory: PlatformFile,
    private val quotaBytes: Long,
    private val maxEntries: Int,
) : ChatAssetSpool {
    private val gate = IosSpoolCoordination
    init {
        require(quotaBytes > 0 && maxEntries in 1..4096)
        synchronized(gate.lock) {
            entries().filter { PARTIAL.matches(it.name) }.forEach { file ->
                requireIosRegularFile(file)
                if (file.path !in gate.reservations) check(file.delete())
            }
            directory.syncToDisk()
            inventory()
        }
    }
    override suspend fun stage(source: UploadSource): StagedChatAsset = withContext(Dispatchers.IO) {
        val expected = source.contentLength
        require(expected in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) { "附件大小超出限制" }
        val id = platformRandomUuid()
        val partial = synchronized(gate.lock) {
            val existing = inventory()
            val reservations = gate.reservations.filterKeys { PlatformFile(it).parent == directory.path }
            check(existing.size + reservations.size < maxEntries) { "待发送附件数量已达上限，请先发送或移除附件" }
            val used = existing.sumOf { it.second.length } + reservations.values.sum()
            check(expected <= quotaBytes - used) { "待发送附件占用已达上限，请先发送或移除附件" }
            PlatformFile(directory, "$id.partial").also { file ->
                check(file.createNewFile()) { "Cannot create private attachment" }
                check(chmod(file.path, 384u) == 0)
                gate.reservations[file.path] = expected
            }
        }
        var installed: PlatformFile? = null
        var accepted = false
        try {
            var written = 0L
            val descriptor = platform.posix.open(partial.path, O_WRONLY or O_NOFOLLOW)
            check(descriptor >= 0)
            val digest = IosSha256()
            val hash = try {
                source.writeTo(UploadSink { bytes, offset, length ->
                    currentCoroutineContext().ensureActive()
                    require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid source chunk" }
                    check(written <= expected - length) { "附件内容超过声明大小" }
                    writeAll(descriptor, bytes, offset, length)
                    digest.update(bytes, offset, length)
                    written += length
                })
                check(written == expected) { "附件内容与声明大小不一致" }
                check(fsync(descriptor) == 0)
                digest.finish()
            } finally { digest.close(); close(descriptor) }
            currentCoroutineContext().ensureActive()
            val staged = StagedChatAsset(id, written, hash)
            synchronized(gate.lock) {
                requireIosRegularFile(partial)
                val ready = PlatformFile(directory, "$id.$hash.blob")
                check(!ready.exists())
                ready.atomicReplaceWith(partial)
                installed = ready
                directory.syncToDisk()
                gate.reservations.remove(partial.path)
                accepted = true
            }
            staged
        } finally {
            synchronized(gate.lock) {
                gate.reservations.remove(partial.path)
                if (partial.exists()) check(partial.delete())
                if (!accepted) installed?.let { check(it.delete()) }
            }
        }
    }
    override fun list(): List<StagedChatAsset> = synchronized(gate.lock) { inventory().map { it.second } }
    override fun open(sourceId: String): UploadSource {
        val (file, staged) = synchronized(gate.lock) { find(sourceId) ?: error("附件源文件已不可用，请重新选择") }
        return object : UploadSource {
            override val contentLength: Long = staged.length
            override suspend fun writeTo(sink: UploadSink): Unit = withContext(Dispatchers.IO) {
                synchronized(gate.lock) {
                    check(file.path !in gate.pendingDeletes) { "附件源文件已移除" }
                    requireIosRegularFile(file)
                    gate.readers[file.path] = (gate.readers[file.path] ?: 0) + 1
                }
                try {
                    val before = fileSnapshot(file)
                    val descriptor = platform.posix.open(file.path, O_RDONLY or O_NOFOLLOW)
                    check(descriptor >= 0) { "Cannot read private attachment" }
                    try {
                        val buffer = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
                        val digest = IosSha256()
                        var total = 0L
                        try {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = readChunk(descriptor, buffer)
                                if (count == 0) break
                                total += count
                                check(total <= staged.length) { "附件源文件内容已变化" }
                                digest.update(buffer, 0, count)
                            }
                            check(total == staged.length && digest.finish() == staged.sha256) { "附件源文件校验失败，请重新选择" }
                        } finally { digest.close() }
                        check(before == fileSnapshot(file)) { "附件源文件在传输中发生变化" }
                        check(lseek(descriptor, 0, SEEK_SET) == 0L)
                        total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = readChunk(descriptor, buffer)
                            if (count == 0) break
                            total += count
                            check(total <= staged.length) { "附件源文件内容已变化" }
                            sink.write(buffer, 0, count)
                        }
                        check(total == staged.length && before == fileSnapshot(file)) { "附件源文件在传输中发生变化" }
                    } finally { close(descriptor) }
                } finally {
                    synchronized(gate.lock) {
                        val remaining = checkNotNull(gate.readers[file.path]) - 1
                        if (remaining > 0) gate.readers[file.path] = remaining else {
                            gate.readers.remove(file.path)
                            if (gate.pendingDeletes.remove(file.path)) deleteFile(file)
                        }
                    }
                }
            }
        }
    }
    override fun delete(sourceId: String): Unit = synchronized(gate.lock) {
        val file = find(sourceId)?.first ?: return@synchronized
        if ((gate.readers[file.path] ?: 0) > 0) gate.pendingDeletes.add(file.path) else deleteFile(file)
        Unit
    }
    private fun deleteFile(file: PlatformFile) { requireIosRegularFile(file); check(file.delete()); directory.syncToDisk() }
    private fun find(sourceId: String): Pair<PlatformFile, StagedChatAsset>? {
        require(ID.matches(sourceId)) { "Invalid private attachment identifier" }
        return inventory().singleOrNull { it.second.sourceId == sourceId }
    }
    private fun inventory(): List<Pair<PlatformFile, StagedChatAsset>> = entries().mapNotNull { file ->
        requireIosRegularFile(file)
        if (PARTIAL.matches(file.name)) {
            check(file.path in gate.reservations) { "Unexpected unfinished chat asset import" }; null
        } else {
            val match = checkNotNull(READY.matchEntire(file.name)) { "Unexpected chat asset spool entry" }
            val length = file.length()
            check(length in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES)
            file to StagedChatAsset(match.groupValues[1], length, match.groupValues[2])
        }
    }.also { check(it.map { it.second.sourceId }.distinct().size == it.size) { "Duplicate private attachment identity" } }
    private fun entries(): List<PlatformFile> = checkNotNull(directory.listFiles()).toList().also {
        check(it.size <= maxEntries) { "Chat asset spool contains too many files" }
    }
    private companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PARTIAL = Regex("${ID.pattern}\\.partial")
        val READY = Regex("(${ID.pattern})\\.([0-9a-f]{64})\\.blob")
    }
}

private class IosSha256 {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>()
    init { check(CC_SHA256_Init(context.ptr) == 1) }
    fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length != 0) bytes.usePinned { check(CC_SHA256_Update(context.ptr, it.addressOf(offset), length.toUInt()) == 1) }
    }
    fun finish(): String = ByteArray(CC_SHA256_DIGEST_LENGTH).also { bytes ->
        bytes.usePinned { check(CC_SHA256_Final(it.addressOf(0).reinterpret(), context.ptr) == 1) }
    }.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun close() = nativeHeap.free(context)
}
private fun writeAll(descriptor: Int, bytes: ByteArray, offset: Int, length: Int) {
    if (length == 0) return
    bytes.usePinned { pinned ->
        var position = offset
        while (position < offset + length) {
            val count = write(descriptor, pinned.addressOf(position), (offset + length - position).toULong())
            if (count < 0 && errno == EINTR) continue
            check(count > 0) { "Cannot write private attachment" }
            position += count.toInt()
        }
    }
}
private fun readChunk(descriptor: Int, bytes: ByteArray): Int = bytes.usePinned { pinned ->
    var count: Long
    do { count = read(descriptor, pinned.addressOf(0), bytes.size.toULong()) } while (count < 0 && errno == EINTR)
    check(count >= 0) { "Cannot read private attachment" }
    count.toInt()
}
private fun fileSnapshot(file: PlatformFile): List<Long> = memScoped {
    val info = alloc<stat>()
    check(lstat(file.path, info.ptr) == 0 && info.st_mode.toInt() and S_IFMT == S_IFREG) { "Invalid private attachment" }
    listOf(info.st_dev.toLong(), info.st_ino.toLong(), info.st_size, info.st_mtimespec.tv_sec, info.st_mtimespec.tv_nsec)
}
