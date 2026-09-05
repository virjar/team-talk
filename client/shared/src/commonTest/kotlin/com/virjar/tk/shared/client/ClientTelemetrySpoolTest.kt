package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.ClientPlatform
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.TelemetryActionOutcome
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import com.virjar.tk.protocol.telemetry.TelemetryEvent
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import com.virjar.tk.protocol.telemetry.TelemetryFaultPayload
import com.virjar.tk.protocol.telemetry.TelemetryLogLevel
import com.virjar.tk.protocol.telemetry.TelemetryLogPayload
import com.virjar.tk.protocol.telemetry.TelemetryNoticeLevel
import com.virjar.tk.protocol.telemetry.TelemetryNoticeOrigin
import com.virjar.tk.protocol.telemetry.TelemetryOutgoingQueuePayload
import com.virjar.tk.protocol.telemetry.TelemetryPolicy
import com.virjar.tk.protocol.telemetry.TelemetryPolicyMode
import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTelemetrySpoolTest {
    @Test
    fun `recorder freezes current unexpired connection context into immutable spool events`() {
        var now = 5_000_000L
        var id = 0
        var current: ConnectionTraceContext? = traceContext("correlation-token-0001", 1L)
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            clock = { now },
            newId = { "id-${++id}" },
            connectionTraceContextProvider = { current },
        )

        assertTrue(recorder.recordFault("fault.old-generation"))
        current = traceContext("correlation-token-0002", 2L)
        assertTrue(recorder.recordFault("fault.new-generation"))
        assertTrue(recorder.flush())

        val oldBatch = checkNotNull(spool.oldest())
        assertEquals("correlation-token-0001", oldBatch.batch.events.single().connectionTraceContext?.correlationId)
        assertEquals(1L, oldBatch.batch.events.single().connectionTraceContext?.connectionGeneration)
        assertTrue(spool.acknowledge(oldBatch.batch.batchId, oldBatch.batch.events.single().sequence))
        val newBatch = checkNotNull(spool.oldest())
        assertEquals("correlation-token-0002", newBatch.batch.events.single().connectionTraceContext?.correlationId)
        assertEquals(2L, newBatch.batch.events.single().connectionTraceContext?.connectionGeneration)

        now = checkNotNull(current).expiresAtEpochMs
        assertTrue(recorder.recordFault("fault.after-expiry"))
        assertTrue(recorder.flush())
        assertTrue(spool.acknowledge(newBatch.batch.batchId, newBatch.batch.events.single().sequence))
        assertNull(checkNotNull(spool.oldest()).batch.events.single().connectionTraceContext)
        assertFalse(
            ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(recorder.heartbeatBatch())
                .contains("connectionTraceContext"),
            "empty heartbeat never carries a connection context",
        )
    }

    @Test
    fun `root retention selects only expired non-current exact namespaces within budget`() {
        val current = telemetryTestIdentity("current")
        val expired = namespace("expired", 100L)
        val boundary = namespace("boundary", 200L)
        val currentCandidate = namespace(current, 1L)
        val invalid = namespace(listOf("not", "a", "namespace"), 1L)
        val duplicate = expired.copy(retentionReferenceEpochMs = 50L)

        val selected = selectExpiredTelemetryNamespaceCleanups(
            currentIdentityDirectories = current,
            scan = StoredTelemetryNamespaceScan(
                namespaces = listOf(boundary, currentCandidate, invalid, expired, duplicate),
                visitedNodes = 12,
                truncated = false,
            ),
            cutoffEpochMs = 200L,
            maxDeletes = 1,
        )

        assertEquals(
            listOf(expired.identityDirectories),
            selected.map { it.snapshot.identityDirectories },
        )
    }

    @Test
    fun `recent marker keeps an active namespace while its expired segments are pruned`() {
        val oldSegment = "telemetry-0000000000100-old.json"
        val candidate = StoredTelemetryNamespace(
            identityDirectories = telemetryTestIdentity("active-other"),
            retentionReferenceEpochMs = 500L,
            directoryStorageIdentity = "active-directory",
            entries = listOf(
                StoredTelemetryNamespaceEntry(
                    fileName = CLIENT_TELEMETRY_MARKER_FILE,
                    byteCount = 1L,
                    lastModifiedEpochMs = 500L,
                    storageIdentity = "active-marker",
                ),
                StoredTelemetryNamespaceEntry(
                    fileName = oldSegment,
                    byteCount = 2L,
                    lastModifiedEpochMs = 100L,
                    storageIdentity = "old-segment",
                ),
            ),
        )

        val cleanup = selectExpiredTelemetryNamespaceCleanups(
            currentIdentityDirectories = telemetryTestIdentity("current"),
            scan = StoredTelemetryNamespaceScan(listOf(candidate), visitedNodes = 1, truncated = false),
            cutoffEpochMs = 200L,
            maxDeletes = 1,
        ).single()

        assertEquals(listOf(oldSegment), cleanup.expiredSegmentFileNames)
        assertFalse(cleanup.deleteWholeNamespace)
        assertEquals(
            1_501L,
            nextTelemetryNamespaceMaintenanceEpochMs(
                currentIdentityDirectories = telemetryTestIdentity("current"),
                scan = StoredTelemetryNamespaceScan(listOf(candidate), visitedNodes = 1, truncated = false),
                cutoffEpochMs = 200L,
                retentionMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `root maintenance rescans at the discovery deadline but retains the exact expiry boundary`() {
        var now = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS
        val store = InMemoryTelemetrySegmentStore { now }
        store.rootNamespaces += namespace("other", now)
        val spool = ClientTelemetrySpool(store, clock = { now })

        spool.retainedBatchIds()
        assertEquals(1, store.rootScanCount)
        now += ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS
        spool.retainedBatchIds()
        assertEquals(2, store.rootScanCount)
        assertEquals(0, store.deletedNamespaceCount, "exact seven-day boundary is still retained")
        now++
        spool.retainedBatchIds()
        assertEquals(3, store.rootScanCount)
        assertEquals(1, store.deletedNamespaceCount)
    }

    @Test
    fun `root maintenance eventually discovers namespace created after an empty scan`() {
        var now = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })

        spool.retainedBatchIds()
        assertEquals(1, store.rootScanCount)
        store.rootNamespaces += namespace("late-expired", 0L)

        now += ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS - 1L
        spool.retainedBatchIds()
        assertEquals(1, store.rootScanCount, "discovery scan remains bounded before its deadline")
        assertEquals(0, store.deletedNamespaceCount)

        now++
        spool.retainedBatchIds()
        assertEquals(2, store.rootScanCount)
        assertEquals(1, store.deletedNamespaceCount)
    }

    @Test
    fun `root maintenance discovery deadline saturates near maximum epoch`() {
        val retention = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS
        var now = Long.MAX_VALUE - retention + 1L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })

        spool.retainedBatchIds()
        assertEquals(1, store.rootScanCount)

        now = Long.MAX_VALUE - 1L
        spool.retainedBatchIds()
        assertEquals(1, store.rootScanCount, "saturating addition must not wrap into an immediate rescan")

        now = Long.MAX_VALUE
        spool.retainedBatchIds()
        assertEquals(2, store.rootScanCount)
    }

    @Test
    fun `paged root maintenance retains the earliest deadline from every page`() {
        var now = 10_000L
        val retention = 1_000L
        val store = InMemoryTelemetrySegmentStore { now }
        store.queuedRootScans.addLast(StoredTelemetryNamespaceScan(
            namespaces = listOf(namespace("early-page", retentionReferenceEpochMs = 9_500L)),
            visitedNodes = 1,
            truncated = true,
        ))
        store.queuedRootScans.addLast(StoredTelemetryNamespaceScan(
            namespaces = listOf(namespace("tail-page", retentionReferenceEpochMs = 9_900L)),
            visitedNodes = 1,
            truncated = false,
        ))
        val spool = ClientTelemetrySpool(store, retentionMillis = retention, clock = { now })

        spool.retainedBatchIds()
        spool.retainedBatchIds()
        assertEquals(2, store.rootScanCount)

        now = 10_500L
        spool.retainedBatchIds()
        assertEquals(2, store.rootScanCount)
        now++
        spool.retainedBatchIds()
        assertEquals(3, store.rootScanCount, "the first page must not be delayed by the tail page")
    }

    @Test
    fun `changed root snapshot is retried on the next IO maintenance pass`() {
        val now = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS + 1L
        val store = InMemoryTelemetrySegmentStore { now }
        store.rootNamespaces += namespace("expired", 0L)
        store.failedNamespaceDeletesRemaining = 1
        val spool = ClientTelemetrySpool(store, clock = { now })

        spool.retainedBatchIds()
        assertEquals(1, store.rootScanCount)
        assertEquals(0, store.deletedNamespaceCount)
        spool.retainedBatchIds()
        assertEquals(2, store.rootScanCount)
        assertEquals(1, store.deletedNamespaceCount)
    }

    @Test
    fun `immutable segment keeps stable batch id and exact ack deletes only that segment`() {
        var now = 10_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val batch = batch("batch-1", now, 4, fault = true)

        assertTrue(spool.append(batch))
        assertTrue(spool.append(batch))
        assertEquals(listOf("batch-1"), spool.retainedBatchIds())
        assertFailsWith<IllegalStateException> {
            spool.append(batch.copy(events = listOf(event(5, fault = true))))
        }
        assertFailsWith<IllegalStateException> { spool.acknowledge("batch-1", 3) }
        assertTrue(spool.acknowledge("batch-1", 4))
        assertTrue(spool.retainedBatchIds().isEmpty())
    }

    @Test
    fun `policy rejection discard requires exact immutable batch id and content`() {
        val now = 15_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val batch = batch("rejected-exact", now, 4, fault = false)
        assertTrue(spool.append(batch))
        val queued = checkNotNull(spool.oldest())

        assertFalse(spool.discardRejectedExact("another-batch", queued.encodedJson))
        assertFalse(spool.discardRejectedExact(batch.batchId, queued.encodedJson + " "))
        assertEquals(listOf(batch.batchId), spool.retainedBatchIds())
        assertEquals(0L, spool.evictedEvents())

        assertTrue(spool.discardRejectedExact(batch.batchId, queued.encodedJson))
        assertTrue(spool.retainedBatchIds().isEmpty())
        assertEquals(batch.events.size.toLong(), spool.evictedEvents())
        assertFalse(spool.discardRejectedExact(batch.batchId, queued.encodedJson))
    }

    @Test
    fun `failed admission never overwrites and priority fault may evict only diagnostic`() {
        var now = 20_000L
        val store = InMemoryTelemetrySegmentStore { now++ }
        val spool = ClientTelemetrySpool(store, maxFiles = 2, maxBytes = 1024 * 1024, clock = { now })
        assertTrue(spool.append(batch("trace-1", now++, 0, fault = false)))
        assertTrue(spool.append(batch("trace-2", now++, 1, fault = false)))
        assertFalse(spool.append(batch("trace-3", now++, 2, fault = false)))
        assertEquals(listOf("trace-1", "trace-2"), spool.retainedBatchIds())

        assertTrue(spool.append(batch("fault-1", now++, 3, fault = true), highPriority = true))
        assertEquals(listOf("trace-2", "fault-1"), spool.retainedBatchIds())
        assertEquals(1, spool.evictedEvents())
    }

    @Test
    fun `seven day retention removes expired immutable segments`() {
        var now = 100_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        assertTrue(spool.append(batch("old", now, 0, fault = true)))
        now += ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS + 1

        assertNull(spool.oldest())
        assertTrue(spool.retainedBatchIds().isEmpty())
    }

    @Test
    fun `record path is memory only and policy boundary preserves trace fault source order`() {
        var now = 1_000_000L
        var id = 0
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val policy = TelemetryPolicy.baseline().copy(
            revision = "diagnostic-1",
            mode = TelemetryPolicyMode.DIAGNOSTIC,
            issuedAtEpochMs = now - 1,
            expiresAtEpochMs = now + 60_000,
        )
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            policyState = ClientTelemetryPolicyState(policy),
            clock = { now++ },
            newId = { "id-${++id}" },
        )

        assertTrue(recorder.recordAppLog(TelemetryLogLevel.TRACE, "First", "trace"))
        assertTrue(recorder.recordAppLog(TelemetryLogLevel.ERROR, "Second", "fault"))
        assertEquals(0, store.writeCount, "record must not perform file IO")
        assertTrue(recorder.flush())
        assertEquals(2, store.writeCount)
        val diagnostic = checkNotNull(spool.oldest()).batch
        assertEquals(listOf(TelemetryEventKind.LOG), diagnostic.events.map { it.kind })
        assertEquals(listOf(0L), diagnostic.events.map { it.sequence })
        assertTrue(spool.acknowledge(diagnostic.batchId, 0L))
        val baseline = checkNotNull(spool.oldest()).batch
        assertEquals(listOf(TelemetryEventKind.FAULT), baseline.events.map { it.kind })
        assertEquals(listOf(1L), baseline.events.map { it.sequence })
    }

    @Test
    fun `diagnostic queue and approved baseline fault seal as separate immutable batches`() {
        var id = 0
        val now = 1_250_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            policyState = ClientTelemetryPolicyState(
                TelemetryPolicy.baseline().copy(
                    revision = "diagnostic-mixed-boundary",
                    mode = TelemetryPolicyMode.DIAGNOSTIC,
                    issuedAtEpochMs = now - 1L,
                    expiresAtEpochMs = now + 60_000L,
                ),
            ),
            clock = { now },
            newId = { "id-${++id}" },
        )

        assertTrue(recorder.recordOutgoingQueue(1, 0, 0, 10L, 1L))
        assertTrue(
            recorder.recordFault(
                code = "mark_read_local_failure",
                page = "chat",
                action = "mark_read",
                origin = "system",
                reasonCode = "sqlite",
            ),
        )
        assertEquals(0, store.writeCount, "record must not perform file IO")
        assertTrue(recorder.flush())

        val diagnostic = checkNotNull(spool.oldest()).batch
        assertEquals(listOf(TELEMETRY_OUTGOING_QUEUE_EVENT_NAME), diagnostic.events.map { it.eventName })
        assertEquals(listOf(0L), diagnostic.events.map { it.sequence })
        assertTrue(spool.acknowledge(diagnostic.batchId, 0L))
        val baseline = checkNotNull(spool.oldest()).batch
        assertEquals(listOf("mark_read_local_failure"), baseline.events.map { it.eventName })
        assertEquals(listOf(1L), baseline.events.map { it.sequence })
        assertTrue(baseline.createdAtEpochMs > diagnostic.createdAtEpochMs)
    }

    @Test
    fun `outgoing queue recorder is diagnostic only and emits numeric typed payload`() {
        var id = 0
        val now = 1_500_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val policyState = ClientTelemetryPolicyState()
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            policyState = policyState,
            clock = { now },
            newId = { "id-${++id}" },
        )

        assertFalse(recorder.recordOutgoingQueue(1, 0, 0, 10, 1))
        policyState.apply(
            TelemetryPolicy.baseline().copy(
                revision = "diagnostic-outgoing",
                mode = TelemetryPolicyMode.DIAGNOSTIC,
                issuedAtEpochMs = now - 1,
                expiresAtEpochMs = now + 60_000,
            ),
        )
        assertTrue(recorder.recordOutgoingQueue(2, 1, 4, 45_000, 7))
        assertEquals(0, store.writeCount)
        assertTrue(recorder.flush())

        val encoded = checkNotNull(spool.oldest()).encodedJson
        val event = ClientTelemetrySpool.TELEMETRY_JSON
            .decodeFromString<TelemetryBatch>(encoded)
            .events.single()
        assertEquals(TelemetryEventKind.OUTGOING_QUEUE, event.kind)
        assertEquals(TELEMETRY_OUTGOING_QUEUE_EVENT_NAME, event.eventName)
        assertEquals(TelemetryOutgoingQueuePayload(2, 1, 4, 45_000, 7), event.payload)
        listOf("chatId", "clientMsgId", "path", "body", "metadata").forEach { forbidden ->
            assertFalse("\"$forbidden\"" in encoded)
        }
    }

    @Test
    fun `outgoing queue bridge preserves disjoint states null age and bounded clamps`() {
        var id = 0
        val now = 1_600_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val policyState = ClientTelemetryPolicyState(
            TelemetryPolicy.baseline().copy(
                revision = "diagnostic-outgoing-bridge",
                mode = TelemetryPolicyMode.DIAGNOSTIC,
                issuedAtEpochMs = now - 1,
                expiresAtEpochMs = now + 60_000,
            ),
        )
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            policyState = policyState,
            clock = { now },
            newId = { "id-${++id}" },
        )

        val snapshots = listOf(
            OutgoingQueueSnapshot(0L, 1L, 0L, 25L, 2L),
            OutgoingQueueSnapshot(0L, 0L, 1L, null, 3L),
            OutgoingQueueSnapshot(
                pendingOrInFlightCount = Long.MAX_VALUE,
                retryWaitCount = Long.MAX_VALUE,
                terminalFailedCount = Long.MAX_VALUE,
                oldestActiveAgeMs = Long.MAX_VALUE,
                maxAttemptCount = Long.MAX_VALUE,
            ),
            OutgoingQueueSnapshot(-1L, -1L, -1L, null, -1L),
        )
        var current = snapshots.first()
        val bridge = OutgoingQueueTelemetryBridge(recorder) { current }
        snapshots.forEach { snapshot ->
            current = snapshot
            assertTrue(bridge.recordIfCurrent(snapshot))
        }
        assertTrue(recorder.flush())

        val payloads = checkNotNull(spool.oldest()).batch.events.map { event ->
            assertEquals(TelemetryEventKind.OUTGOING_QUEUE, event.kind)
            event.payload as TelemetryOutgoingQueuePayload
        }
        assertEquals(TelemetryOutgoingQueuePayload(0, 1, 0, 25L, 2L), payloads[0])
        assertEquals(TelemetryOutgoingQueuePayload(0, 0, 1, 0L, 3L), payloads[1])
        assertEquals(
            TelemetryOutgoingQueuePayload(
                pendingCount = ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_COUNT,
                retryWaitCount = 0,
                terminalFailedCount = ClientTelemetryLimits.MAX_OUTGOING_TERMINAL_FAILED_COUNT,
                oldestActiveAgeMillis = ClientTelemetryLimits.MAX_OUTGOING_ACTIVE_AGE_MILLIS,
                maxAttemptCount = ClientTelemetryLimits.MAX_OUTGOING_ATTEMPT_COUNT,
            ),
            payloads[2],
        )
        assertEquals(TelemetryOutgoingQueuePayload(0, 0, 0, 0L, 0L), payloads[3])
    }

    @Test
    fun `outgoing queue lifecycle bridge deduplicates one policy generation but seeds the next`() {
        var id = 0
        val now = 1_700_000L
        val store = InMemoryTelemetrySegmentStore { now }
        val spool = ClientTelemetrySpool(store, clock = { now })
        val diagnosticOne = TelemetryPolicy.baseline().copy(
            revision = "diagnostic-queue-one",
            mode = TelemetryPolicyMode.DIAGNOSTIC,
            issuedAtEpochMs = now - 100L,
            expiresAtEpochMs = now + 60_000L,
        )
        val policyState = ClientTelemetryPolicyState(diagnosticOne)
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            policyState = policyState,
            clock = { now },
            newId = { "id-${++id}" },
        )
        val empty = OutgoingQueueSnapshot(0L, 0L, 0L, null, 0L)
        val pending = OutgoingQueueSnapshot(1L, 0L, 0L, 25L, 1L)
        var current = empty
        val bridge = OutgoingQueueTelemetryBridge(recorder) { current }

        assertTrue(bridge.recordIfCurrent(empty))
        assertFalse(bridge.recordIfCurrent(empty), "same flow/callback value must be emitted once")
        current = pending
        assertFalse(bridge.recordIfCurrent(empty), "a delayed collector value must not regress telemetry")
        assertTrue(bridge.recordIfCurrent(pending))

        val baseline = TelemetryPolicy.baseline().copy(
            revision = "baseline-between-diagnostics",
            issuedAtEpochMs = now,
        )
        assertTrue(policyState.apply(baseline))
        assertFalse(bridge.recordIfCurrent(pending))
        assertFalse(policyState.apply(baseline), "identical policy is not a new generation")
        assertFalse(
            policyState.apply(diagnosticOne.copy(revision = "stale", issuedAtEpochMs = now - 1L)),
            "older policy is ignored",
        )

        val diagnosticTwo = diagnosticOne.copy(
            revision = "diagnostic-queue-two",
            issuedAtEpochMs = now + 1L,
        )
        assertTrue(policyState.apply(diagnosticTwo))
        assertTrue(
            bridge.recordIfCurrent(pending),
            "a new diagnostic generation receives one immediate snapshot even when values match",
        )
        assertFalse(bridge.recordIfCurrent(pending))
        assertTrue(recorder.flush())

        val payloads = checkNotNull(spool.oldest()).batch.events.map { event ->
            event.payload as TelemetryOutgoingQueuePayload
        }
        assertEquals(
            listOf(
                TelemetryOutgoingQueuePayload(0, 0, 0, 0L, 0L),
                TelemetryOutgoingQueuePayload(1, 0, 0, 25L, 1L),
                TelemetryOutgoingQueuePayload(1, 0, 0, 25L, 1L),
            ),
            payloads,
        )
    }

    @Test
    fun `large throwable is bounded redacted and cannot poison batch`() {
        var id = 0
        val store = InMemoryTelemetrySegmentStore { 2_000_000L }
        val spool = ClientTelemetrySpool(store, clock = { 2_000_000L })
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = spool,
            clock = { 2_000_000L },
            newId = { "id-${++id}" },
        )
        val throwable = IllegalStateException("password=must-not-leak https://secret.invalid/a")
        throwable.stackTrace = Array(48) { index ->
            StackTraceElement(
                "com.example.${"C".repeat(150)}",
                "method${index}${"M".repeat(140)}",
                "/private/user/Secret.kt",
                index + 1,
            )
        }

        assertTrue(
            recorder.recordAppLog(
                TelemetryLogLevel.ERROR,
                "FaultLogger",
                "open https://example.invalid/private at /Users/person/private token=top-secret " +
                    "authorization: Bearer synthetic-credential-value " +
                    "phone 13800138000 email person@example.invalid " +
                    "id 123e4567-e89b-42d3-a456-426614174000 " +
                    "opaque AbCdEfGhIjKlMnOpQrStUvWx12345678",
                throwable,
            ),
        )
        assertEquals(0, store.writeCount)
        assertTrue(recorder.flush())
        val queued = checkNotNull(spool.oldest())
        assertTrue(queued.encodedJson.encodeToByteArray().size < 256 * 1024)
        assertFalse("top-secret" in queued.encodedJson)
        assertFalse("synthetic-credential-value" in queued.encodedJson)
        assertFalse("example.invalid" in queued.encodedJson)
        assertFalse("13800138000" in queued.encodedJson)
        assertFalse("person@example.invalid" in queued.encodedJson)
        assertFalse("123e4567" in queued.encodedJson)
        assertFalse("AbCdEfGh" in queued.encodedJson)
        assertFalse("must-not-leak" in queued.encodedJson, "Throwable.message must never be serialized")
        val decoded = ClientTelemetrySpool.TELEMETRY_JSON.decodeFromString<TelemetryBatch>(queued.encodedJson)
        assertEquals(48, (decoded.events.single().payload as TelemetryFaultPayload).stackFrames.size)
    }

    @Test
    fun `client sanitizer removes invisible separators and conservative structured identity channels`() {
        val sanitized = sanitizeTelemetryText(
            "普通中文诊断文案 成功/失败 com.example.Service.handle pkg/Class " +
                "{\"client_secret\":\"short-synthetic-value\",\"refresh_token\":\"refresh-synthetic-value\"} " +
                "phones 138-0013-8000 and +1 (202) 555-0199 " +
                "paths \\\\synthetic-host\\private-share\\note.txt ../private/note.txt " +
                "/private-note.txt C:\\private-note.txt " +
                "to\u200Bken=zero-width-synthetic-value bidi\u202Etext",
        )

        listOf(
            "short-synthetic-value",
            "refresh-synthetic-value",
            "138-0013-8000",
            "+1 (202) 555-0199",
            "synthetic-host",
            "../private/note.txt",
            "/private-note.txt",
            "C:\\private-note.txt",
            "zero-width-synthetic-value",
        ).forEach { privateMaterial -> assertFalse(sanitized.contains(privateMaterial), privateMaterial) }
        assertFalse('\u200B' in sanitized)
        assertFalse('\u202E' in sanitized)
        assertTrue("普通中文诊断文案" in sanitized)
        assertTrue("成功/失败" in sanitized)
        assertTrue("com.example.Service.handle" in sanitized)
        assertTrue("pkg/Class" in sanitized)
    }

    @Test
    fun `baseline filters trace but admits safe product notice without IO`() {
        var id = 0
        val store = InMemoryTelemetrySegmentStore { 3_000_000L }
        val recorder = ClientTelemetryRecorder(
            runtimeInfo = runtimeInfo(),
            spool = ClientTelemetrySpool(store, clock = { 3_000_000L }),
            clock = { 3_000_000L },
            newId = { "id-${++id}" },
        )
        assertFalse(recorder.recordAppLog(TelemetryLogLevel.TRACE, "Log", "trace"))
        assertTrue(recorder.recordUserNotice(
            feedbackCode = "message.send_failed",
            page = "chat",
            action = "send",
            origin = TelemetryNoticeOrigin.TOAST,
            message = "发送失败",
            level = TelemetryNoticeLevel.ERROR,
        ))
        assertEquals(0, store.writeCount)
        assertEquals(1, recorder.stats().pendingEvents)
    }

    private fun batch(batchId: String, createdAt: Long, sequence: Long, fault: Boolean) = TelemetryBatch(
        batchId = batchId,
        createdAtEpochMs = createdAt,
        runtimeInfo = runtimeInfo(),
        events = listOf(event(sequence, fault)),
    )

    private fun event(sequence: Long, fault: Boolean): TelemetryEvent = TelemetryEvent(
        eventId = "event-$sequence",
        runId = "run-1",
        sequence = sequence,
        occurredAtEpochMs = sequence + 1,
        eventName = if (fault) "fault.test" else "log.trace",
        kind = if (fault) TelemetryEventKind.FAULT else TelemetryEventKind.LOG,
        payload = if (fault) {
            TelemetryFaultPayload("Test", "failed", faultCode = "fault.test")
        } else {
            TelemetryLogPayload(TelemetryLogLevel.TRACE, "Test", "trace")
        },
    )

    private fun runtimeInfo() = ClientRuntimeInfo(
        platform = ClientPlatform.DESKTOP,
        osName = "test",
        osVersion = "1",
        architecture = "test",
        deviceModel = "test",
        appVersion = "1.0.0",
        buildNumber = "1",
        gitCommit = "0123456789ab",
        buildIdentity = "1.0.0+0123456789abcdef",
        buildTime = "2026-08-27 16:00",
        protocolVersion = 9,
        distribution = "test",
    )

    private fun traceContext(correlationId: String, generation: Long) = ConnectionTraceContext(
        correlationId = correlationId,
        traceId = "trace-token-000000001",
        sessionId = "session-token-0000001",
        connectionGeneration = generation,
        policyRevision = 1L,
        expiresAtEpochMs = 5_010_000L,
    )
}

private class InMemoryTelemetrySegmentStore(
    private val clock: () -> Long,
) : ClientTelemetrySegmentStore {
    private data class Entry(val content: String, val modifiedAt: Long)
    private val entries = linkedMapOf<String, Entry>()
    override val identityDirectories: List<String> = telemetryTestIdentity("current")
    val rootNamespaces = mutableListOf<StoredTelemetryNamespace>()
    val queuedRootScans = ArrayDeque<StoredTelemetryNamespaceScan>()
    var writeCount = 0
        private set
    var rootScanCount = 0
        private set
    var deletedNamespaceCount = 0
        private set
    var failedNamespaceDeletesRemaining = 0
    private var cycleDeadlineEpochMs: Long? = null
    private var cycleNeedsImmediateRetry = false

    override fun writeNew(fileName: String, content: String) {
        entries[fileName]?.let { existing ->
            check(existing.content == content) { "immutable collision" }
            return
        }
        entries[fileName] = Entry(content, clock())
        writeCount++
    }

    override fun read(fileName: String): String? = entries[fileName]?.content

    override fun list(): List<StoredTelemetrySegmentFile> = entries.map { (name, entry) ->
        StoredTelemetrySegmentFile(name, entry.content.encodeToByteArray().size.toLong(), entry.modifiedAt)
    }

    override fun delete(fileName: String): Boolean = entries.remove(fileName) != null

    override fun maintainNamespaces(
        nowEpochMs: Long,
        cutoffEpochMs: Long,
        retentionMillis: Long,
        maxVisitedNodes: Int,
        maxDeletes: Int,
    ): TelemetryNamespaceMaintenanceResult {
        rootScanCount++
        val scan = if (queuedRootScans.isNotEmpty()) {
            queuedRootScans.removeFirst()
        } else {
            StoredTelemetryNamespaceScan(
                namespaces = rootNamespaces.toList(),
                visitedNodes = minOf(rootNamespaces.size, maxVisitedNodes),
                truncated = rootNamespaces.size > maxVisitedNodes,
            )
        }
        val cleanups = selectExpiredTelemetryNamespaceCleanups(
            currentIdentityDirectories = identityDirectories,
            scan = scan,
            cutoffEpochMs = cutoffEpochMs,
            maxDeletes = maxDeletes,
        )
        var changedSnapshots = 0
        cleanups.forEach { cleanup ->
            if (failedNamespaceDeletesRemaining > 0) {
                failedNamespaceDeletesRemaining--
                changedSnapshots++
            } else {
                val removed = rootNamespaces.remove(cleanup.snapshot)
                if (removed) deletedNamespaceCount++ else changedSnapshots++
            }
        }
        val discoveryDeadline = saturatingEpochAdd(nowEpochMs, retentionMillis, 0L)
        val pageDeadline = nextTelemetryNamespaceMaintenanceEpochMs(
            currentIdentityDirectories = identityDirectories,
            scan = scan,
            cutoffEpochMs = cutoffEpochMs,
            retentionMillis = retentionMillis,
        )?.coerceAtMost(discoveryDeadline) ?: discoveryDeadline
        cycleDeadlineEpochMs = cycleDeadlineEpochMs?.coerceAtMost(pageDeadline) ?: pageDeadline
        cycleNeedsImmediateRetry = cycleNeedsImmediateRetry ||
            cleanups.size == maxDeletes ||
            changedSnapshots > 0
        val next = if (scan.truncated) {
            nowEpochMs
        } else {
            val completedDeadline = checkNotNull(cycleDeadlineEpochMs)
            val completedRetry = cycleNeedsImmediateRetry
            cycleDeadlineEpochMs = null
            cycleNeedsImmediateRetry = false
            if (completedRetry) nowEpochMs else maxOf(nowEpochMs, completedDeadline)
        }
        return TelemetryNamespaceMaintenanceResult(scan.visitedNodes, scan.truncated, next)
    }
}

private fun namespace(identity: String, retentionReferenceEpochMs: Long): StoredTelemetryNamespace =
    namespace(telemetryTestIdentity(identity), retentionReferenceEpochMs)

private fun namespace(
    identityDirectories: List<String>,
    retentionReferenceEpochMs: Long,
): StoredTelemetryNamespace = StoredTelemetryNamespace(
    identityDirectories = identityDirectories,
    retentionReferenceEpochMs = retentionReferenceEpochMs,
    directoryStorageIdentity = "directory-${identityDirectories.joinToString("-")}",
    entries = emptyList(),
)

private fun telemetryTestIdentity(value: String): List<String> = listOf(
    stableTelemetryNamespace("deployment-$value"),
    stableTelemetryNamespace("dataset-$value"),
    stableTelemetryNamespace("uid-$value"),
)
