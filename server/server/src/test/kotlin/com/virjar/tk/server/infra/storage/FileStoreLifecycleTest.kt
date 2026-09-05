package com.virjar.tk.server.infra.storage

import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.RocksDB
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class FileStoreLifecycleTest {

    @Test
    fun `rocksdb tier threshold must fit its explicit heap array budget`() {
        val oversizedThreshold = Int.MAX_VALUE.toLong() + 1L

        assertFailsWith<IllegalArgumentException> {
            FileStore(
                dbPath = "unused-rocksdb",
                fsRoot = "unused-files",
                largeFileThreshold = oversizedThreshold,
                maxFileSize = oversizedThreshold,
            )
        }
    }

    @Test
    fun `ordinary startup failure with successful rollback remains retryable`() {
        val root = Files.createTempDirectory("tk-file-store-retryable-startup-").toFile()
        val startupFailure = FileStoreStartupFailure("transient startup checkpoint failed")
        var shouldFail = true
        var handlesClosed = 0
        var databasesClosed = 0
        val store = FileStore(
            dbPath = File(root, "rocksdb").absolutePath,
            fsRoot = File(root, "files").absolutePath,
            largeFileThreshold = 8,
            maxFileSize = 64,
            nativeResourceCloser = FileStoreNativeResourceCloser { resource ->
                closeFileStoreNativeResource(resource)
                when (resource) {
                    is ColumnFamilyHandle -> handlesClosed += 1
                    is RocksDB -> databasesClosed += 1
                }
            },
            afterNativeOpen = {
                if (shouldFail) {
                    shouldFail = false
                    throw startupFailure
                }
            },
        )

        try {
            val first = try {
                store.init()
                fail("a regular file cannot be used as the filesystem tier root")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(startupFailure, first)
            assertFalse(store.isRunning)
            assertEquals(4, handlesClosed, "retryable startup must still roll every handle back")
            assertEquals(1, databasesClosed, "retryable startup must still roll the database back")

            store.init()
            assertTrue(store.isRunning, "fully rolled-back startup failure must not terminalize the instance")
            store.close()
            store.close()
            store.init()
            assertTrue(store.isRunning, "a successful close must permit a clean restart")
            store.close()
        } finally {
            if (store.isRunning) store.close()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete retryable FileStore startup root: $root"
            }
        }
    }

    @Test
    fun `option close failures drain every native resource before startup remains unpublished`() {
        val root = Files.createTempDirectory("tk-file-store-startup-").toFile()
        val dbPath = File(root, "rocksdb").absolutePath
        val fsPath = File(root, "files").absolutePath
        val ordinaryFailure = OptionCloseFailure("default options close failed")
        val fatalFailure = NativeCloseFatalFailure("database options close failed")
        var columnFamilyOptionsClosed = 0
        var dbOptionsClosed = 0
        var handlesClosed = 0
        var databasesClosed = 0

        val store = FileStore(
            dbPath = dbPath,
            fsRoot = fsPath,
            largeFileThreshold = 8,
            maxFileSize = 64,
            nativeResourceCloser = FileStoreNativeResourceCloser { resource ->
                closeFileStoreNativeResource(resource)
                when (resource) {
                    is ColumnFamilyOptions -> {
                        columnFamilyOptionsClosed += 1
                        if (columnFamilyOptionsClosed == 1) throw ordinaryFailure
                    }

                    is DBOptions -> {
                        dbOptionsClosed += 1
                        throw fatalFailure
                    }

                    is ColumnFamilyHandle -> handlesClosed += 1
                    is RocksDB -> databasesClosed += 1
                }
            },
        )

        try {
            val observed = try {
                store.init()
                fail("native option close failure should abort FileStore startup")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(fatalFailure, observed)
            assertTrue(fatalFailure.suppressed.any { it === ordinaryFailure })
            assertFalse(store.isRunning)
            assertEquals(4, columnFamilyOptionsClosed, "all column-family options must close")
            assertEquals(1, dbOptionsClosed, "the database options must close")
            assertEquals(4, handlesClosed, "every opened column-family handle must roll back")
            assertEquals(1, databasesClosed, "the opened RocksDB must roll back")

            val closeCount = columnFamilyOptionsClosed + dbOptionsClosed + handlesClosed + databasesClosed
            val repeatedInit = try {
                store.init()
                fail("cleanup-failed startup must remain terminal")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(fatalFailure, repeatedInit)
            val repeatedClose = try {
                store.close()
                fail("close must replay a startup cleanup terminal failure")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(fatalFailure, repeatedClose)
            assertEquals(
                closeCount,
                columnFamilyOptionsClosed + dbOptionsClosed + handlesClosed + databasesClosed,
                "terminal replay must not touch native resources twice",
            )

            // 打开同一个数据库证明失败的实例没有继续持有原生锁。
            val recovered = FileStore(dbPath, fsPath, largeFileThreshold = 8, maxFileSize = 64)
            try {
                recovered.init()
                assertTrue(recovered.isRunning)
            } finally {
                recovered.close()
            }
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete FileStore startup lifecycle root: $root"
            }
        }
    }

    @Test
    fun `startup rollback close failure terminalizes the instance with fatal priority`() {
        val root = Files.createTempDirectory("tk-file-store-rollback-lifecycle-").toFile()
        val dbPath = File(root, "rocksdb").absolutePath
        val fsPath = File(root, "files").absolutePath
        val startupFailure = FileStoreStartupFailure("startup checkpoint failed")
        val cleanupFatalFailure = NativeCloseFatalFailure("handle rollback failed")
        var handlesClosed = 0
        var databasesClosed = 0
        val store = FileStore(
            dbPath = dbPath,
            fsRoot = fsPath,
            largeFileThreshold = 8,
            maxFileSize = 64,
            nativeResourceCloser = FileStoreNativeResourceCloser { resource ->
                closeFileStoreNativeResource(resource)
                when (resource) {
                    is ColumnFamilyHandle -> {
                        handlesClosed += 1
                        if (handlesClosed == 1) throw cleanupFatalFailure
                    }

                    is RocksDB -> databasesClosed += 1
                }
            },
            afterNativeOpen = { throw startupFailure },
        )

        try {
            val observed = try {
                store.init()
                fail("injected startup checkpoint should fail")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(cleanupFatalFailure, observed)
            assertTrue(cleanupFatalFailure.suppressed.any { it === startupFailure })
            assertFalse(store.isRunning)
            assertEquals(4, handlesClosed, "fatal rollback must not skip later handles")
            assertEquals(1, databasesClosed, "fatal rollback must not skip the database")

            val repeatedInit = try {
                store.init()
                fail("rollback-failed startup must remain terminal")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(cleanupFatalFailure, repeatedInit)
            val repeatedClose = try {
                store.close()
                fail("close must replay the startup rollback terminal failure")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(cleanupFatalFailure, repeatedClose)
            assertEquals(4, handlesClosed)
            assertEquals(1, databasesClosed)

            val recovered = FileStore(dbPath, fsPath, largeFileThreshold = 8, maxFileSize = 64)
            try {
                recovered.init()
            } finally {
                recovered.close()
            }
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete FileStore rollback lifecycle root: $root"
            }
        }
    }

    @Test
    fun `failed close drains all resources and replays one terminal failure`() {
        val root = Files.createTempDirectory("tk-file-store-close-lifecycle-").toFile()
        val ordinaryFailure = OptionCloseFailure("column-family handle close failed")
        val fatalFailure = NativeCloseFatalFailure("database close failed")
        var handlesClosed = 0
        var databasesClosed = 0
        val store = FileStore(
            dbPath = File(root, "rocksdb").absolutePath,
            fsRoot = File(root, "files").absolutePath,
            largeFileThreshold = 8,
            maxFileSize = 64,
            nativeResourceCloser = FileStoreNativeResourceCloser { resource ->
                closeFileStoreNativeResource(resource)
                when (resource) {
                    is ColumnFamilyHandle -> {
                        handlesClosed += 1
                        if (handlesClosed == 1) throw ordinaryFailure
                    }

                    is RocksDB -> {
                        databasesClosed += 1
                        throw fatalFailure
                    }
                }
            },
        )

        try {
            store.init()
            val first = try {
                store.close()
                fail("injected native close failures should escape")
            } catch (failure: Throwable) {
                failure
            }

            assertSame(fatalFailure, first)
            assertTrue(fatalFailure.suppressed.any { it === ordinaryFailure })
            assertFalse(store.isRunning)
            assertEquals(4, handlesClosed, "a handle failure must not skip the remaining handles")
            assertEquals(1, databasesClosed, "database close must still run")

            val repeatedClose = try {
                store.close()
                fail("failed close must replay its terminal failure")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(fatalFailure, repeatedClose)

            val restart = try {
                store.init()
                fail("an instance with a failed close must not restart")
            } catch (failure: Throwable) {
                failure
            }
            assertSame(fatalFailure, restart)
            assertEquals(4, handlesClosed, "terminal replay must not close handles twice")
            assertEquals(1, databasesClosed, "terminal replay must not close the database twice")
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete FileStore close lifecycle root: $root"
            }
        }
    }
}

private class OptionCloseFailure(message: String) : RuntimeException(message)

private class FileStoreStartupFailure(message: String) : RuntimeException(message)

private class NativeCloseFatalFailure(message: String) : Error(message)
