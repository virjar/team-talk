package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.client.AccountDataOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import java.util.UUID

actual fun createChatAssetSpool(
    dataDir: File,
    owner: AccountDataOwner,
    quotaBytes: Long,
    maxEntries: Int,
): ChatAssetSpool = DurableChatAssetSpool(
    chatAssetSpoolFiles(dataDir, chatAssetSpoolDirectories(owner)), quotaBytes, maxEntries,
)

internal interface ChatAssetSpoolFiles {
    val directory: Path
    fun createFile(name: String): Path
    fun requireFile(path: Path): BasicFileAttributes
    fun forceDirectory()
}

internal expect fun chatAssetSpoolFiles(dataDir: File, directories: List<String>): ChatAssetSpoolFiles

/** Fixed shards coordinate briefly held reservations/read leases across owners and reopened stores. */
private object SpoolCoordination {
    class Shard {
        val reservations = mutableMapOf<Path, Long>()
        val readers = mutableMapOf<Path, Int>()
        val pendingDeletes = mutableSetOf<Path>()
    }
    private val shards = Array(64) { Shard() }
    fun forPath(path: Path): Shard = shards[Math.floorMod(path.hashCode(), shards.size)]
}

private class DurableChatAssetSpool(
    private val files: ChatAssetSpoolFiles,
    private val quotaBytes: Long,
    private val maxEntries: Int,
) : ChatAssetSpool {
    private val gate = SpoolCoordination.forPath(files.directory)

    init {
        require(quotaBytes > 0 && maxEntries in 1..4096) { "Invalid chat asset spool limits" }
        synchronized(gate) {
            // A partial file has never been referenced by a durable job. Keep any other live import.
            entries().filter { PARTIAL.matches(it.fileName.toString()) }.forEach { path ->
                files.requireFile(path)
                if (path !in gate.reservations) Files.delete(path)
            }
            files.forceDirectory()
            inventory()
        }
    }

    override suspend fun stage(source: UploadSource): StagedChatAsset = withContext(Dispatchers.IO) {
        val expectedLength = source.contentLength
        require(expectedLength in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) { "附件大小超出限制" }
        val id = UUID.randomUUID().toString()
        val partial = synchronized(gate) {
            val existing = inventory()
            val reservations = gate.reservations.filterKeys { it.parent == files.directory }
            check(existing.size + reservations.size < maxEntries) { "待发送附件数量已达上限，请先发送或移除附件" }
            val used = existing.sumOf { it.second.length } + reservations.values.sum()
            check(expectedLength <= quotaBytes - used) { "待发送附件占用已达上限，请先发送或移除附件" }
            files.createFile("$id.partial").also { gate.reservations[it] = expectedLength }
        }
        var installed: Path? = null
        var accepted = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            FileChannel.open(partial, WRITE, NOFOLLOW_LINKS).use { channel ->
                source.writeTo(UploadSink { bytes, offset, length ->
                    currentCoroutineContext().ensureActive()
                    require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid source chunk" }
                    check(written <= expectedLength - length) { "附件内容超过声明大小" }
                    val buffer = ByteBuffer.wrap(bytes, offset, length)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    digest.update(bytes, offset, length)
                    written += length
                })
                check(written == expectedLength) { "附件内容与声明大小不一致" }
                channel.force(true)
            }
            currentCoroutineContext().ensureActive()
            val staged = StagedChatAsset(id, written, digest.digest().hex())
            synchronized(gate) {
                files.requireFile(partial)
                val ready = files.directory.resolve("$id.${staged.sha256}.blob")
                Files.move(partial, ready, ATOMIC_MOVE)
                installed = ready
                files.requireFile(ready)
                files.forceDirectory()
                gate.reservations.remove(partial)
                accepted = true
            }
            staged
        } finally {
            synchronized(gate) {
                gate.reservations.remove(partial)
                Files.deleteIfExists(partial)
                if (!accepted) installed?.let(Files::deleteIfExists)
            }
        }
    }

    override fun list(): List<StagedChatAsset> = synchronized(gate) { inventory().map { it.second } }

    override fun open(sourceId: String): UploadSource {
        val (path, staged) = synchronized(gate) { find(sourceId) ?: error("附件源文件已不可用，请重新选择") }
        return object : UploadSource {
            override val contentLength = staged.length

            override suspend fun writeTo(sink: UploadSink) = withContext(Dispatchers.IO) {
                synchronized(gate) {
                    check(path !in gate.pendingDeletes) { "附件源文件已移除" }
                    files.requireFile(path)
                    gate.readers[path] = (gate.readers[path] ?: 0) + 1
                }
                try {
                    val before = files.requireFile(path)
                    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                        // Verify the persisted digest before sending any bytes, including after a restart.
                        val digest = MessageDigest.getInstance("SHA-256")
                        val bytes = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
                        val buffer = ByteBuffer.wrap(bytes)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            buffer.clear()
                            val count = channel.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            check(total <= staged.length) { "附件源文件内容已变化" }
                            digest.update(bytes, 0, count)
                        }
                        check(total == staged.length && digest.digest().hex() == staged.sha256) {
                            "附件源文件校验失败，请重新选择"
                        }
                        requireSameSnapshot(before, files.requireFile(path))
                        channel.position(0)
                        total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            buffer.clear()
                            val count = channel.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            check(total <= staged.length) { "附件源文件内容已变化" }
                            sink.write(bytes, 0, count)
                        }
                        check(total == staged.length) { "附件源文件内容已变化" }
                    }
                    requireSameSnapshot(before, files.requireFile(path))
                } finally {
                    synchronized(gate) {
                        val remaining = checkNotNull(gate.readers[path]) - 1
                        if (remaining > 0) gate.readers[path] = remaining else {
                            gate.readers.remove(path)
                            if (gate.pendingDeletes.remove(path)) deleteFile(path)
                        }
                    }
                }
            }
        }
    }

    override fun delete(sourceId: String) = synchronized(gate) {
        val path = find(sourceId)?.first ?: return@synchronized
        if ((gate.readers[path] ?: 0) > 0) gate.pendingDeletes.add(path) else deleteFile(path)
        Unit
    }

    private fun deleteFile(path: Path) {
        files.requireFile(path)
        Files.delete(path)
        files.forceDirectory()
    }

    private fun find(sourceId: String): Pair<Path, StagedChatAsset>? {
        require(ID.matches(sourceId)) { "Invalid private attachment identifier" }
        return inventory().singleOrNull { it.second.sourceId == sourceId }
    }

    private fun inventory(): List<Pair<Path, StagedChatAsset>> = entries().mapNotNull { path ->
        val name = path.fileName.toString()
        if (PARTIAL.matches(name)) {
            files.requireFile(path)
            check(path in gate.reservations) { "Unexpected unfinished chat asset import" }
            null
        } else {
            val match = checkNotNull(READY.matchEntire(name)) { "Unexpected chat asset spool entry" }
            val size = files.requireFile(path).size()
            check(size in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) { "Invalid private attachment length" }
            path to StagedChatAsset(match.groupValues[1], size, match.groupValues[2])
        }
    }.also { records ->
        check(records.map { it.second.sourceId }.distinct().size == records.size) { "Duplicate private attachment identity" }
    }

    private fun entries(): List<Path> = Files.newDirectoryStream(files.directory).use { stream ->
        val result = mutableListOf<Path>()
        for (path in stream) {
            check(result.size < maxEntries) { "Chat asset spool contains too many files" }
            result.add(path)
        }
        result
    }

    private companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PARTIAL = Regex("${ID.pattern}\\.partial")
        val READY = Regex("(${ID.pattern})\\.([0-9a-f]{64})\\.blob")
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun requireSameSnapshot(before: BasicFileAttributes, after: BasicFileAttributes) {
    check(before.fileKey() == after.fileKey() && before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime()) { "附件源文件在传输中发生变化" }
}
