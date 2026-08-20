package com.virjar.tk.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopSessionResourcesTest {

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
                val aliceFile = alice.mediaCache.ensureDownloaded("files/private.txt", "private.txt")
                val bobFile = bob.mediaCache.ensureDownloaded("files/private.txt", "private.txt")

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
    fun `same owner sees rotated token while changed identity is rejected`() = runBlocking {
        withTempDirectory { dataDir ->
            var currentUid = "owner"
            var currentToken = "token-a"
            val seenTokens = mutableListOf<String>()
            val resources = DesktopSessionResources(
                ownerUid = "owner",
                serverUrl = "https://chat.example",
                credentialProvider = { DesktopCredentialSnapshot(currentUid, currentToken) },
                dataDir = dataDir,
                downloader = downloader { request, partial ->
                    seenTokens += request.authorizationToken
                    partial.writeText(request.authorizationToken)
                },
            )
            try {
                resources.mediaCache.ensureDownloaded("owner/first.bin")
                currentToken = "token-b"
                resources.mediaCache.ensureDownloaded("owner/second.bin")
                assertEquals(listOf("token-a", "token-b"), seenTokens)

                currentUid = "other"
                currentToken = "other-token"
                assertFailsWith<IllegalStateException> {
                    resources.mediaCache.cachedFile("owner/first.bin")
                }
                assertFailsWith<IllegalStateException> {
                    resources.mediaCache.ensureDownloaded("owner/third.bin")
                }
                assertEquals(listOf("token-a", "token-b"), seenTokens)
            } finally {
                resources.close()
            }
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
                    async { resources.mediaCache.ensureDownloaded("owner/report.md", "report.md") }
                }
                started.await()
                assertEquals(1, calls.get())
                assertEquals(null, resources.mediaCache.cachedFile("owner/report.md", "report.md"))
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
                    resources.mediaCache.ensureDownloaded("owner/failure.txt", "failure.txt")
                }
                assertEquals(null, resources.mediaCache.cachedFile("owner/failure.txt", "failure.txt"))
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
                resources.mediaCache.ensureDownloaded("owner/video.mp4", "video.mp4")
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
                        // Simulate a transport that reports one stale progress value while unwinding.
                        progress(0.75f)
                        throw CancellationException("closed")
                    }
                    partial.length()
                },
            )
            val download = async {
                resources.mediaCache.ensureDownloaded("owner/late.bin") {
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
                val first = resources.mediaCache.ensureDownloaded("owner/1.bin", "1.bin")
                first.setLastModified(1)
                val second = resources.mediaCache.ensureDownloaded("owner/2.bin", "2.bin")
                second.setLastModified(2)
                val third = resources.mediaCache.ensureDownloaded("owner/3.bin", "3.bin")

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

    private fun resources(
        dataDir: File,
        uid: String = "owner",
        token: String = "fixed-token",
        server: String = "https://chat.example",
        quotaBytes: Long = DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES,
        downloader: DesktopMediaDownloader,
    ) = DesktopSessionResources(
        ownerUid = uid,
        serverUrl = server,
        credentialProvider = { DesktopCredentialSnapshot(uid, token) },
        dataDir = dataDir,
        quotaBytes = quotaBytes,
        downloader = downloader,
    )

    private fun downloader(
        block: suspend (DesktopMediaDownloadRequest, File) -> Unit = { request, partial ->
            partial.writeText(request.resolvedUrl)
        },
    ) = DesktopMediaDownloader { request, partial, _ ->
        block(request, partial)
        partial.length()
    }

    private suspend fun withTempDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-media-test").toFile()
        try {
            block(directory)
        } finally {
            // The directory is owned by this test and never contains user data.
            directory.deleteRecursively()
        }
    }
}
