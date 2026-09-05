package com.virjar.tk.server.infra.storage

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class FileStoreOwnerQuotaTest {

    @Test
    fun `same owner concurrent zero byte stores cannot claim the final owner slot`() {
        val root = Files.createTempDirectory("tk-file-owner-concurrent-").toFile()
        val store = quotaStore(root, maxTotalFiles = 2, maxOwnerFiles = 1)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var workers = emptyList<Thread>()
        try {
            store.init()
            val sources = List(2) { index -> quotaSource(root, "same-owner-empty-$index", 0) }
            workers = List(2) { index ->
                thread(start = true, isDaemon = true, name = "file-store-owner-count-$index") {
                    ready.countDown()
                    try {
                        start.await()
                        paths += store.store(
                            OWNER_A,
                            "empty-$index.bin",
                            "application/octet-stream",
                            sources[index],
                        )
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            workers.forEach { it.join(5_000) }

            assertTrue(workers.none { it.isAlive }, "owner-capacity workers must terminate")
            assertEquals(1, paths.size)
            val failure = assertNotNull(failures.singleOrNull() as? FileStoreCapacityExceededException)
            assertEquals(FileStoreCapacityScope.OWNER, failure.scope)
            assertEquals(FileStoreUsage(0, 1), store.accountedOwnerUsage(OWNER_A))
            assertEquals(1, store.accountedStoredFiles)
        } finally {
            start.countDown()
            workers.forEach { worker ->
                if (worker.isAlive) worker.interrupt()
                worker.join(5_000)
            }
            closeAndDelete(store, root)
        }
    }

    @Test
    fun `one exhausted owner does not consume another owner's capacity`() {
        val root = Files.createTempDirectory("tk-file-owner-isolation-").toFile()
        val store = quotaStore(
            root = root,
            maxTotalBytes = 4,
            maxTotalFiles = 4,
            maxOwnerBytes = 1,
            maxOwnerFiles = 4,
        )
        try {
            store.init()
            store.store(OWNER_A, "first.bin", MIME, quotaSource(root, "owner-a-first", 1))

            val ownerFailure = assertNotNull(
                runCatching {
                    store.store(OWNER_A, "second.bin", MIME, quotaSource(root, "owner-a-second", 1))
                }.exceptionOrNull() as? FileStoreCapacityExceededException,
            )
            assertEquals(FileStoreCapacityScope.OWNER, ownerFailure.scope)

            val otherPath = store.store(
                OWNER_B,
                "other.bin",
                MIME,
                quotaSource(root, "owner-b-first", 1),
            )
            assertNotNull(store.getMeta(otherPath))
            assertEquals(FileStoreUsage(1, 1), store.accountedOwnerUsage(OWNER_A))
            assertEquals(FileStoreUsage(1, 1), store.accountedOwnerUsage(OWNER_B))
            assertEquals(2, store.accountedStoredBytes)
            assertEquals(2, store.accountedStoredFiles)
        } finally {
            closeAndDelete(store, root)
        }
    }

    @Test
    fun `restart rebuilds owner usage before accepting another object`() {
        val root = Files.createTempDirectory("tk-file-owner-restart-").toFile()
        var store = quotaStore(root, maxTotalFiles = 3, maxOwnerFiles = 1)
        try {
            store.init()
            store.store(OWNER_A, "empty.bin", MIME, quotaSource(root, "restart-empty", 0))
            store.close()

            store = quotaStore(root, maxTotalFiles = 3, maxOwnerFiles = 1).also { it.init() }
            assertEquals(FileStoreUsage(0, 1), store.accountedOwnerUsage(OWNER_A))
            val ownerFailure = assertNotNull(
                runCatching {
                    store.store(OWNER_A, "again.bin", MIME, quotaSource(root, "restart-again", 0))
                }.exceptionOrNull() as? FileStoreCapacityExceededException,
            )
            assertEquals(FileStoreCapacityScope.OWNER, ownerFailure.scope)

            val otherPath = store.store(
                OWNER_B,
                "other.bin",
                MIME,
                quotaSource(root, "restart-other", 0),
            )
            assertNotNull(store.getMeta(otherPath))
            assertEquals(FileStoreUsage(0, 1), store.accountedOwnerUsage(OWNER_B))
            assertEquals(2, store.accountedStoredFiles)
        } finally {
            closeAndDelete(store, root)
        }
    }

    @Test
    fun `startup fails closed when historical owner metadata exceeds its bound`() {
        val root = Files.createTempDirectory("tk-file-owner-history-bound-").toFile()
        var store = quotaStore(root, maxTotalFiles = 3, maxOwnerFiles = 2)
        try {
            store.init()
            repeat(2) { index ->
                store.store(
                    OWNER_A,
                    "empty-$index.bin",
                    MIME,
                    quotaSource(root, "history-empty-$index", 0),
                )
            }
            store.close()

            val constrained = quotaStore(root, maxTotalFiles = 3, maxOwnerFiles = 1)
            val failure = assertNotNull(runCatching { constrained.init() }.exceptionOrNull())
            assertEquals("FileStore owner persistent capacity is exceeded", failure.message)
            assertFalse(constrained.isRunning)
            assertFalse(constrained.isHealthy)

            store = quotaStore(root, maxTotalFiles = 3, maxOwnerFiles = 2).also { it.init() }
            assertEquals(FileStoreUsage(0, 2), store.accountedOwnerUsage(OWNER_A))
            assertEquals(2, store.accountedStoredFiles)
        } finally {
            closeAndDelete(store, root)
        }
    }

    @Test
    fun `deleting an object releases only its metadata owner`() {
        val root = Files.createTempDirectory("tk-file-owner-delete-").toFile()
        val store = quotaStore(root, maxTotalBytes = 8, maxTotalFiles = 4, maxOwnerBytes = 8)
        try {
            store.init()
            val ownerAPath = store.store(
                OWNER_A,
                "a.bin",
                MIME,
                quotaSource(root, "delete-owner-a", 2),
            )
            val ownerBPath = store.store(
                OWNER_B,
                "b.bin",
                MIME,
                quotaSource(root, "delete-owner-b", 3),
            )

            store.rollbackUnpublished(listOf(ownerAPath))

            assertNull(store.getDurableMeta(ownerAPath))
            assertNotNull(store.getMeta(ownerBPath))
            assertEquals(FileStoreUsage.EMPTY, store.accountedOwnerUsage(OWNER_A))
            assertEquals(FileStoreUsage(3, 1), store.accountedOwnerUsage(OWNER_B))
            assertEquals(3, store.accountedStoredBytes)
            assertEquals(1, store.accountedStoredFiles)
        } finally {
            closeAndDelete(store, root)
        }
    }

    @Test
    fun `Rocks create fault rolls both ledgers back after durable cleanup`() {
        val root = Files.createTempDirectory("tk-file-owner-rocks-create-fault-").toFile()
        val injected = OwnerQuotaMutationFailure("rocks create failed")
        val faults = OwnerQuotaFaultInjector().apply {
            failAt(FileStoreMutationPoint.COMMIT_ROCKS_CREATE, injected)
        }
        val store = quotaStore(root, faults = faults, largeFileThreshold = 8)
        try {
            store.init()
            val observed = try {
                store.store(OWNER_A, "small.bin", MIME, quotaSource(root, "rocks-create", 2))
                fail("the injected RocksDB create failure should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(injected, observed)
            assertEquals(0, store.accountedStoredBytes)
            assertEquals(0, store.accountedStoredFiles)
            assertEquals(FileStoreUsage.EMPTY, store.accountedOwnerUsage(OWNER_A))
            assertTrue(store.isHealthy)
        } finally {
            closeAndDelete(store, root)
        }
    }

    @Test
    fun `Rocks delete fault retains both ledgers and closes admission until restart`() {
        val root = Files.createTempDirectory("tk-file-owner-rocks-delete-fault-").toFile()
        val injected = OwnerQuotaMutationFailure("rocks delete failed")
        val faults = OwnerQuotaFaultInjector()
        var store = quotaStore(root, faults = faults, largeFileThreshold = 8)
        try {
            store.init()
            val path = store.store(
                OWNER_A,
                "small.bin",
                MIME,
                quotaSource(root, "rocks-delete", 2),
            )
            faults.failAt(FileStoreMutationPoint.COMMIT_ROCKS_DELETE, injected)

            val observed = try {
                store.rollbackUnpublished(listOf(path))
                fail("the injected RocksDB delete failure should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(injected, observed)
            assertNotNull(store.getMeta(path))
            assertEquals(2, store.accountedStoredBytes)
            assertEquals(1, store.accountedStoredFiles)
            assertEquals(FileStoreUsage(2, 1), store.accountedOwnerUsage(OWNER_A))
            assertFalse(store.isHealthy)
            val blockedAdmission = runCatching {
                store.store(OWNER_B, "blocked.bin", MIME, quotaSource(root, "rocks-blocked", 1))
            }.exceptionOrNull()
            assertSame(injected, blockedAdmission)

            store.close()
            store = quotaStore(root, largeFileThreshold = 8).also { it.init() }
            assertEquals(FileStoreUsage(2, 1), store.accountedOwnerUsage(OWNER_A))
            assertEquals(2, store.accountedStoredBytes)
            assertEquals(1, store.accountedStoredFiles)
        } finally {
            closeAndDelete(store, root)
        }
    }

    private companion object {
        const val OWNER_A = "owner-a"
        const val OWNER_B = "owner-b"
        const val MIME = "application/octet-stream"
    }
}

private class OwnerQuotaMutationFailure(message: String) : RuntimeException(message)

private class OwnerQuotaFaultInjector : FileStoreMutationFaultInjector {
    private val failures = mutableMapOf<FileStoreMutationPoint, Throwable>()

    fun failAt(point: FileStoreMutationPoint, failure: Throwable) {
        failures[point] = failure
    }

    override fun before(point: FileStoreMutationPoint, metadata: FileMetadata) {
        failures[point]?.let { throw it }
    }
}

private fun quotaStore(
    root: File,
    faults: FileStoreMutationFaultInjector = FileStoreMutationFaultInjector { _, _ -> },
    largeFileThreshold: Long = 8,
    maxTotalBytes: Long = 16,
    maxTotalFiles: Int = 16,
    maxOwnerBytes: Long = 16,
    maxOwnerFiles: Int = 16,
): FileStore = FileStore(
    dbPath = File(root, "rocksdb").absolutePath,
    fsRoot = File(root, "files").absolutePath,
    largeFileThreshold = largeFileThreshold,
    maxFileSize = 16,
    nativeResourceCloser = FileStoreNativeResourceCloser(::closeFileStoreNativeResource),
    mutationFaultInjector = faults,
    maxTotalBytes = maxTotalBytes,
    maxTotalFiles = maxTotalFiles,
    maxOwnerBytes = maxOwnerBytes,
    maxOwnerFiles = maxOwnerFiles,
)

private fun quotaSource(root: File, name: String, size: Int): File = File(root, "$name.bin").apply {
    writeBytes(ByteArray(size) { it.toByte() })
}

private fun closeAndDelete(store: FileStore, root: File) {
    var failure: Throwable? = null
    try {
        if (store.isRunning) store.close()
    } catch (error: Throwable) {
        failure = error
    }
    try {
        check(root.deleteRecursively() || !root.exists()) {
            "Failed to delete FileStore owner quota test root"
        }
    } catch (error: Throwable) {
        val first = failure
        if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
    }
    failure?.let { throw it }
}
