package com.virjar.tk.server.infra.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileStoreCrashResidueTest {

    @Test
    fun `startup removes every managed residue and preserves unknown entries`() {
        val root = Files.createTempDirectory("tk-file-store-residue-cleanup-").toFile()
        val tmpRoot = File(root, "tmp")
        val managedResidues = listOf(
            ManagedTempFiles.create(tmpRoot, UPLOAD_STAGING_TEMP_PREFIX, STAGING_TEMP_SUFFIX),
            ManagedTempFiles.create(tmpRoot, FILE_STORE_TEMP_PREFIX, STAGING_TEMP_SUFFIX),
            ManagedTempFiles.create(tmpRoot, THUMBNAIL_TEMP_PREFIX, THUMBNAIL_TEMP_SUFFIX),
            ManagedTempFiles.create(tmpRoot, THUMBNAIL_RESULT_TEMP_PREFIX, STAGING_TEMP_SUFFIX),
        ).onEach { residue -> residue.writeBytes(byteArrayOf(1)) }
        val unknownFile = File(tmpRoot, "teamtalk-probe-unknown.tmp").apply { writeText("keep") }
        val similarButUnmanaged = File(tmpRoot, "teamtalk-upload-unknown.bin").apply { writeText("keep") }
        val unknownDirectory = File(tmpRoot, "unknown-directory").apply {
            check(mkdir()) { "Failed to create unknown temporary directory fixture" }
        }
        val store = newCrashResidueTestStore(root, tmpRoot)

        try {
            store.init()

            managedResidues.forEach { residue -> assertFalse(residue.exists()) }
            assertTrue(unknownFile.isFile)
            assertTrue(similarButUnmanaged.isFile)
            assertTrue(unknownDirectory.isDirectory)
            assertTrue(store.isRunning)
        } finally {
            if (store.isRunning) store.close()
            deleteTestRoot(root)
        }
    }

    @Test
    fun `contender cannot clean residue before acquiring RocksDB ownership`() {
        val root = Files.createTempDirectory("tk-file-store-residue-owner-").toFile()
        val tmpRoot = File(root, "tmp")
        val owner = newCrashResidueTestStore(root, tmpRoot)
        val contender = newCrashResidueTestStore(root, tmpRoot)

        try {
            owner.init()
            val residue = ManagedTempFiles.create(
                tmpRoot,
                UPLOAD_STAGING_TEMP_PREFIX,
                STAGING_TEMP_SUFFIX,
            ).apply { writeBytes(byteArrayOf(1)) }

            assertFails("the second FileStore must not acquire an already-owned RocksDB") {
                contender.init()
            }
            assertFalse(contender.isRunning)
            assertTrue(residue.isFile, "a process without the RocksDB lock must not clean residue")

            owner.close()
            contender.init()
            assertFalse(residue.exists())
            assertTrue(contender.isRunning)
        } finally {
            if (contender.isRunning) contender.close()
            if (owner.isRunning) owner.close()
            deleteTestRoot(root)
        }
    }

    @Test
    fun `managed non regular residue keeps startup unpublished and retryable`() {
        val root = Files.createTempDirectory("tk-file-store-residue-shape-").toFile()
        val tmpRoot = ManagedTempFiles.ensureDirectory(File(root, "tmp"))
        val managedDirectory = File(
            tmpRoot,
            "${UPLOAD_STAGING_TEMP_PREFIX}broken$STAGING_TEMP_SUFFIX",
        ).apply {
            check(mkdir()) { "Failed to create managed-name directory fixture" }
        }
        val unknownFile = File(tmpRoot, "keep.txt").apply { writeText("keep") }
        val store = newCrashResidueTestStore(root, tmpRoot)

        try {
            assertFailsWith<IllegalStateException> { store.init() }
            assertFalse(store.isRunning)
            assertTrue(managedDirectory.isDirectory)
            assertTrue(unknownFile.isFile)

            assertTrue(managedDirectory.delete())
            store.init()
            assertTrue(store.isRunning, "successful native rollback must permit a clean retry")
            assertTrue(unknownFile.isFile)
        } finally {
            if (store.isRunning) store.close()
            deleteTestRoot(root)
        }
    }

    @Test
    fun `managed deletion failure keeps startup unpublished and retryable`() {
        val root = Files.createTempDirectory("tk-file-store-residue-delete-").toFile()
        val tmpRoot = File(root, "tmp")
        val residue = ManagedTempFiles.create(
            tmpRoot,
            FILE_STORE_TEMP_PREFIX,
            STAGING_TEMP_SUFFIX,
        ).apply { writeBytes(byteArrayOf(1)) }
        val unknownFile = File(tmpRoot, "keep.txt").apply { writeText("keep") }
        val deletionFailure = ManagedResidueDeletionFailure("injected managed-residue deletion failure")
        var rejectDeletion = true
        val store = newCrashResidueTestStore(root, tmpRoot) { path ->
            if (rejectDeletion && path == residue.toPath().toAbsolutePath().normalize()) {
                throw deletionFailure
            }
            Files.delete(path)
        }

        try {
            val observed = assertFailsWith<ManagedTempResidueException> { store.init() }
            assertSame(deletionFailure, observed.cause)
            assertFalse(store.isRunning)
            assertTrue(residue.isFile)
            assertTrue(unknownFile.isFile)

            rejectDeletion = false
            store.init()
            assertFalse(residue.exists())
            assertTrue(unknownFile.isFile)
            assertTrue(store.isRunning)
        } finally {
            if (store.isRunning) store.close()
            deleteTestRoot(root)
        }
    }

    @Test
    fun `runtime retirement reports typed residue and retries absence without a second delete`() {
        val root = Files.createTempDirectory("tk-file-store-runtime-retire-").toFile()
        val tmpRoot = File(root, "tmp")
        val deletionFailure = ManagedResidueDeletionFailure("injected runtime retirement failure")
        var rejectDeletion = true
        var deleteCalls = 0
        val store = newCrashResidueTestStore(root, tmpRoot) { path ->
            deleteCalls += 1
            if (rejectDeletion) throw deletionFailure
            Files.delete(path)
        }

        try {
            store.init()
            val staged = store.createTemporaryFile(UPLOAD_STAGING_TEMP_PREFIX, STAGING_TEMP_SUFFIX)
                .apply { writeBytes(byteArrayOf(1)) }
            val observed = assertFailsWith<ManagedTempResidueException> {
                store.retireTemporaryFile(staged)
            }
            assertSame(deletionFailure, observed.cause)
            assertTrue(staged.isFile)

            rejectDeletion = false
            store.retireTemporaryFile(staged)
            assertFalse(staged.exists())
            store.retireTemporaryFile(staged)
            assertTrue(deleteCalls == 2, "an already absent entry must not invoke the deleter again")
        } finally {
            if (store.isRunning) store.close()
            deleteTestRoot(root)
        }
    }
}

private fun newCrashResidueTestStore(
    root: File,
    tmpRoot: File,
    managedTempFileDeleter: (Path) -> Unit = { path -> Files.delete(path) },
): FileStore = FileStore(
    dbPath = File(root, "rocksdb").absolutePath,
    fsRoot = File(root, "files").absolutePath,
    largeFileThreshold = 8,
    maxFileSize = 64,
    nativeResourceCloser = FileStoreNativeResourceCloser { resource ->
        closeFileStoreNativeResource(resource)
    },
    tmpRoot = tmpRoot,
    managedTempFileDeleter = managedTempFileDeleter,
)

private fun deleteTestRoot(root: File) {
    check(root.deleteRecursively() || !root.exists()) {
        "Failed to delete FileStore crash-residue test root: $root"
    }
}

private class ManagedResidueDeletionFailure(message: String) : RuntimeException(message)
