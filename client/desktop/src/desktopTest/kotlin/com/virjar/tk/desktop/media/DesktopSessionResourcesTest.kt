package com.virjar.tk.desktop.media

import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.log.NoopLogger
import com.virjar.tk.shared.log.TkLogger
import com.virjar.tk.shared.repository.FileOps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TEST_MEDIA_DATASET_ID = "00000000-0000-4000-8000-000000000001"
private const val OTHER_MEDIA_DATASET_ID = "00000000-0000-4000-8000-000000000002"

class DesktopSessionResourcesTest {

    @Test
    fun `constructor cleanup promotes cancellation or VM fatal and retains construction failure`() {
        val constructionFailure = IllegalStateException("media graph construction failed")
        val ordinaryCleanupFailure = IOException("ordinary cleanup failed")
        val cancellation = CancellationException("media owner cancelled during cleanup")

        val terminal = terminalDesktopMediaConstructionFailure(
            constructionFailure = constructionFailure,
            cleanupFailures = listOf(ordinaryCleanupFailure, cancellation),
        )

        assertSame(cancellation, terminal)
        assertTrue(terminal.suppressed.any { it === constructionFailure })
        assertTrue(terminal.suppressed.any { it === ordinaryCleanupFailure })

        val secondConstructionFailure = IllegalStateException("second construction failed")
        val fatal = AssertionError("cleanup invariant failed")
        val fatalTerminal = terminalDesktopMediaConstructionFailure(
            constructionFailure = secondConstructionFailure,
            cleanupFailures = listOf(fatal),
        )
        assertSame(fatal, fatalTerminal)
        assertTrue(fatalTerminal.suppressed.any { it === secondConstructionFailure })
    }

    @Test
    fun `http downloader cancellation disconnects a blocking read`() = runBlocking {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val disconnectCalls = AtomicInteger()
        val downloader = HttpDesktopMediaDownloader(
            connectionFactory = { raw ->
                object : HttpURLConnection(URL(raw)) {
                    override fun disconnect() {
                        disconnectCalls.incrementAndGet()
                        releaseRead.countDown()
                    }

                    override fun usingProxy(): Boolean = false
                    override fun connect() = Unit
                    override fun getResponseCode(): Int = HTTP_OK
                    override fun getContentLengthLong(): Long = 1L
                    override fun getInputStream(): InputStream = object : InputStream() {
                        override fun read(): Int = error("bulk read expected")

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            readEntered.countDown()
                            check(releaseRead.await(5, TimeUnit.SECONDS))
                            throw IOException("connection aborted")
                        }
                    }
                }
            },
        )
        val partial = Files.createTempFile("desktop-http-cancel", ".part").toFile()
        val cancellation = CancellationException("desktop page retired")
        try {
            val download = async(kotlinx.coroutines.Dispatchers.IO) {
                downloader.download(
                    DesktopMediaDownloadRequest("https://server.example/file", "secret", 1L),
                    partial,
                ) {}
            }
            assertTrue(readEntered.await(5, TimeUnit.SECONDS))

            download.cancel(cancellation)
            val terminal = assertFailsWith<CancellationException> {
                withTimeout(5_000) { download.await() }
            }

            assertEquals(cancellation.message, terminal.message)
            assertEquals(1, disconnectCalls.get())
        } finally {
            releaseRead.countDown()
            downloader.close()
            partial.delete()
        }
    }

    @Test
    fun `http downloader close drains a registered request before first io`() = runBlocking {
        val registered = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val allowFirstUse = CountDownLatch(1)
        val firstUse = AtomicBoolean(false)
        val downloader = HttpDesktopMediaDownloader(
            connectionFactory = { raw ->
                object : HttpURLConnection(URL(raw)) {
                    override fun disconnect() {
                        disconnected.complete(Unit)
                    }

                    override fun usingProxy(): Boolean = false
                    override fun connect() = Unit
                    override fun getResponseCode(): Int {
                        firstUse.set(true)
                        return HTTP_OK
                    }

                    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                }
            },
            beforeFirstIo = {
                registered.complete(Unit)
                allowFirstUse.await()
            },
        )
        val partial = Files.createTempFile("desktop-http-close", ".part").toFile()
        try {
            val download = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    downloader.download(
                        DesktopMediaDownloadRequest("https://server.example/file", "secret", 0L),
                        partial,
                    ) {}
                }
            }
            registered.await()
            val closing = async(kotlinx.coroutines.Dispatchers.IO) { downloader.close() }
            disconnected.await()

            assertFalse(closing.isCompleted, "close 必须等待已登记请求退出")
            allowFirstUse.countDown()
            assertTrue(download.await().exceptionOrNull() is IllegalStateException)
            closing.await()
            assertFalse(firstUse.get(), "close 先完成线性化时不得再发起首次 bearer IO")
        } finally {
            allowFirstUse.countDown()
            downloader.close()
            partial.delete()
        }
    }

    @Test
    fun `same server and attachment are isolated by account`() = runBlocking {
        withTempDirectory { dataDir ->
            val seenTokens = mutableListOf<String>()
            val downloader = downloader { request, partial ->
                synchronized(seenTokens) { seenTokens += request.authorizationToken }
                partial.writeText(request.authorizationToken)
            }
            val alice = resources(dataDir, uid = "alice", token = "alice-token", downloader = downloader)
            val bob = resources(dataDir, uid = "bob", token = "bob-token", downloader = downloader)
            try {
                val aliceFile = alice.mediaCache.ensureDownloaded(
                    "files/private.txt", "private.txt", "alice-token".length.toLong(),
                )
                val bobFile = bob.mediaCache.ensureDownloaded(
                    "files/private.txt", "private.txt", "bob-token".length.toLong(),
                )

                assertNotEquals(alice.mediaDirectory, bob.mediaDirectory)
                assertNotEquals(aliceFile.absolutePath, bobFile.absolutePath)
                assertEquals("alice-token", aliceFile.readText())
                assertEquals("bob-token", bobFile.readText())
                assertEquals(setOf("alice-token", "bob-token"), seenTokens.toSet())
            } finally {
                alice.close()
                bob.close()
            }
        }
    }

    @Test
    fun `same account on different servers has different cache root`() = runBlocking {
        withTempDirectory { dataDir ->
            val first = resources(dataDir, server = "https://one.example", downloader = downloader())
            val second = resources(dataDir, server = "https://two.example", downloader = downloader())
            try {
                assertNotEquals(first.serverFingerprint, second.serverFingerprint)
                assertNotEquals(first.mediaDirectory, second.mediaDirectory)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun `same deployment account on rebuilt dataset cannot reuse old media cache`() = runBlocking {
        withTempDirectory { dataDir ->
            val networkCalls = AtomicInteger()
            val datasetDownloader = downloader { _, partial ->
                networkCalls.incrementAndGet()
                partial.writeText("same")
            }
            val first = resources(
                dataDir,
                datasetId = TEST_MEDIA_DATASET_ID,
                downloader = datasetDownloader,
            )
            val firstDirectory = first.mediaDirectory
            try {
                first.mediaCache.ensureDownloaded("owner/same.bin", "same.bin", 4L)
            } finally {
                first.close()
            }

            val rebuilt = resources(
                dataDir,
                datasetId = OTHER_MEDIA_DATASET_ID,
                downloader = datasetDownloader,
            )
            try {
                assertNotEquals(first.sessionFingerprint, rebuilt.sessionFingerprint)
                assertNotEquals(firstDirectory, rebuilt.mediaDirectory)
                assertEquals(
                    null,
                    rebuilt.mediaCache.cachedFile("owner/same.bin", "same.bin", 4L),
                )
                rebuilt.mediaCache.ensureDownloaded("owner/same.bin", "same.bin", 4L)
                assertEquals(2, networkCalls.get(), "新 dataset 必须重新下载而不是复用旧文件")
            } finally {
                rebuilt.close()
            }
            assertFailsWith<IllegalArgumentException> {
                resources(dataDir, datasetId = "not-canonical", downloader = downloader())
            }
        }
    }

    @Test
    fun `same HTTP base on different TCP deployments has different cache root`() = runBlocking {
        withTempDirectory { dataDir ->
            val first = resources(
                dataDir,
                deploymentIdentity = deployment("https://files.example", tcpPort = 5100),
                downloader = downloader(),
            )
            val second = resources(
                dataDir,
                deploymentIdentity = deployment("https://files.example", tcpPort = 5200),
                downloader = downloader(),
            )
            try {
                assertNotEquals(first.serverFingerprint, second.serverFingerprint)
                assertNotEquals(first.mediaDirectory, second.mediaDirectory)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun `same owner sees rotated token while changed identity is rejected`() = runBlocking {
        withTempDirectory { dataDir ->
            var currentUid = "owner"
            var currentToken = "token-a"
            var identityEpoch = 4L
            val seenTokens = mutableListOf<String>()
            val resources = DesktopSessionResources(
                ownerUid = "owner",
                datasetId = TEST_MEDIA_DATASET_ID,
                deploymentIdentity = deployment("https://chat.example"),
                credentialProvider = { SessionHttpCredentials(currentUid, currentToken, identityEpoch) },
                dataDir = dataDir,
                diagnosticLogger = NoopLogger,
                downloader = downloader { request, partial ->
                    seenTokens += request.authorizationToken
                    partial.writeText(request.authorizationToken)
                },
            )
            try {
                resources.mediaCache.ensureDownloaded("owner/first.bin", expectedBytes = currentToken.length.toLong())
                currentToken = "token-b"
                resources.mediaCache.ensureDownloaded("owner/second.bin", expectedBytes = currentToken.length.toLong())
                assertEquals(listOf("token-a", "token-b"), seenTokens)

                identityEpoch = 5L
                currentToken = "same-uid-replacement-token"
                assertFailsWith<IllegalStateException> {
                    resources.mediaCache.ensureDownloaded("owner/replacement.bin", expectedBytes = 1L)
                }

                currentUid = "other"
                currentToken = "other-token"
                assertFailsWith<IllegalStateException> {
                    resources.mediaCache.cachedFile(
                        "owner/first.bin",
                        expectedBytes = "token-a".length.toLong(),
                    )
                }
                assertFailsWith<IllegalStateException> {
                    resources.mediaCache.ensureDownloaded("owner/third.bin", expectedBytes = 1L)
                }
                assertEquals(listOf("token-a", "token-b"), seenTokens)
            } finally {
                resources.close()
            }
        }
    }

    @Test
    fun `UI result gate only converts closed or replaced owner into non delivery`() = runBlocking {
        withTempDirectory { dataDir ->
            var credentials = SessionHttpCredentials(
                uid = "owner",
                accessToken = "fixed-token",
                identityEpoch = 7L,
            )
            var providerFailure: Throwable? = null
            val resources = DesktopSessionResources(
                ownerUid = "owner",
                datasetId = TEST_MEDIA_DATASET_ID,
                deploymentIdentity = deployment("https://chat.example"),
                credentialProvider = {
                    providerFailure?.let { throw it }
                    credentials
                },
                dataDir = dataDir,
                diagnosticLogger = NoopLogger,
                downloader = downloader(),
            )
            try {
                assertTrue(resources.canDeliverUiResult())

                val cancellation = CancellationException("UI owner cancelled")
                providerFailure = cancellation
                assertSame(
                    cancellation,
                    assertFailsWith<CancellationException> { resources.canDeliverUiResult() },
                )

                val fatal = AssertionError("credential provider invariant")
                providerFailure = fatal
                assertSame(fatal, assertFailsWith<AssertionError> { resources.canDeliverUiResult() })

                val ordinaryBug = IllegalStateException("unexpected credential provider failure")
                providerFailure = ordinaryBug
                assertSame(
                    ordinaryBug,
                    assertFailsWith<IllegalStateException> { resources.canDeliverUiResult() },
                )

                providerFailure = null
                credentials = credentials.copy(identityEpoch = 8L)
                assertFalse(resources.canDeliverUiResult())
            } finally {
                providerFailure = null
                resources.close()
            }
            assertFalse(resources.canDeliverUiResult())
        }
    }

    @Test
    fun `concurrent callers share one download and only publish complete file`() = runBlocking {
        withTempDirectory { dataDir ->
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resources = resources(
                dataDir,
                downloader = downloader { _, partial ->
                    calls.incrementAndGet()
                    partial.writeText("partial")
                    started.complete(Unit)
                    release.await()
                    partial.appendText("-complete")
                },
            )
            try {
                val downloads = List(8) {
                    async {
                        resources.mediaCache.ensureDownloaded(
                            "owner/report.md",
                            "report.md",
                            "partial-complete".length.toLong(),
                        )
                    }
                }
                started.await()
                assertEquals(1, calls.get())
                assertEquals(
                    null,
                    resources.mediaCache.cachedFile(
                        "owner/report.md",
                        "report.md",
                        "partial-complete".length.toLong(),
                    ),
                )
                assertTrue(
                    resources.mediaDirectory.listFiles().orEmpty()
                        .filter(File::isFile)
                        .all { it.name.endsWith(".part") },
                )

                release.complete(Unit)
                val files = downloads.awaitAll()
                assertEquals(1, calls.get())
                assertEquals(1, files.map(File::getAbsolutePath).distinct().size)
                assertEquals("partial-complete", files.first().readText())
                assertTrue(
                    resources.mediaDirectory.listFiles().orEmpty()
                        .filter(File::isFile)
                        .none { it.name.endsWith(".part") },
                )
            } finally {
                resources.close()
            }
        }
    }

    @Test
    fun `failed transfer removes partial and never creates cache hit`() = runBlocking {
        withTempDirectory { dataDir ->
            val resources = resources(
                dataDir,
                downloader = downloader { _, partial ->
                    partial.writeText("secret-prefix")
                    error("network failed")
                },
            )
            try {
                assertFailsWith<IllegalStateException> {
                    resources.mediaCache.ensureDownloaded(
                        "owner/failure.txt",
                        "failure.txt",
                        "secret-prefix".length.toLong(),
                    )
                }
                assertEquals(
                    null,
                    resources.mediaCache.cachedFile(
                        "owner/failure.txt",
                        "failure.txt",
                        "secret-prefix".length.toLong(),
                    ),
                )
                assertTrue(resources.mediaDirectory.listFiles().orEmpty().none(File::isFile))
            } finally {
                resources.close()
            }
        }
    }

    @Test
    fun `closing session cancels transfer and closes credential gate idempotently`() = runBlocking {
        withTempDirectory { dataDir ->
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            val resources = resources(
                dataDir,
                downloader = downloader { _, partial ->
                    partial.writeText("incomplete")
                    started.complete(Unit)
                    never.await()
                },
            )
            val download = async {
                resources.mediaCache.ensureDownloaded(
                    "owner/video.mp4",
                    "video.mp4",
                    "incomplete".length.toLong(),
                )
            }
            started.await()

            resources.close()
            resources.close()

            assertFailsWith<IllegalStateException> { resources.ensureOpen() }
            assertFailsWith<IllegalStateException> { resources.credentialGate.requireAccessToken() }
            assertFailsWith<kotlinx.coroutines.CancellationException> { download.await() }
            assertTrue(
                resources.mediaDirectory.listFiles().orEmpty()
                    .filter(File::isFile)
                    .none { !it.name.endsWith(".part") },
            )
        }
    }

    @Test
    fun `late downloader progress cannot escape a closed session`() = runBlocking {
        withTempDirectory { dataDir ->
            val started = CompletableDeferred<Unit>()
            val waitForClose = CompletableDeferred<Unit>()
            val callbackCount = AtomicInteger()
            val resources = resources(
                dataDir,
                downloader = DesktopMediaDownloader { _, partial, progress ->
                    partial.writeText("incomplete")
                    started.complete(Unit)
                    try {
                        waitForClose.await()
                    } catch (_: CancellationException) {
                        // 模拟一个在展开栈回退时仍上报一次过期进度值的传输层。
                        progress(0.75f)
                        throw CancellationException("closed")
                    }
                    partial.length()
                },
            )
            val download = async {
                resources.mediaCache.ensureDownloaded(
                    "owner/late.bin",
                    expectedBytes = "incomplete".length.toLong(),
                ) {
                    callbackCount.incrementAndGet()
                }
            }
            started.await()

            resources.close()

            assertFailsWith<CancellationException> { download.await() }
            assertEquals(0, callbackCount.get())
        }
    }

    @Test
    fun `closing session synchronously aborts a blocking authenticated downloader`() = runBlocking {
        withTempDirectory { dataDir ->
            val started = CompletableDeferred<Unit>()
            val releasedByClose = CompletableDeferred<Unit>()
            val downloaderClosed = AtomicBoolean(false)
            val downloader = object : DesktopMediaDownloader {
                override suspend fun download(
                    request: DesktopMediaDownloadRequest,
                    partialFile: File,
                    onProgress: (Float) -> Unit,
                ): Long {
                    partialFile.writeText(request.authorizationToken)
                    started.complete(Unit)
                    releasedByClose.await()
                    return partialFile.length()
                }

                override fun close() {
                    downloaderClosed.set(true)
                    releasedByClose.complete(Unit)
                }
            }
            val resources = resources(dataDir, downloader = downloader)
            val download = async {
                resources.mediaCache.ensureDownloaded(
                    "owner/blocking.bin",
                    "blocking.bin",
                    "fixed-token".length.toLong(),
                )
            }
            started.await()

            resources.close()

            assertTrue(downloaderClosed.get(), "session close 必须同步关闭认证下载器")
            assertFailsWith<CancellationException> { download.await() }
            assertTrue(
                resources.mediaDirectory.listFiles().orEmpty().none { it.isFile && !it.name.endsWith(".part") },
                "旧下载在 close 后不得发布最终缓存",
            )
        }
    }

    @Test
    fun `concurrent and repeated session close joins drain and replays failure`() = runBlocking {
        withTempDirectory { dataDir ->
            val closeEntered = CountDownLatch(1)
            val allowClose = CountDownLatch(1)
            val closeCalls = AtomicInteger()
            val drainFailure = IllegalStateException("synthetic downloader close failure")
            val downloader = object : DesktopMediaDownloader {
                override suspend fun download(
                    request: DesktopMediaDownloadRequest,
                    partialFile: File,
                    onProgress: (Float) -> Unit,
                ): Long = error("download is not used by this test")

                override fun close() {
                    closeCalls.incrementAndGet()
                    closeEntered.countDown()
                    check(allowClose.await(5, TimeUnit.SECONDS)) { "test did not release downloader close" }
                    throw drainFailure
                }
            }
            val resources = resources(dataDir, downloader = downloader)
            val leaderFailure = AtomicReference<Throwable?>()
            val followerFailure = AtomicReference<Throwable?>()
            val leader = thread(name = "desktop-session-close-leader") {
                leaderFailure.set(runCatching(resources::close).exceptionOrNull())
            }
            var follower: Thread? = null
            try {
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "leader never entered downloader close")
                val followerStarted = CountDownLatch(1)
                val followerThread = thread(name = "desktop-session-close-follower") {
                    followerStarted.countDown()
                    followerFailure.set(runCatching(resources::close).exceptionOrNull())
                }
                follower = followerThread
                assertTrue(followerStarted.await(5, TimeUnit.SECONDS))
                assertTrue(
                    awaitBlockedCloseCaller(followerThread),
                    "concurrent close must wait for the leader's downloader drain",
                )
            } finally {
                allowClose.countDown()
                leader.join(5_000)
                follower?.join(5_000)
            }

            assertFalse(leader.isAlive, "leader close did not finish")
            assertFalse(follower.isAlive, "follower close did not finish")
            val completedFailure = leaderFailure.get()
            assertTrue(completedFailure is IllegalStateException)
            assertTrue(completedFailure.suppressed.any { it === drainFailure })
            assertTrue(followerFailure.get() === completedFailure)
            assertTrue(runCatching(resources::close).exceptionOrNull() === completedFailure)
            assertEquals(1, closeCalls.get())
        }
    }

    @Test
    fun `concurrent session close drains and replays the same fatal object`() = runBlocking {
        withTempDirectory { dataDir ->
            val closeEntered = CountDownLatch(1)
            val allowClose = CountDownLatch(1)
            val fatal = AssertionError("synthetic downloader close invariant")
            val downloader = object : DesktopMediaDownloader {
                override suspend fun download(
                    request: DesktopMediaDownloadRequest,
                    partialFile: File,
                    onProgress: (Float) -> Unit,
                ): Long = error("download is not used by this test")

                override fun close() {
                    closeEntered.countDown()
                    check(allowClose.await(5, TimeUnit.SECONDS)) { "test did not release downloader close" }
                    throw fatal
                }
            }
            val resources = resources(dataDir, downloader = downloader)
            val leaderFailure = AtomicReference<Throwable?>()
            val followerFailure = AtomicReference<Throwable?>()
            val followerStarted = CountDownLatch(1)
            val followerCompleted = CountDownLatch(1)
            val leader = thread(name = "desktop-session-fatal-close-leader") {
                leaderFailure.set(runCatching(resources::close).exceptionOrNull())
            }
            var follower: Thread? = null
            try {
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "leader never entered downloader close")
                follower = thread(name = "desktop-session-fatal-close-follower") {
                    followerStarted.countDown()
                    followerFailure.set(runCatching(resources::close).exceptionOrNull())
                    followerCompleted.countDown()
                }
                assertTrue(followerStarted.await(5, TimeUnit.SECONDS), "follower never attempted close")
                assertFalse(
                    followerCompleted.await(100, TimeUnit.MILLISECONDS),
                    "concurrent close must join the fatal cleanup boundary",
                )
            } finally {
                allowClose.countDown()
                leader.join(5_000)
                follower?.join(5_000)
            }

            assertFalse(leader.isAlive)
            assertFalse(follower.isAlive)
            assertSame(fatal, leaderFailure.get())
            assertSame(fatal, followerFailure.get())
            assertSame(fatal, runCatching(resources::close).exceptionOrNull())
            assertFailsWith<IllegalStateException> { resources.ensureOpen() }
            assertFalse(resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_OPEN_FAILED))
        }
    }

    @Test
    fun `session close replays the same cancellation terminal object`() = runBlocking {
        withTempDirectory { dataDir ->
            val cancellation = CancellationException("desktop media owner cancelled")
            val resources = resources(
                dataDir = dataDir,
                downloader = object : DesktopMediaDownloader {
                    override suspend fun download(
                        request: DesktopMediaDownloadRequest,
                        partialFile: File,
                        onProgress: (Float) -> Unit,
                    ): Long = error("download is not used by this test")

                    override fun close() {
                        throw cancellation
                    }
                },
            )

            assertSame(cancellation, assertFailsWith<CancellationException> { resources.close() })
            assertSame(cancellation, assertFailsWith<CancellationException> { resources.close() })
            assertFailsWith<IllegalStateException> { resources.ensureOpen() }
        }
    }

    @Test
    fun `reentrant session close throws instead of returning through cleanup`() = runBlocking {
        withTempDirectory { dataDir ->
            val reentrantClose = AtomicReference<Result<Unit>?>()
            lateinit var resources: DesktopSessionResources
            val downloader = object : DesktopMediaDownloader {
                override suspend fun download(
                    request: DesktopMediaDownloadRequest,
                    partialFile: File,
                    onProgress: (Float) -> Unit,
                ): Long = error("download is not used by this test")

                override fun close() {
                    reentrantClose.set(runCatching(resources::close))
                }
            }
            resources = resources(dataDir, downloader = downloader)

            resources.close()

            val failure = reentrantClose.get()?.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure.message.orEmpty().contains("重入"))
            resources.close()
        }
    }

    @Test
    fun `active downloader reentrant close hands drain and failure to external closer`() = runBlocking {
        withTempDirectory { dataDir ->
            val reentrantClose = AtomicReference<Result<Unit>?>()
            val reentrantAttempted = CountDownLatch(1)
            val allowOperationExit = CountDownLatch(1)
            val firstUse = AtomicBoolean(false)
            val disconnectCalls = AtomicInteger()
            val disconnectFailure = IllegalStateException("synthetic reentrant disconnect failure")
            lateinit var resources: DesktopSessionResources
            val downloader = HttpDesktopMediaDownloader(
                connectionFactory = { raw ->
                    object : HttpURLConnection(URL(raw)) {
                        override fun disconnect() {
                            disconnectCalls.incrementAndGet()
                            throw disconnectFailure
                        }

                        override fun usingProxy(): Boolean = false
                        override fun connect() {
                            firstUse.set(true)
                        }
                    }
                },
                beforeFirstIo = {
                    reentrantClose.set(runCatching(resources::close))
                    reentrantAttempted.countDown()
                    check(allowOperationExit.await(5, TimeUnit.SECONDS)) {
                        "test did not release the admitted downloader operation"
                    }
                },
            )
            resources = resources(dataDir, downloader = downloader)
            val download = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    resources.mediaCache.ensureDownloaded("owner/reentrant.bin", "reentrant.bin", 1L)
                }
            }
            assertTrue(reentrantAttempted.await(5, TimeUnit.SECONDS))

            val externalClose = AtomicReference<Result<Unit>?>()
            val externalStarted = CountDownLatch(1)
            val externalCloser = thread(name = "desktop-session-external-close") {
                externalStarted.countDown()
                externalClose.set(runCatching(resources::close))
            }
            try {
                assertTrue(externalStarted.await(5, TimeUnit.SECONDS))
                assertTrue(
                    awaitBlockedCloseCaller(externalCloser),
                    "external close must take over and join the reentrant downloader operation",
                )
            } finally {
                allowOperationExit.countDown()
                externalCloser.join(5_000)
            }

            assertFalse(externalCloser.isAlive, "external close did not finish after operation exit")
            val boundaryFailure = reentrantClose.get()?.exceptionOrNull()
            assertTrue(boundaryFailure is IllegalStateException)
            assertTrue(boundaryFailure.message.orEmpty().contains("重入"))
            val completedFailure = externalClose.get()?.exceptionOrNull()
            assertTrue(completedFailure is IllegalStateException)
            assertTrue(
                completedFailure.suppressed.any { child ->
                    child.suppressed.any { it === disconnectFailure }
                },
            )
            assertTrue(runCatching(resources::close).exceptionOrNull() === completedFailure)
            assertTrue(download.await().isFailure)
            assertEquals(1, disconnectCalls.get())
            assertFalse(firstUse.get(), "reentrant close must seal the gate before first bearer IO")
        }
    }

    @Test
    fun `quota evicts least recently used completed entry`() = runBlocking {
        withTempDirectory { dataDir ->
            val resources = resources(
                dataDir,
                quotaBytes = 25,
                downloader = downloader { request, partial ->
                    partial.writeBytes(ByteArray(10) { request.resolvedUrl.last().code.toByte() })
                },
            )
            try {
                val first = resources.mediaCache.ensureDownloaded("owner/1.bin", "1.bin", 10L)
                first.setLastModified(1)
                val second = resources.mediaCache.ensureDownloaded("owner/2.bin", "2.bin", 10L)
                second.setLastModified(2)
                val third = resources.mediaCache.ensureDownloaded("owner/3.bin", "3.bin", 10L)

                assertFalse(first.exists())
                assertTrue(second.exists())
                assertTrue(third.exists())
                assertTrue(
                    resources.mediaDirectory.listFiles().orEmpty()
                        .filter(File::isFile)
                        .sumOf(File::length) <= 25,
                )
            } finally {
                resources.close()
            }
        }
    }

    @Test
    fun `malicious attachment name cannot influence cache path`() = runBlocking {
        withTempDirectory { dataDir ->
            val resources = resources(dataDir, downloader = downloader())
            try {
                val file = resources.mediaCache.ensureDownloaded(
                    reference = "owner/object",
                    suggestedFileName = "../../escape.<script>/../TXT",
                    expectedBytes = expectedDownloadBytes("owner/object"),
                )
                assertEquals(resources.mediaDirectory.canonicalFile, file.parentFile.canonicalFile)
                assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.[a-z0-9]{1,10}")))
                assertFalse(File(dataDir, "escape").exists())
            } finally {
                resources.close()
            }
        }
    }

    @Test
    fun `server identity normalization is stable and rejects credentials`() {
        assertEquals("https://example.com/base", canonicalDesktopServerBase("HTTPS://EXAMPLE.COM:443/base/"))
        assertFailsWith<IllegalArgumentException> { canonicalDesktopServerBase("https://user@example.com") }
        assertFailsWith<IllegalArgumentException> { canonicalDesktopServerBase("https://example.com?q=1") }
    }

    @Test
    fun `session diagnostics are redacted and reject retained tasks after close`() = runBlocking {
        withTempDirectory { dataDir ->
            val logger = RecordingTkLogger()
            val resources = resources(
                dataDir = dataDir,
                diagnosticLogger = logger,
                downloader = downloader(),
            )
            val secretReference = "owner/private/acquisition-plan-2026.txt"
            val secretName = "board-only-acquisition-plan.txt"

            resources.mediaCache.ensureDownloaded(
                secretReference,
                secretName,
                expectedDownloadBytes(secretReference),
            )
            assertTrue(resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED))

            val messagesBeforeClose = logger.messages.toList()
            assertEquals(
                listOf("trace:media cache stored", "fault:file download failed"),
                messagesBeforeClose,
            )
            assertFalse(messagesBeforeClose.any { secretReference in it || secretName in it })

            resources.close()
            assertFalse(resources.diagnostics.record(DesktopSessionDiagnosticEvent.FILE_OPEN_FAILED))
            assertEquals(messagesBeforeClose, logger.messages)
        }
    }

    @Test
    fun `session diagnostics isolate ordinary logger failures but propagate cancellation and fatal defects`() {
        val logger = ThrowingTkLogger(IOException("ordinary logger failure"))
        val diagnostics = DesktopSessionDiagnostics(logger)

        assertTrue(diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED))

        val cancellation = CancellationException("logger owner cancelled")
        logger.failure = cancellation
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED)
            },
        )

        val fatal = AssertionError("logger invariant")
        logger.failure = fatal
        assertSame(
            fatal,
            assertFailsWith<AssertionError> {
                diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED)
            },
        )

        diagnostics.close()
        assertFalse(diagnostics.record(DesktopSessionDiagnosticEvent.FILE_DOWNLOAD_FAILED))
    }

    private fun resources(
        dataDir: File,
        uid: String = "owner",
        datasetId: String = TEST_MEDIA_DATASET_ID,
        token: String = "fixed-token",
        server: String = "https://chat.example",
        deploymentIdentity: DeploymentIdentity = deployment(server),
        quotaBytes: Long = DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES,
        diagnosticLogger: TkLogger = NoopLogger,
        downloader: DesktopMediaDownloader,
    ) = DesktopSessionResources(
        ownerUid = uid,
        datasetId = datasetId,
        deploymentIdentity = deploymentIdentity,
        credentialProvider = { SessionHttpCredentials(uid, token) },
        dataDir = dataDir,
        diagnosticLogger = diagnosticLogger,
        quotaBytes = quotaBytes,
        downloader = downloader,
    )

    private fun deployment(
        server: String,
        tcpPort: Int = 5100,
    ): DeploymentIdentity = DeploymentIdentity.from(
        tcpHost = java.net.URI(server).host,
        tcpPort = tcpPort,
        serverUrl = server,
    )

    private fun downloader(
        block: suspend (DesktopMediaDownloadRequest, File) -> Unit = { request, partial ->
            partial.writeText(request.resolvedUrl)
        },
    ) = DesktopMediaDownloader { request, partial, _ ->
        block(request, partial)
        partial.length()
    }

    private fun expectedDownloadBytes(
        reference: String,
        server: String = "https://chat.example",
    ): Long = FileOps.resolveUrl(server, reference).toByteArray().size.toLong()

    private fun awaitBlockedCloseCaller(caller: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (caller.isAlive && System.nanoTime() < deadline) {
            when (caller.state) {
                Thread.State.BLOCKED,
                Thread.State.WAITING,
                Thread.State.TIMED_WAITING,
                -> return true

                else -> Thread.yield()
            }
        }
        return false
    }

    private suspend fun withTempDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-media-test").toFile()
        try {
            block(directory)
        } finally {
            // 该目录由本测试独占，永远不会包含用户数据。
            directory.deleteRecursively()
        }
    }

    private class RecordingTkLogger : TkLogger {
        val messages = mutableListOf<String>()

        override fun trace(msg: String) {
            messages += "trace:$msg"
        }

        override fun fault(msg: String, t: Throwable?) {
            messages += "fault:$msg"
        }
    }

    private class ThrowingTkLogger(
        var failure: Throwable,
    ) : TkLogger {
        override fun trace(msg: String) {
            throw failure
        }

        override fun fault(msg: String, t: Throwable?) {
            throw failure
        }
    }
}
