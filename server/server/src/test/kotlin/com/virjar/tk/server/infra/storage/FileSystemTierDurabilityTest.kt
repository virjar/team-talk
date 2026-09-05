package com.virjar.tk.server.infra.storage

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readFully
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemTierDurabilityTest {
    @Test
    fun `publication forces bytes before every containing directory entry`() {
        val root = Files.createTempDirectory("tk-filesystem-durability-")
        try {
            val leaf = Files.createDirectories(root.resolve("aa").resolve("bb"))
            val target = Files.write(leaf.resolve("object.dat"), byteArrayOf(1, 2, 3))
            val forced = mutableListOf<Pair<Path, Boolean>>()

            forcePublishedAttachment(target.toFile(), root.toFile()) { path, metadata ->
                forced += path to metadata
            }

            assertEquals(
                listOf(target, leaf, leaf.parent, root).map(Path::toAbsolutePath).map(Path::normalize),
                forced.map { it.first },
            )
            assertTrue(forced.all { it.second })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `publication rejects a target outside the configured storage root`() {
        val root = Files.createTempDirectory("tk-filesystem-root-")
        val outside = Files.createTempFile("tk-filesystem-outside-", ".dat")
        try {
            assertFailsWith<IllegalArgumentException> {
                forcePublishedAttachment(outside.toFile(), root.toFile()) { _, _ -> }
            }
        } finally {
            Files.deleteIfExists(outside)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `whole-file reads cross the blocking IO dispatcher boundary`() = runTest {
        val file = Files.createTempFile("tk-filesystem-stream-", ".dat")
        val expected = ByteArray(2 * 64 * 1024 + 7) { index -> (index % 251).toByte() }
        Files.write(file, expected)
        val dispatcher = RecordingDispatcher()
        val channel = ByteChannel()
        try {
            streamFilesystemEntry(
                file = file.toFile(),
                storageKey = "whole-file-test",
                channel = channel,
                range = null,
                ioDispatcher = dispatcher,
            )

            val actual = ByteArray(expected.size).also { channel.readFully(it) }
            assertContentEquals(expected, actual)
            assertTrue(dispatcher.dispatchCount > 0)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatchCount = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount += 1
            block.run()
        }
    }
}
