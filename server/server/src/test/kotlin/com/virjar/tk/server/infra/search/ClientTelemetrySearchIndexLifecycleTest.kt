package com.virjar.tk.server.infra.search

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryIngestStatus
import com.virjar.tk.server.domain.telemetry.TelemetryNumericRange
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueMetrics
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueQuery
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_MESSAGE
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_SEARCH_TEXT
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTelemetrySearchIndexLifecycleTest {
    @Test
    fun `numeric protocol ids above the former byte range survive Lucene reopen`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-protocol-id-").toFile()
        val versions = listOf(256, 1 shl 16, Int.MAX_VALUE)
        try {
            val store = ClientTelemetrySearchIndex(root)
            try {
                assertTrue(store.start())
                versions.forEachIndexed { index, version ->
                    val batch = lifecycleBatch(index + 1L).let {
                        it.copy(runtime = it.runtime.copy(protocolVersion = version))
                    }
                    assertEquals(
                        TelemetryIngestStatus.ACCEPTED,
                        store.ingest("protocol-uid", "protocol-device", batch, index + 1L, 1_024).status,
                    )
                }
                val invalid = lifecycleBatch(4L).let { it.copy(runtime = it.runtime.copy(protocolVersion = -1)) }
                assertFailsWith<IllegalArgumentException> {
                    store.ingest("protocol-uid", "protocol-device", invalid, 4L, 1_024)
                }
            } finally {
                store.close()
            }
            val reopened = ClientTelemetrySearchIndex(root)
            try {
                assertTrue(reopened.start())
                val result = reopened.search(
                    TelemetrySearchQuery(uid = "protocol-uid", receivedAtFrom = 1L, receivedAtUntil = 3L),
                    0,
                    10,
                )
                assertEquals(versions, result.hits.map { it.event.runtime.protocolVersion }.sorted())
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `outgoing queue metrics round trip and every numeric field filters in Lucene`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-outgoing-query-").toFile()
        val store = ClientTelemetrySearchIndex(root)
        val uid = "outgoing-query-uid"
        val deviceId = "outgoing-query-device"
        val receivedAt = 1_000L
        val expected = TelemetryOutgoingQueueMetrics(
            pendingCount = 2,
            retryWaitCount = 1,
            terminalFailedCount = 4,
            oldestActiveAgeMillis = 45_000L,
            maxAttemptCount = 7L,
        )
        try {
            assertTrue(store.start())
            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest(
                    uid,
                    deviceId,
                    outgoingQueueBatch(sequence = 1L, metrics = expected),
                    receivedAt,
                    1_024,
                ).status,
            )
            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest(
                    uid,
                    deviceId,
                    outgoingQueueBatch(
                        sequence = 2L,
                        metrics = TelemetryOutgoingQueueMetrics(0, 0, 1, 0L, 3L),
                    ),
                    receivedAt + 1L,
                    1_024,
                ).status,
            )

            val result = store.search(
                TelemetrySearchQuery(
                    uid = uid,
                    deviceId = deviceId,
                    receivedAtFrom = receivedAt,
                    receivedAtUntil = receivedAt + 1L,
                    outgoingQueue = TelemetryOutgoingQueueQuery(
                        pendingCount = TelemetryNumericRange(2L, 2L),
                        retryWaitCount = TelemetryNumericRange(1L, 1L),
                        terminalFailedCount = TelemetryNumericRange(4L, 4L),
                        oldestActiveAgeMillis = TelemetryNumericRange(45_000L, 45_000L),
                        maxAttemptCount = TelemetryNumericRange(7L, 7L),
                    ),
                ),
                offset = 0,
                limit = 10,
            )

            assertEquals(1L, result.total)
            val stored = result.hits.single().event.event
            assertEquals(TelemetryEventKind.OUTGOING_QUEUE.name, stored.category)
            assertEquals(TELEMETRY_OUTGOING_QUEUE_EVENT_NAME, stored.eventName)
            assertEquals(OUTGOING_QUEUE_STORED_MESSAGE, stored.message)
            assertEquals(expected, stored.outgoingQueue)

            store.close()
            val reopened = ClientTelemetrySearchIndex(root)
            try {
                assertTrue(reopened.start(), "typed telemetry fields must survive startup validation")
                val reopenedResult = reopened.search(
                    TelemetrySearchQuery(
                        uid = uid,
                        receivedAtFrom = receivedAt,
                        receivedAtUntil = receivedAt + 1L,
                        outgoingQueue = TelemetryOutgoingQueueQuery(
                            pendingCount = TelemetryNumericRange(2L, 2L),
                        ),
                    ),
                    0,
                    10,
                )
                assertEquals(expected, reopenedResult.hits.single().event.event.outgoingQueue)
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `outgoing queue draft and query enforce independent bounded numeric semantics`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-outgoing-boundaries-").toFile()
        val store = ClientTelemetrySearchIndex(root)
        try {
            assertTrue(store.start())

            // 重试等待与待处理互斥，因此仅重试的行也是有效的活动队列。
            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest(
                    "outgoing-boundary-uid",
                    "outgoing-boundary-device",
                    outgoingQueueBatch(1L, TelemetryOutgoingQueueMetrics(0, 1, 0, 1L, 2L)),
                    1_000L,
                    1_024,
                ).status,
            )
            // maxAttemptCount 覆盖每个非成功行，包括仅终态失败的行。
            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest(
                    "outgoing-boundary-uid",
                    "outgoing-boundary-device",
                    outgoingQueueBatch(2L, TelemetryOutgoingQueueMetrics(0, 0, 1, 0L, 2L)),
                    1_001L,
                    1_024,
                ).status,
            )

            assertFailsWith<IllegalArgumentException> {
                store.ingest(
                    "outgoing-boundary-uid",
                    "outgoing-boundary-device",
                    outgoingQueueBatch(3L, TelemetryOutgoingQueueMetrics(0, 0, 0, 1L, 0L)),
                    1_002L,
                    1_024,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                store.ingest(
                    "outgoing-boundary-uid",
                    "outgoing-boundary-device",
                    outgoingQueueBatch(4L, TelemetryOutgoingQueueMetrics(0, 0, 0, 0L, 1L)),
                    1_003L,
                    1_024,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                store.ingest(
                    "outgoing-boundary-uid",
                    "outgoing-boundary-device",
                    outgoingQueueBatch(
                        5L,
                        TelemetryOutgoingQueueMetrics(
                            ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT,
                            1,
                            0,
                            1L,
                            1L,
                        ),
                    ),
                    1_004L,
                    1_024,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                store.ingest(
                    "outgoing-boundary-uid",
                    "outgoing-boundary-device",
                    outgoingQueueBatch(6L, TelemetryOutgoingQueueMetrics(1, 0, 0, 1L, 1L)).copy(
                        events = listOf(
                            outgoingQueueEvent(6L, TelemetryOutgoingQueueMetrics(1, 0, 0, 1L, 1L)).copy(
                                message = "/private/chat/message-body",
                            ),
                        ),
                    ),
                    1_005L,
                    1_024,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                store.search(
                    TelemetrySearchQuery(
                        receivedAtFrom = 0L,
                        receivedAtUntil = Long.MAX_VALUE,
                        outgoingQueue = TelemetryOutgoingQueueQuery(
                            maxAttemptCount = TelemetryNumericRange(
                                maxInclusive = ClientTelemetryLimits.MAX_OUTGOING_ATTEMPT_COUNT + 1L,
                            ),
                        ),
                    ),
                    0,
                    10,
                )
            }
            assertTrue(store.isAvailable(), "invalid caller filters must not terminalize Lucene")
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `initial open failure can retry after the local path is repaired`() {
        val parent = Files.createTempDirectory("teamtalk-telemetry-start-retry-").toFile()
        val root = parent.resolve("index")
        root.writeText("temporarily blocks the index directory")
        val store = ClientTelemetrySearchIndex(root)
        try {
            assertFalse(store.start())
            assertTrue(root.delete())
            assertTrue(root.mkdirs())
            assertTrue(store.start())
            assertTrue(store.isAvailable())
        } finally {
            store.close()
            parent.deleteRecursively()
        }
    }

    @Test
    fun `authenticated device id accepts the full one hundred character boundary`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-device-boundary-").toFile()
        val store = ClientTelemetrySearchIndex(root)
        try {
            assertTrue(store.start())
            val deviceId = "d".repeat(AuthRules.DEVICE_ID_MAX_LENGTH)
            val batch = lifecycleBatch(1L)

            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest("device-boundary-uid", deviceId, batch, 100L, 1_024).status,
            )
            assertNotNull(store.findBatchReceipt("device-boundary-uid", deviceId, batch.batchId))
            assertFailsWith<IllegalArgumentException> {
                store.ingest(
                    "device-boundary-uid",
                    "d".repeat(AuthRules.DEVICE_ID_MAX_LENGTH + 1),
                    lifecycleBatch(2L),
                    101L,
                    1_024,
                )
            }
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `current generation read failure makes queued writes fail before commit`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-read-terminal-").toFile()
        val store = ClientTelemetrySearchIndex(root, groupCommitDelayMillis = 200L)
        val uid = "read-terminal-uid"
        val deviceId = "read-terminal-device"
        val batch = lifecycleBatch(1L)
        try {
            assertTrue(store.start())
            supervisorScope {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    store.ingest(uid, deviceId, batch, 100L, 1_024)
                }
                delay(25L)
                store.searchLeaseHookForTest = { throw IOException("private read failure detail") }

                val readFailure = assertFailsWith<TelemetrySearchUnavailableException> {
                    store.search(
                        TelemetrySearchQuery(
                            uid = uid,
                            receivedAtFrom = 0L,
                            receivedAtUntil = Long.MAX_VALUE,
                        ),
                        0,
                        20,
                    )
                }
                assertEquals("client telemetry event store is unavailable", readFailure.message)
                assertFalse(store.isAvailable())
                assertFailsWith<TelemetrySearchUnavailableException> { pending.await() }
                assertFalse(store.start(), "a terminal runtime cannot reuse its closed channels")
            }
        } finally {
            store.searchLeaseHookForTest = null
            runCatching { store.close() }
        }

        val reopened = ClientTelemetrySearchIndex(root)
        try {
            assertTrue(reopened.start())
            assertNull(reopened.findBatchReceipt(uid, deviceId, batch.batchId))
        } finally {
            reopened.close()
            root.deleteRecursively()
        }
    }
}

private fun outgoingQueueBatch(
    sequence: Long,
    metrics: TelemetryOutgoingQueueMetrics,
): TelemetryBatchDraft {
    val identity = UUID.randomUUID().toString()
    return TelemetryBatchDraft(
        batchId = "outgoing-batch-$identity",
        payloadSha256 = "1".repeat(64),
        createdAt = 1L,
        runtime = lifecycleRuntime(),
        events = listOf(outgoingQueueEvent(sequence, metrics, identity)),
    )
}

private fun outgoingQueueEvent(
    sequence: Long,
    metrics: TelemetryOutgoingQueueMetrics,
    identity: String = UUID.randomUUID().toString(),
) = TelemetryEventDraft(
    eventId = "outgoing-event-$identity",
    runId = "outgoing-run-$identity",
    sequence = sequence,
    occurredAt = 1L,
    category = TelemetryEventKind.OUTGOING_QUEUE.name,
    eventName = TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
    message = OUTGOING_QUEUE_STORED_MESSAGE,
    searchText = OUTGOING_QUEUE_STORED_SEARCH_TEXT,
    outgoingQueue = metrics,
)

private fun lifecycleRuntime() = TelemetryRuntimeSnapshot(
    platform = "DESKTOP",
    osName = "macOS",
    osVersion = "15",
    architecture = "arm64",
    deviceModel = "test-device",
    appVersion = "1.0.0",
    buildNumber = "1",
    gitCommit = "0123456789ab",
    buildIdentity = "1.0.0+0123456789abcdef0123456789abcdef01234567",
    buildTime = "2026-08-27 12:00",
    protocolVersion = 1,
    distribution = "compose-desktop",
)

private fun lifecycleBatch(sequence: Long): TelemetryBatchDraft {
    val identity = UUID.randomUUID().toString()
    return TelemetryBatchDraft(
        batchId = "batch-$identity",
        payloadSha256 = "0".repeat(64),
        createdAt = 1L,
        runtime = lifecycleRuntime(),
        events = listOf(
            TelemetryEventDraft(
                eventId = "event-$identity",
                runId = "run-$identity",
                sequence = sequence,
                occurredAt = 1L,
                category = "FAULT",
                eventName = "fault.reported",
                message = "test fault",
                searchText = "fault.reported test fault",
            ),
        ),
    )
}
