package com.virjar.tk.desktop

import com.virjar.tk.desktop.media.DesktopMediaFileLease
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAttachmentExportTest {
    @Test
    fun `export holds the cache lease while selecting and until the actual copy finishes`() = runBlocking {
        withExportFiles { source, target, attachment ->
            val selected = CompletableDeferred<File?>()
            val copying = CompletableDeferred<Unit>()
            val finishCopy = CompletableDeferred<Unit>()
            val released = AtomicInteger()
            val export = async {
                exportDesktopAttachment(
                    DesktopMediaFileLease(source) { released.incrementAndGet() }, attachment,
                    ensureOpen = {}, onFailure = { throw it },
                    selectDestination = { selected.await() },
                    copyFile = { file, destination ->
                        copying.complete(Unit)
                        finishCopy.await()
                        assertEquals(0, released.get(), "an outstanding copy still owns its source")
                        file.copyTo(destination)
                    },
                )
            }
            kotlinx.coroutines.yield()
            assertEquals(0, released.get())
            selected.complete(target)
            copying.await()
            assertEquals(0, released.get())
            assertFalse(target.exists())
            finishCopy.complete(Unit)
            export.await()
            assertEquals(source.readText(), target.readText())
            assertEquals(1, released.get())
        }
    }

    @Test
    fun `cancelling an export while its picker waits releases the lease and never copies`() = runBlocking {
        withExportFiles { source, _, attachment ->
            val selecting = CompletableDeferred<Unit>()
            val destination = CompletableDeferred<File?>()
            val released = AtomicInteger()
            val export = async {
                exportDesktopAttachment(
                    DesktopMediaFileLease(source) { released.incrementAndGet() }, attachment,
                    ensureOpen = {}, onFailure = { throw AssertionError("Cancellation must propagate", it) },
                    selectDestination = { selecting.complete(Unit); destination.await() },
                    copyFile = { _, _ -> error("Cancelled export must not write") },
                )
            }
            selecting.await()
            export.cancelAndJoin()
            assertEquals(1, released.get())
        }
    }

    @Test
    fun `dismissed picker and failed copy both release their cache leases`() = runBlocking {
        withExportFiles { source, target, attachment ->
            val released = AtomicInteger()
            val failures = mutableListOf<Exception>()
            exportDesktopAttachment(
                DesktopMediaFileLease(source) { released.incrementAndGet() }, attachment,
                ensureOpen = {}, onFailure = { failures += it },
                selectDestination = { null }, copyFile = { _, _ -> error("No destination") },
            )
            assertEquals(1, released.get())
            assertTrue(failures.isEmpty())
            val rejected = IOException("destination unavailable")
            exportDesktopAttachment(
                DesktopMediaFileLease(source) { released.incrementAndGet() }, attachment,
                ensureOpen = {}, onFailure = {
                    assertEquals(2, released.get(), "failure feedback cannot keep the source pinned")
                    failures += it
                },
                selectDestination = { target }, copyFile = { _, _ -> throw rejected },
            )
            assertEquals(listOf<Exception>(rejected), failures)
            assertEquals(2, released.get())
        }
    }

    private suspend fun withExportFiles(block: suspend (File, File, Attachment) -> Unit) {
        val root = Files.createTempDirectory("desktop-attachment-export").toFile()
        try {
            val source = File(root, "cached.txt").apply { writeText("完整本地附件") }
            block(source, File(root, "exported.txt"), Attachment(
                path = "files/source.txt", name = "source.txt", contentType = "text/plain", size = source.length(),
            ))
        } finally {
            root.deleteRecursively()
        }
    }
}
