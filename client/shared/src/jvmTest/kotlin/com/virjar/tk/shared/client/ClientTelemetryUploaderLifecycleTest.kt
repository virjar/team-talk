package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.ClientPlatform
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.TelemetryAck
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import com.virjar.tk.protocol.telemetry.TelemetryEvent
import com.virjar.tk.protocol.telemetry.TelemetryFaultPayload
import com.virjar.tk.protocol.telemetry.TelemetryPolicy
import com.virjar.tk.protocol.telemetry.TelemetryPolicyMode
import com.virjar.tk.protocol.telemetry.TelemetryUploadResponse
import com.virjar.tk.shared.log.LogBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientTelemetryUploaderLifecycleTest {
    @Test
    fun `platform IO worker is bounded single threaded and joined on close`() {
        val worker = createPlatformTelemetryHttpIoWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicInteger()
        val threads = CopyOnWriteArraySet<Thread>()
        try {
            assertTrue(worker.execute {
                threads += Thread.currentThread()
                executed.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(8) {
                assertTrue(worker.execute {
                    threads += Thread.currentThread()
                    executed.incrementAndGet()
                })
            }
            assertFalse(worker.execute { error("bounded queue admitted a ninth waiter") })

            release.countDown()
            worker.closeAndDrain()

            assertEquals(9, executed.get())
            assertEquals(1, threads.size)
            assertFalse(threads.single().isAlive)
            assertFalse(worker.execute { error("retired worker admitted work") })
        } finally {
            release.countDown()
            runCatching(worker::closeAndDrain)
        }
    }

    @Test
    fun `escaped worker fatal stops admission and is replayed unchanged`() {
        val worker = createPlatformTelemetryHttpIoWorker()
        val fatal = AssertionError("telemetry worker corrupted")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownedThread = AtomicReference<Thread?>()
        try {
            assertTrue(worker.execute {
                ownedThread.set(Thread.currentThread())
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                throw fatal
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            checkNotNull(ownedThread.get()).join(5_000)

            assertFalse(checkNotNull(ownedThread.get()).isAlive)
            assertFalse(worker.execute { error("failed worker admitted work") })
            assertSame(fatal, assertFailsWith<AssertionError> { worker.closeAndDrain() })
        } finally {
            release.countDown()
            runCatching(worker::closeAndDrain)
        }
    }

    @Test
    fun `stop durably flushes the final memory batch for restart`() = withUploaderDirectory { dataDir ->
        val store = TestTelemetrySegmentStore()
        val fixture = uploaderFixture(dataDir, store, NoopTelemetryTransport())
        assertTrue(fixture.recorder.recordFault("cache.mark_read_failed", reasonCode = "sqlite.write_failed"))
        assertTrue(store.fileNames().isEmpty(), "record path must remain memory-only")

        fixture.uploader.stop()

        val restartedSpool = ClientTelemetrySpool(store)
        assertEquals(1, restartedSpool.retainedBatchIds().size)
        assertFailsWith<IllegalStateException> { fixture.uploader.retryPending() }
        assertFailsWith<IllegalStateException> {
            fixture.uploader.refreshPolicyForConnectionTraceChange()
        }
    }

    @Test
    fun `full durable spool is drained before retrying the stable memory batch`() =
        withUploaderDirectory { dataDir ->
            val store = TestTelemetrySegmentStore()
            val spool = ClientTelemetrySpool(store, maxFiles = 1, maxBytes = 1024 * 1024)
            var oldId = 0
            val oldRecorder = ClientTelemetryRecorder(
                runtimeInfo = testTelemetryRuntimeInfo(),
                spool = spool,
                clock = System::currentTimeMillis,
                newId = { "old-${++oldId}" },
            )
            assertTrue(oldRecorder.recordFault("old.retained"))
            assertTrue(oldRecorder.flush())

            val transport = AcknowledgingTelemetryTransport()
            val fixture = uploaderFixture(dataDir, store, transport, spool = spool)
            try {
                assertTrue(fixture.recorder.recordFault("new.memory"))
                runBlocking { fixture.uploader.retryPending().join() }

                assertEquals(
                    listOf("old.retained", "new.memory"),
                    transport.batches.flatMap { batch -> batch.events.map { it.eventName } },
                )
                assertTrue(store.fileNames().isEmpty())
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `policy rejected oldest is dropped exactly and next baseline batch uploads in same cycle`() =
        withUploaderDirectory { dataDir ->
            val now = System.currentTimeMillis()
            val oldNow = now - 10_000L
            val store = TestTelemetrySegmentStore()
            val spool = ClientTelemetrySpool(store)
            var oldId = 0
            val oldRecorder = ClientTelemetryRecorder(
                runtimeInfo = testTelemetryRuntimeInfo(),
                spool = spool,
                policyState = ClientTelemetryPolicyState(
                    TelemetryPolicy.baseline().copy(
                        revision = "expired-diagnostic",
                        mode = TelemetryPolicyMode.DIAGNOSTIC,
                        issuedAtEpochMs = oldNow - 1_000L,
                        expiresAtEpochMs = oldNow + 5_000L,
                    ),
                ),
                clock = { oldNow },
                newId = { "old-${++oldId}" },
            )
            assertTrue(oldRecorder.recordOutgoingQueue(1, 0, 0, 10L, 1L))
            assertTrue(
                oldRecorder.recordFault(
                    code = "mark_read_local_failure",
                    page = "chat",
                    action = "mark_read",
                    origin = "system",
                    reasonCode = "sqlite",
                ),
            )
            assertTrue(oldRecorder.flush())
            assertEquals(2, spool.retainedBatchIds().size)

            val transport = RejectFirstPolicyBatchTelemetryTransport()
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = store,
                transport = transport,
                spool = spool,
            )
            try {
                runBlocking { fixture.uploader.retryPending().join() }

                assertEquals(
                    listOf(
                        listOf(com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME),
                        listOf("mark_read_local_failure"),
                    ),
                    transport.batches.map { batch -> batch.events.map { it.eventName } },
                )
                assertEquals(
                    listOf(listOf(0L), listOf(1L)),
                    transport.batches.map { it.events.map(TelemetryEvent::sequence) },
                )
                assertEquals(
                    1,
                    transport.batches.flatMap { it.events }.map(TelemetryEvent::runId).distinct().size,
                )
                assertEquals(2, transport.batches.map(TelemetryBatch::batchId).distinct().size)
                assertTrue(store.fileNames().isEmpty())
                assertEquals(1L, fixture.recorder.stats().droppedEvents)
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `diagnostic policy observer runs after policy admission and cannot poison ACK`() =
        withUploaderDirectory { dataDir ->
            val now = System.currentTimeMillis()
            val transport = AcknowledgingTelemetryTransport(
                policy = TelemetryPolicy.baseline().copy(
                    revision = "diagnostic-observer-v1",
                    mode = TelemetryPolicyMode.DIAGNOSTIC,
                    issuedAtEpochMs = now - 1_000L,
                    expiresAtEpochMs = now + 60_000L,
                ),
            )
            val store = TestTelemetrySegmentStore()
            val callbackAdmitted = AtomicReference<Boolean?>()
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = store,
                transport = transport,
                onPolicyApplied = { recorder ->
                    callbackAdmitted.set(
                        recorder.recordOutgoingQueue(
                            pendingCount = 1,
                            retryWaitCount = 0,
                            terminalFailedCount = 0,
                            oldestActiveAgeMillis = 10L,
                            maxAttemptCount = 1L,
                        ),
                    )
                    error("observer failure must remain diagnostic-only")
                },
            )
            try {
                assertTrue(fixture.recorder.recordFault("policy.observer"))
                runBlocking { fixture.uploader.retryPending().join() }

                assertEquals(true, callbackAdmitted.get())
                assertTrue(store.fileNames().isEmpty(), "observer failure must not block durable ACK")
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `policy observer runs once per accepted generation and ignores identical or stale policy`() =
        withUploaderDirectory { dataDir ->
            val now = System.currentTimeMillis()
            val diagnosticOne = TelemetryPolicy.baseline().copy(
                revision = "diagnostic-generation-one",
                mode = TelemetryPolicyMode.DIAGNOSTIC,
                issuedAtEpochMs = now - 1_000L,
                expiresAtEpochMs = now + 60_000L,
            )
            val transport = AcknowledgingTelemetryTransport(diagnosticOne)
            val callbacks = AtomicInteger()
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = TestTelemetrySegmentStore(),
                transport = transport,
                onPolicyApplied = { callbacks.incrementAndGet() },
            )
            try {
                runBlocking { fixture.uploader.retryPending().join() }
                assertEquals(1, callbacks.get())

                transport.policy = diagnosticOne
                runBlocking { fixture.uploader.retryPending().join() }
                assertEquals(1, callbacks.get(), "identical policy must not retrigger the observer")

                transport.policy = diagnosticOne.copy(
                    revision = "diagnostic-stale",
                    issuedAtEpochMs = diagnosticOne.issuedAtEpochMs - 1L,
                )
                runBlocking { fixture.uploader.retryPending().join() }
                assertEquals(1, callbacks.get(), "stale policy must not retrigger the observer")

                transport.policy = diagnosticOne.copy(
                    revision = "diagnostic-generation-two",
                    issuedAtEpochMs = now,
                )
                runBlocking { fixture.uploader.retryPending().join() }
                assertEquals(2, callbacks.get())
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `live trace enable fetches short diagnostic policy with heartbeat immediately`() =
        withUploaderDirectory { dataDir ->
            val now = System.currentTimeMillis()
            val diagnostic = TelemetryPolicy.baseline().copy(
                revision = "live-trace-enable",
                mode = TelemetryPolicyMode.DIAGNOSTIC,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + 60_000L,
                uploadIntervalSeconds = 10,
            )
            val transport = AcknowledgingTelemetryTransport(diagnostic)
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = TestTelemetrySegmentStore(),
                transport = transport,
            )
            try {
                runBlocking { fixture.uploader.refreshPolicyForConnectionTraceChange().join() }

                assertEquals(1, transport.batches.size)
                assertTrue(transport.batches.single().heartbeat)
                assertTrue(transport.batches.single().events.isEmpty())
                assertEquals(
                    TelemetryPolicyMode.DIAGNOSTIC,
                    fixture.recorder.outgoingQueuePolicySnapshot().mode,
                )
                assertTrue(fixture.recorder.recordOutgoingQueue(1, 0, 0, 10L, 1L))
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `live trace disable applies baseline heartbeat before stale diagnostic spool`() =
        withUploaderDirectory { dataDir ->
            val now = System.currentTimeMillis()
            val diagnostic = TelemetryPolicy.baseline().copy(
                revision = "live-trace-diagnostic",
                mode = TelemetryPolicyMode.DIAGNOSTIC,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + 60_000L,
                uploadIntervalSeconds = 10,
            )
            val baseline = TelemetryPolicy.baseline().copy(
                revision = "live-trace-disabled",
                issuedAtEpochMs = now + 1L,
            )
            val store = TestTelemetrySegmentStore()
            val transport = AcknowledgingTelemetryTransport(diagnostic)
            val fixture = uploaderFixture(dataDir, store, transport)
            try {
                runBlocking { fixture.uploader.refreshPolicyForConnectionTraceChange().join() }
                assertEquals(TelemetryPolicyMode.DIAGNOSTIC, fixture.recorder.outgoingQueuePolicySnapshot().mode)
                assertTrue(fixture.recorder.recordOutgoingQueue(1, 0, 0, 10L, 1L))
                assertTrue(fixture.recorder.flush())
                assertEquals(1, store.fileNames().size)

                transport.policy = baseline
                runBlocking { fixture.uploader.refreshPolicyForConnectionTraceChange().join() }

                assertEquals(2, transport.batches.size)
                assertTrue(transport.batches.all(TelemetryBatch::heartbeat))
                assertTrue(transport.batches.all { it.events.isEmpty() })
                assertEquals(1, store.fileNames().size, "policy heartbeat must not touch the stale spool")
                assertEquals(
                    TelemetryPolicyMode.BASELINE,
                    fixture.recorder.outgoingQueuePolicySnapshot().mode,
                )
                assertFalse(
                    fixture.recorder.recordOutgoingQueue(2, 0, 0, 20L, 1L),
                    "the new BASELINE policy must gate the very next event",
                )

                runBlocking { fixture.uploader.retryPending().join() }
                assertEquals(3, transport.batches.size)
                assertTrue(transport.batches[1].heartbeat)
                assertFalse(transport.batches[2].heartbeat)
                assertEquals(1, transport.batches[2].events.size)
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `new diagnostic policy interrupts baseline timer and uploads its queue seed immediately`() =
        withUploaderDirectory { dataDir ->
            val now = System.currentTimeMillis()
            val diagnostic = TelemetryPolicy.baseline().copy(
                revision = "diagnostic-immediate-queue",
                mode = TelemetryPolicyMode.DIAGNOSTIC,
                issuedAtEpochMs = now - 1_000L,
                expiresAtEpochMs = now + 60_000L,
                uploadIntervalSeconds = 30,
            )
            val transport = AcknowledgingTelemetryTransport(diagnostic)
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = TestTelemetrySegmentStore(),
                transport = transport,
                onPolicyApplied = { recorder ->
                    val snapshot = OutgoingQueueSnapshot(1L, 0L, 0L, 25L, 1L)
                    val bridge = OutgoingQueueTelemetryBridge(recorder) { snapshot }
                    assertTrue(bridge.recordIfCurrent(snapshot))
                },
            )
            try {
                fixture.uploader.start()

                assertTrue(
                    transport.secondRequest.await(5, TimeUnit.SECONDS),
                    "diagnostic policy must not remain behind the original five-minute timer",
                )
                assertEquals(2, transport.requestCount.get())
                val outgoingEvents = transport.batches.flatMap(TelemetryBatch::events)
                    .filter { it.kind == com.virjar.tk.protocol.telemetry.TelemetryEventKind.OUTGOING_QUEUE }
                assertEquals(1, outgoingEvents.size)
            } finally {
                fixture.uploader.stop()
            }
        }

    @Test
    fun `stop disconnects blocked request drains worker and never borrows replacement bearer`() =
        withUploaderDirectory { dataDir ->
            val store = TestTelemetrySegmentStore()
            val transport = BlockingTelemetryTransport()
            val credentials = AtomicReference(SessionHttpCredentials("uid-a", "token-old", identityEpoch = 4L))
            val reads = AtomicInteger()
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = store,
                transport = transport,
                credentialsProvider = {
                    reads.incrementAndGet()
                    credentials.get()
                },
            )
            try {
                assertTrue(fixture.recorder.recordFault("transport.blocked"))
                fixture.uploader.retryPending()
                assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
                val workerThread = checkNotNull(transport.operationThread)
                assertTrue(workerThread.name.startsWith("teamtalk-telemetry-http-io-"))
                credentials.set(SessionHttpCredentials("uid-a", "token-new", identityEpoch = 5L))

                fixture.uploader.stop()

                assertTrue(transport.completed.await(5, TimeUnit.SECONDS))
                assertFalse(workerThread.isAlive)
                assertEquals(1, transport.closeCalls.get())
                assertEquals(1, reads.get())
                assertEquals("Bearer token-old", transport.authorization)
                assertFailsWith<IllegalStateException> { fixture.uploader.retryPending() }
            } finally {
                transport.release.countDown()
                runCatching(fixture.uploader::stop)
            }
        }

    @Test
    fun `current exact bearer 401 reaches only its session terminal`() = withUploaderDirectory { dataDir ->
        val rejected = CopyOnWriteArrayList<String>()
        val terminal = CountDownLatch(1)
        val store = TestTelemetrySegmentStore()
        val fixture = uploaderFixture(
            dataDir = dataDir,
            store = store,
            transport = FixedStatusTelemetryTransport(401),
            onAuthExpired = { token ->
                rejected += token
                terminal.countDown()
            },
        )
        try {
            assertTrue(fixture.recorder.recordFault("auth.rejected"))
            fixture.uploader.retryPending()

            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("token-current"), rejected)
        } finally {
            fixture.uploader.stop()
        }
    }

    @Test
    fun `fatal transport close propagates unchanged after terminal fencing`() =
        withUploaderDirectory { dataDir ->
            val fatal = AssertionError("telemetry transport close defect")
            val fixture = uploaderFixture(
                dataDir,
                TestTelemetrySegmentStore(),
                FatalCloseTelemetryTransport(fatal),
            )

            assertSame(fatal, assertFailsWith<AssertionError> { fixture.uploader.stop() })
            assertFailsWith<IllegalStateException> { fixture.uploader.retryPending() }
        }

    @Test
    fun `fatal HTTP operation is retained by worker and replayed unchanged by stop`() =
        withUploaderDirectory { dataDir ->
            val fatal = AssertionError("telemetry HTTP operation defect")
            val transport = FatalPostTelemetryTransport(fatal)
            val fixture = uploaderFixture(dataDir, TestTelemetrySegmentStore(), transport)
            try {
                assertTrue(fixture.recorder.recordFault("transport.fatal"))
                fixture.uploader.retryPending()
                assertTrue(transport.entered.await(5, TimeUnit.SECONDS))

                assertSame(fatal, assertFailsWith<AssertionError> { fixture.uploader.stop() })
                assertSame(fatal, assertFailsWith<AssertionError> { fixture.uploader.stop() })
            } finally {
                runCatching(fixture.uploader::stop)
            }
        }

    @Test
    fun `next session recovers fixed fatal marker into durable structured telemetry`() =
        withUploaderDirectory { dataDir ->
            CrashDumper(dataDir, TEST_TELEMETRY_DEPLOYMENT, TEST_SYNC_DATASET_ID, "uid-a")
                .flushPending(CLIENT_TELEMETRY_FATAL_MARKER)
            val restartedCrashOwner = CrashDumper(
                dataDir,
                TEST_TELEMETRY_DEPLOYMENT,
                TEST_SYNC_DATASET_ID,
                "uid-a",
            )
            val transport = AcknowledgingTelemetryTransport()
            val store = TestTelemetrySegmentStore()
            val fixture = uploaderFixture(
                dataDir = dataDir,
                store = store,
                transport = transport,
                crashDumper = restartedCrashOwner,
            )
            try {
                runBlocking { fixture.uploader.retryPending().join() }

                assertFalse(restartedCrashOwner.hasPending())
                assertTrue(store.fileNames().isEmpty())
                assertEquals(1, transport.batches.size, "event ACK already refreshes policy; no extra heartbeat")
                val durableBatch = transport.batches.first { !it.heartbeat }
                val event = durableBatch.events.single()
                assertEquals("fault.uncaught", event.eventName)
                assertEquals("process.uncaught_exception", assertIs<TelemetryFaultPayload>(event.payload).faultCode)
                assertFalse(transport.encodedRequests.any { CLIENT_TELEMETRY_FATAL_MARKER in it })
            } finally {
                fixture.uploader.stop()
            }
        }
}

private data class TelemetryUploaderFixture(
    val recorder: ClientTelemetryRecorder,
    val uploader: ClientTelemetryUploader,
)

private fun uploaderFixture(
    dataDir: java.io.File,
    store: TestTelemetrySegmentStore,
    transport: PlatformTelemetryHttpTransport,
    credentialsProvider: () -> SessionHttpCredentials = {
        SessionHttpCredentials("uid-a", "token-current", identityEpoch = 4L)
    },
    crashDumper: CrashDumper = CrashDumper(
        dataDir,
        TEST_TELEMETRY_DEPLOYMENT,
        TEST_SYNC_DATASET_ID,
        "uid-a",
    ),
    onAuthExpired: (String) -> Unit = {},
    onPolicyApplied: (ClientTelemetryRecorder) -> Unit = {},
    spool: ClientTelemetrySpool = ClientTelemetrySpool(store),
): TelemetryUploaderFixture {
    var id = 0
    val recorder = ClientTelemetryRecorder(
        runtimeInfo = testTelemetryRuntimeInfo(),
        spool = spool,
        clock = System::currentTimeMillis,
        newId = { "id-${++id}" },
    )
    return TelemetryUploaderFixture(
        recorder = recorder,
        uploader = ClientTelemetryUploader(
            recorder = recorder,
            spool = spool,
            serverUrl = "https://old.example.test",
            ownerUid = "uid-a",
            ownerIdentityEpoch = 4L,
            credentialsProvider = credentialsProvider,
            localDiagnostics = LogBuffer(64),
            emergencyCrashDumper = crashDumper,
            transport = transport,
            onAuthExpired = onAuthExpired,
            onPolicyApplied = { onPolicyApplied(recorder) },
        ),
    )
}

private inline fun withUploaderDirectory(block: (java.io.File) -> Unit) {
    val dataDir = Files.createTempDirectory("teamtalk-telemetry-uploader-").toFile()
    try {
        block(dataDir)
    } finally {
        dataDir.deleteRecursively()
    }
}

private fun testTelemetryRuntimeInfo() = ClientRuntimeInfo(
    platform = ClientPlatform.DESKTOP,
    osName = "test",
    osVersion = "1",
    architecture = "test",
    deviceModel = "test",
    appVersion = "1.0.0",
    buildNumber = "1",
    gitCommit = "0123456789ab",
    buildIdentity = "1.0.0+0123456789abcdef",
    buildTime = "2026-08-27T16:00:00Z",
    protocolVersion = 9,
    distribution = "test",
)

private val TEST_TELEMETRY_DEPLOYMENT = DeploymentIdentity.from(
    tcpHost = "old.example.test",
    tcpPort = 5100,
    serverUrl = "https://old.example.test",
)

private class TestTelemetrySegmentStore : ClientTelemetrySegmentStore {
    private data class Entry(val content: String, val modifiedAt: Long)
    private val entries = linkedMapOf<String, Entry>()
    override val identityDirectories: List<String> = listOf(
        stableTelemetryNamespace("test-deployment"),
        stableTelemetryNamespace("test-dataset"),
        stableTelemetryNamespace("test-owner"),
    )

    @Synchronized
    override fun writeNew(fileName: String, content: String) {
        entries[fileName]?.let { existing ->
            check(existing.content == content) { "immutable collision" }
            return
        }
        entries[fileName] = Entry(content, System.currentTimeMillis())
    }

    @Synchronized
    override fun read(fileName: String): String? = entries[fileName]?.content

    @Synchronized
    override fun list(): List<StoredTelemetrySegmentFile> = entries.map { (name, entry) ->
        StoredTelemetrySegmentFile(name, entry.content.encodeToByteArray().size.toLong(), entry.modifiedAt)
    }

    @Synchronized
    override fun delete(fileName: String): Boolean = entries.remove(fileName) != null

    override fun maintainNamespaces(
        nowEpochMs: Long,
        cutoffEpochMs: Long,
        retentionMillis: Long,
        maxVisitedNodes: Int,
        maxDeletes: Int,
    ): TelemetryNamespaceMaintenanceResult = TelemetryNamespaceMaintenanceResult(
        visitedNodes = 0,
        truncated = false,
        nextMaintenanceEpochMs = saturatingEpochAdd(nowEpochMs, retentionMillis, 0L),
    )

    @Synchronized
    fun fileNames(): List<String> = entries.keys.toList()
}

private open class NoopTelemetryTransport : PlatformTelemetryHttpTransport {
    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse = PlatformTelemetryHttpResponse(503, null)

    override fun close() = Unit
}

private class BlockingTelemetryTransport : PlatformTelemetryHttpTransport {
    val entered = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closeCalls = AtomicInteger()
    @Volatile var operationThread: Thread? = null
    @Volatile var authorization: String? = null

    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse {
        operationThread = Thread.currentThread()
        authorization = headers["Authorization"]
        entered.countDown()
        release.await(5, TimeUnit.SECONDS)
        completed.countDown()
        return PlatformTelemetryHttpResponse(503, null)
    }

    override fun close() {
        closeCalls.incrementAndGet()
        release.countDown()
    }
}

private class FixedStatusTelemetryTransport(
    private val statusCode: Int,
) : NoopTelemetryTransport() {
    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse = PlatformTelemetryHttpResponse(statusCode, null)
}

private class FatalCloseTelemetryTransport(
    private val fatal: AssertionError,
) : NoopTelemetryTransport() {
    override fun close(): Unit = throw fatal
}

private class FatalPostTelemetryTransport(
    private val fatal: AssertionError,
) : NoopTelemetryTransport() {
    val entered = CountDownLatch(1)

    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse {
        entered.countDown()
        throw fatal
    }
}

private class AcknowledgingTelemetryTransport(
    @Volatile var policy: TelemetryPolicy? = null,
) : NoopTelemetryTransport() {
    val batches = CopyOnWriteArrayList<TelemetryBatch>()
    val encodedRequests = CopyOnWriteArrayList<String>()
    val requestCount = AtomicInteger()
    val secondRequest = CountDownLatch(1)

    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse {
        val encoded = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
        val batch = ClientTelemetrySpool.TELEMETRY_JSON.decodeFromString<TelemetryBatch>(encoded)
        encodedRequests += encoded
        batches += batch
        if (requestCount.incrementAndGet() >= 2) secondRequest.countDown()
        return PlatformTelemetryHttpResponse(
            statusCode = 200,
            body = ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(
                TelemetryUploadResponse(
                    ack = TelemetryAck(
                        batchId = batch.batchId,
                        acceptedThroughSequence = batch.events.lastOrNull()?.sequence,
                    ),
                    policy = policy,
                ),
            ),
        )
    }
}

private class RejectFirstPolicyBatchTelemetryTransport : NoopTelemetryTransport() {
    val batches = CopyOnWriteArrayList<TelemetryBatch>()
    private val requestCount = AtomicInteger()

    override fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse {
        val encoded = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
        val batch = ClientTelemetrySpool.TELEMETRY_JSON.decodeFromString<TelemetryBatch>(encoded)
        batches += batch
        if (requestCount.incrementAndGet() == 1) {
            return PlatformTelemetryHttpResponse(statusCode = 403, body = null)
        }
        return PlatformTelemetryHttpResponse(
            statusCode = 200,
            body = ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(
                TelemetryUploadResponse(
                    ack = TelemetryAck(
                        batchId = batch.batchId,
                        acceptedThroughSequence = batch.events.lastOrNull()?.sequence,
                    ),
                ),
            ),
        )
    }
}
