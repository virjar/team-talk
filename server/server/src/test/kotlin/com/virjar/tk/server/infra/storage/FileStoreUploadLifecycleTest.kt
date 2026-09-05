package com.virjar.tk.server.infra.storage

import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import java.io.File
import java.nio.charset.StandardCharsets
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

class FileStoreUploadLifecycleTest {

    @Test
    fun `activation failure after filesystem move leaves durable accounted pending create for restart`() {
        val root = Files.createTempDirectory("tk-file-pending-create-").toFile()
        val activationFailure = MutationFailure("metadata activation failed")
        val deleteFailure = MutationFailure("entity cleanup failed")
        val faults = RecordingMutationFaultInjector().apply {
            failAt(FileStoreMutationPoint.ACTIVATE_FILESYSTEM_METADATA, activationFailure)
            failAt(FileStoreMutationPoint.DELETE_FILESYSTEM_ENTITY, deleteFailure)
        }
        val store = newTestStore(root, faults = faults)
        var recovered: FileStore? = null
        try {
            store.init()
            val observed = try {
                store.store(
                    "user-1",
                    "moved.bin",
                    "application/octet-stream",
                    source(root, "pending-create", 3),
                )
                fail("injected activation failure should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(activationFailure, observed)
            assertTrue(activationFailure.suppressed.any { it === deleteFailure })
            val attempted = assertNotNull(
                faults.metadataAt(FileStoreMutationPoint.ACTIVATE_FILESYSTEM_METADATA),
            )
            val pending = assertNotNull(store.getDurableMeta(attempted.path))
            assertEquals(FileMetadataLifecycle.PENDING_CREATE, pending.lifecycle)
            assertNull(store.getMeta(attempted.path))
            assertEquals(attempted.size, store.accountedStoredBytes)
            assertEquals(1, store.accountedStoredFiles)
            assertEquals(FileStoreUsage(attempted.size, 1), store.accountedOwnerUsage("user-1"))
            val physicalObject = assertNotNull(store.getFile(pending))
            assertTrue(physicalObject.isFile)
            assertFalse(store.isHealthy)

            store.close()
            recovered = newTestStore(root).also { it.init() }
            assertTrue(recovered.isHealthy)
            assertNull(recovered.getDurableMeta(attempted.path))
            assertFalse(physicalObject.exists())
            assertEquals(0, recovered.accountedStoredBytes)
            assertEquals(0, recovered.accountedStoredFiles)
            assertEquals(FileStoreUsage.EMPTY, recovered.accountedOwnerUsage("user-1"))
        } finally {
            if (store.isRunning) store.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete pending-create test root: $root"
            }
        }
    }

    @Test
    fun `filesystem deletion failure persists tombstone and restart completes retirement`() {
        val root = Files.createTempDirectory("tk-file-pending-delete-").toFile()
        val faults = RecordingMutationFaultInjector()
        val store = newTestStore(root, faults = faults)
        var recovered: FileStore? = null
        try {
            store.init()
            val path = store.store(
                "user-1",
                "large.bin",
                "application/octet-stream",
                source(root, "pending-delete", 3),
            )
            val metadata = assertNotNull(store.getMeta(path))
            val physicalObject = assertNotNull(store.getFile(metadata))
            val deleteFailure = MutationFailure("entity deletion failed")
            faults.failAt(FileStoreMutationPoint.DELETE_FILESYSTEM_ENTITY, deleteFailure)

            val observed = try {
                store.rollbackUnpublished(listOf(path))
                fail("injected deletion failure should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(deleteFailure, observed)
            assertEquals(
                FileMetadataLifecycle.PENDING_DELETE,
                assertNotNull(store.getDurableMeta(path)).lifecycle,
            )
            assertNull(store.getMeta(path))
            assertTrue(physicalObject.isFile)
            assertEquals(metadata.size, store.accountedStoredBytes)
            assertEquals(1, store.accountedStoredFiles)
            assertEquals(FileStoreUsage(metadata.size, 1), store.accountedOwnerUsage("user-1"))
            assertFalse(store.isHealthy)

            store.close()
            recovered = newTestStore(root).also { it.init() }
            assertNull(recovered.getDurableMeta(path))
            assertFalse(physicalObject.exists())
            assertEquals(0, recovered.accountedStoredBytes)
            assertEquals(0, recovered.accountedStoredFiles)
            assertEquals(FileStoreUsage.EMPTY, recovered.accountedOwnerUsage("user-1"))
            assertTrue(recovered.isHealthy)
        } finally {
            if (store.isRunning) store.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete pending-delete test root: $root"
            }
        }
    }

    @Test
    fun `metadata finalization failure remains accounted after entity deletion until restart`() {
        val root = Files.createTempDirectory("tk-file-finalize-delete-").toFile()
        val faults = RecordingMutationFaultInjector()
        val store = newTestStore(root, faults = faults)
        var recovered: FileStore? = null
        try {
            store.init()
            val path = store.store(
                "user-1",
                "large.bin",
                "application/octet-stream",
                source(root, "finalize-delete", 3),
            )
            val metadata = assertNotNull(store.getMeta(path))
            val physicalObject = assertNotNull(store.getFile(metadata))
            val finalizeFailure = MutationFailure("metadata delete failed")
            faults.failAt(FileStoreMutationPoint.FINALIZE_FILESYSTEM_METADATA_DELETE, finalizeFailure)

            val observed = try {
                store.rollbackUnpublished(listOf(path))
                fail("injected metadata finalization failure should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(finalizeFailure, observed)
            assertFalse(physicalObject.exists(), "entity deletion completes before metadata accounting retires")
            assertEquals(
                FileMetadataLifecycle.PENDING_DELETE,
                assertNotNull(store.getDurableMeta(path)).lifecycle,
            )
            assertEquals(metadata.size, store.accountedStoredBytes)
            assertEquals(1, store.accountedStoredFiles)
            assertEquals(FileStoreUsage(metadata.size, 1), store.accountedOwnerUsage("user-1"))
            assertFalse(store.isHealthy)

            store.close()
            recovered = newTestStore(root).also { it.init() }
            assertNull(recovered.getDurableMeta(path))
            assertEquals(0, recovered.accountedStoredBytes)
            assertEquals(0, recovered.accountedStoredFiles)
            assertEquals(FileStoreUsage.EMPTY, recovered.accountedOwnerUsage("user-1"))
            assertTrue(recovered.isHealthy)
        } finally {
            if (store.isRunning) store.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete finalize-delete test root: $root"
            }
        }
    }

    @Test
    fun `startup stays unpublished while a pending filesystem object cannot be retired`() {
        val root = Files.createTempDirectory("tk-file-reconcile-fail-closed-").toFile()
        val first = newTestStore(root)
        var recovered: FileStore? = null
        try {
            first.init()
            val path = first.store(
                "user-1",
                "large.bin",
                "application/octet-stream",
                source(root, "reconcile-fail-closed", 3),
            )
            val metadata = assertNotNull(first.getMeta(path))
            val physicalObject = assertNotNull(first.getFile(metadata))
            assertTrue(physicalObject.delete())
            assertTrue(physicalObject.mkdir())
            val blocker = File(physicalObject, "blocks-retirement").apply { writeText("occupied") }
            assertTrue(
                runCatching { first.rollbackUnpublished(listOf(path)) }.isFailure,
                "runtime rollback must report the physical deletion failure",
            )
            assertEquals(
                FileMetadataLifecycle.PENDING_DELETE,
                assertNotNull(first.getDurableMeta(path)).lifecycle,
            )
            first.close()

            recovered = newTestStore(root)
            assertTrue(
                runCatching { recovered.init() }.isFailure,
                "startup must fail while a pending physical object cannot be retired",
            )
            assertFalse(recovered.isRunning)
            assertFalse(recovered.isHealthy)

            assertTrue(blocker.delete())
            assertTrue(physicalObject.delete())
            recovered.init()
            assertTrue(recovered.isHealthy)
            assertNull(recovered.getDurableMeta(path))
            assertEquals(0, recovered.accountedStoredBytes)
        } finally {
            if (first.isRunning) first.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete reconcile-fail-closed test root: $root"
            }
        }
    }

    @Test
    fun `concurrent and repeated rollback retires one filesystem object exactly once`() {
        val fixture = StoreFixture(largeFileThreshold = 2, maxFileSize = 16)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var workers = emptyList<Thread>()
        try {
            val path = fixture.store.store(
                "user-1",
                "large.bin",
                "application/octet-stream",
                fixture.source("concurrent-rollback", 3),
            )
            val metadata = assertNotNull(fixture.store.getMeta(path))
            val physicalObject = assertNotNull(fixture.store.getFile(metadata))
            workers = List(2) { index ->
                thread(start = true, isDaemon = true, name = "file-store-rollback-$index") {
                    ready.countDown()
                    try {
                        start.await()
                        fixture.store.rollbackUnpublished(listOf(path))
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            workers.forEach { it.join(5_000) }

            assertTrue(workers.none { it.isAlive }, "rollback workers must terminate")
            assertTrue(failures.isEmpty(), "idempotent rollback must not underflow accounting: $failures")
            fixture.store.rollbackUnpublished(listOf(path))
            assertNull(fixture.store.getDurableMeta(path))
            assertFalse(physicalObject.exists())
            assertEquals(0, fixture.store.accountedStoredBytes)
        } finally {
            start.countDown()
            workers.forEach { worker ->
                if (worker.isAlive) worker.interrupt()
                worker.join(5_000)
            }
            fixture.close()
        }
    }

    @Test
    fun `startup rejects metadata missing its lifecycle instead of assuming active`() {
        assertInvalidFileMetadataRejected(
            rawMetadata = """
                {
                  "path":"user-1/strict-metadata",
                  "originalName":"strict.bin",
                  "contentType":"application/octet-stream",
                  "size":0,
                  "tier":"ROCKSDB",
                  "storageKey":"0123456789abcdef0123456789abcdef",
                  "uploadedAt":1,
                  "uid":"user-1"
                }
            """.trimIndent(),
            expectedDiagnostic = "lifecycle",
        )
    }

    @Test
    fun `startup rejects metadata with an unknown lifecycle field`() {
        assertInvalidFileMetadataRejected(
            rawMetadata = """
                {
                  "path":"user-1/strict-metadata",
                  "originalName":"strict.bin",
                  "contentType":"application/octet-stream",
                  "size":0,
                  "tier":"ROCKSDB",
                  "storageKey":"0123456789abcdef0123456789abcdef",
                  "uploadedAt":1,
                  "uid":"user-1",
                  "lifecycle":"ACTIVE",
                  "futureLifecycle":"ACTIVE"
                }
            """.trimIndent(),
            expectedDiagnostic = "futureLifecycle",
        )
    }

    @Test
    fun `startup deletes unowned filesystem objects before reporting healthy`() {
        val root = Files.createTempDirectory("tk-file-orphan-reconcile-").toFile()
        val storageKey = "a".repeat(32)
        val orphan = File(root, "files/aa/aa/$storageKey.dat").apply {
            check(parentFile.mkdirs())
            writeBytes(ByteArray(7) { it.toByte() })
        }
        val store = newTestStore(root)
        try {
            store.init()
            assertFalse(orphan.exists())
            assertEquals(0, store.accountedStoredBytes)
            assertTrue(store.isHealthy)
        } finally {
            if (store.isRunning) store.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete orphan-reconcile test root: $root"
            }
        }
    }

    @Test
    fun `startup retires dangling active metadata after entity loss`() {
        val root = Files.createTempDirectory("tk-file-dangling-reconcile-").toFile()
        val first = newTestStore(root)
        var recovered: FileStore? = null
        try {
            first.init()
            val path = first.store(
                "user-1",
                "large.bin",
                "application/octet-stream",
                source(root, "dangling", 3),
            )
            val metadata = assertNotNull(first.getMeta(path))
            val physicalObject = assertNotNull(first.getFile(metadata))
            first.close()
            assertTrue(physicalObject.delete())

            recovered = newTestStore(root).also { it.init() }
            assertNull(recovered.getDurableMeta(path))
            assertEquals(0, recovered.accountedStoredBytes)
            assertTrue(recovered.isHealthy)
        } finally {
            if (first.isRunning) first.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete dangling-reconcile test root: $root"
            }
        }
    }

    @Test
    fun `startup atomically removes dangling RocksDB metadata and unowned payloads`() {
        val root = Files.createTempDirectory("tk-file-rocks-reconcile-").toFile()
        val first = newTestStore(root, largeFileThreshold = 8, maxFileSize = 16)
        var recovered: FileStore? = null
        val orphanPath = "unowned/rocks-payload"
        try {
            first.init()
            val danglingPath = first.store(
                "user-1",
                "small.bin",
                "application/octet-stream",
                source(root, "rocks-dangling", 3),
            )
            assertNotNull(first.getMeta(danglingPath))
            first.close()

            withRawFileStoreDatabase(root) { database, _, dataCf ->
                WriteBatch().use { batch ->
                    batch.delete(dataCf, danglingPath.toByteArray(StandardCharsets.UTF_8))
                    batch.put(
                        dataCf,
                        orphanPath.toByteArray(StandardCharsets.UTF_8),
                        byteArrayOf(1, 2, 3, 4),
                    )
                    authoritativeRocksWriteOptions().use { options -> database.write(options, batch) }
                }
            }

            recovered = newTestStore(root, largeFileThreshold = 8, maxFileSize = 16).also { it.init() }
            assertNull(recovered.getDurableMeta(danglingPath))
            assertEquals(0, recovered.accountedStoredBytes)
            assertTrue(recovered.isHealthy)
            recovered.close()

            withRawFileStoreDatabase(root) { database, metaCf, dataCf ->
                assertNull(database.get(metaCf, danglingPath.toByteArray(StandardCharsets.UTF_8)))
                assertNull(database.get(dataCf, danglingPath.toByteArray(StandardCharsets.UTF_8)))
                assertNull(database.get(dataCf, orphanPath.toByteArray(StandardCharsets.UTF_8)))
            }
        } finally {
            if (first.isRunning) first.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete RocksDB reconcile test root: $root"
            }
        }
    }

    @Test
    fun `zero byte objects consume a durable file slot across restart until retirement`() {
        val root = Files.createTempDirectory("tk-file-count-restart-").toFile()
        val first = newTestStore(root, maxTotalFiles = 1)
        var recovered: FileStore? = null
        try {
            first.init()
            val path = first.store(
                "user-1",
                "empty.bin",
                "application/octet-stream",
                source(root, "empty-first", 0),
            )
            assertEquals(0, first.accountedStoredBytes)
            assertEquals(1, first.accountedStoredFiles)
            first.close()

            val reopened = newTestStore(root, maxTotalFiles = 1).also { it.init() }
            recovered = reopened
            assertEquals(0, reopened.accountedStoredBytes)
            assertEquals(1, reopened.accountedStoredFiles)
            val rejected = runCatching {
                reopened.store(
                    "user-1",
                    "another-empty.bin",
                    "application/octet-stream",
                    source(root, "empty-rejected", 0),
                )
            }.exceptionOrNull()
            assertTrue(rejected is FileStoreCapacityExceededException)
            assertEquals(FileStoreCapacityScope.GLOBAL, rejected.scope)

            reopened.rollbackUnpublished(listOf(path))
            assertEquals(0, reopened.accountedStoredFiles)
            val replacement = reopened.store(
                "user-1",
                "replacement.bin",
                "application/octet-stream",
                source(root, "empty-replacement", 0),
            )
            assertNotNull(reopened.getMeta(replacement))
            assertEquals(1, reopened.accountedStoredFiles)
        } finally {
            if (first.isRunning) first.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete file-count restart test root: $root"
            }
        }
    }

    @Test
    fun `concurrent zero byte stores cannot claim the same final file slot`() {
        val root = Files.createTempDirectory("tk-file-count-concurrent-").toFile()
        val store = newTestStore(root, maxTotalFiles = 1)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var workers = emptyList<Thread>()
        try {
            store.init()
            val sources = List(2) { index -> source(root, "concurrent-empty-$index", 0) }
            workers = List(2) { index ->
                thread(start = true, isDaemon = true, name = "file-store-count-$index") {
                    ready.countDown()
                    try {
                        start.await()
                        paths += store.store(
                            "user-$index",
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

            assertTrue(workers.none { it.isAlive }, "capacity workers must terminate")
            assertEquals(1, paths.size)
            assertEquals(1, failures.size)
            val capacityFailure = failures.single()
            assertTrue(capacityFailure is FileStoreCapacityExceededException)
            assertEquals(FileStoreCapacityScope.GLOBAL, capacityFailure.scope)
            assertEquals(1, store.accountedStoredFiles)
            assertEquals(0, store.accountedStoredBytes)
            store.rollbackUnpublished(paths)
            assertEquals(0, store.accountedStoredFiles)
        } finally {
            start.countDown()
            workers.forEach { worker ->
                if (worker.isAlive) worker.interrupt()
                worker.join(5_000)
            }
            if (store.isRunning) store.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete concurrent file-count test root: $root"
            }
        }
    }

    @Test
    fun `startup fails closed when durable metadata exceeds the configured file bound`() {
        val root = Files.createTempDirectory("tk-file-count-startup-bound-").toFile()
        val writer = newTestStore(root, maxTotalFiles = 2)
        var constrained: FileStore? = null
        var recovered: FileStore? = null
        try {
            writer.init()
            repeat(2) { index ->
                writer.store(
                    "user-1",
                    "empty-$index.bin",
                    "application/octet-stream",
                    source(root, "startup-bound-$index", 0),
                )
            }
            writer.close()

            val boundedStore = newTestStore(root, maxTotalFiles = 1)
            constrained = boundedStore
            val failure = assertNotNull(runCatching { boundedStore.init() }.exceptionOrNull())
            assertEquals("FileStore global persistent capacity is exceeded", failure.message)
            assertFalse(boundedStore.isRunning)
            assertFalse(boundedStore.isHealthy)

            recovered = newTestStore(root, maxTotalFiles = 2).also { it.init() }
            assertEquals(2, recovered.accountedStoredFiles)
            assertEquals(0, recovered.accountedStoredBytes)
            assertTrue(recovered.isHealthy)
        } finally {
            if (writer.isRunning) writer.close()
            constrained?.takeIf { it.isRunning }?.close()
            recovered?.takeIf { it.isRunning }?.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete startup file-count bound test root: $root"
            }
        }
    }

}

private fun assertInvalidFileMetadataRejected(
    rawMetadata: String,
    expectedDiagnostic: String,
) {
    val root = Files.createTempDirectory("tk-file-strict-metadata-").toFile()
    val initializer = newTestStore(root)
    var candidate: FileStore? = null
    try {
        initializer.init()
        initializer.close()
        withRawFileStoreDatabase(root) { database, metaCf, _ ->
            authoritativeRocksWriteOptions().use { options ->
                database.put(
                    metaCf,
                    options,
                    STRICT_METADATA_PATH.toByteArray(StandardCharsets.UTF_8),
                    rawMetadata.toByteArray(StandardCharsets.UTF_8),
                )
            }
        }

        val strictStore = newTestStore(root)
        candidate = strictStore
        val failure = assertNotNull(runCatching { strictStore.init() }.exceptionOrNull())
        assertTrue(
            failure.message.orEmpty().contains(expectedDiagnostic),
            "strict metadata rejection should identify $expectedDiagnostic: ${failure.message}",
        )
        assertFalse(strictStore.isRunning)
        assertFalse(strictStore.isHealthy)
    } finally {
        if (initializer.isRunning) initializer.close()
        candidate?.takeIf { it.isRunning }?.close()
        check(root.deleteRecursively() || !root.exists()) {
            "Failed to delete strict FileStore metadata test root: $root"
        }
    }
}

private const val STRICT_METADATA_PATH = "user-1/strict-metadata"

private class MutationFailure(message: String) : RuntimeException(message)

private class RecordingMutationFaultInjector : FileStoreMutationFaultInjector {
    private val failures = mutableMapOf<FileStoreMutationPoint, Throwable>()
    private val observedMetadata = mutableMapOf<FileStoreMutationPoint, FileMetadata>()

    fun failAt(point: FileStoreMutationPoint, failure: Throwable) {
        failures[point] = failure
    }

    fun metadataAt(point: FileStoreMutationPoint): FileMetadata? = observedMetadata[point]

    override fun before(point: FileStoreMutationPoint, metadata: FileMetadata) {
        observedMetadata[point] = metadata
        failures[point]?.let { throw it }
    }
}

private fun newTestStore(
    root: File,
    faults: FileStoreMutationFaultInjector = FileStoreMutationFaultInjector { _, _ -> },
    largeFileThreshold: Long = 2,
    maxFileSize: Long = 16,
    maxTotalBytes: Long = DEFAULT_FILE_STORE_MAX_TOTAL_BYTES,
    maxTotalFiles: Int = 100_000,
    maxOwnerBytes: Long = DEFAULT_FILE_STORE_MAX_OWNER_BYTES,
    maxOwnerFiles: Int = DEFAULT_FILE_STORE_MAX_OWNER_FILES,
): FileStore = FileStore(
    dbPath = File(root, "rocksdb").absolutePath,
    fsRoot = File(root, "files").absolutePath,
    largeFileThreshold = largeFileThreshold,
    maxFileSize = maxFileSize,
    nativeResourceCloser = FileStoreNativeResourceCloser(::closeFileStoreNativeResource),
    mutationFaultInjector = faults,
    maxTotalBytes = maxTotalBytes,
    maxTotalFiles = maxTotalFiles,
    maxOwnerBytes = maxOwnerBytes,
    maxOwnerFiles = maxOwnerFiles,
)

private fun source(root: File, name: String, size: Int): File = File(root, "$name.bin").apply {
    writeBytes(ByteArray(size) { it.toByte() })
}

private inline fun <T> withRawFileStoreDatabase(
    root: File,
    block: (RocksDB, ColumnFamilyHandle, ColumnFamilyHandle) -> T,
): T {
    RocksDB.loadLibrary()
    val handles = mutableListOf<ColumnFamilyHandle>()
    val familyOptions = List(4) { ColumnFamilyOptions() }
    val databaseOptions = DBOptions()
        .setCreateIfMissing(false)
        .setCreateMissingColumnFamilies(false)
    val descriptors = listOf(
        ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, familyOptions[0]),
        ColumnFamilyDescriptor("meta".toByteArray(StandardCharsets.UTF_8), familyOptions[1]),
        ColumnFamilyDescriptor("data".toByteArray(StandardCharsets.UTF_8), familyOptions[2]),
        ColumnFamilyDescriptor("uploads".toByteArray(StandardCharsets.UTF_8), familyOptions[3]),
    )
    val database = RocksDB.open(
        databaseOptions,
        File(root, "rocksdb").absolutePath,
        descriptors,
        handles,
    )
    try {
        return block(database, handles[1], handles[2])
    } finally {
        handles.asReversed().forEach { handle -> handle.close() }
        database.closeE()
        databaseOptions.close()
        familyOptions.asReversed().forEach { options -> options.close() }
    }
}

private class StoreFixture(
    largeFileThreshold: Long,
    maxFileSize: Long,
) : AutoCloseable {
    private val root = Files.createTempDirectory("tk-file-upload-lifecycle-").toFile()
    val store: FileStore

    init {
        try {
            store = FileStore(
                dbPath = File(root, "rocksdb").absolutePath,
                fsRoot = File(root, "files").absolutePath,
                largeFileThreshold = largeFileThreshold,
                maxFileSize = maxFileSize,
            ).also { it.init() }
        } catch (failure: Throwable) {
            runCatching {
                check(root.deleteRecursively() || !root.exists()) {
                    "Failed to delete FileStore lifecycle root after initialization failure: $root"
                }
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    fun source(name: String, size: Int): File = File(root, "$name.bin").apply {
        writeBytes(ByteArray(size) { it.toByte() })
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            store.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete FileStore lifecycle test root: $root"
            }
        } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
