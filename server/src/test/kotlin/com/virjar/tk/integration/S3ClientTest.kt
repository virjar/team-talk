package com.virjar.tk.integration

import com.virjar.tk.s3.S3Client
import com.virjar.tk.service.FileService
import io.netty.channel.nio.NioEventLoopGroup
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIf
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * S3Client + FileService 集成测试。
 * 需要 MinIO 运行在 127.0.0.1:9000。
 * 通过环境变量 RUN_INTEGRATION_TESTS=true 启用。
 */
@EnabledIf("isIntegrationTestsEnabled")
class S3ClientTest {

    companion object {
        private const val ENDPOINT = "http://127.0.0.1:9000"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private val BUCKET = "teamtalk-test-${System.currentTimeMillis()}"

        private lateinit var eventLoopGroup: NioEventLoopGroup
        private lateinit var s3Client: S3Client
        private lateinit var fileService: FileService

        private const val TIMEOUT_SECONDS = 10L

        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true

        @BeforeAll
        @JvmStatic
        fun setUp() {
            eventLoopGroup = NioEventLoopGroup(2)
            s3Client = S3Client(ENDPOINT, ACCESS_KEY, SECRET_KEY, BUCKET, eventLoopGroup)
            fileService = FileService(ENDPOINT, ACCESS_KEY, SECRET_KEY, BUCKET)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            fileService.close()
            eventLoopGroup.shutdownGracefully()
        }
    }

    // ---- S3Client 底层测试 ----

    @org.junit.jupiter.api.Test
    fun `bucketExists returns false for non-existent bucket`() {
        val client = S3Client(ENDPOINT, ACCESS_KEY, SECRET_KEY, "nonexistent-bucket-${UUID.randomUUID()}", eventLoopGroup)
        val exists = client.bucketExists().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertFalse(exists)
    }

    @org.junit.jupiter.api.Test
    fun `makeBucket and bucketExists`() {
        val exists = s3Client.bucketExists().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(exists) // FileService init already creates it
    }

    @org.junit.jupiter.api.Test
    fun `putObject and headObject round-trip`() {
        val key = "test/put-head-${UUID.randomUUID()}.txt"
        val data = "Hello, S3!".toByteArray(Charsets.UTF_8)

        s3Client.putObject(key, data, "text/plain").get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val meta = s3Client.headObject(key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(meta)
        assertEquals(data.size.toLong(), meta.contentLength)
        assertEquals("text/plain", meta.contentType)
    }

    @org.junit.jupiter.api.Test
    fun `headObject returns null for non-existent key`() {
        val meta = s3Client.headObject("nonexistent-${UUID.randomUUID()}").get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNull(meta)
    }

    @org.junit.jupiter.api.Test
    fun `getObject retrieves uploaded content`() {
        val key = "test/get-${UUID.randomUUID()}.txt"
        val data = "Content for GET test".toByteArray(Charsets.UTF_8)

        s3Client.putObject(key, data, "text/plain").get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val response = s3Client.getObject(key).get(30, TimeUnit.SECONDS)

        assertTrue(response.isSuccessful())
        assertTrue(response.body.contentEquals(data))
    }

    @org.junit.jupiter.api.Test
    fun `getObject with range returns partial content`() {
        val key = "test/range-${UUID.randomUUID()}.txt"
        val data = "0123456789ABCDEF".toByteArray(Charsets.UTF_8)

        s3Client.putObject(key, data, "application/octet-stream").get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val response = s3Client.getObject(key, range = "bytes=4-7").get(30, TimeUnit.SECONDS)

        assertEquals(206, response.statusCode)
        assertTrue(response.body.size <= 4)
    }

    @org.junit.jupiter.api.Test
    fun `deleteObject removes uploaded content`() {
        val key = "test/delete-${UUID.randomUUID()}.txt"
        val data = "to be deleted".toByteArray(Charsets.UTF_8)

        s3Client.putObject(key, data, "text/plain").get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        s3Client.deleteObject(key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val meta = s3Client.headObject(key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNull(meta)
    }

    // ---- FileService 上层测试 ----

    @org.junit.jupiter.api.Test
    fun `FileService upload and streamFile`() {
        val path = fileService.upload("test-uid", "hello.txt", "text/plain", "Hello from FileService".toByteArray())
        assertTrue(path.startsWith("test-uid/"))
        assertTrue(path.endsWith(".txt"))

        val stream = fileService.streamFile(path)
        assertNotNull(stream)
        assertEquals("text/plain", stream.contentType)

        val baos = ByteArrayOutputStream()
        stream.inputStream.copyTo(baos)
        assertEquals("Hello from FileService", String(baos.toByteArray(), Charsets.UTF_8))
    }

    @org.junit.jupiter.api.Test
    fun `FileService streamFileWithRange returns full file when no range`() {
        val data = ByteArray(1024) { (it % 256).toByte() }
        val path = fileService.upload("test-uid", "fullfile.bin", "application/octet-stream", data)

        val stream = fileService.streamFileWithRange(path) ?: fail("Expected non-null stream")
        assertFalse(stream.isRange)
        assertEquals(data.size.toLong(), stream.contentLength)
        assertEquals(data.size.toLong(), stream.totalSize)

        val baos = ByteArrayOutputStream()
        stream.inputStream.copyTo(baos)
        assertTrue(baos.toByteArray().contentEquals(data))
    }

    @org.junit.jupiter.api.Test
    fun `FileService streamFileWithRange returns range for valid Range header`() {
        val data = ByteArray(1024) { it.toByte() }
        val path = fileService.upload("test-uid", "rangefile.bin", "application/octet-stream", data)

        val stream = fileService.streamFileWithRange(path, "bytes=100-199") ?: fail("Expected non-null stream")
        assertTrue(stream.isRange)
        assertEquals(100L, stream.rangeStart)
        assertEquals(199L, stream.rangeEnd)
        assertEquals(100L, stream.contentLength)
        assertEquals(1024L, stream.totalSize)

        val baos = ByteArrayOutputStream()
        stream.inputStream.copyTo(baos)
        assertEquals(100, baos.size())
        for (i in 0 until 100) {
            assertEquals((100 + i).toByte(), baos.toByteArray()[i])
        }
    }

    @org.junit.jupiter.api.Test
    fun `FileService streamFileWithRange supports open-ended range`() {
        val data = ByteArray(500) { it.toByte() }
        val path = fileService.upload("test-uid", "openrange.bin", "application/octet-stream", data)

        val stream = fileService.streamFileWithRange(path, "bytes=400-") ?: fail("Expected non-null stream")
        assertTrue(stream.isRange)
        assertEquals(400L, stream.rangeStart)
        assertEquals(499L, stream.rangeEnd)
        assertEquals(100L, stream.contentLength)

        val baos = ByteArrayOutputStream()
        stream.inputStream.copyTo(baos)
        assertEquals(100, baos.size())
    }

    @org.junit.jupiter.api.Test
    fun `FileService streamFileWithRange supports suffix range`() {
        val data = ByteArray(500) { it.toByte() }
        val path = fileService.upload("test-uid", "suffixrange.bin", "application/octet-stream", data)

        val stream = fileService.streamFileWithRange(path, "bytes=-50") ?: fail("Expected non-null stream")
        assertTrue(stream.isRange)
        assertEquals(450L, stream.rangeStart)
        assertEquals(499L, stream.rangeEnd)
        assertEquals(50L, stream.contentLength)
    }

    @org.junit.jupiter.api.Test
    fun `FileService streamFileWithRange returns null for non-existent file`() {
        val stream = fileService.streamFileWithRange("nonexistent/path/file.txt")
        assertNull(stream)
    }

    @org.junit.jupiter.api.Test
    fun `FileService headObject returns metadata`() {
        val data = "metadata test".toByteArray(Charsets.UTF_8)
        val path = fileService.upload("test-uid", "meta.txt", "text/plain", data)

        val meta = fileService.headObject(path)
        assertNotNull(meta)
        assertEquals(data.size.toLong(), meta.contentLength)
        assertEquals("text/plain", meta.contentType)
    }

    @org.junit.jupiter.api.Test
    fun `FileService delete removes file`() {
        val path = fileService.upload("test-uid", "todelete.txt", "text/plain", "bye".toByteArray())
        fileService.delete(path)

        val meta = fileService.headObject(path)
        assertNull(meta)
    }

    @org.junit.jupiter.api.Test
    fun `streamFileWithRange with large file`() {
        val data = ByteArray(100 * 1024) { (it % 256).toByte() }
        val path = fileService.upload("test-uid", "largefile.bin", "application/octet-stream", data)

        // 给 MinIO 异步写入一个小延迟
        Thread.sleep(100)

        val stream = fileService.streamFileWithRange(path, "bytes=65536-65635") ?: fail("Expected non-null stream")
        assertTrue(stream.isRange)
        assertEquals(65536L, stream.rangeStart)
        assertEquals(65635L, stream.rangeEnd)
        assertEquals(100L, stream.contentLength)

        val baos = ByteArrayOutputStream()
        stream.inputStream.copyTo(baos)
        assertEquals(100, baos.size())
    }
}
