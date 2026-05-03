package com.virjar.tk.storage

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
        val cacheable = meta.contentType.startsWith("video/") && meta.size >= readCache.cacheThreshold
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
        if (range != null) {
            val start = range.start.toInt().coerceIn(0, data.size)
            val end = (range.end + 1).toInt().coerceAtMost(data.size)
            channel.writeFully(data, start, end)
        } else {
            channel.writeFully(data)
        }
        channel.flush()
    }

    override fun deleteData(meta: FileMetadata) {
        readCache.remove(meta.path)
        db.delete(dataCf, meta.path.toByteArray(StandardCharsets.UTF_8))
    }

    fun addToBatch(batch: WriteBatch, meta: FileMetadata, data: ByteArray) {
        batch.put(dataCf, meta.path.toByteArray(StandardCharsets.UTF_8), data)
    }

    fun addDeleteToBatch(batch: WriteBatch, meta: FileMetadata) {
        batch.delete(dataCf, meta.path.toByteArray(StandardCharsets.UTF_8))
    }

    fun clearCache() {
        readCache.clear()
    }

    private class ReadCache(
        val maxSizeBytes: Long,
        val cacheThreshold: Long,
    ) {
        private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
        private var currentSize = 0L

        @Synchronized
        fun get(key: String): ByteArray? = entries[key]

        @Synchronized
        fun put(key: String, data: ByteArray) {
            entries[key]?.let { old ->
                currentSize -= old.size
                entries.remove(key)
            }
            evictIfNeeded(data.size.toLong())
            entries[key] = data
            currentSize += data.size
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

        private fun evictIfNeeded(neededBytes: Long) {
            val iter = entries.entries.iterator()
            while (iter.hasNext() && currentSize + neededBytes > maxSizeBytes) {
                currentSize -= iter.next().value.size.toLong()
                iter.remove()
            }
        }
    }
}
