package com.virjar.tk.server.infra.storage

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readFully
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadRangeTest {
    @Test
    fun `inclusive ranges become bounded offset and length slices`() {
        assertEquals(BoundedReadSlice(3L, 3L), ReadRange(3L, 5L).boundedReadSlice(10L))
        assertEquals(BoundedReadSlice(8L, 2L), ReadRange(8L, 20L).boundedReadSlice(10L))
        assertEquals(BoundedReadSlice(10L, 0L), ReadRange(20L, 30L).boundedReadSlice(10L))
        assertEquals(BoundedReadSlice(0L, 10L), (null as ReadRange?).boundedReadSlice(10L))
    }

    @Test
    fun `invalid or overflowing ranges fail before tier IO`() {
        assertFailsWith<IllegalArgumentException> { ReadRange(-1L, 0L) }
        assertFailsWith<IllegalArgumentException> { ReadRange(2L, 1L) }
        assertFailsWith<IllegalArgumentException> { ReadRange(0L, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { ReadRange(0L, 0L).boundedReadSlice(-1L) }
    }

    @Test
    fun `RocksDB and filesystem tiers stream the same nonzero inclusive range`() = runTest {
        val root = Files.createTempDirectory("tk-read-range-").toFile()
        val store = FileStore(
            dbPath = File(root, "rocksdb").absolutePath,
            fsRoot = File(root, "files").absolutePath,
            largeFileThreshold = 5L,
            maxFileSize = 64L,
        )
        try {
            store.init()
            val rocksMeta = storeMeta(store, root, "rocks.bin", 5)
            val filesystemMeta = storeMeta(store, root, "filesystem.bin", 6)
            assertEquals(StorageTier.ROCKSDB, rocksMeta.tier)
            assertEquals(StorageTier.FILESYSTEM, filesystemMeta.tier)

            val range = ReadRange(start = 2L, end = 4L)
            assertEquals(byteArrayOf(2, 3, 4).toList(), store.readRange(rocksMeta, range, 3).toList())
            assertEquals(byteArrayOf(2, 3, 4).toList(), store.readRange(filesystemMeta, range, 3).toList())
        } finally {
            if (store.isRunning) store.close()
            root.deleteRecursively()
        }
    }

    private fun storeMeta(store: FileStore, root: File, name: String, size: Int): FileMetadata {
        val source = File(root, name).apply { writeBytes(ByteArray(size) { it.toByte() }) }
        val path = store.store("range-owner", name, "application/octet-stream", source)
        return requireNotNull(store.getMeta(path))
    }

    private suspend fun FileStore.readRange(
        metadata: FileMetadata,
        range: ReadRange,
        expectedLength: Int,
    ): ByteArray {
        val channel = ByteChannel()
        streamTo(metadata, channel, range)
        return ByteArray(expectedLength).also { channel.readFully(it) }
    }
}
