package com.virjar.tk.client

import com.virjar.tk.util.LogBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpLogUploaderLifecycleTest {
    @Test
    fun `stop disconnects blocked request and never reads replacement credentials`() {
        val dataDir = Files.createTempDirectory("teamtalk-log-http-gate-").toFile()
        val trace = LogBuffer(8)
        val fault = LogBuffer(8)
        val transport = BlockingLogTransport()
        val reads = AtomicInteger()
        var credentials = SessionHttpCredentials("same-uid", "old-token", identityEpoch = 4L)
        val uploader = HttpLogUploader(
            traceBuffer = trace,
            faultBuffer = fault,
            serverUrl = "https://old.example.test",
            ownerUid = "same-uid",
            ownerIdentityEpoch = 4L,
            credentialsProvider = {
                reads.incrementAndGet()
                credentials
            },
            crashDumper = CrashDumper(dataDir, "https://old.example.test", "same-uid"),
            intervalMs = Long.MAX_VALUE,
            transport = transport,
        )

        try {
            trace.append("trace", "test", "blocked")
            uploader.manualUpload()
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            credentials = SessionHttpCredentials("same-uid", "new-token", identityEpoch = 5L)

            uploader.stop()
            assertTrue(transport.completed.await(5, TimeUnit.SECONDS))

            assertEquals(1, transport.closeCalls.get())
            assertEquals(1, reads.get(), "quiesce 后旧 uploader 不得读取新 token")
            assertEquals("https://old.example.test/api/client-logs", transport.url)
            assertEquals("Bearer old-token", transport.authorization)
            assertFailsWith<IllegalStateException> { uploader.manualUpload() }
            assertEquals(1, reads.get())
        } finally {
            runCatching { uploader.stop() }
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `worker released after stop cannot rewrite crash namespace or buffers`() {
        val dataDir = Files.createTempDirectory("teamtalk-log-http-late-").toFile()
        val trace = LogBuffer(8)
        val fault = LogBuffer(8)
        val transport = LateFailingLogTransport()
        val crash = CrashDumper(dataDir, "https://old.example.test", "same-uid")
        val uploader = HttpLogUploader(
            traceBuffer = trace,
            faultBuffer = fault,
            serverUrl = "https://old.example.test",
            ownerUid = "same-uid",
            ownerIdentityEpoch = 4L,
            credentialsProvider = {
                SessionHttpCredentials("same-uid", "old-token", identityEpoch = 4L)
            },
            crashDumper = crash,
            intervalMs = Long.MAX_VALUE,
            transport = transport,
        )

        try {
            trace.append("trace", "test", "owned payload")
            uploader.manualUpload()
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))

            uploader.stop()
            assertEquals(1, transport.closeCalls.get())
            transport.release.countDown()
            assertTrue(transport.completed.await(5, TimeUnit.SECONDS))

            assertFalse(crash.hasPending(), "retired worker persisted into replacement namespace")
            assertEquals(null, trace.drain(), "retired worker appended a late diagnostic")
        } finally {
            transport.release.countDown()
            runCatching { uploader.stop() }
            dataDir.deleteRecursively()
        }
    }
}

private class BlockingLogTransport : PlatformLogHttpTransport {
    val entered = CountDownLatch(1)
    val completed = CountDownLatch(1)
    private val release = CountDownLatch(1)
    val closeCalls = AtomicInteger()
    @Volatile var url: String? = null
    @Volatile var authorization: String? = null

    override fun postGzip(url: String, compressed: ByteArray, headers: Map<String, String>): Int {
        this.url = url
        authorization = headers["Authorization"]
        entered.countDown()
        release.await(5, TimeUnit.SECONDS)
        completed.countDown()
        return 200
    }

    override fun close() {
        closeCalls.incrementAndGet()
        release.countDown()
    }
}

private class LateFailingLogTransport : PlatformLogHttpTransport {
    val entered = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closeCalls = AtomicInteger()

    override fun postGzip(url: String, compressed: ByteArray, headers: Map<String, String>): Int {
        entered.countDown()
        release.await(5, TimeUnit.SECONDS)
        completed.countDown()
        throw IllegalStateException("late transport failure")
    }

    override fun close() {
        closeCalls.incrementAndGet()
        // Deliberately model a transport whose close cannot interrupt the native/blocking call.
    }
}
