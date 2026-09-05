package com.virjar.tk.server.domain.attachment

import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.FileStoreUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentRetentionServiceTest {
    @Test
    fun `expired unreferenced mixed-tier objects retire and restore every quota dimension`() = runTest {
        val fixture = RetentionFixture()
        try {
            val rocks = fixture.store("rocks.bin", 1)
            val filesystem = fixture.store("filesystem.bin", 3)
            val referenced = fixture.store("referenced.bin", 2)
            fixture.references.paths += referenced

            assertEquals(6, fixture.files.accountedStoredBytes)
            assertEquals(3, fixture.files.accountedStoredFiles)
            assertEquals(2, fixture.retention.cleanupExpiredUnreferenced())

            assertNull(fixture.files.getAttachment(rocks))
            assertNull(fixture.files.getAttachment(filesystem))
            assertNotNull(fixture.files.getAttachment(referenced))
            assertEquals(2, fixture.files.accountedStoredBytes)
            assertEquals(1, fixture.files.accountedStoredFiles)
            assertEquals(FileStoreUsage(2, 1), fixture.files.accountedOwnerUsage(OWNER))

            fixture.references.paths -= referenced
            assertEquals(1, fixture.retention.cleanupExpiredUnreferenced())
            assertNull(fixture.files.getAttachment(referenced))
            assertEquals(0, fixture.files.accountedStoredBytes)
            assertEquals(0, fixture.files.accountedStoredFiles)
            assertEquals(FileStoreUsage.EMPTY, fixture.files.accountedOwnerUsage(OWNER))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `reference commit holding the lifecycle fence wins over a concurrent retirement scan`() = runTest {
        val fixture = RetentionFixture()
        try {
            val path = fixture.store("race.bin", 1)
            val mutationEntered = CompletableDeferred<Unit>()
            val releaseMutation = CompletableDeferred<Unit>()
            val mutation = async(Dispatchers.Default) {
                fixture.lifecycle.withReferenceMutation(listOf(path)) {
                    mutationEntered.complete(Unit)
                    releaseMutation.await()
                    fixture.references.paths += path
                }
            }
            mutationEntered.await()

            val cleanup = async(Dispatchers.Default) { fixture.retention.cleanupExpiredUnreferenced() }
            yield()
            assertFalse(cleanup.isCompleted, "retirement must wait for the durable reference decision")

            releaseMutation.complete(Unit)
            mutation.await()
            assertEquals(0, cleanup.await())
            assertNotNull(fixture.files.getAttachment(path))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `retention environment rejects malformed or unbounded leases`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentRetentionConfig.fromEnvironment { "not-a-number" }
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentRetentionConfig.fromEnvironment { "8761" }
        }
        val configured = AttachmentRetentionConfig.fromEnvironment { name ->
            if (name == AttachmentRetentionConfig.TTL_HOURS_ENV) "24" else null
        }
        assertEquals(24L * 60L * 60L * 1_000L, configured.unreferencedTtlMillis)
    }

    @Test
    fun `metadata scan budget counts recent rows instead of traversing until an expired match`() {
        val fixture = RetentionFixture()
        try {
            repeat(3) { index -> fixture.store("recent-$index.bin", 1) }

            val first = fixture.files.scanRetirementCandidates(
                uploadedAtOrBefore = 0,
                afterPath = null,
                limit = 2,
            )
            assertEquals(emptyList(), first.candidates)
            assertNotNull(first.lastScannedPath)
            assertTrue(first.hasMore, "the first fixed metadata page must stop before the third row")

            val second = fixture.files.scanRetirementCandidates(
                uploadedAtOrBefore = 0,
                afterPath = first.lastScannedPath,
                limit = 2,
            )
            assertEquals(emptyList(), second.candidates)
            assertNotNull(second.lastScannedPath)
            assertFalse(second.hasMore)
        } finally {
            fixture.close()
        }
    }

    private class RetentionFixture : AutoCloseable {
        val root: File = Files.createTempDirectory("tk-attachment-retention-").toFile()
        val files = FileStore(
            dbPath = File(root, "rocks").absolutePath,
            fsRoot = File(root, "files").absolutePath,
            tmpRoot = File(root, "tmp"),
            largeFileThreshold = 2,
            maxFileSize = 64,
            maxTotalBytes = 1_024,
            maxTotalFiles = 32,
            maxOwnerBytes = 1_024,
            maxOwnerFiles = 32,
        )
        val references = MutableReferences()
        val lifecycle = AttachmentLifecycleGate()
        val retention = AttachmentRetentionService(
            files = files,
            references = references,
            lifecycle = lifecycle,
            config = AttachmentRetentionConfig(
                unreferencedTtlMillis = 1,
                pageSize = 2,
                maxPagesPerRun = 8,
            ),
            wallClockMillis = { Long.MAX_VALUE / 4 },
        )

        init {
            files.init()
        }

        fun store(name: String, size: Int): String {
            val source = File(root, "source-${System.nanoTime()}-$name")
            source.writeBytes(ByteArray(size) { it.toByte() })
            return files.store(OWNER, name, "application/octet-stream", source)
        }

        override fun close() {
            if (files.isRunning) files.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete attachment retention fixture: $root"
            }
        }
    }

    private class MutableReferences : AttachmentReferences {
        val paths = linkedSetOf<String>()

        override fun getChatIds(path: String): Set<String> =
            if (path in paths) setOf("referenced-chat") else emptySet()

        override fun getReferencedPaths(paths: Set<String>): Set<String> =
            paths.filterTo(linkedSetOf()) { it in this.paths }
    }

    private companion object {
        const val OWNER = "retention-owner"
    }
}
