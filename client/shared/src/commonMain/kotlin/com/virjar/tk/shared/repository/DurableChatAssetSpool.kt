package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.platform.PlatformLock
import com.virjar.tk.shared.platform.platformRandomUuid
import com.virjar.tk.shared.platform.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed shards bound coordination memory while reopened stores share reservations and read leases. */
private object SpoolCoordination {
    data class Key(val directory: String, val name: String)
    class Shard {
        val lock = PlatformLock()
        val reservations = mutableMapOf<Key, Long>()
        val readers = mutableMapOf<Key, Int>()
        val pendingDeletes = mutableSetOf<Key>()
    }
    private val shards = Array(64) { Shard() }
    fun forDirectory(directory: String): Shard = shards[(directory.hashCode() and Int.MAX_VALUE) % shards.size]
}

/** Account namespace, immutable file format and publication/read ownership are identical on every OS. */
internal class DurableChatAssetSpool(
    private val files: ChatAssetSpoolStorage,
    private val quotaBytes: Long,
    private val maxEntries: Int,
) : ChatAssetSpool {
    private val gate = SpoolCoordination.forDirectory(files.directoryKey)
    private fun key(name: String) = SpoolCoordination.Key(files.directoryKey, name)

    init {
        require(quotaBytes > 0 && maxEntries in 1..4096) { "Invalid chat asset spool limits" }
        synchronized(gate.lock) {
            entries().filter(PARTIAL::matches).forEach { name ->
                files.inspect(name)
                if (key(name) !in gate.reservations) files.delete(name)
            }
            files.forceDirectory()
            inventory()
        }
    }

    override suspend fun stage(source: UploadSource): StagedChatAsset = withContext(Dispatchers.IO) {
        val expected = source.contentLength
        require(expected in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) { "附件大小超出限制" }
        val id = platformRandomUuid()
        val partial = "$id.partial"
        synchronized(gate.lock) {
            val existing = inventory()
            val reservations = gate.reservations.filterKeys { it.directory == files.directoryKey }
            check(existing.size + reservations.size < maxEntries) { "待发送附件数量已达上限，请先发送或移除附件" }
            val used = existing.sumOf { it.second.length } + reservations.values.sum()
            check(expected <= quotaBytes - used) { "待发送附件占用已达上限，请先发送或移除附件" }
            files.create(partial)
            gate.reservations[key(partial)] = expected
        }
        var installed: String? = null
        var accepted = false
        try {
            var written = 0L
            val hash = createChatAssetDigest().use { digest ->
                files.openWriter(partial).use { writer ->
                    source.writeTo(UploadSink { bytes, offset, length ->
                        currentCoroutineContext().ensureActive()
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid source chunk" }
                        check(written <= expected - length) { "附件内容超过声明大小" }
                        writer.write(bytes, offset, length)
                        digest.update(bytes, offset, length)
                        written += length
                    })
                    check(written == expected) { "附件内容与声明大小不一致" }
                    writer.sync()
                }
                digest.finish()
            }
            currentCoroutineContext().ensureActive()
            val staged = StagedChatAsset(id, written, hash)
            synchronized(gate.lock) {
                files.inspect(partial)
                val ready = "$id.$hash.blob"
                files.publish(partial, ready)
                installed = ready
                files.inspect(ready)
                files.forceDirectory()
                gate.reservations.remove(key(partial))
                accepted = true
            }
            staged
        } finally {
            synchronized(gate.lock) {
                gate.reservations.remove(key(partial))
                files.delete(partial)
                if (!accepted) installed?.let(files::delete)
            }
        }
    }

    override fun list(): List<StagedChatAsset> = synchronized(gate.lock) { inventory().map { it.second } }

    override fun open(sourceId: String): UploadSource {
        val (name, staged) = synchronized(gate.lock) { find(sourceId) ?: error("附件源文件已不可用，请重新选择") }
        val key = key(name)
        return object : UploadSource {
            override val contentLength = staged.length
            override suspend fun writeTo(sink: UploadSink): Unit = withContext(Dispatchers.IO) {
                synchronized(gate.lock) {
                    check(key !in gate.pendingDeletes) { "附件源文件已移除" }
                    files.inspect(name)
                    gate.readers[key] = (gate.readers[key] ?: 0) + 1
                }
                try {
                    val before = files.inspect(name)
                    files.openReader(name).use { reader ->
                        val bytes = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
                        // Digest verification must finish before even the first byte reaches a caller.
                        createChatAssetDigest().use { digest ->
                            consume(reader, bytes, staged.length) { count -> digest.update(bytes, 0, count) }
                            check(digest.finish() == staged.sha256) { "附件源文件校验失败，请重新选择" }
                        }
                        check(before == files.inspect(name)) { "附件源文件在传输中发生变化" }
                        reader.rewind()
                        consume(reader, bytes, staged.length) { count -> sink.write(bytes, 0, count) }
                    }
                    check(before == files.inspect(name)) { "附件源文件在传输中发生变化" }
                } finally {
                    synchronized(gate.lock) {
                        val remaining = checkNotNull(gate.readers[key]) - 1
                        if (remaining > 0) gate.readers[key] = remaining else {
                            gate.readers.remove(key)
                            if (gate.pendingDeletes.remove(key)) deleteFile(name)
                        }
                    }
                }
            }
        }
    }

    private suspend inline fun consume(reader: ChatAssetReader, bytes: ByteArray, expected: Long, consume: (Int) -> Unit) {
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = reader.read(bytes)
            if (count < 0) break
            if (count == 0) continue
            total += count
            check(total <= expected) { "附件源文件内容已变化" }
            consume(count)
        }
        check(total == expected) { "附件源文件内容已变化" }
    }

    override fun delete(sourceId: String): Unit = synchronized(gate.lock) {
        val name = find(sourceId)?.first ?: return@synchronized
        val key = key(name)
        if ((gate.readers[key] ?: 0) > 0) gate.pendingDeletes.add(key) else deleteFile(name)
        Unit
    }
    private fun deleteFile(name: String) { files.inspect(name); files.delete(name); files.forceDirectory() }
    private fun find(sourceId: String): Pair<String, StagedChatAsset>? {
        require(ID.matches(sourceId)) { "Invalid private attachment identifier" }
        return inventory().singleOrNull { it.second.sourceId == sourceId }
    }
    private fun inventory(): List<Pair<String, StagedChatAsset>> = entries().mapNotNull { name ->
        if (PARTIAL.matches(name)) {
            files.inspect(name)
            check(key(name) in gate.reservations) { "Unexpected unfinished chat asset import" }
            null
        } else {
            val match = checkNotNull(READY.matchEntire(name)) { "Unexpected chat asset spool entry" }
            val snapshot = files.inspect(name)
            check(snapshot.size in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) { "Invalid private attachment length" }
            name to StagedChatAsset(match.groupValues[1], snapshot.size, match.groupValues[2])
        }
    }.also { check(it.map { it.second.sourceId }.distinct().size == it.size) { "Duplicate private attachment identity" } }
    private fun entries(): List<String> = files.entries(maxEntries)

    private companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PARTIAL = Regex("${ID.pattern}\\.partial")
        val READY = Regex("(${ID.pattern})\\.([0-9a-f]{64})\\.blob")
    }
}
