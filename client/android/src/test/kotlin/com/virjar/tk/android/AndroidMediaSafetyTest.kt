package com.virjar.tk.android

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.UserSession
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.protocol.model.Attachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TEST_MEDIA_DATASET_ID = "00000000-0000-4000-8000-000000000001"
private const val OTHER_MEDIA_DATASET_ID = "00000000-0000-4000-8000-000000000002"

class AndroidMediaSafetyTest {
    @Test
    fun `concurrent thumbnail and gallery requests share one atomic cache write`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-single-flight").toFile()
        try {
            val target = File(root, "same-media.png")
            val firstWriterStarted = CompletableDeferred<Unit>()
            val allowFirstWriterToFinish = CompletableDeferred<Unit>()
            val secondCallerStarted = CompletableDeferred<Unit>()
            val writes = AtomicInteger()

            val thumbnail = async(Dispatchers.Default) {
                materializeMediaCacheFile(target) { partial ->
                    writes.incrementAndGet()
                    firstWriterStarted.complete(Unit)
                    allowFirstWriterToFinish.await()
                    partial.writeText("complete-image")
                }
            }
            firstWriterStarted.await()
            val gallery = async(Dispatchers.Default) {
                secondCallerStarted.complete(Unit)
                materializeMediaCacheFile(target) { partial ->
                    writes.incrementAndGet()
                    partial.writeText("duplicate-download")
                }
            }
            secondCallerStarted.await()
            allowFirstWriterToFinish.complete(Unit)

            assertEquals(target, thumbnail.await())
            assertEquals(target, gallery.await())
            assertEquals(1, writes.get(), "同一媒体只应发起一次下载")
            assertEquals("complete-image", target.readText())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed media cache write leaves no final or partial file and can retry`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-retry").toFile()
        try {
            val target = File(root, "retry.png")
            assertFailsWith<IllegalStateException> {
                materializeMediaCacheFile(target) { partial ->
                    partial.writeText("incomplete")
                    error("network failed")
                }
            }

            assertFalse(target.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })

            val cached = materializeMediaCacheFile(target) { partial -> partial.writeText("complete") }
            assertEquals(target, cached)
            assertEquals("complete", cached.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `bounded copy accepts an input exactly at the limit`() {
        val source = ByteArray(128) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied = copyBounded(ByteArrayInputStream(source), output, source.size.toLong())

        assertEquals(source.size.toLong(), copied)
        assertContentEquals(source, output.toByteArray())
    }

    @Test
    fun `bounded copy rejects an input beyond the limit`() {
        val error = assertFailsWith<SelectedMediaTooLargeException> {
            copyBounded(ByteArrayInputStream(ByteArray(129)), ByteArrayOutputStream(), 128)
        }

        assertEquals(128, error.maxBytes)
        assertTrue(error.message.orEmpty().contains("所选文件不能超过"))
    }

    @Test
    fun `android download validates optional content length and actual chunked bytes before publication`() = runBlocking {
        val payload = "chunked-ok".encodeToByteArray()
        withDownloadSession(payload, declaredLength = -1L) { root, session ->
            val attachment = attachment("missing-length.bin", payload.size.toLong())
            downloadAttachmentToCacheLease(root, session, attachment).use { lease ->
                assertContentEquals(payload, lease.file.readBytes())
            }
        }

        withDownloadSession(payload, declaredLength = 2L) { root, session ->
            val attachment = attachment("forged-length.bin", payload.size.toLong())
            assertFailsWith<MediaDownloadSizeException> {
                downloadAttachmentToCacheLease(root, session, attachment)
            }
            assertNoAndroidDownloadCache(root, session.cacheNamespace)
        }

        withDownloadSession(payload, declaredLength = -1L) { root, session ->
            val attachment = attachment("chunked-over.bin", payload.size.toLong() - 1L)
            assertFailsWith<MediaDownloadSizeException> {
                downloadAttachmentToCacheLease(root, session, attachment)
            }
            assertNoAndroidDownloadCache(root, session.cacheNamespace)
        }
    }

    @Test
    fun `android cache reservations bound concurrent aggregate and release after cancellation`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-android-media-reservation").toFile()
        val namespace = "test-namespace"
        val firstTarget = testDownloadCacheFile(root, namespace, "first")
        val secondTarget = testDownloadCacheFile(root, namespace, "second")
        try {
            val first = AndroidMediaCacheCapacityRegistry.reserve(
                root, expectedBytes = 6L, target = firstTarget, quotaBytes = 10L,
            )
            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    root, expectedBytes = 5L, target = secondTarget, quotaBytes = 10L,
                )
            }
            assertEquals(6L, AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root))
            assertEquals(1, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
            first.close()

            assertFailsWith<CancellationException> {
                materializeMediaCacheFile(
                    target = secondTarget,
                    expectedBytes = 5L,
                    reserveCapacity = {
                        AndroidMediaCacheCapacityRegistry.reserve(
                            root, expectedBytes = 5L, target = secondTarget, quotaBytes = 10L,
                        )
                    },
                ) { throw CancellationException("synthetic cache cancellation") }
            }
            assertEquals(0L, AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root))
            assertEquals(0, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android capacity lru preserves a pinned player file until playback releases it`() {
        val root = Files.createTempDirectory("teamtalk-android-media-player-pin").toFile()
        val namespace = "test-namespace"
        val playing = testDownloadCacheFile(root, namespace, "playing", "mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(6))
            setLastModified(1L)
        }
        val next = testDownloadCacheFile(root, namespace, "next", "mp4")
        try {
            val lease = checkNotNull(
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    root,
                    playing,
                    expectedBytes = 6L,
                    quotaBytes = 10L,
                ),
            )
            assertEquals(playing, lease.file)
            assertEquals(1, AndroidMediaCacheCapacityRegistry.pinnedFilesForTest(root))
            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    root,
                    expectedBytes = 5L,
                    target = next,
                    quotaBytes = 10L,
                )
            }
            assertTrue(playing.isFile, "播放中的最终文件不能被容量驱逐")

            lease.close()
            assertEquals(0, AndroidMediaCacheCapacityRegistry.pinnedFilesForTest(root))
            AndroidMediaCacheCapacityRegistry.reserve(
                root,
                expectedBytes = 5L,
                target = next,
                quotaBytes = 10L,
            ).close()
            assertFalse(playing.exists(), "播放器释放后旧文件可以参与 LRU 驱逐")
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android materialization atomically converts capacity reservation into player lease`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-android-media-atomic-lease").toFile()
        val namespace = "test-namespace"
        val target = testDownloadCacheFile(root, namespace, "playing", "mp4")
        val next = testDownloadCacheFile(root, namespace, "next", "mp4")
        try {
            val lease = materializePinnedMediaCacheFile(
                target = target,
                expectedBytes = 6L,
                acquireCachedLease = {
                    AndroidMediaCacheCapacityRegistry.cachedLease(
                        root,
                        target,
                        expectedBytes = 6L,
                        quotaBytes = 10L,
                    )
                },
                reserveCapacity = {
                    AndroidMediaCacheCapacityRegistry.reserve(
                        root,
                        expectedBytes = 6L,
                        target = target,
                        quotaBytes = 10L,
                    )
                },
            ) { partial -> partial.writeBytes(ByteArray(6)) }

            assertEquals(target, lease.file)
            assertEquals(1, AndroidMediaCacheCapacityRegistry.pinnedFilesForTest(root))
            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    root,
                    expectedBytes = 5L,
                    target = next,
                    quotaBytes = 10L,
                )
            }
            assertTrue(target.isFile)
            lease.close()
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android force refresh replaces a same-size corrupt cached video`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-android-media-force-refresh").toFile()
        val namespace = "test-namespace"
        val target = testDownloadCacheFile(root, namespace, "video", "mp4").apply {
            parentFile?.mkdirs()
            writeText("bad!")
        }
        try {
            val lease = materializePinnedMediaCacheFile(
                target = target,
                expectedBytes = 4L,
                acquireCachedLease = {
                    AndroidMediaCacheCapacityRegistry.cachedLease(
                        root,
                        target,
                        expectedBytes = 4L,
                        forceRefresh = true,
                        quotaBytes = 10L,
                    )
                },
                reserveCapacity = {
                    AndroidMediaCacheCapacityRegistry.reserve(
                        root,
                        expectedBytes = 4L,
                        target = target,
                        quotaBytes = 10L,
                    )
                },
            ) { partial -> partial.writeText("good") }

            assertEquals("good", lease.file.readText())
            lease.close()
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android zero-byte transfers reserve and release the entry cap before network`() {
        val root = Files.createTempDirectory("teamtalk-android-media-entry-reservation").toFile()
        val namespace = "test-namespace"
        val firstTarget = testDownloadCacheFile(root, namespace, "first")
        val secondTarget = testDownloadCacheFile(root, namespace, "second")
        try {
            val first = AndroidMediaCacheCapacityRegistry.reserve(
                root,
                expectedBytes = 0L,
                target = firstTarget,
                quotaBytes = 10L,
                maxEntries = 1,
            )
            assertEquals(0L, AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root))
            assertEquals(1, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))

            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    root,
                    expectedBytes = 0L,
                    target = secondTarget,
                    quotaBytes = 10L,
                    maxEntries = 1,
                )
            }
            first.close()
            assertEquals(0, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android entry LRU removes startup partials and bounds zero-byte final files`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-android-media-entry-lru").toFile()
        val namespace = "test-namespace"
        val downloadRoot = mediaCacheDirectory(root, namespace, "downloads").apply { mkdirs() }
        val oldFinal = testAttachmentCacheFile(root, namespace, "old", "old.part").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf())
            setLastModified(1L)
        }
        val abandonedPartial = File(downloadRoot, ".teamtalk-part-abandoned.part")
            .apply { writeBytes(byteArrayOf()) }
        val firstTarget = testDownloadCacheFile(root, namespace, "first-new")
        val secondTarget = testDownloadCacheFile(root, namespace, "second-new")
        try {
            materializeMediaCacheFile(
                target = firstTarget,
                expectedBytes = 0L,
                reserveCapacity = {
                    AndroidMediaCacheCapacityRegistry.reserve(
                        root,
                        expectedBytes = 0L,
                        target = firstTarget,
                        quotaBytes = 10L,
                        maxEntries = 2,
                    )
                },
            ) { partial -> partial.writeBytes(byteArrayOf()) }

            assertFalse(abandonedPartial.exists(), "首次预留必须先清理上次进程残留的 part")
            assertTrue(oldFinal.exists(), "合法的 .part 附件扩展名不得被当作临时文件")

            materializeMediaCacheFile(
                target = secondTarget,
                expectedBytes = 0L,
                reserveCapacity = {
                    AndroidMediaCacheCapacityRegistry.reserve(
                        root,
                        expectedBytes = 0L,
                        target = secondTarget,
                        quotaBytes = 10L,
                        maxEntries = 2,
                    )
                },
            ) { partial -> partial.writeBytes(byteArrayOf()) }

            assertFalse(oldFinal.exists(), "零字节最终文件也必须受条目 LRU 上限约束")
            assertTrue(firstTarget.isFile)
            assertTrue(secondTarget.isFile)
            assertEquals(0, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android cache applies one lru budget across namespace downloads and attachments`() {
        val root = Files.createTempDirectory("teamtalk-android-media-global-lru").toFile()
        val firstNamespace = "first-namespace"
        val secondNamespace = "second-namespace"
        val targetNamespace = "target-namespace"
        val oldestDownload = testDownloadCacheFile(root, firstNamespace, "oldest", "MP4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(6))
            setLastModified(1L)
        }
        val newerAttachment = testAttachmentCacheFile(
            root,
            secondNamespace,
            "newer",
            "会议 纪要.pdf",
        ).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(3))
            setLastModified(2L)
        }
        val target = testDownloadCacheFile(root, targetNamespace, "target")
        try {
            val reservation = AndroidMediaCacheCapacityRegistry.reserve(
                root,
                expectedBytes = 5L,
                target = target,
                quotaBytes = 10L,
            )

            assertFalse(oldestDownload.exists(), "跨 namespace 最旧文件应先被驱逐")
            assertTrue(newerAttachment.isFile, "较新的附件应保留")
            assertEquals(
                5L,
                AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root),
                "根目录级状态应观察到所有 namespace 的共享预留",
            )
            reservation.close()
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android attachment publication stays pinned across final progress handoff`() = runBlocking {
        val payload = ByteArray(6) { it.toByte() }
        withDownloadSession(payload, declaredLength = payload.size.toLong()) { root, session ->
            val attachment = attachment("handoff.bin", payload.size.toLong())
            val cached = attachmentCacheFile(root, session.cacheNamespace, attachment)
            val pressureTarget = testDownloadCacheFile(root, "other-namespace", "pressure")
            var pressureObserved = false

            val lease = downloadAttachmentToCacheLease(
                cacheRoot = root,
                mediaSession = session,
                attachment = attachment,
                cacheQuotaBytes = 10L,
            ) { progress ->
                if (
                    progress == 1f &&
                    AndroidMediaCacheCapacityRegistry.pinnedFilesForTest(root) == 1
                ) {
                    pressureObserved = true
                    assertFailsWith<MediaCacheQuotaException> {
                        AndroidMediaCacheCapacityRegistry.reserve(
                            cacheRoot = root,
                            expectedBytes = 5L,
                            target = pressureTarget,
                            quotaBytes = 10L,
                        )
                    }
                    assertTrue(cached.isFile, "发布完成到返回调用方之间不能被其他 namespace 驱逐")
                }
            }
            assertTrue(pressureObserved, "最终进度回调必须观察到已交接的消费租约")
            assertEquals(cached, lease.file)
            assertContentEquals(payload, lease.file.readBytes())

            lease.close()
            AndroidMediaCacheCapacityRegistry.reserve(
                cacheRoot = root,
                expectedBytes = 5L,
                target = pressureTarget,
                quotaBytes = 10L,
            ).close()
            assertFalse(cached.exists(), "消费租约释放后普通附件应重新参与 LRU")
        }
    }

    @Test
    fun `cancelled dispatcher return closes a completed media lease instead of losing it`() {
        val root = Files.createTempDirectory("teamtalk-android-media-cancelled-handoff").toFile()
        val cached = testDownloadCacheFile(root, "namespace", "completed").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
        }
        val caller = QueuedMediaTestDispatcher()
        val worker = QueuedMediaTestDispatcher()
        val owner = Job()
        var terminalFailure: Throwable? = null
        try {
            val task = CoroutineScope(owner + caller).launch {
                try {
                    withCloseableContext(worker) {
                        checkNotNull(
                            AndroidMediaCacheCapacityRegistry.cachedLease(
                                cacheRoot = root,
                                file = cached,
                                expectedBytes = 4L,
                                quotaBytes = 10L,
                            ),
                        )
                    }.close()
                } catch (failure: Throwable) {
                    terminalFailure = failure
                }
            }

            caller.runNext()
            worker.runNext()
            assertEquals(1, AndroidMediaCacheCapacityRegistry.pinnedFilesForTest(root))
            assertFalse(task.isCompleted, "成功结果仍在等待切回调用方 dispatcher")

            task.cancel(CancellationException("caller cancelled before lease delivery"))
            caller.runAll()

            assertTrue(task.isCompleted)
            assertIs<CancellationException>(terminalFailure)
            assertEquals(0, AndroidMediaCacheCapacityRegistry.pinnedFilesForTest(root))
        } finally {
            owner.cancel()
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `progress publication stays inside the media close boundary`() {
        val callbackEntered = CountDownLatch(1)
        val allowCallbackToFinish = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val session = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = {
                SessionHttpCredentials("uid-a", "token-a", identityEpoch = 1L)
            },
        )
        val progressFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val progressThread = thread(
            name = "media-progress-close-boundary",
            isDaemon = true,
            start = false,
        ) {
            try {
                check(
                    session.runIfOpen {
                        callbackEntered.countDown()
                        check(allowCallbackToFinish.await(5L, TimeUnit.SECONDS))
                    },
                )
            } catch (failure: Throwable) {
                progressFailure.set(failure)
            }
        }
        val closeThread = thread(
            name = "media-progress-close",
            isDaemon = true,
            start = false,
        ) {
            try {
                closeStarted.countDown()
                session.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            } finally {
                closeFinished.countDown()
            }
        }
        try {
            progressThread.start()
            assertTrue(callbackEntered.await(5L, TimeUnit.SECONDS))
            closeThread.start()
            assertTrue(closeStarted.await(5L, TimeUnit.SECONDS))
            assertFalse(
                closeFinished.await(200L, TimeUnit.MILLISECONDS),
                "close 必须等待已准入的轻量进度发布完成",
            )

            allowCallbackToFinish.countDown()
            progressThread.join(5_000L)
            closeThread.join(5_000L)

            assertFalse(progressThread.isAlive)
            assertFalse(closeThread.isAlive)
            assertEquals(null, progressFailure.get())
            assertEquals(null, closeFailure.get())
            var lateCallback = false
            assertFalse(session.runIfOpen { lateCallback = true })
            assertFalse(lateCallback, "close 完成后不得再发布进度")
        } finally {
            allowCallbackToFinish.countDown()
            progressThread.interrupt()
            closeThread.interrupt()
            progressThread.join(1_000L)
            closeThread.join(1_000L)
            if (!progressThread.isAlive && !closeThread.isAlive) session.close()
        }
    }

    @Test
    fun `android global lru recognizes generated attachment names with unicode line separators`() {
        val root = Files.createTempDirectory("teamtalk-android-media-unicode-name").toFile()
        val generated = listOf('\u0085', '\u2028', '\u2029').mapIndexed { index, separator ->
            testAttachmentCacheFile(
                root,
                "namespace-$index",
                "attachment-$index",
                "line${separator}separator-$index.txt",
            ).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(index.toByte()))
                setLastModified(index + 1L)
            }
        }
        try {
            AndroidMediaCacheCapacityRegistry.reserve(
                cacheRoot = root,
                expectedBytes = 0L,
                target = testDownloadCacheFile(root, "target-namespace", "target"),
                quotaBytes = 10L,
                maxEntries = 1,
            ).close()

            assertTrue(generated.none(File::exists), "生产者允许的附件名都必须计入全局 LRU")
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android managed cache rejects a symlink scope before creating producer directories`() {
        val root = Files.createTempDirectory("teamtalk-android-media-symlink-scope").toFile()
        val outside = Files.createTempDirectory("teamtalk-android-media-symlink-outside").toFile()
        val namespace = "symlink-namespace"
        val mediaRoot = File(root, "teamtalk-media").apply { mkdirs() }
        val scope = File(mediaRoot, sha256Hex(namespace).take(32))
        try {
            Files.createSymbolicLink(scope.toPath(), outside.toPath())

            assertFailsWith<IllegalStateException> {
                ensureManagedMediaCacheDirectory(root, namespace, "downloads")
            }
            assertFalse(File(outside, "downloads").exists())
            assertTrue(Files.isSymbolicLink(scope.toPath()), "未知 symlink 本身不得被扫描清理")
        } finally {
            scope.delete()
            outside.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun `android managed cache concurrent cold start converges on one plain directory`() {
        val root = Files.createTempDirectory("teamtalk-android-media-concurrent-create").toFile()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val results = arrayOfNulls<File>(8)
        val failures = arrayOfNulls<Throwable>(8)
        val workers = results.indices.map { index ->
            thread(isDaemon = true, name = "android-media-cache-create-$index") {
                ready.countDown()
                try {
                    check(start.await(5L, TimeUnit.SECONDS))
                    results[index] = ensureManagedMediaCacheDirectory(
                        cacheRoot = root,
                        cacheNamespace = "concurrent-namespace",
                        category = "downloads",
                    )
                } catch (failure: Throwable) {
                    failures[index] = failure
                }
            }
        }
        try {
            assertTrue(ready.await(5L, TimeUnit.SECONDS))
            start.countDown()
            workers.forEach { worker ->
                worker.join(5_000L)
                assertFalse(worker.isAlive, "并发缓存目录创建不能卡住")
            }

            assertTrue(failures.all { it == null }, failures.filterNotNull().joinToString())
            assertEquals(
                1,
                results.filterNotNull().map { it.absoluteFile.normalize().path }.distinct().size,
            )
            assertTrue(results.filterNotNull().all(File::isDirectory))
        } finally {
            start.countDown()
            workers.forEach { it.join(1_000L) }
            root.deleteRecursively()
        }
    }

    @Test
    fun `android cache hit removes an exact final symlink without touching its target`() {
        val root = Files.createTempDirectory("teamtalk-android-media-symlink-final").toFile()
        val outside = Files.createTempFile("teamtalk-android-media-target", ".bin").toFile().apply {
            writeBytes(ByteArray(4))
        }
        val link = testDownloadCacheFile(root, "namespace", "linked")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            assertEquals(
                null,
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    cacheRoot = root,
                    file = link,
                    expectedBytes = 4L,
                    quotaBytes = 10L,
                ),
            )
            assertFalse(Files.isSymbolicLink(link.toPath()))
            assertTrue(outside.isFile)
            assertEquals(4L, outside.length())
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            link.delete()
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `android cache applies one entry budget across namespaces`() {
        val root = Files.createTempDirectory("teamtalk-android-media-global-entry-lru").toFile()
        val oldest = testAttachmentCacheFile(root, "first-namespace", "oldest", "old.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf())
            setLastModified(1L)
        }
        val newer = testDownloadCacheFile(root, "second-namespace", "newer").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf())
            setLastModified(2L)
        }
        try {
            AndroidMediaCacheCapacityRegistry.reserve(
                root,
                expectedBytes = 0L,
                target = testDownloadCacheFile(root, "target-namespace", "target"),
                quotaBytes = 10L,
                maxEntries = 2,
            ).close()

            assertFalse(oldest.exists())
            assertTrue(newer.isFile)
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android cache coordinates reservations per root and explicit cleanup retires idle states`() {
        val firstRoot = Files.createTempDirectory("teamtalk-android-media-root-a").toFile()
        val secondRoot = Files.createTempDirectory("teamtalk-android-media-root-b").toFile()
        val initialStateCount = AndroidMediaCacheCapacityRegistry.stateCountForTest()
        val firstNamespace = "first-namespace"
        val secondNamespace = "second-namespace"
        try {
            val first = AndroidMediaCacheCapacityRegistry.reserve(
                firstRoot,
                expectedBytes = 6L,
                target = testDownloadCacheFile(firstRoot, firstNamespace, "first"),
                quotaBytes = 10L,
            )
            assertEquals(initialStateCount + 1, AndroidMediaCacheCapacityRegistry.stateCountForTest())
            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    firstRoot,
                    expectedBytes = 5L,
                    target = testDownloadCacheFile(firstRoot, secondNamespace, "second"),
                    quotaBytes = 10L,
                )
            }

            val isolated = AndroidMediaCacheCapacityRegistry.reserve(
                secondRoot,
                expectedBytes = 5L,
                target = testDownloadCacheFile(secondRoot, secondNamespace, "isolated"),
                quotaBytes = 10L,
            )
            assertEquals(initialStateCount + 2, AndroidMediaCacheCapacityRegistry.stateCountForTest())
            isolated.close()
            first.close()

            AndroidMediaCacheCapacityRegistry.forget(firstRoot)
            AndroidMediaCacheCapacityRegistry.forget(secondRoot)

            assertEquals(
                initialStateCount,
                AndroidMediaCacheCapacityRegistry.stateCountForTest(),
                "测试显式释放后不能遗留根目录状态",
            )
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(firstRoot)
            AndroidMediaCacheCapacityRegistry.forget(secondRoot)
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    @Test
    fun `android cache pin in one namespace protects playback from another namespace reservation`() {
        val root = Files.createTempDirectory("teamtalk-android-media-global-pin").toFile()
        val playingNamespace = "playing-namespace"
        val otherNamespace = "other-namespace"
        val targetNamespace = "target-namespace"
        val playing = testDownloadCacheFile(root, playingNamespace, "playing", "mp4").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(6))
        }
        val evictable = testAttachmentCacheFile(root, otherNamespace, "evictable", "other.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
            setLastModified(1L)
        }
        val target = testDownloadCacheFile(root, targetNamespace, "target")
        try {
            val lease = checkNotNull(
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    root,
                    playing,
                    expectedBytes = 6L,
                    quotaBytes = 10L,
                ),
            )
            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    root,
                    expectedBytes = 5L,
                    target = target,
                    quotaBytes = 10L,
                )
            }
            assertTrue(playing.isFile, "其他 namespace 的预留不能驱逐播放 pin")
            assertFalse(evictable.exists(), "未固定的跨 namespace 文件仍可参与 LRU")

            lease.close()
            AndroidMediaCacheCapacityRegistry.reserve(
                root,
                expectedBytes = 5L,
                target = target,
                quotaBytes = 10L,
            ).close()
            assertFalse(playing.exists(), "最后一个播放 owner 释放后文件可以参与 LRU")
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android global lru ignores unknown paths and names while cleaning known partials`() {
        val root = Files.createTempDirectory("teamtalk-android-media-global-boundary").toFile()
        val namespace = "known-namespace"
        val targetNamespace = "target-namespace"
        val known = testDownloadCacheFile(root, namespace, "known").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(6))
            setLastModified(2L)
        }
        val unknownName = File(known.parentFile, "manual-copy.bin").apply {
            writeBytes(ByteArray(32))
            setLastModified(1L)
        }
        val partialDirectory = mediaCacheDirectory(root, "partial-namespace", "downloads")
            .apply { mkdirs() }
        val abandonedPartial = File(partialDirectory, ".teamtalk-part-old.part").apply {
            writeBytes(ByteArray(32))
        }
        val unknownScope = File(root, "teamtalk-media/not-a-scope/downloads").apply { mkdirs() }
        val unknownScopeFile = File(unknownScope, "${sha256Hex("unknown")}.bin").apply {
            writeBytes(ByteArray(32))
        }
        val unrelated = File(root, "other-cache.bin").apply { writeBytes(ByteArray(32)) }
        val target = testDownloadCacheFile(root, targetNamespace, "target")
        try {
            checkNotNull(
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    root,
                    known,
                    expectedBytes = 6L,
                    quotaBytes = 10L,
                ),
            ).close()
            assertTrue(abandonedPartial.isFile, "cache hit 不应扫描其他 namespace")
            assertEquals(
                null,
                AndroidMediaCacheCapacityRegistry.cachedLease(
                    root,
                    target,
                    expectedBytes = 5L,
                    quotaBytes = 10L,
                ),
            )
            assertTrue(abandonedPartial.isFile, "cache miss 不应在 reserve 之前重复扫描")

            AndroidMediaCacheCapacityRegistry.reserve(
                root,
                expectedBytes = 5L,
                target = target,
                quotaBytes = 10L,
            ).close()

            assertFalse(known.exists(), "容量计算只应驱逐已知最终缓存")
            assertTrue(unknownName.isFile)
            assertTrue(unknownScopeFile.isFile)
            assertTrue(unrelated.isFile, "不得触碰 cacheRoot 内其他缓存")
            assertFalse(abandonedPartial.exists(), "已知缓存目录内的遗留 partial 应独立清理")
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android cache rejects a single file over quota before any write`() {
        val root = Files.createTempDirectory("teamtalk-android-media-single-over").toFile()
        val namespace = "test-namespace"
        val target = testDownloadCacheFile(root, namespace, "large")
        try {
            assertFailsWith<MediaCacheQuotaException> {
                AndroidMediaCacheCapacityRegistry.reserve(
                    root, expectedBytes = 11L, target = target, quotaBytes = 10L,
                )
            }
            assertFalse(target.exists())
            assertEquals(0L, AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root))
            assertEquals(0, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
        } finally {
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `owner replacement rejects atomic install and releases android cache reservation`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-android-media-owner-replacement").toFile()
        val namespace = "test-namespace"
        val target = testDownloadCacheFile(root, namespace, "owner")
        var credentials = SessionHttpCredentials("uid-a", "token-a", identityEpoch = 1L)
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { credentials },
        )
        try {
            assertFailsWith<IllegalStateException> {
                materializeMediaCacheFile(
                    target = target,
                    expectedBytes = 8L,
                    reserveCapacity = {
                        AndroidMediaCacheCapacityRegistry.reserve(
                            root, expectedBytes = 8L, target = target, quotaBytes = 10L,
                        )
                    },
                    install = mediaSession::installCacheFile,
                ) { partial ->
                    partial.writeBytes(ByteArray(8))
                    credentials = credentials.copy(accessToken = "token-b", identityEpoch = 2L)
                }
            }
            assertFalse(target.exists())
            assertEquals(0L, AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root))
            assertEquals(0, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
        } finally {
            runCatching(mediaSession::close)
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    @Test
    fun `android attachment download primitive leaves a synthetic main dispatcher before network io`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val connectThread = AtomicReference<String>()
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "synthetic-compose-main")
        }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        val root = Files.createTempDirectory("teamtalk-android-media-io").toFile()
        val session = downloadSession(payload, -1L) { connectThread.set(Thread.currentThread().name) }
        try {
            withContext(callerDispatcher) {
                downloadAttachmentToCacheLease(
                    root,
                    session,
                    attachment("io.bin", payload.size.toLong()),
                ).close()
            }
            assertFalse(connectThread.get().contains("synthetic-compose-main"))
        } finally {
            session.close()
            callerDispatcher.close()
            callerExecutor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun `thumbnail sampling stays near the requested render size`() {
        assertEquals(1, calculateBitmapSampleSize(800, 600, 800, 600))
        assertEquals(4, calculateBitmapSampleSize(4000, 3000, 800, 600))
        assertEquals(8, calculateBitmapSampleSize(8000, 6000, 800, 600))
        assertEquals(4, calculateBitmapSampleSize(10000, 10000, 3000, 3000))
        assertEquals(1, calculateBitmapSampleSize(0, 0, 800, 600))
    }

    @Test
    fun `thumbnail decode failure refreshes a corrupt cache exactly once`() = runBlocking {
        val attempts = mutableListOf<Boolean>()

        val decoded = loadAndroidThumbWithSingleRefresh { forceRefresh ->
            attempts += forceRefresh
            if (forceRefresh) "decoded" else null
        }

        assertEquals("decoded", decoded)
        assertEquals(listOf(false, true), attempts)
    }

    @Test
    fun `video thumbnail keeps aspect ratio within a small decode target`() {
        assertEquals(512 to 288, scaledVideoThumbnailSize(3840, 2160))
        assertEquals(288 to 512, scaledVideoThumbnailSize(2160, 3840))
        assertEquals(320 to 240, scaledVideoThumbnailSize(320, 240))
        assertEquals(512 to 512, scaledVideoThumbnailSize(0, 0))
    }

    @Test
    fun `media cache namespace is opaque stable and isolated by dataset deployment and owner`() {
        val first = mediaCacheNamespace(
            deployment("https://server-a.example"), TEST_MEDIA_DATASET_ID, "uid-a",
        )
        val sameSession = mediaCacheNamespace(
            deployment("https://server-a.example/"), TEST_MEDIA_DATASET_ID, "uid-a",
        )
        val anotherDataset = mediaCacheNamespace(
            deployment("https://server-a.example"), OTHER_MEDIA_DATASET_ID, "uid-a",
        )
        val anotherAccount = mediaCacheNamespace(
            deployment("https://server-a.example"), TEST_MEDIA_DATASET_ID, "uid-b",
        )
        val anotherServer = mediaCacheNamespace(
            deployment("https://server-b.example"), TEST_MEDIA_DATASET_ID, "uid-a",
        )
        val anotherTcp = mediaCacheNamespace(
            deployment("https://server-a.example", tcpPort = 5200), TEST_MEDIA_DATASET_ID, "uid-a",
        )

        assertEquals(first, sameSession)
        assertNotEquals(first, anotherDataset)
        assertNotEquals(first, anotherAccount)
        assertNotEquals(first, anotherServer)
        assertNotEquals(first, anotherTcp)
        assertFalse(first.contains("uid-a"))
        assertFalse(first.contains(TEST_MEDIA_DATASET_ID))
        assertFailsWith<IllegalArgumentException> {
            mediaCacheNamespace(deployment("https://server-a.example"), "not-canonical", "uid-a")
        }
    }

    @Test
    fun `media session follows same-user token rotation and fails closed after uid changes`() {
        val userSession = UserSession().apply {
            onAuthSuccess(
                "uid-a", "alice", "Alice", "refresh-a", "token-a1", TEST_MEDIA_DATASET_ID,
            )
        }
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example/"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = userSession::httpCredentialsSnapshot,
        )
        val originalNamespace = mediaSession.cacheNamespace

        assertEquals(TEST_MEDIA_DATASET_ID, mediaSession.datasetId)
        assertEquals(
            mediaCacheNamespace(
                deployment("https://server.example/"), TEST_MEDIA_DATASET_ID, "uid-a",
            ),
            originalNamespace,
        )
        assertEquals("token-a1", mediaSession.accessTokenForRequest())
        userSession.onAuthSuccess(
            "uid-a", "alice", "Alice", "refresh-a2", "token-a2", TEST_MEDIA_DATASET_ID,
        )
        assertEquals("token-a2", mediaSession.accessTokenForRequest())
        assertEquals(originalNamespace, mediaSession.cacheNamespace)

        userSession.onAuthFailed("retired")
        userSession.onAuthSuccess(
            "uid-b", "bob", "Bob", "refresh-b", "token-b", OTHER_MEDIA_DATASET_ID,
        )
        assertFalse(mediaSession.isCurrentOwner())
        assertFailsWith<IllegalStateException> { mediaSession.accessTokenForRequest() }

        mediaSession.close()
        mediaSession.close()
        assertFalse(mediaSession.isCurrentOwner())
        assertFailsWith<IllegalStateException> { mediaSession.accessTokenForRequest() }
    }

    @Test
    fun `media session rejects same uid replacement epoch`() {
        var credentials = SessionHttpCredentials("uid-a", "old-token", identityEpoch = 4L)
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { credentials },
        )
        assertEquals("old-token", mediaSession.accessTokenForRequest())

        credentials = SessionHttpCredentials("uid-a", "replacement-token", identityEpoch = 5L)

        assertFalse(mediaSession.isCurrentOwner())
        assertFailsWith<IllegalStateException> { mediaSession.accessTokenForRequest() }
        mediaSession.close()
    }

    @Test
    fun `current media bearer 401 carries each rejected token while 403 stays business`() = runBlocking {
        val reports = AtomicInteger()
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = {
                SessionHttpCredentials("uid-a", "token-a", identityEpoch = 4L)
            },
            onAuthExpired = { rejected ->
                assertEquals("token-a", rejected)
                reports.incrementAndGet()
            },
            connectionFactory = ::noIoConnection,
        )
        try {
            repeat(2) {
                assertSame(
                    AppError.AuthExpired,
                    assertFailsWith<AppError.AuthExpired> {
                        mediaSession.withAuthenticatedConnection("https://server.example/file") {
                            throw androidMediaDownloadHttpFailure(HttpURLConnection.HTTP_UNAUTHORIZED)
                        }
                    },
                )
            }
            assertEquals(2, reports.get())

            val forbidden = assertFailsWith<AppError.Business> {
                mediaSession.withAuthenticatedConnection("https://server.example/file") {
                    throw androidMediaDownloadHttpFailure(HttpURLConnection.HTTP_FORBIDDEN)
                }
            }
            assertEquals(HttpURLConnection.HTTP_FORBIDDEN, forbidden.code)
            assertEquals("下载失败（HTTP 403）", forbidden.message)
            assertEquals(2, reports.get())
        } finally {
            mediaSession.close()
        }
    }

    @Test
    fun `401 from a superseded Android media bearer cannot retire its successor`() = runBlocking {
        var credentials = SessionHttpCredentials("uid-a", "token-a", identityEpoch = 4L)
        val reports = AtomicInteger()
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { credentials },
            onAuthExpired = { _ -> reports.incrementAndGet() },
            connectionFactory = ::noIoConnection,
        )
        try {
            assertIs<AndroidMediaSupersededCredentialException>(
                assertFailsWith<AndroidMediaSupersededCredentialException> {
                    mediaSession.withAuthenticatedConnection("https://server.example/file") {
                        credentials = credentials.copy(accessToken = "token-b")
                        throw AppError.AuthExpired
                    }
                },
            )
            assertEquals(0, reports.get())
            assertEquals("token-b", mediaSession.accessTokenForRequest())
        } finally {
            mediaSession.close()
        }
    }

    @Test
    fun `media ownership probes isolate ordinary mismatch but propagate cancellation and fatal defects`() {
        var credentialFailure: Throwable? = null
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = {
                credentialFailure?.let { throw it }
                SessionHttpCredentials("uid-a", "token-a", identityEpoch = 6L)
            },
        )

        val cancellation = CancellationException("credential owner cancelled")
        credentialFailure = cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { mediaSession.isCurrentOwner() })

        val fatal = AssertionError("credential owner invariant")
        credentialFailure = fatal
        assertSame(fatal, assertFailsWith<AssertionError> { mediaSession.runIfOpen {} })

        mediaSession.close()
    }

    private fun noIoConnection(raw: String): HttpURLConnection = object : HttpURLConnection(URL(raw)) {
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }

    @Test
    fun `rejected operation registration promotes abort fatal over the ordinary rejection`() = runBlocking {
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 7L) },
        )
        mediaSession.close()
        val fatal = AssertionError("abort invariant")

        val thrown = assertFailsWith<AssertionError> {
            mediaSession.withRegisteredOperation(abort = { throw fatal }) { Unit }
        }

        assertSame(fatal, thrown)
        assertTrue(thrown.suppressed.any { it is IllegalStateException })
    }

    @Test
    fun `concurrent media close drains after abort fatal and replays the same terminal object`() = runBlocking {
        val operationEntered = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val abortEntered = CountDownLatch(1)
        val allowAbortToFinish = CountDownLatch(1)
        val laterCleanupReached = AtomicBoolean(false)
        val fatal = AssertionError("media abort invariant")
        val ordinaryCleanup = IOException("synthetic later cleanup failure")
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 8L) },
            connectionFactory = { raw -> URL(raw).openConnection() as HttpURLConnection },
            afterHttpGateClose = {
                laterCleanupReached.set(true)
                throw ordinaryCleanup
            },
        )
        val operation = async(Dispatchers.Default) {
            mediaSession.withRegisteredOperation(
                abort = {
                    abortEntered.countDown()
                    releaseOperation.complete(Unit)
                    check(allowAbortToFinish.await(5, TimeUnit.SECONDS)) {
                        "test did not release the abort action"
                    }
                    throw fatal
                },
            ) {
                operationEntered.complete(Unit)
                releaseOperation.await()
            }
        }
        operationEntered.await()

        val leaderFailure = AtomicReference<Throwable?>()
        val followerFailure = AtomicReference<Throwable?>()
        val followerStarted = CountDownLatch(1)
        val followerCompleted = CountDownLatch(1)
        val leader = thread(name = "android-media-close-leader") {
            leaderFailure.set(runCatching(mediaSession::close).exceptionOrNull())
        }
        var follower: Thread? = null
        try {
            assertTrue(abortEntered.await(5, TimeUnit.SECONDS), "leader never entered abort")
            follower = thread(name = "android-media-close-follower") {
                followerStarted.countDown()
                followerFailure.set(runCatching(mediaSession::close).exceptionOrNull())
                followerCompleted.countDown()
            }
            assertTrue(followerStarted.await(5, TimeUnit.SECONDS), "follower never attempted close")
            assertFalse(
                followerCompleted.await(100, TimeUnit.MILLISECONDS),
                "concurrent close must wait for the complete cleanup boundary",
            )
        } finally {
            releaseOperation.complete(Unit)
            allowAbortToFinish.countDown()
            leader.join(5_000)
            follower?.join(5_000)
        }

        operation.await()
        assertFalse(leader.isAlive)
        assertFalse(follower.isAlive)
        assertTrue(laterCleanupReached.get(), "fatal abort must not skip later owners")
        assertSame(fatal, leaderFailure.get())
        assertSame(fatal, followerFailure.get())
        assertTrue(fatal.suppressed.any { it === ordinaryCleanup })
        assertSame(fatal, runCatching(mediaSession::close).exceptionOrNull())
    }

    @Test
    fun `media close replays the same cancellation after later owners are drained`() {
        val cancellation = CancellationException("media owner cancelled")
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 10L) },
            connectionFactory = { raw -> URL(raw).openConnection() as HttpURLConnection },
            afterHttpGateClose = { throw cancellation },
        )

        assertSame(cancellation, assertFailsWith<CancellationException> { mediaSession.close() })
        assertSame(cancellation, assertFailsWith<CancellationException> { mediaSession.close() })
    }

    @Test
    fun `connection setup failure drains the admitted bearer owner and preserves fatal cleanup`() = runBlocking {
        val setupFailure = IllegalArgumentException("invalid request configuration")
        val disconnectFatal = AssertionError("connection cleanup invariant")
        val disconnectCalls = AtomicInteger()
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 11L) },
            connectionFactory = { raw ->
                object : HttpURLConnection(URL(raw)) {
                    override fun disconnect() {
                        disconnectCalls.incrementAndGet()
                        throw disconnectFatal
                    }

                    override fun usingProxy(): Boolean = false
                    override fun connect() = Unit
                }
            },
        )

        assertFailsWith<AssertionError> {
            mediaSession.withAuthenticatedConnection(
                url = "https://server.example/file",
                configure = { throw setupFailure },
            ) { Unit }
        }

        assertEquals(1, disconnectCalls.get())
        assertTrue(disconnectFatal.suppressedExceptions.any { it === setupFailure })
        assertSame(disconnectFatal, assertFailsWith<AssertionError> { mediaSession.close() })
    }

    @Test
    fun `closing media session drains a registered connection before first network use`() = runBlocking {
        val registered = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val allowFirstUse = CountDownLatch(1)
        val firstUse = AtomicBoolean(false)
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 9L) },
            connectionFactory = { raw ->
                object : HttpURLConnection(URL(raw)) {
                    override fun disconnect() {
                        disconnected.complete(Unit)
                    }

                    override fun usingProxy(): Boolean = false
                    override fun connect() {
                        firstUse.set(true)
                    }
                }
            },
            beforeFirstIo = {
                registered.complete(Unit)
                allowFirstUse.await()
            },
        )
        val operation = async(Dispatchers.IO) {
            runCatching {
                mediaSession.withAuthenticatedConnection("https://server.example/file") {
                    Unit
                }
            }
        }
        registered.await()
        val closing = async(Dispatchers.IO) { mediaSession.close() }
        disconnected.await()

        assertFalse(closing.isCompleted, "close 必须等待已经登记但尚未首用的 bearer 操作退出")
        allowFirstUse.countDown()
        assertTrue(operation.await().exceptionOrNull() is IllegalStateException)
        closing.await()

        assertFalse(firstUse.get(), "close 先完成线性化时不得再发起首次 bearer IO")
    }

    @Test
    fun `rejected HTTP registration disconnect failure is reported to concurrent close`() = runBlocking {
        val outerRegistered = CompletableDeferred<Unit>()
        val gateClosed = CompletableDeferred<Unit>()
        val allowGateRegistration = CountDownLatch(1)
        val disconnectFailure = IOException("synthetic disconnect failure")
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 9L) },
            connectionFactory = { raw ->
                object : HttpURLConnection(URL(raw)) {
                    override fun disconnect() = throw disconnectFailure
                    override fun usingProxy(): Boolean = false
                    override fun connect() = Unit
                }
            },
            beforeHttpGateRegistration = {
                outerRegistered.complete(Unit)
                allowGateRegistration.await()
            },
            afterHttpGateClose = { gateClosed.complete(Unit) },
        )
        val operation = async(Dispatchers.IO) {
            runCatching {
                mediaSession.withAuthenticatedConnection("https://server.example/file") { Unit }
            }
        }
        outerRegistered.await()
        val closing = async(Dispatchers.IO) { runCatching(mediaSession::close) }
        gateClosed.await()

        assertFalse(closing.isCompleted, "close must join the outer operation registration")
        allowGateRegistration.countDown()

        val operationFailure = operation.await().exceptionOrNull()
        assertTrue(operationFailure is IllegalStateException)
        assertTrue(operationFailure.suppressed.any { it === disconnectFailure })
        val closeFailure = closing.await().exceptionOrNull()
        assertTrue(closeFailure is IllegalStateException)
        assertTrue(closeFailure.suppressed.any { it === disconnectFailure })
        assertTrue(runCatching(mediaSession::close).exceptionOrNull() === closeFailure)
    }

    @Test
    fun `closed media session rejects late atomic cache publication`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-close-commit").toFile()
        val mediaSession = AndroidMediaSession.create(
            deploymentIdentity = deployment("https://server.example"),
            datasetId = TEST_MEDIA_DATASET_ID,
            ownerUid = "uid-a",
            credentialsProvider = { SessionHttpCredentials("uid-a", "token-a", identityEpoch = 2L) },
        )
        try {
            val target = File(root, "late.bin")
            val writerStarted = CompletableDeferred<Unit>()
            val allowWriterToFinish = CompletableDeferred<Unit>()
            val writer = async<IllegalStateException?>(Dispatchers.Default) {
                try {
                    materializeMediaCacheFile(
                        target = target,
                        install = mediaSession::installCacheFile,
                    ) { partial ->
                        partial.writeText("old-session")
                        writerStarted.complete(Unit)
                        allowWriterToFinish.await()
                    }
                    null
                } catch (expected: IllegalStateException) {
                    expected
                }
            }
            writerStarted.await()

            mediaSession.close()
            allowWriterToFinish.complete(Unit)

            assertTrue(writer.await() is IllegalStateException)
            assertFalse(target.exists(), "close 后旧任务不得发布最终缓存文件")
        } finally {
            mediaSession.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancelling a blocking media operation invokes abort before cancellation completes`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseBlockingRead = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val operation = async(Dispatchers.IO) {
            withCancellationAbort(
                abort = {
                    aborted.set(true)
                    releaseBlockingRead.countDown()
                },
            ) {
                entered.complete(Unit)
                releaseBlockingRead.await()
            }
        }
        entered.await()

        operation.cancel()

        assertTrue(aborted.get(), "页面任务取消必须同步关闭底层阻塞资源")
        assertFailsWith<kotlinx.coroutines.CancellationException> { operation.await() }
        Unit
    }

    @Test
    fun `cancellation abort propagates its fatal object after releasing the blocking body`() = runBlocking {
        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val releaseBlockingRead = CountDownLatch(1)
            val fatal = AssertionError("blocking abort invariant")
            val operation = async(Dispatchers.IO) {
                withCancellationAbort(
                    abort = {
                        releaseBlockingRead.countDown()
                        throw fatal
                    },
                ) {
                    entered.complete(Unit)
                    releaseBlockingRead.await()
                }
            }
            val completionFailure = AtomicReference<Throwable?>()
            operation.invokeOnCompletion(completionFailure::set)
            entered.await()

            operation.cancel(CancellationException("caller cancelled"))

            // Deferred.await 会应用协程栈轨迹恢复，并可能复制 Throwable。
            // Job 完成原因是所有者边界发出的权威对象。
            assertFailsWith<AssertionError> { operation.await() }
            assertSame(fatal, completionFailure.get())
            assertTrue(fatal.suppressed.any { it is CancellationException })
        }
    }

    @Test
    fun `normal media operation completion does not invoke cancellation abort`() = runBlocking {
        val abortCalls = AtomicInteger()

        val value = withCancellationAbort(abortCalls::incrementAndGet) { "complete" }

        assertEquals("complete", value)
        assertEquals(0, abortCalls.get())
    }

    @Test
    fun `session media directories never resolve to legacy global caches`() {
        val root = Files.createTempDirectory("teamtalk-media-path-test").toFile()
        try {
            val namespace = mediaCacheNamespace(
                deployment("https://server.example"), TEST_MEDIA_DATASET_ID, "uid-a",
            )
            val downloads = mediaCacheDirectory(root, namespace, "downloads")
            val attachments = mediaCacheDirectory(root, namespace, "attachments")

            assertTrue(downloads.toPath().startsWith(File(root, "teamtalk-media").toPath()))
            assertTrue(
                attachments.relativeTo(root).invariantSeparatorsPath.startsWith(FILE_PROVIDER_ATTACHMENTS_PATH),
            )
            assertFalse(
                downloads.relativeTo(root).invariantSeparatorsPath.startsWith(FILE_PROVIDER_ATTACHMENTS_PATH),
                "FileProvider 只能暴露可打开的附件，不得暴露普通下载、缩略图或录音临时文件",
            )
            assertNotEquals(File(root, "downloads").canonicalFile, downloads.canonicalFile)
            assertNotEquals(File(root, "media").canonicalFile, downloads.canonicalFile)
            assertFalse(downloads.absolutePath.contains("token-a"))
            assertFalse(downloads.absolutePath.contains("uid-a"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `final media cache survives page close and reopens without another download`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-page-reuse").toFile()
        try {
            val namespace = mediaCacheNamespace(
                deployment("https://server.example"), TEST_MEDIA_DATASET_ID, "uid-a",
            )
            val target = File(mediaCacheDirectory(root, namespace, "downloads"), "shared.png")
            val writes = AtomicInteger()
            val firstPageFile = materializeMediaCacheFile(target, expectedBytes = 6L) { partial ->
                writes.incrementAndGet()
                partial.writeText("shared")
            }
            assertEquals("shared", firstPageFile.readText())

            // 之后的页面/进程在离线状态下必须使用已定稿的对象。
            val reopenedFile = materializeMediaCacheFile(target, expectedBytes = 6L) { _ ->
                error("cache hit must not invoke the network writer")
            }

            assertEquals(target, reopenedFile)
            assertEquals("shared", reopenedFile.readText())
            assertEquals(1, writes.get())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `metadata cleanup degrades ordinary native state but preserves fatal identity`() {
        releaseAndroidMediaMetadata { throw IllegalStateException("already released") }

        val cancellation = CancellationException("metadata owner cancelled")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                releaseAndroidMediaMetadata { throw cancellation }
            },
        )

        val fatal = AssertionError("metadata release invariant")
        assertSame(
            fatal,
            assertFailsWith<AssertionError> {
                releaseAndroidMediaMetadata { throw fatal }
            },
        )
    }

    private suspend fun withDownloadSession(
        payload: ByteArray,
        declaredLength: Long,
        block: suspend (File, AndroidMediaSession) -> Unit,
    ) {
        val root = Files.createTempDirectory("teamtalk-android-media-download").toFile()
        val session = downloadSession(payload, declaredLength)
        try {
            block(root, session)
        } finally {
            session.close()
            AndroidMediaCacheCapacityRegistry.forget(root)
            root.deleteRecursively()
        }
    }

    private fun downloadSession(
        payload: ByteArray,
        declaredLength: Long,
        onConnect: () -> Unit = {},
    ): AndroidMediaSession = AndroidMediaSession.create(
        deploymentIdentity = deployment("https://server.example"),
        datasetId = TEST_MEDIA_DATASET_ID,
        ownerUid = "uid-a",
        credentialsProvider = {
            SessionHttpCredentials("uid-a", "fixed-token", identityEpoch = 1L)
        },
        connectionFactory = { raw ->
            object : HttpURLConnection(URL(raw)) {
                override fun connect() = onConnect()
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = HTTP_OK
                override fun getContentLengthLong(): Long = declaredLength
                override fun getInputStream() = ByteArrayInputStream(payload)
            }
        },
    )

    private fun attachment(name: String, size: Long) = Attachment(
        path = "uid-a/$name",
        name = name,
        contentType = "application/octet-stream",
        size = size,
    )

    private fun assertNoAndroidDownloadCache(root: File, namespace: String) {
        val cached = sequenceOf(
            mediaCacheDirectory(root, namespace, "downloads"),
            mediaCacheDirectory(root, namespace, "attachments"),
        ).flatMap { directory -> directory.walkTopDown().filter(File::isFile) }.toList()
        assertTrue(cached.isEmpty())
        assertEquals(0L, AndroidMediaCacheCapacityRegistry.reservedBytesForTest(root))
        assertEquals(0, AndroidMediaCacheCapacityRegistry.reservedEntriesForTest(root))
    }

    private fun testDownloadCacheFile(
        root: File,
        namespace: String,
        discriminator: String,
        extension: String = "bin",
    ): File = File(
        ensureManagedMediaCacheDirectory(root, namespace, "downloads"),
        "${sha256Hex(discriminator)}.$extension",
    )

    private fun testAttachmentCacheFile(
        root: File,
        namespace: String,
        discriminator: String,
        leaf: String,
    ): File = File(
        ensureManagedMediaCacheDirectory(root, namespace, "attachments"),
        "${sha256Hex(discriminator).take(32)}-$leaf",
    )

    private fun deployment(
        serverUrl: String,
        tcpPort: Int = 5100,
    ): DeploymentIdentity = DeploymentIdentity.from(
        tcpHost = java.net.URI(serverUrl).host,
        tcpPort = tcpPort,
        serverUrl = serverUrl,
    )
}

private class QueuedMediaTestDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runNext() {
        check(tasks.isNotEmpty()) { "expected a queued coroutine task" }
        tasks.removeFirst().run()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
