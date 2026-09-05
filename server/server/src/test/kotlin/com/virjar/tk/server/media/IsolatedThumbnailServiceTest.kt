package com.virjar.tk.server.media

import com.virjar.tk.server.infra.media.THUMBNAIL_HELPER_CLASSPATH_PROPERTY
import com.virjar.tk.server.infra.media.ThumbnailHelperProcessLauncher
import com.virjar.tk.server.infra.media.ThumbnailHelperRequest
import com.virjar.tk.server.infra.media.ThumbnailService
import com.virjar.tk.server.infra.storage.ManagedTempFiles
import com.virjar.tk.server.infra.storage.STAGING_TEMP_SUFFIX
import com.virjar.tk.server.infra.storage.UPLOAD_STAGING_TEMP_PREFIX
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsolatedThumbnailServiceTest {
    @Test
    fun `helper JVM 成功结果通过固定协议和摘要校验`() {
        val root = Files.createTempDirectory("tk-isolated-thumbnail-success-").toFile()
        try {
            val source = managedPng(root, 800, 600)
            val service = ThumbnailService(
                tempDirectory = root,
                maxConcurrentHelpers = 1,
                helperTimeoutMillis = 10_000,
            )

            val result = assertNotNull(service.processImage(source))
            assertEquals(800, result.width)
            assertEquals(600, result.height)
            assertEquals(null, result.durationSec)
            val thumbnail = assertNotNull(result.thumbFile)
            val decoded = assertNotNull(ImageIO.read(thumbnail))
            assertEquals(480, decoded.width)
            assertEquals(360, decoded.height)
            assertEquals(
                setOf(source.name, thumbnail.name),
                root.listFiles().orEmpty().map(File::getName).toSet(),
                "result protocol temporary file must retire before handoff",
            )

            ManagedTempFiles.retire(root, thumbnail)
            ManagedTempFiles.retire(root, source)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `卡死 helper 超时后被进程级回收且不泄露临时文件`() {
        val root = Files.createTempDirectory("tk-isolated-thumbnail-hang-").toFile()
        try {
            val source = managedPng(root, 16, 16)
            val launcher = FaultProcessLauncher(FaultBehavior.HANG)
            val service = isolatedService(root, launcher, timeoutMillis = 150)

            assertNull(service.processImage(source))
            val process = assertNotNull(launcher.lastProcess)
            assertFalse(process.isAlive, "timed-out helper must be reaped before the call returns")
            assertEquals(setOf(source.name), root.listFiles().orEmpty().map(File::getName).toSet())
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `helper 并发饱和立即降级且不启动替代进程`() {
        val root = Files.createTempDirectory("tk-isolated-thumbnail-saturated-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val source = managedPng(root, 16, 16)
            val launcher = FaultProcessLauncher(FaultBehavior.HANG, expectedStarts = 2)
            val service = isolatedService(
                root,
                launcher,
                timeoutMillis = 1_000,
                maxConcurrentHelpers = 2,
            )
            val first = executor.submit<ThumbnailService.MediaInfo?> { service.processImage(source) }
            val second = executor.submit<ThumbnailService.MediaInfo?> { service.processImage(source) }
            assertTrue(launcher.started.await(3, TimeUnit.SECONDS), "two admitted helpers did not start")

            val elapsed = measureTimeMillis {
                assertNull(service.processImage(source))
            }
            assertTrue(elapsed < 250, "saturated helper admission must not queue: ${elapsed}ms")
            assertEquals(2, launcher.startCount.get(), "saturation must not spawn a third helper")
            assertNull(first.get(3, TimeUnit.SECONDS))
            assertNull(second.get(3, TimeUnit.SECONDS))
            assertTrue(launcher.processes.all { !it.isAlive })
            assertEquals(setOf(source.name), root.listFiles().orEmpty().map(File::getName).toSet())
        } finally {
            executor.shutdownNow()
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `畸形 helper 输出被拒且结果与缩略图临时文件均退休`() {
        val root = Files.createTempDirectory("tk-isolated-thumbnail-malformed-").toFile()
        try {
            val source = managedPng(root, 16, 16)
            val launcher = FaultProcessLauncher(FaultBehavior.MALFORMED_RESULT)
            val service = isolatedService(root, launcher, timeoutMillis = 2_000)

            assertNull(service.processImage(source))
            assertEquals(1, launcher.startCount.get())
            assertEquals(setOf(source.name), root.listFiles().orEmpty().map(File::getName).toSet())
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    private fun isolatedService(
        root: File,
        launcher: ThumbnailHelperProcessLauncher,
        timeoutMillis: Long,
        maxConcurrentHelpers: Int = 1,
    ): ThumbnailService = ThumbnailService(
        tempDirectory = root,
        maxConcurrentHelpers = maxConcurrentHelpers,
        helperTimeoutMillis = timeoutMillis,
        terminationGraceMillis = 200,
        retireTempFile = { file -> ManagedTempFiles.retire(root, file) },
        processLauncher = launcher,
    )
}

internal enum class FaultBehavior(val wireName: String) {
    HANG("hang"),
    MALFORMED_RESULT("malformed-result"),
}

internal class FaultProcessLauncher(
    private val behavior: FaultBehavior,
    expectedStarts: Int = 1,
) : ThumbnailHelperProcessLauncher {
    val startCount = AtomicInteger()
    val started = CountDownLatch(expectedStarts)
    val processes = CopyOnWriteArrayList<Process>()

    @Volatile
    var lastProcess: Process? = null

    override fun start(request: ThumbnailHelperRequest): Process {
        startCount.incrementAndGet()
        val javaHome = checkNotNull(System.getProperty("java.home"))
        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        val classpath = System.getProperty(THUMBNAIL_HELPER_CLASSPATH_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: checkNotNull(System.getProperty("java.class.path"))
        val process = ProcessBuilder(
            File(File(javaHome, "bin"), executableName).absolutePath,
            "-Xms8m",
            "-Xmx64m",
            "-cp",
            classpath,
            ThumbnailHelperFaultProcessMain::class.java.name,
            behavior.wireName,
            request.output.toString(),
            request.result.toString(),
        )
            .directory(request.tempRoot.toFile())
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment().clear() }
            .start()
        processes += process
        lastProcess = process
        started.countDown()
        return process
    }
}

/** 独立 JVM 夹具：它可以挂起或损坏协议文件，而不会危及测试 JVM。 */
internal object ThumbnailHelperFaultProcessMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 3)
        val output = Path.of(arguments[1])
        val result = Path.of(arguments[2])
        when (arguments[0]) {
            FaultBehavior.HANG.wireName -> {
                Files.write(
                    output,
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                while (true) Thread.sleep(60_000)
            }
            FaultBehavior.MALFORMED_RESULT.wireName -> Files.write(
                result,
                ByteArray(58) { 0x5A },
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            else -> error("Unknown fault behavior")
        }
    }
}

private fun managedPng(root: File, width: Int, height: Int): File {
    val source = ManagedTempFiles.create(root, UPLOAD_STAGING_TEMP_PREFIX, STAGING_TEMP_SUFFIX)
    assertTrue(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", source))
    return source
}
