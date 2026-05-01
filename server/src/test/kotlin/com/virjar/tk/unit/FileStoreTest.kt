package com.virjar.tk.unit

import com.virjar.tk.storage.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoreTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        val dbPath = File(tempDir, "file-store/rocksdb").absolutePath
        val fsRoot = File(tempDir, "file-store/files").absolutePath
        FileStore.start(dbPath, fsRoot)
    }

    @AfterEach
    fun teardown() {
        FileStore.close()
    }

    private fun writeTempFile(data: ByteArray): File {
        val tmp = File(tempDir, "upload-${UUID.randomUUID()}.tmp")
        tmp.writeBytes(data)
        return tmp
    }

    private fun readAll(path: String, range: ReadRange? = null): ByteArray = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val meta: FileMetadata = FileStore.getMeta(path)!!
        withContext(Dispatchers.IO) {
            FileStore.streamTo(meta, channel, range)
        }
        channel.close()
        channel.toByteArray()
    }

    @Test
    fun `store and stream small file`() {
        val data = "Hello, World!".toByteArray()
        val tmp = writeTempFile(data)

        val path = FileStore.store("user1", "test.txt", "text/plain", tmp)
        assertFalse(tmp.exists(), "temp file should be deleted after small file store")

        val result = readAll(path)
        assertEquals(String(data), String(result))
    }

    @Test
    fun `storeBytes and stream`() {
        val data = "bytes test".toByteArray()
        val path = FileStore.storeBytes("user1", "test.txt", "text/plain", data)

        val result = readAll(path)
        assertEquals(String(data), String(result))
    }

    @Test
    fun `getMeta returns stored metadata`() {
        val data = "meta test".toByteArray()
        val tmp = writeTempFile(data)
        val path = FileStore.store("user1", "photo.jpg", "image/jpeg", tmp)

        val meta = FileStore.getMeta(path)
        assertNotNull(meta)
        assertEquals(path, meta.path)
        assertEquals("user1", meta.uid)
        assertEquals("image/jpeg", meta.contentType)
        assertEquals("photo.jpg", meta.originalName)
        assertEquals(data.size.toLong(), meta.size)
    }

    @Test
    fun `getMeta returns null for nonexistent`() {
        assertNull(FileStore.getMeta("no/such/file.txt"))
    }

    @Test
    fun `streamTo with range returns partial content`() {
        val data = "0123456789".toByteArray()
        val tmp = writeTempFile(data)
        val path = FileStore.store("user1", "data.bin", "application/octet-stream", tmp)

        val result = readAll(path, ReadRange(2, 5))
        assertEquals("2345", String(result))
    }

    @Test
    fun `streamTo throws for missing data`() {
        val tmp = writeTempFile("test".toByteArray())
        val path = FileStore.store("user1", "test.txt", "text/plain", tmp)
        val meta = FileStore.getMeta(path)!!
        FileStore.delete(path)
        try {
            runBlocking {
                val channel = ByteChannel(autoFlush = true)
                withContext(Dispatchers.IO) {
                    FileStore.streamTo(meta, channel)
                }
            }
            assertTrue(false, "should have thrown")
        } catch (_: IllegalStateException) {
            // expected: meta exists but data missing
        }
    }

    @Test
    fun `delete removes file`() {
        val tmp = writeTempFile("to delete".toByteArray())
        val path = FileStore.store("user1", "del.txt", "text/plain", tmp)
        assertTrue(FileStore.getMeta(path) != null)

        assertTrue(FileStore.delete(path))
        assertNull(FileStore.getMeta(path))
    }

    @Test
    fun `delete returns false for nonexistent`() {
        assertFalse(FileStore.delete("no/such/file"))
    }

    @Test
    fun `markArchived and findDeletable`() {
        val tmp = writeTempFile("archive test".toByteArray())
        val path = FileStore.store("user1", "old.txt", "text/plain", tmp)

        FileStore.markArchived(path)
        val deletable = FileStore.findDeletable(System.currentTimeMillis(), 100)
        assertTrue(deletable.any { it.path == path })
    }

    @Test
    fun `findArchivable returns old unarchived files`() {
        val tmp = writeTempFile("old file".toByteArray())
        val path = FileStore.store("user1", "old.txt", "text/plain", tmp)

        val archivable = FileStore.findArchivable(System.currentTimeMillis() + 1, 100)
        assertTrue(archivable.any { it.path == path })
    }

    @Test
    fun `isHealthy reflects state`() {
        assertTrue(FileStore.isHealthy)
        FileStore.close()
        assertFalse(FileStore.isHealthy)
        FileStore.start(
            File(tempDir, "file-store/rocksdb").absolutePath,
            File(tempDir, "file-store/files").absolutePath
        )
    }
}
