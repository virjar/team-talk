package com.virjar.tk.integration

import com.virjar.tk.infra.storage.FileStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `store and retrieve small file`() = runTest {
        val content = "Hello, TeamTalk!".toByteArray()
        val tempFile = File.createTempFile("test-upload", ".txt")
        tempFile.writeBytes(content)
        tempFile.deleteOnExit()

        val path = ctx.fileStore.store("user-1", "hello.txt", "text/plain", tempFile)
        assertNotNull(path)

        val meta = ctx.fileStore.getMeta(path)
        assertNotNull(meta)
        assertEquals("hello.txt", meta.originalName)
        assertEquals("text/plain", meta.contentType)
        assertEquals(content.size.toLong(), meta.size)
        assertEquals("user-1", meta.uid)
        assertEquals(
            com.virjar.tk.model.Attachment(path, "hello.txt", "text/plain", content.size.toLong()),
            ctx.fileStore.getAttachment(path),
        )
    }

    @Test
    fun `get meta for non-existent file returns null`() = runTest {
        val meta = ctx.fileStore.getMeta("/non/existent/path")
        assertNull(meta)
    }

    @Test
    fun `store generates unique paths`() = runTest {
        val content = "content".toByteArray()

        val tempFile1 = File.createTempFile("test-unique1", ".bin")
        tempFile1.writeBytes(content)
        tempFile1.deleteOnExit()
        val path1 = ctx.fileStore.store("user-1", "a.txt", "text/plain", tempFile1)

        val tempFile2 = File.createTempFile("test-unique2", ".bin")
        tempFile2.writeBytes(content)
        tempFile2.deleteOnExit()
        val path2 = ctx.fileStore.store("user-1", "b.txt", "text/plain", tempFile2)

        assertTrue(path1 != path2, "Each upload should get a unique path")
    }

    @Test
    fun `original filename is reduced to a safe leaf name`() = runTest {
        val tempFile = File.createTempFile("test-name", ".txt").apply {
            writeText("safe")
            deleteOnExit()
        }
        val path = ctx.fileStore.store("user-1", "../../folder/unsafe.txt", "text/plain", tempFile)
        assertEquals("unsafe.txt", ctx.fileStore.getAttachment(path)?.name)
        assertTrue(path.endsWith(".txt"))
    }

    @Test
    fun `filesystem attachment is unavailable when its data file is missing`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("tk-file-existence-").toFile()
        val store = FileStore(
            dbPath = File(root, "rocksdb").absolutePath,
            fsRoot = File(root, "files").absolutePath,
            largeFileThreshold = 1,
        ).also { it.init() }
        try {
            val source = File(root, "source.bin").apply { writeBytes(byteArrayOf(1, 2)) }
            val path = store.store("user-1", "data.bin", "application/octet-stream", source)
            val metadata = assertNotNull(store.getMeta(path))
            val storedFile = assertNotNull(store.getFile(metadata))
            assertTrue(storedFile.delete(), "测试应能删除文件系统 tier 的实体文件")
            assertNull(store.getAttachment(path), "孤儿元数据不能被解析为可发送附件")
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}
