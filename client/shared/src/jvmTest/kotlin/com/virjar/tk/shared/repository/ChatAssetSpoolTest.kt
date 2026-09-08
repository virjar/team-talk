package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAssetSpoolTest {
    private val owner = AccountDataOwner("a".repeat(64), "00000000-0000-4000-8000-000000000001", "owner")

    @Test
    fun `private frozen source survives reopen and later edits to the selected user file`() = runBlocking {
        withRoot { root ->
            val original = File(root, "user-selected.txt").apply { writeText("original bytes") }
            val spool = createChatAssetSpool(root, owner)
            val staged = spool.stage(original.asUploadSource())
            original.writeText("replacement bytes")
            val reopened = createChatAssetSpool(root, owner)
            assertEquals(listOf(staged), reopened.list())
            assertEquals("original bytes", reopened.open(staged.sourceId).bytes().decodeToString())
            reopened.delete(staged.sourceId)
            assertEquals("replacement bytes", original.readText())
            assertTrue(reopened.list().isEmpty())
        }
    }

    @Test
    fun `failed or oversized source never publishes partial bytes and releases its reservation`() = runBlocking {
        withRoot { root ->
            val spool = createChatAssetSpool(root, owner, quotaBytes = 4, maxEntries = 1)
            val wrongLength = object : UploadSource {
                override val contentLength = 3L
                override suspend fun writeTo(sink: UploadSink) = sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
            }
            assertFailsWith<IllegalStateException> { spool.stage(wrongLength) }
            assertTrue(spool.list().isEmpty())
            assertFailsWith<IllegalStateException> { spool.stage(ByteArray(5).asSmallUploadSource()) }
            val correct = spool.stage(byteArrayOf(1, 2, 3, 4).asSmallUploadSource())
            assertContentEquals(byteArrayOf(1, 2, 3, 4), spool.open(correct.sourceId).bytes())
        }
    }

    @Test
    fun `concurrent and reopened importers share byte reservations`() = runBlocking {
        withRoot { root ->
            val spool = createChatAssetSpool(root, owner, quotaBytes = 4, maxEntries = 2)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val source = object : UploadSource {
                override val contentLength = 4L
                override suspend fun writeTo(sink: UploadSink) {
                    started.complete(Unit)
                    finish.await()
                    sink.write(byteArrayOf(1, 2, 3, 4), 0, 4)
                }
            }
            val upload = async(Dispatchers.IO) { spool.stage(source) }
            try {
                withTimeout(5_000) { started.await() }
                val reopened = createChatAssetSpool(root, owner, quotaBytes = 4, maxEntries = 2)
                assertFailsWith<IllegalStateException> { reopened.stage(byteArrayOf(5).asSmallUploadSource()) }
                finish.complete(Unit)
                val result = withTimeout(5_000) { upload.await() }
                assertEquals(listOf(result), reopened.list())
            } finally {
                finish.complete(Unit)
                upload.cancel()
            }
        }
    }

    @Test
    fun `deletion waits for the current stream but immediately rejects new readers`() = runBlocking {
        withRoot { root ->
            val spool = createChatAssetSpool(root, owner)
            val staged = spool.stage("keep until closed".encodeToByteArray().asSmallUploadSource())
            val reading = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val output = ByteArrayOutputStream()
            val upload = async(Dispatchers.IO) {
                spool.open(staged.sourceId).writeTo(UploadSink { bytes, offset, length ->
                    reading.complete(Unit)
                    resume.await()
                    output.write(bytes, offset, length)
                })
            }
            try {
                withTimeout(5_000) { reading.await() }
                spool.delete(staged.sourceId)
                assertFailsWith<IllegalStateException> { spool.open(staged.sourceId).bytes() }
                resume.complete(Unit)
                withTimeout(5_000) { upload.await() }
                assertEquals("keep until closed", output.toString(Charsets.UTF_8))
                assertTrue(spool.list().isEmpty())
            } finally {
                resume.complete(Unit)
                upload.cancel()
            }
        }
    }

    @Test
    fun `reopened source rejects corrupt bytes before emitting a single byte`() = runBlocking {
        withRoot { root ->
            val staged = createChatAssetSpool(root, owner).stage("source".encodeToByteArray().asSmallUploadSource())
            val file = namespace(root).listFiles()!!.single()
            file.writeText("broken")
            var emitted = 0
            val reopened = createChatAssetSpool(root, owner)
            assertFailsWith<IllegalStateException> {
                reopened.open(staged.sourceId).writeTo(UploadSink { _, _, size -> emitted += size })
            }
            assertEquals(0, emitted)
            assertTrue(file.exists(), "Corrupt user work is retained for explicit removal")
        }
    }

    @Test
    fun `startup keeps committed work cleans only owned partials and isolates other accounts`() = runBlocking {
        withRoot { root ->
            val spool = createChatAssetSpool(root, owner)
            val retained = spool.stage(byteArrayOf(1).asSmallUploadSource())
            val data = JvmPrivateDataDirectory.openExisting(root)
            val partial = data.preparePrivateFile(chatAssetSpoolDirectories(owner),
                "00000000-0000-4000-8000-000000000099.partial")
            partial.writeBytes(byteArrayOf(9))
            val reopened = createChatAssetSpool(root, owner)
            assertFalse(partial.exists())
            assertEquals(listOf(retained), reopened.list())
            val other = createChatAssetSpool(root, owner.copy(uid = "other"))
            assertTrue(other.list().isEmpty())
            assertFailsWith<IllegalStateException> { other.open(retained.sourceId) }
            val unexpected = File(namespace(root), "user-notes.txt").apply { writeText("do not remove") }
            assertFailsWith<IllegalStateException> { createChatAssetSpool(root, owner) }
            assertEquals("do not remove", unexpected.readText())
        }
    }

    @Test
    fun `entry quota includes empty assets and symlinks are not followed or erased`() = runBlocking {
        withRoot { root ->
            val spool = createChatAssetSpool(root, owner, quotaBytes = 4, maxEntries = 1)
            val empty = spool.stage(byteArrayOf().asSmallUploadSource())
            assertFailsWith<IllegalStateException> { spool.stage(byteArrayOf().asSmallUploadSource()) }
            val path = namespace(root).listFiles()!!.single().toPath()
            val outside = File(root, "original.txt").apply { writeText("keep") }
            Files.delete(path)
            Files.createSymbolicLink(path, outside.toPath())
            assertFailsWith<IllegalArgumentException> { spool.open(empty.sourceId) }
            assertFailsWith<IllegalArgumentException> { spool.delete(empty.sourceId) }
            assertEquals("keep", outside.readText())
        }
    }

    private fun namespace(root: File): File = chatAssetSpoolDirectories(owner).fold(root) { dir, name -> File(dir, name) }

    private suspend fun UploadSource.bytes(): ByteArray = ByteArrayOutputStream().also { output ->
        writeTo(UploadSink { bytes, offset, size -> output.write(bytes, offset, size) })
    }.toByteArray()

    private suspend fun withRoot(action: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-chat-spool-").toFile()
        try {
            Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
