package com.virjar.tk.integration

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
}
