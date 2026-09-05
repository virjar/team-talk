package com.virjar.tk.server.infra.storage

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class FileStoreUploadTransactionTest {

    @Test
    fun `upload id rejects UUID aliases instead of sharing a durable key`() {
        assertFailsWith<IllegalArgumentException> {
            canonicalFileStoreUploadId("1-1-1-1-1")
        }
        assertFailsWith<IllegalArgumentException> {
            canonicalFileStoreUploadId(UUID.randomUUID().toString().uppercase())
        }
    }

    @Test
    fun `post-durable failure aborts from attempt metadata before handle registration`() {
        val injected = PostDurableUploadFailure("after durable object")
        var discoveryCount = 0
        val faults = UploadTransactionFaultInjector(
            FileStoreMutationPoint.AFTER_TRANSACTION_OBJECT_DURABLE,
            injected,
        )
        val fixture = UploadTransactionFixture(
            faults = faults,
            beforeUploadAttemptDiscovery = { discoveryCount += 1 },
        )
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 2)
            val observed = runCatching {
                transaction.storeReserved("main.bin", MIME, fixture.source("main", 2))
            }.exceptionOrNull()

            assertSame(injected, observed)
            assertEquals(0, fixture.store.accountedStoredBytes)
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
            assertEquals(1, discoveryCount)
            assertTrue(fixture.store.isHealthy)

            fixture.reopen()
            val replacement = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 2)
            replacement.abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `upload object mutation and close share the FileStore lifecycle monitor`() {
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val closeAttempting = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val storedPaths = Collections.synchronizedList(mutableListOf<String>())
        val fixture = UploadTransactionFixture(
            faults = FileStoreMutationFaultInjector { point, _ ->
                if (point == FileStoreMutationPoint.AFTER_TRANSACTION_OBJECT_DURABLE) {
                    mutationEntered.countDown()
                    releaseMutation.await()
                }
            },
        )
        var workers = emptyList<Thread>()
        try {
            val transaction = fixture.store.beginStarted(UUID.randomUUID().toString(), FINGERPRINT_A, 1)
            val writer = thread(isDaemon = true, name = "file-store-upload-mutation") {
                runCatching {
                    transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
                }.onSuccess(storedPaths::add).onFailure(failures::add)
            }
            workers = listOf(writer)
            assertTrue(mutationEntered.await(5, TimeUnit.SECONDS))

            val closer = thread(isDaemon = true, name = "file-store-close-during-upload") {
                closeAttempting.countDown()
                runCatching { fixture.store.close() }.onFailure(failures::add)
                closeReturned.countDown()
            }
            workers = workers + closer
            assertTrue(closeAttempting.await(5, TimeUnit.SECONDS))
            assertFalse(
                closeReturned.await(250, TimeUnit.MILLISECONDS),
                "close must wait while an upload mutation owns the FileStore lifecycle monitor",
            )

            releaseMutation.countDown()
            workers.forEach { it.join(5_000) }

            assertTrue(workers.none(Thread::isAlive), "upload and close workers must terminate")
            assertTrue(failures.isEmpty(), "upload and close must not race native state: $failures")
            val storedPath = storedPaths.single()
            assertFalse(fixture.store.isRunning)

            fixture.store.init()
            assertNull(fixture.store.getDurableMeta(storedPath))
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
            transaction.close()
        } finally {
            releaseMutation.countDown()
            workers.forEach { worker ->
                worker.join(5_000)
                if (worker.isAlive) worker.interrupt()
                worker.join(5_000)
            }
            fixture.close()
        }
    }

    @Test
    fun `uncertain abort keeps STARTED when a journaled backing object disappeared`() {
        val injected = PostDurableUploadFailure("after second durable object")
        var injectFailure = false
        lateinit var fixture: UploadTransactionFixture
        lateinit var journaledPath: String
        val faults = FileStoreMutationFaultInjector { point, _ ->
            if (injectFailure && point == FileStoreMutationPoint.AFTER_TRANSACTION_OBJECT_DURABLE) {
                throw injected
            }
        }
        fixture = UploadTransactionFixture(
            faults = faults,
            beforeUploadAttemptDiscovery = {
                fixture.store.rollbackUnpublished(listOf(journaledPath))
            },
        )
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            journaledPath = transaction.storeReserved(
                "main.bin",
                MIME,
                fixture.source("main", 1),
            )
            transaction.reserveObject(1)
            injectFailure = true

            val observed = assertNotNull(
                runCatching {
                    transaction.storeReserved("thumb.bin", MIME, fixture.source("thumb", 1))
                }.exceptionOrNull(),
            )

            assertSame(injected, observed)
            assertTrue(
                observed.suppressed.any {
                    it.message.orEmpty().contains("do not cover its journal")
                },
            )
            assertFalse(fixture.store.isHealthy)
            assertEquals(1, fixture.store.accountedStoredFiles)

            fixture.reopen()
            assertEquals(0, fixture.store.accountedStoredFiles)
            val replacement = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            replacement.abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `abort before body storage does not scan the metadata keyspace`() {
        var discoveryCount = 0
        val fixture = UploadTransactionFixture(
            beforeUploadAttemptDiscovery = { discoveryCount += 1 },
        )
        try {
            val transaction = assertIs<BeginFileStoreUploadResult.Started>(
                fixture.store.beginUploadTransaction(
                    OWNER,
                    UUID.randomUUID().toString(),
                    payloadLength = 3,
                    receiptLeaseExpiresAt = Long.MAX_VALUE,
                ),
            ).transaction

            transaction.abort()

            assertEquals(0, discoveryCount)
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `stale handle close after restart cannot abort a replacement attempt`() {
        val fixture = UploadTransactionFixture()
        val uploadId = UUID.randomUUID().toString()
        try {
            val stale = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            val stalePath = stale.storeReserved("stale.bin", MIME, fixture.source("stale", 1))
            fixture.restartSameInstance()
            assertNull(fixture.store.getDurableMeta(stalePath))

            val replacement = assertIs<BeginFileStoreUploadResult.Started>(
                fixture.store.beginUploadTransaction(
                    OWNER,
                    uploadId,
                    payloadLength = 1,
                    receiptLeaseExpiresAt = Long.MAX_VALUE,
                ),
            ).transaction
            assertEquals(1, fixture.store.accountedPendingFiles)

            stale.close()
            stale.close()
            assertEquals(1, fixture.store.accountedPendingFiles)
            assertEquals(0, fixture.store.accountedStoredFiles)

            replacement.bindFingerprint(FINGERPRINT_A)
            val replacementPath = replacement.storeReserved(
                "replacement.bin",
                MIME,
                fixture.source("replacement", 1),
            )
            replacement.completeAndRelease(RECEIPT)
            assertNotNull(fixture.store.getMeta(replacementPath))
            assertEquals(1, fixture.store.accountedStoredFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `normal abort keeps STARTED when a journaled backing object is missing`() {
        val fixture = UploadTransactionFixture()
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            val path = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            fixture.store.rollbackUnpublished(listOf(path))

            val failure = assertFailsWith<IllegalStateException> { transaction.abort() }

            assertTrue(failure.message.orEmpty().contains("journal backing object is missing"))
            assertFalse(fixture.store.isHealthy)
            fixture.reopen()
            val replacement = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            replacement.abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `clock rollback clamps completion time and receipt survives restart`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 2_000,
            )
            transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            now = 900L
            val completed = transaction.completeAndRelease(RECEIPT)
            assertEquals(1_000L, completed.completedAt)

            fixture.reopen()
            val candidate = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 2_000),
            ).candidate
            val replay = candidate.use { it.requireSameFingerprint(FINGERPRINT_A) }
            assertEquals(completed, replay)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `lease expiry is inclusive at begin and complete then typed after crossing`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        try {
            val expiredId = UUID.randomUUID().toString()
            val beginExpired = assertIs<FileStoreUploadExpiredException>(
                runCatching {
                    fixture.store.beginUploadTransaction(OWNER, expiredId, 1, 999)
                }.exceptionOrNull(),
            )
            assertEquals(expiredId, beginExpired.uploadId)
            assertEquals(999L, beginExpired.receiptLeaseExpiresAt)
            assertEquals(0, fixture.store.accountedPendingFiles)

            val exactId = UUID.randomUUID().toString()
            val exact = fixture.store.beginStarted(
                exactId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_000,
            )
            exact.storeReserved("exact.bin", MIME, fixture.source("exact", 1))
            assertEquals(1_000L, exact.completeAndRelease(RECEIPT).completedAt)

            val crossingId = UUID.randomUUID().toString()
            val crossing = fixture.store.beginStarted(
                crossingId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_000,
            )
            val crossingPath = crossing.storeReserved(
                "crossing.bin",
                MIME,
                fixture.source("crossing", 1),
            )
            now = 1_001L
            val completeExpired = assertIs<FileStoreUploadExpiredException>(
                runCatching { crossing.complete(RECEIPT) }.exceptionOrNull(),
            )
            assertEquals(crossingId, completeExpired.uploadId)
            assertEquals(1_000L, completeExpired.receiptLeaseExpiresAt)
            assertNull(fixture.store.getDurableMeta(crossingPath))
            assertEquals(1, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `completed mixed-tier upload replays the exact receipt after restart`() {
        val fixture = UploadTransactionFixture(largeFileThreshold = 2, maxFileSize = 16)
        val uploadId = UUID.randomUUID().toString()
        try {
            val started = fixture.store.beginStarted(uploadId, FINGERPRINT_A, payloadLength = 1)
            val mainPath = started.storeReserved("main.bin", MIME, fixture.source("main", 1))
            started.reserveObject(3)
            val thumbnailPath = started.storeReserved("thumb.jpg", "image/jpeg", fixture.source("thumb", 3))
            val completed = started.completeAndRelease(RECEIPT)

            assertEquals(listOf(mainPath, thumbnailPath), completed.objects.map { it.path })
            assertEquals(RECEIPT, completed.encodedReceipt)
            assertEquals(StorageTier.ROCKSDB, fixture.store.getMeta(mainPath)?.tier)
            assertEquals(StorageTier.FILESYSTEM, fixture.store.getMeta(thumbnailPath)?.tier)
            fixture.reopen()

            val candidate = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, Long.MAX_VALUE),
            ).candidate
            val replay = candidate.use { it.requireSameFingerprint(FINGERPRINT_A) }
            assertEquals(completed, replay)
            assertEquals(4, fixture.store.accountedStoredBytes)
            assertEquals(2, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `same id with another fingerprint conflicts without allocating a second object`() {
        val fixture = UploadTransactionFixture()
        val uploadId = UUID.randomUUID().toString()
        try {
            val started = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 2)
            val path = started.storeReserved("main.bin", MIME, fixture.source("main", 2))
            started.completeAndRelease(RECEIPT)

            val replay = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                // 仅凭长度不能决定重放：调用方必须暂存并绑定完整哈希。
                fixture.store.beginUploadTransaction(OWNER, uploadId, 3, Long.MAX_VALUE),
            ).candidate
            val failure = replay.use { candidate ->
                assertIs<FileStoreUploadConflictException>(
                    runCatching {
                        candidate.requireSameFingerprint(FINGERPRINT_B)
                    }.exceptionOrNull(),
                )
            }
            assertEquals(uploadId, failure.uploadId)
            assertNotNull(fixture.store.getMeta(path))
            assertEquals(1, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `concurrent begin has one owner and one bounded in-progress rejection`() {
        val fixture = UploadTransactionFixture()
        val uploadId = UUID.randomUUID().toString()
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val results = Collections.synchronizedList(mutableListOf<BeginFileStoreUploadResult>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var workers = emptyList<Thread>()
        try {
            workers = List(2) { index ->
                thread(isDaemon = true, name = "upload-transaction-begin-$index") {
                    ready.countDown()
                    start.await()
                    runCatching {
                        fixture.store.beginUploadTransaction(OWNER, uploadId, 0, Long.MAX_VALUE)
                    }.onSuccess(results::add).onFailure(failures::add)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            workers.forEach { it.join(5_000) }

            assertTrue(workers.none(Thread::isAlive))
            val owner = assertIs<BeginFileStoreUploadResult.Started>(results.single()).transaction
            assertIs<FileStoreUploadInProgressException>(failures.single())
            assertEquals(1, fixture.store.accountedPendingFiles)
            assertEquals(0, fixture.store.accountedStoredFiles)
            owner.abort()
            assertEquals(0, fixture.store.accountedPendingFiles)
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
    fun `abort removes mixed-tier objects and releases every reservation`() {
        val fixture = UploadTransactionFixture(largeFileThreshold = 2, maxFileSize = 16)
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            val main = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            transaction.reserveObject(3)
            val thumbnail = transaction.storeReserved("thumb.jpg", "image/jpeg", fixture.source("thumb", 3))
            transaction.abort()

            assertNull(fixture.store.getDurableMeta(main))
            assertNull(fixture.store.getDurableMeta(thumbnail))
            assertEquals(0, fixture.store.accountedStoredBytes)
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)

            val replacement = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            replacement.abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `failed mixed-tier abort retains cleanup intent and recovers after restart`() {
        val deleteFailure = IllegalStateException("injected filesystem deletion failure")
        var failDeletion = true
        val fixture = UploadTransactionFixture(
            largeFileThreshold = 2,
            maxFileSize = 16,
            faults = FileStoreMutationFaultInjector { point, _ ->
                if (failDeletion && point == FileStoreMutationPoint.DELETE_FILESYSTEM_ENTITY) {
                    throw deleteFailure
                }
            },
        )
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            val main = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            val mainMetadata = assertNotNull(fixture.store.getMeta(main))
            transaction.reserveObject(3)
            val thumbnail = transaction.storeReserved("thumb.jpg", "image/jpeg", fixture.source("thumb", 3))
            val thumbnailMetadata = assertNotNull(fixture.store.getMeta(thumbnail))
            val thumbnailFile = assertNotNull(fixture.store.getFile(thumbnailMetadata))

            // 在事务预检之后、PENDING_DELETE 落盘之后模拟实体删除失败。
            assertSame(deleteFailure, assertFailsWith<IllegalStateException> { transaction.abort() })
            assertNull(fixture.store.getMeta(thumbnail), "pending deletion must not remain publicly readable")
            assertEquals(
                FileMetadataLifecycle.PENDING_DELETE,
                assertNotNull(fixture.store.getDurableMeta(thumbnail)).lifecycle,
            )
            assertNull(fixture.store.getDurableMeta(main), "earlier objects must still be rolled back")
            assertFalse(fixture.store.hasBackingData(mainMetadata))
            assertTrue(thumbnailFile.exists())
            assertEquals(3, fixture.store.accountedStoredBytes)
            assertEquals(1, fixture.store.accountedStoredFiles)
            assertEquals(FileStoreUsage(3, 1), fixture.store.accountedOwnerUsage(OWNER))
            assertFalse(fixture.store.isHealthy, "unresolved physical cleanup must remain visible")

            // 故障解除后由启动恢复清理残留对象及 STARTED 记录，同一上传身份可重新使用。
            failDeletion = false
            fixture.reopen()
            assertNull(fixture.store.getDurableMeta(thumbnail))
            assertFalse(thumbnailFile.exists())
            assertEquals(0, fixture.store.accountedStoredBytes)
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
            assertTrue(fixture.store.isHealthy)
            fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1).abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `restart reconciles mixed-tier STARTED objects before reuse`() {
        val fixture = UploadTransactionFixture(largeFileThreshold = 2, maxFileSize = 16)
        val uploadId = UUID.randomUUID().toString()
        try {
            val abandoned = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            val rocksPath = abandoned.storeReserved("main.bin", MIME, fixture.source("main", 1))
            abandoned.reserveObject(3)
            val filesystemPath = abandoned.storeReserved(
                "thumb.jpg",
                "image/jpeg",
                fixture.source("thumb", 3),
            )
            assertEquals(2, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)

            fixture.reopen()

            assertNull(fixture.store.getDurableMeta(rocksPath))
            assertNull(fixture.store.getDurableMeta(filesystemPath))
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
            val replacement = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            replacement.abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `restart releases a STARTED reservation whose body was never written`() {
        val fixture = UploadTransactionFixture(maxTotalFiles = 1, maxOwnerFiles = 1)
        val uploadId = UUID.randomUUID().toString()
        try {
            assertIs<BeginFileStoreUploadResult.Started>(
                fixture.store.beginUploadTransaction(
                    OWNER,
                    uploadId,
                    payloadLength = 0,
                    receiptLeaseExpiresAt = Long.MAX_VALUE,
                ),
            )
            assertEquals(1, fixture.store.accountedPendingFiles)

            fixture.reopen()

            assertEquals(0, fixture.store.accountedPendingFiles)
            val replacement = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 0)
            replacement.abort()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `startup rejects an unexpired completed receipt with missing backing`() {
        val fixture = UploadTransactionFixture()
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 2)
            val path = transaction.storeReserved("main.bin", MIME, fixture.source("main", 2))
            transaction.completeAndRelease(RECEIPT)
            fixture.store.rollbackUnpublished(listOf(path))
            fixture.store.close()

            val failure = assertNotNull(runCatching { fixture.store.init() }.exceptionOrNull())
            assertTrue(
                failure.message.orEmpty().contains("backing objects are incomplete"),
                "startup diagnostic should identify the torn receipt: ${failure.message}",
            )
            assertFalse(fixture.store.isRunning)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `zero-byte begin reserves the last object slot before any body write`() {
        val fixture = UploadTransactionFixture(
            maxTotalBytes = 1,
            maxTotalFiles = 1,
            maxOwnerBytes = 1,
            maxOwnerFiles = 1,
        )
        try {
            val transaction = assertIs<BeginFileStoreUploadResult.Started>(
                fixture.store.beginUploadTransaction(
                    OWNER,
                    UUID.randomUUID().toString(),
                    payloadLength = 0,
                    receiptLeaseExpiresAt = Long.MAX_VALUE,
                ),
            ).transaction
            assertEquals(FileStoreUsage(0, 1), fixture.store.accountedOwnerCapacityUsage(OWNER).pending)

            val failure = assertIs<FileStoreCapacityExceededException>(
                runCatching {
                    fixture.store.store(OWNER, "other.bin", MIME, fixture.source("other", 0))
                }.exceptionOrNull(),
            )
            assertEquals(FileStoreCapacityScope.GLOBAL, failure.scope)

            transaction.bindFingerprint(FINGERPRINT_A)
            val path = transaction.storeReserved("empty.bin", MIME, fixture.source("empty", 0))
            transaction.completeAndRelease(RECEIPT)
            assertNotNull(fixture.store.getMeta(path))
            assertEquals(1, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `upload begin and direct store contend for one final file slot`() {
        val fixture = UploadTransactionFixture(
            maxTotalBytes = 1,
            maxTotalFiles = 1,
            maxOwnerBytes = 1,
            maxOwnerFiles = 1,
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val transactions = Collections.synchronizedList(mutableListOf<FileStoreUploadTransaction>())
        val storedPaths = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        var workers = emptyList<Thread>()
        try {
            val uploadId = UUID.randomUUID().toString()
            val directSource = fixture.source("direct-empty", 0)
            workers = listOf(
                thread(isDaemon = true, name = "file-store-final-slot-upload") {
                    ready.countDown()
                    runCatching {
                        start.await()
                        assertIs<BeginFileStoreUploadResult.Started>(
                            fixture.store.beginUploadTransaction(
                                OWNER,
                                uploadId,
                                payloadLength = 0,
                                receiptLeaseExpiresAt = Long.MAX_VALUE,
                            ),
                        ).transaction
                    }.onSuccess(transactions::add).onFailure(failures::add)
                },
                thread(isDaemon = true, name = "file-store-final-slot-direct") {
                    ready.countDown()
                    runCatching {
                        start.await()
                        fixture.store.store(
                            OWNER,
                            "direct-empty.bin",
                            MIME,
                            directSource,
                        )
                    }.onSuccess(storedPaths::add).onFailure(failures::add)
                },
            )
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            workers.forEach { it.join(5_000) }

            assertTrue(workers.none(Thread::isAlive), "final-slot contenders must terminate")
            assertEquals(1, transactions.size + storedPaths.size)
            val capacityFailure = assertIs<FileStoreCapacityExceededException>(failures.single())
            assertEquals(FileStoreCapacityScope.GLOBAL, capacityFailure.scope)
            assertEquals(
                FileStoreUsage(0, 1),
                fixture.store.accountedOwnerCapacityUsage(OWNER).admitted,
            )
            assertEquals(
                1,
                fixture.store.accountedStoredFiles + fixture.store.accountedPendingFiles,
            )

            transactions.forEach(FileStoreUploadTransaction::abort)
            fixture.store.rollbackUnpublished(storedPaths)
            assertEquals(FileStoreUsage.EMPTY, fixture.store.accountedOwnerCapacityUsage(OWNER).admitted)
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            start.countDown()
            workers.forEach { worker ->
                worker.join(5_000)
                if (worker.isAlive) worker.interrupt()
                worker.join(5_000)
            }
            transactions.forEach { transaction -> runCatching { transaction.abort() } }
            runCatching { fixture.store.rollbackUnpublished(storedPaths) }
            fixture.close()
        }
    }

    @Test
    fun `thumbnail reservation failure aborts the already stored main object`() {
        val fixture = UploadTransactionFixture(
            maxTotalBytes = 4,
            maxTotalFiles = 2,
            maxOwnerBytes = 4,
            maxOwnerFiles = 2,
        )
        try {
            val transaction = fixture.store.beginStarted(UUID.randomUUID().toString(), FINGERPRINT_A, 2)
            val main = transaction.storeReserved("main.bin", MIME, fixture.source("main", 2))

            assertIs<FileStoreCapacityExceededException>(
                runCatching { transaction.reserveObject(3) }.exceptionOrNull(),
            )
            assertNull(fixture.store.getDurableMeta(main))
            assertEquals(0, fixture.store.accountedStoredFiles)
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `first delivery lease blocks begin and retirement across expiry until close`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_100,
            )
            val path = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            val completion = transaction.complete(RECEIPT)
            assertEquals(RECEIPT, completion.receipt.encodedReceipt)

            // 模拟持久完成之后、首个 200 响应结束之前的时间流逝。
            now = 1_101L
            assertIs<FileStoreUploadInProgressException>(
                runCatching {
                    fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_200)
                }.exceptionOrNull(),
            )
            assertNotNull(fixture.store.uploadReceiptLease(path, now))
            assertTrue(fixture.store.scanRetirementCandidates(now, null, 10).candidates.isEmpty())

            completion.deliveryLease.close()
            completion.deliveryLease.close()
            assertNull(fixture.store.uploadReceiptLease(path, now))
            val retirement = fixture.store.scanRetirementCandidates(now, null, 10).candidates.single()
            assertTrue(fixture.store.retireIfExpiredAndUnchanged(retirement, now))
            assertNull(fixture.store.getMeta(path))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `stale first delivery close after restart cannot release the current replay pin`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 2_000,
            )
            transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            val staleCompletion = transaction.complete(RECEIPT)

            fixture.store.close()
            fixture.store.init()
            val current = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 2_000),
            ).candidate

            staleCompletion.deliveryLease.close()
            staleCompletion.deliveryLease.close()
            assertIs<FileStoreUploadInProgressException>(
                runCatching {
                    fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 2_000)
                }.exceptionOrNull(),
            )
            assertEquals(RECEIPT, current.requireSameFingerprint(FINGERPRINT_A).encodedReceipt)
            current.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `replay crossing its lease returns typed expiry while concurrent begin and retirement wait`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_100,
            )
            val path = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            transaction.completeAndRelease(RECEIPT)
            now = 1_050L
            val candidate = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_100),
            ).candidate

            now = 1_101L
            var competingFailure: Throwable? = null
            val competitor = thread(isDaemon = true, name = "upload-replay-competitor") {
                competingFailure = runCatching {
                    fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_200)
                }.exceptionOrNull()
            }
            competitor.join(5_000)
            assertFalse(competitor.isAlive)
            assertIs<FileStoreUploadInProgressException>(competingFailure)
            assertNotNull(fixture.store.uploadReceiptLease(path, now))
            assertTrue(fixture.store.scanRetirementCandidates(now, null, 10).candidates.isEmpty())

            val expired = assertIs<FileStoreUploadExpiredException>(
                runCatching {
                    candidate.requireSameFingerprint(FINGERPRINT_A)
                }.exceptionOrNull(),
            )
            assertEquals(1_100L, expired.receiptLeaseExpiresAt)
            assertTrue(fixture.store.scanRetirementCandidates(now, null, 10).candidates.isEmpty())

            candidate.close()
            candidate.close()
            assertNull(fixture.store.uploadReceiptLease(path, now))
            val retirement = fixture.store.scanRetirementCandidates(now, null, 10).candidates.single()
            assertTrue(fixture.store.retireIfExpiredAndUnchanged(retirement, now))
            assertNull(fixture.store.getMeta(path))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resolved replay stays pinned through the response delivery window`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_100,
            )
            val path = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            val completed = transaction.completeAndRelease(RECEIPT)
            now = 1_100L
            val candidate = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_100),
            ).candidate
            assertEquals(completed, candidate.requireSameFingerprint(FINGERPRINT_A))

            // 模拟重新校验之后、respondText 完成之前的时间流逝。
            now = 1_101L
            assertTrue(fixture.store.scanRetirementCandidates(now, null, 10).candidates.isEmpty())
            assertIs<FileStoreUploadInProgressException>(
                runCatching {
                    fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_200)
                }.exceptionOrNull(),
            )

            candidate.close()
            val retirement = fixture.store.scanRetirementCandidates(now, null, 10).candidates.single()
            assertTrue(fixture.store.retireIfExpiredAndUnchanged(retirement, now))
            assertNull(fixture.store.getMeta(path))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `stale replay close after same-instance restart cannot release a new attempt`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(clock = { now })
        val uploadId = UUID.randomUUID().toString()
        try {
            val completed = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_100,
            )
            completed.storeReserved("main.bin", MIME, fixture.source("main", 1))
            completed.completeAndRelease(RECEIPT)
            val stale = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_100),
            ).candidate

            fixture.store.close()
            fixture.store.init()
            val current = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_100),
            ).candidate
            assertIs<FileStoreUploadStaleAttemptException>(
                runCatching {
                    stale.requireSameFingerprint(FINGERPRINT_A)
                }.exceptionOrNull(),
            )
            stale.close()
            assertIs<FileStoreUploadInProgressException>(
                runCatching {
                    fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_100)
                }.exceptionOrNull(),
            )
            assertEquals(RECEIPT, current.requireSameFingerprint(FINGERPRINT_A).encodedReceipt)
            current.close()

            now = 1_101L
            val replacement = assertIs<BeginFileStoreUploadResult.Started>(
                fixture.store.beginUploadTransaction(OWNER, uploadId, 1, 1_200),
            ).transaction
            assertEquals(1, fixture.store.accountedPendingFiles)

            stale.close()
            stale.close()
            assertEquals(1, fixture.store.accountedPendingFiles)
            replacement.abort()
            assertEquals(0, fixture.store.accountedPendingFiles)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `receipt lease blocks retirement then detaches and permits a fresh identity`() {
        var now = 1_000L
        val fixture = UploadTransactionFixture(
            clock = { now },
        )
        val uploadId = UUID.randomUUID().toString()
        try {
            val transaction = fixture.store.beginStarted(
                uploadId,
                FINGERPRINT_A,
                payloadLength = 1,
                receiptLeaseExpiresAt = 1_100,
            )
            val path = transaction.storeReserved("main.bin", MIME, fixture.source("main", 1))
            val receipt = transaction.completeAndRelease(RECEIPT)
            assertEquals(1_100L, receipt.receiptLeaseExpiresAt)
            assertNotNull(fixture.store.uploadReceiptLease(path, now))
            assertTrue(fixture.store.scanRetirementCandidates(now, null, 10).candidates.isEmpty())

            now = 1_100L
            fixture.reopen()
            val boundaryCandidate = assertIs<BeginFileStoreUploadResult.ReplayCandidate>(
                fixture.store.beginUploadTransaction(
                    OWNER,
                    uploadId,
                    payloadLength = 1,
                    receiptLeaseExpiresAt = 1_200,
                ),
            ).candidate
            val boundaryReplay = boundaryCandidate.use {
                it.requireSameFingerprint(FINGERPRINT_A)
            }
            assertEquals(receipt, boundaryReplay)
            assertNotNull(fixture.store.uploadReceiptLease(path, now))
            assertTrue(fixture.store.scanRetirementCandidates(now, null, 10).candidates.isEmpty())

            now = 1_101L
            val candidate = fixture.store.scanRetirementCandidates(now, null, 10).candidates.single()
            assertTrue(fixture.store.retireIfExpiredAndUnchanged(candidate, now))
            assertNull(fixture.store.getMeta(path))

            val fresh = fixture.store.beginStarted(uploadId, FINGERPRINT_A, 1)
            fresh.abort()
        } finally {
            fixture.close()
        }
    }

    private companion object {
        const val OWNER = "upload-owner"
        const val MIME = "application/octet-stream"
        const val FINGERPRINT_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val FINGERPRINT_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val RECEIPT = "{\"ok\":true,\"descriptor\":\"opaque\"}"
    }
}

private fun FileStoreUploadTransaction.completeAndRelease(
    encodedReceipt: String,
): FileStoreUploadReceipt {
    val completion = complete(encodedReceipt)
    return completion.deliveryLease.use { completion.receipt }
}

private fun FileStore.beginStarted(
    uploadId: String,
    fingerprint: String,
    payloadLength: Long,
    receiptLeaseExpiresAt: Long = Long.MAX_VALUE,
): FileStoreUploadTransaction = assertIs<BeginFileStoreUploadResult.Started>(
    beginUploadTransaction("upload-owner", uploadId, payloadLength, receiptLeaseExpiresAt),
).transaction.also { transaction -> transaction.bindFingerprint(fingerprint) }

private class PostDurableUploadFailure(message: String) : RuntimeException(message)

private class UploadTransactionFaultInjector(
    private val point: FileStoreMutationPoint,
    private val failure: Throwable,
) : FileStoreMutationFaultInjector {
    override fun before(point: FileStoreMutationPoint, metadata: FileMetadata) {
        if (point == this.point) throw failure
    }
}

private class UploadTransactionFixture(
    private val largeFileThreshold: Long = 8,
    private val maxFileSize: Long = 16,
    private val maxTotalBytes: Long = 32,
    private val maxTotalFiles: Int = 8,
    private val maxOwnerBytes: Long = 32,
    private val maxOwnerFiles: Int = 8,
    private val faults: FileStoreMutationFaultInjector = FileStoreMutationFaultInjector { _, _ -> },
    private val beforeUploadAttemptDiscovery: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val root = Files.createTempDirectory("tk-file-upload-transaction-").toFile()
    var store: FileStore = createStore()
        private set

    init {
        store.init()
    }

    fun source(name: String, size: Int): File = File(root, "$name-${UUID.randomUUID()}.bin").apply {
        writeBytes(ByteArray(size) { index -> index.toByte() })
    }

    fun reopen() {
        store.close()
        store = createStore().also(FileStore::init)
    }

    fun restartSameInstance() {
        store.close()
        store.init()
    }

    override fun close() {
        var failure: Throwable? = null
        runCatching { if (store.isRunning) store.close() }.onFailure { failure = it }
        runCatching {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete upload transaction test root: $root"
            }
        }.onFailure { cleanupFailure ->
            val first = failure
            if (first == null) failure = cleanupFailure else first.addSuppressed(cleanupFailure)
        }
        failure?.let { throw it }
    }

    private fun createStore(): FileStore = FileStore(
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
        beforeUploadAttemptDiscovery = beforeUploadAttemptDiscovery,
        clock = clock,
    )
}
