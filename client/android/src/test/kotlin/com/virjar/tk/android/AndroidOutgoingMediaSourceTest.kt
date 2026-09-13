package com.virjar.tk.android

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidOutgoingMediaSourceTest {
    @Test
    fun `completed camera send releases both owned recording and prepared copy`() = runBlocking {
        val root = Files.createTempDirectory("android-captured-send").toFile()
        try {
            val captured = File(root, "captured.mp4").apply { writeText("video") }
            val copied = File(root, "selected.tmp")
            var task: Job? = null
            assertTrue(launchWithOwnedMediaSource(captured, { action ->
                task = launch(start = CoroutineStart.UNDISPATCHED) { action() }
                true
            }) {
                captured.copyTo(copied)
                PreparedMedia(copied, "video.mp4", "video/mp4", copied.length()).use { source ->
                    assertEquals("video", source.file.readText())
                    assertTrue(captured.exists())
                }
            })
            checkNotNull(task).join()
            assertFalse(captured.exists())
            assertFalse(copied.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retiring an admitted upload releases its source and prepared copy`() = runBlocking {
        val root = Files.createTempDirectory("android-cancelled-send").toFile()
        try {
            val captured = File(root, "captured.mp4").apply { writeText("video") }
            val copied = File(root, "selected.tmp")
            val uploading = CompletableDeferred<Unit>()
            var task: Job? = null
            assertTrue(launchWithOwnedMediaSource(captured, { action ->
                task = launch(start = CoroutineStart.UNDISPATCHED) { action() }
                true
            }) {
                captured.copyTo(copied)
                PreparedMedia(copied, "video.mp4", "video/mp4", copied.length()).use {
                    uploading.complete(Unit)
                    awaitCancellation()
                }
            })
            uploading.await()
            checkNotNull(task).cancelAndJoin()
            assertFalse(captured.exists())
            assertFalse(copied.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejected late recording result releases the source without starting work`() {
        val recorded = Files.createTempFile("android-rejected-recording", ".aac").toFile()
        try {
            assertFalse(launchWithOwnedMediaSource(recorded, { false }) {
                error("Retired session must not start upload")
            })
            assertFalse(recorded.exists())
        } finally {
            recorded.delete()
        }
    }

    @Test
    fun `picker source is borrowed and only the application's prepared copy is deleted`() = runBlocking {
        val root = Files.createTempDirectory("android-picker-send").toFile()
        try {
            val external = File(root, "external-provider.mp4").apply { writeText("provider data") }
            val copied = File(root, "selected.tmp")
            var task: Job? = null
            assertTrue(launchWithOwnedMediaSource(null, { action ->
                task = launch(start = CoroutineStart.UNDISPATCHED) { action() }
                true
            }) {
                external.copyTo(copied)
                PreparedMedia(copied, external.name, "video/mp4", copied.length()).use { source ->
                    assertEquals("provider data", source.file.readText())
                }
            })
            checkNotNull(task).join()
            assertEquals("provider data", external.readText())
            assertFalse(copied.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
