package com.virjar.tk.server.infra.storage

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import java.nio.charset.StandardCharsets

class RocksDbTier(db: RocksDB, dataCf: ColumnFamilyHandle) : StorageTierBackend(db, dataCf) {

    private val readCache = ReadCache(
        maxSizeBytes = 128L * 1024 * 1024,
        cacheThreshold = 2L * 1024 * 1024,
    )

    override suspend fun streamTo(meta: FileMetadata, channel: ByteWriteChannel, range: ReadRange?) {
        val cacheable = meta.contentType.startsWith("video/") &&
            meta.size in readCache.cacheThreshold..readCache.maxSizeBytes
        if (cacheable) {
            val cached = readCache.get(meta.path)
            if (cached != null) {
                writeToChannel(channel, cached, range)
                return
            }
        }

        val data = db.get(dataCf, meta.path.toByteArray(StandardCharsets.UTF_8))
            ?: throw IllegalStateException("File data missing: ${meta.path}")

        if (cacheable) {
            readCache.put(meta.path, data)
        }

        writeToChannel(channel, data, range)
    }

    private suspend fun writeToChannel(channel: ByteWriteChannel, data: ByteArray, range: ReadRange?) {
        val slice = range.boundedReadSlice(data.size.toLong())
        val startIndex = slice.offset.toInt()
        val endIndex = Math.addExact(slice.offset, slice.length).toInt()
        // Ktor 的 ByteWriteChannel 重载使用 [startIndex, endIndex)，而不是 offset + length。
        channel.writeFully(data, startIndex, endIndex)
        channel.flush()
    }

    fun addToBatch(batch: WriteBatch, meta: FileMetadata, data: ByteArray) {
        batch.put(dataCf, meta.path.toByteArray(StandardCharsets.UTF_8), data)
    }

    internal fun deleteFromBatch(batch: WriteBatch, path: String) {
        batch.delete(dataCf, path.toByteArray(StandardCharsets.UTF_8))
    }

    internal fun forgetDeleted(path: String) {
        readCache.remove(path)
    }

    internal fun contains(path: String): Boolean =
        db.get(dataCf, path.toByteArray(StandardCharsets.UTF_8)) != null

    fun clearCache() {
        readCache.clear()
    }

    internal class ReadCache(
        val maxSizeBytes: Long,
        val cacheThreshold: Long,
    ) {
        private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
        private var currentSize = 0L

        init {
            require(maxSizeBytes > 0L) { "read-cache byte capacity must be positive" }
            require(cacheThreshold in 1..maxSizeBytes) {
                "read-cache threshold must be positive and no larger than its capacity"
            }
        }

        @Synchronized
        fun get(key: String): ByteArray? = entries[key]

        @Synchronized
        fun put(key: String, data: ByteArray): Boolean {
            // 当一个值永远无法装入时，保持现有缓存不变。在保留一个超容量值之前
            // 逐出每个热点条目，会同时违反字节上界与
            // LRU 契约。
            if (data.size.toLong() > maxSizeBytes) return false
            entries[key]?.let { old ->
                currentSize -= old.size
                entries.remove(key)
            }
            evictIfNeeded(data.size.toLong())
            entries[key] = data
            currentSize += data.size
            return true
        }

        @Synchronized
        fun remove(key: String) {
            entries.remove(key)?.let { currentSize -= it.size }
        }

        @Synchronized
        fun clear() {
            entries.clear()
            currentSize = 0
        }

        @Synchronized
        internal fun snapshot(): ReadCacheSnapshot = ReadCacheSnapshot(
            entryCount = entries.size,
            sizeBytes = currentSize,
            keysInEvictionOrder = entries.keys.toList(),
        )

        private fun evictIfNeeded(neededBytes: Long) {
            val iter = entries.entries.iterator()
            while (iter.hasNext() && currentSize + neededBytes > maxSizeBytes) {
                currentSize -= iter.next().value.size.toLong()
                iter.remove()
            }
        }
    }

    internal data class ReadCacheSnapshot(
        val entryCount: Int,
        val sizeBytes: Long,
        val keysInEvictionOrder: List<String>,
    )
}
