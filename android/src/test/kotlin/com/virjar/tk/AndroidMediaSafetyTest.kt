package com.virjar.tk

import com.virjar.tk.client.UserSession
import com.virjar.tk.client.SessionHttpCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
    fun `thumbnail sampling stays near the requested render size`() {
        assertEquals(1, calculateBitmapSampleSize(800, 600, 800, 600))
        assertEquals(4, calculateBitmapSampleSize(4000, 3000, 800, 600))
        assertEquals(8, calculateBitmapSampleSize(8000, 6000, 800, 600))
        assertEquals(4, calculateBitmapSampleSize(10000, 10000, 3000, 3000))
        assertEquals(1, calculateBitmapSampleSize(0, 0, 800, 600))
    }

    @Test
    fun `video thumbnail keeps aspect ratio within a small decode target`() {
        assertEquals(512 to 288, scaledVideoThumbnailSize(3840, 2160))
        assertEquals(288 to 512, scaledVideoThumbnailSize(2160, 3840))
        assertEquals(320 to 240, scaledVideoThumbnailSize(320, 240))
        assertEquals(512 to 512, scaledVideoThumbnailSize(0, 0))
    }

    @Test
    fun `media cache namespace is opaque stable across token rotation and isolated by owner`() {
        val first = mediaCacheNamespace("https://server-a.example", "uid-a")
        val sameSession = mediaCacheNamespace("https://server-a.example/", "uid-a")
        val anotherAccount = mediaCacheNamespace("https://server-a.example", "uid-b")
        val anotherServer = mediaCacheNamespace("https://server-b.example", "uid-a")

        assertEquals(first, sameSession)
        assertNotEquals(first, anotherAccount)
        assertNotEquals(first, anotherServer)
        assertFalse(first.contains("uid-a"))
    }

    @Test
    fun `media session follows same-user token rotation and fails closed after uid changes`() {
        val userSession = UserSession().apply {
            onAuthSuccess("uid-a", "alice", "Alice", "refresh-a", "token-a1")
        }
        val mediaSession = AndroidMediaSession.create(
            serverUrl = "https://server.example/",
            ownerUid = "uid-a",
            credentialsProvider = userSession::httpCredentialsSnapshot,
        )
        val originalNamespace = mediaSession.cacheNamespace

        assertEquals("token-a1", mediaSession.accessTokenForRequest())
        userSession.onAuthSuccess("uid-a", "alice", "Alice", "refresh-a2", "token-a2")
        assertEquals("token-a2", mediaSession.accessTokenForRequest())
        assertEquals(originalNamespace, mediaSession.cacheNamespace)

        userSession.onAuthFailed("retired")
        userSession.onAuthSuccess("uid-b", "bob", "Bob", "refresh-b", "token-b")
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
            serverUrl = "https://server.example",
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
    fun `session media directories never resolve to legacy global caches`() {
        val root = Files.createTempDirectory("teamtalk-media-path-test").toFile()
        try {
            val namespace = mediaCacheNamespace("https://server.example", "uid-a")
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
    fun `clearing one media session preserves other sessions and legacy caches`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-clear-test").toFile()
        try {
            val first = mediaCacheNamespace("https://server.example", "uid-a")
            val second = mediaCacheNamespace("https://server.example", "uid-b")
            val firstFile = File(mediaCacheDirectory(root, first, "downloads"), "first.bin")
            val secondFile = File(mediaCacheDirectory(root, second, "downloads"), "second.bin")
            val firstAttachment = File(mediaCacheDirectory(root, first, "attachments"), "first.txt")
            val secondAttachment = File(mediaCacheDirectory(root, second, "attachments"), "second.txt")
            val legacyDownload = File(root, "downloads/legacy.bin")
            val legacyMedia = File(root, "media/legacy.bin")
            listOf(
                firstFile,
                secondFile,
                firstAttachment,
                secondAttachment,
                legacyDownload,
                legacyMedia,
            ).forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText("data")
            }

            assertTrue(clearMediaCacheNamespace(root, first))

            assertFalse(firstFile.exists())
            assertFalse(firstAttachment.exists())
            assertTrue(secondFile.exists())
            assertTrue(secondAttachment.exists())
            assertTrue(legacyDownload.exists(), "不得把旧全局缓存当作当前会话缓存清理或授权")
            assertTrue(legacyMedia.exists(), "不得把旧全局缓存当作当前会话缓存清理或授权")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `closing one owner never clears another page cache`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-lease-owners").toFile()
        try {
            val namespace = mediaCacheNamespace("https://server.example", "uid-a")
            val firstPage = acquireMediaCacheLease(root, namespace)
            val secondPage = acquireMediaCacheLease(root, namespace)
            val cached = File(mediaCacheDirectory(root, namespace, "downloads"), "shared.png").apply {
                parentFile?.mkdirs()
                writeText("shared")
            }

            firstPage.close()?.join()
            assertTrue(cached.exists(), "关闭旧页面不能删除新页面正在使用的缓存")

            secondPage.close()?.join()
            assertFalse(cached.exists(), "最后一个所有者退出后才清理该会话")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `quick page switch cancels queued cleanup while target is being written`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-lease-switch").toFile()
        try {
            val namespace = mediaCacheNamespace("https://server.example", "uid-a")
            val oldPage = acquireMediaCacheLease(root, namespace)
            val target = File(mediaCacheDirectory(root, namespace, "downloads"), "switch.png")
            val writerStarted = CompletableDeferred<Unit>()
            val allowWriterToFinish = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.Default) {
                materializeMediaCacheFile(target) { partial ->
                    writerStarted.complete(Unit)
                    allowWriterToFinish.await()
                    partial.writeText("new-page-cache")
                }
            }
            writerStarted.await()

            val queuedCleanup = oldPage.close()
            val newPage = acquireMediaCacheLease(root, namespace)
            allowWriterToFinish.complete(Unit)
            writer.await()
            queuedCleanup?.join()

            assertEquals("new-page-cache", target.readText())
            newPage.close()?.join()
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun `explicit session cleanup waits for an active atomic cache write`() = runBlocking {
        val root = Files.createTempDirectory("teamtalk-media-clear-write-race").toFile()
        try {
            val namespace = mediaCacheNamespace("https://server.example", "uid-a")
            val target = File(mediaCacheDirectory(root, namespace, "downloads"), "active.png")
            val writerStarted = CompletableDeferred<Unit>()
            val allowWriterToFinish = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.Default) {
                materializeMediaCacheFile(target) { partial ->
                    writerStarted.complete(Unit)
                    allowWriterToFinish.await()
                    partial.writeText("complete")
                }
            }
            writerStarted.await()

            val cleanup = async(Dispatchers.Default) { clearMediaCacheNamespace(root, namespace) }
            kotlinx.coroutines.yield()
            assertFalse(cleanup.isCompleted, "清理必须等待进行中的原子写入")

            allowWriterToFinish.complete(Unit)
            writer.await()
            assertTrue(cleanup.await())
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `api 26 skips local video frame while newer Android uses scaled decode`() {
        assertEquals(VideoThumbnailDecodeStrategy.SkipLocalFrame, videoThumbnailStrategyForSdk(26))
        assertEquals(VideoThumbnailDecodeStrategy.ScaledRetriever, videoThumbnailStrategyForSdk(27))
    }

    @Test
    fun `application context file open requires a new task`() {
        assertTrue(fileOpenRequiresNewTask(isActivityContext = false))
        assertFalse(fileOpenRequiresNewTask(isActivityContext = true))
    }

    @Test
    fun `voice mode preflights permission and the first later press records`() {
        val gate = VoiceRecordPermissionGate()

        assertEquals(
            VoicePermissionDecision.REQUEST_PERMISSION,
            gate.enterVoiceMode(permissionGranted = false),
        )
        assertEquals(
            VoicePermissionDecision.NO_ACTION,
            gate.onPermissionResult(granted = true),
            "授权回调不能自动启动麦克风",
        )

        assertEquals(
            VoicePermissionDecision.START_RECORDING,
            gate.requestStart(permissionGranted = true),
            "预授权后第一次真正长按应立即录音",
        )
    }

    @Test
    fun `permission dialog cancellation never turns into background recording`() {
        val gate = VoiceRecordPermissionGate()
        assertEquals(
            VoicePermissionDecision.REQUEST_PERMISSION,
            gate.requestStart(permissionGranted = false),
        )

        gate.clear()

        assertEquals(
            VoicePermissionDecision.NO_ACTION,
            gate.onPermissionResult(granted = true),
        )
        assertEquals(
            VoicePermissionDecision.START_RECORDING,
            gate.requestStart(permissionGranted = true),
            "只有新的长按才能启动录音",
        )
    }

    @Test
    fun `voice permission request is not launched twice while in flight`() {
        val gate = VoiceRecordPermissionGate()

        assertEquals(
            VoicePermissionDecision.REQUEST_PERMISSION,
            gate.enterVoiceMode(permissionGranted = false),
        )
        assertEquals(
            VoicePermissionDecision.NO_ACTION,
            gate.requestStart(permissionGranted = false),
        )
        assertEquals(
            VoicePermissionDecision.NO_ACTION,
            gate.onPermissionResult(granted = false),
        )
        assertEquals(
            VoicePermissionDecision.REQUEST_PERMISSION,
            gate.requestStart(permissionGranted = false),
            "用户拒绝后仍允许新的长按重试申请",
        )
    }

    @Test
    fun `recording cancellation releases and deletes even when stop fails`() {
        val file = Files.createTempFile("teamtalk-voice-cancel", ".aac").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val calls = mutableListOf<String>()
        val lease = VoiceRecordingLease<String>()
        assertTrue(lease.attach("recorder", file, 100L))

        lease.discard(
            stop = {
                calls += "stop"
                error("stop failed")
            },
            release = { calls += "release" },
        )

        assertEquals(listOf("stop", "release"), calls)
        assertFalse(lease.isActive)
        assertFalse(file.exists())
    }

    @Test
    fun `failed recording finalization never yields a sendable file`() {
        val file = Files.createTempFile("teamtalk-voice-failed", ".aac").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val calls = mutableListOf<String>()
        val lease = VoiceRecordingLease<String>()
        assertTrue(lease.attach("recorder", file, 100L))

        val result = lease.finishForSend(
            stop = {
                calls += "stop"
                error("stop failed")
            },
            release = {
                calls += "release"
                error("release failed")
            },
        )

        assertTrue(result is VoiceRecordingFinishResult.Failed)
        assertEquals(listOf("stop", "release"), calls)
        assertFalse(lease.isActive)
        assertFalse(file.exists())
    }

    @Test
    fun `successful recording finalization keeps exactly one sendable file`() {
        val file = Files.createTempFile("teamtalk-voice-ready", ".aac").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            val lease = VoiceRecordingLease<String>()
            assertTrue(lease.attach("recorder", file, 123L))

            val result = lease.finishForSend(stop = {}, release = {})

            assertTrue(result is VoiceRecordingFinishResult.Ready)
            assertEquals(file, result.file)
            assertEquals(123L, result.startedAt)
            assertTrue(file.exists())
            assertFalse(lease.isActive)
        } finally {
            file.delete()
        }
    }
}
