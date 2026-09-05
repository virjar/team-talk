package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventDraft
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.domain.telemetry.ConnectionTraceQuery
import com.virjar.tk.server.domain.telemetry.ConnectionTraceStoragePolicy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionTraceSearchIndexTest {
    @Test
    fun `query requires exact owner device and all connection identity fields`() {
        withStore { store ->
            val base = event(occurredAt = 1_000L)
            val candidates = listOf(
                base,
                base.copy(connectionGeneration = 2L, detail = "event=secondGeneration"),
                base.copy(policyRevision = 2L, detail = "event=secondPolicy"),
                base.copy(correlationId = token("other-correlation"), detail = "event=otherCorrelation"),
                base.copy(traceId = token("other-trace"), detail = "event=otherTrace"),
                base.copy(sessionId = token("other-session"), detail = "event=otherSession"),
                base.copy(uid = "other-trace-uid", detail = "event=otherOwner"),
                base.copy(deviceId = "other-trace-device", detail = "event=otherDevice"),
            )
            candidates.forEach { assertTrue(store.tryAppend(it)) }
            await { store.snapshot().documentCount == candidates.size.toLong() }

            val result = store.query(query(base))
            assertFalse(result.truncated)
            assertEquals(listOf(base), result.events.map { it.event })
            assertTrue(store.query(query(base.copy(connectionGeneration = 3L))).events.isEmpty())
        }
    }

    @Test
    fun `retention physically removes only expired traces`() {
        val now = ConnectionTraceStoragePolicy.RETENTION_MILLIS + 10_000L
        withStore(clock = { now }) { store ->
            val expired = event(occurredAt = 1_000L)
            val live = event(
                occurredAt = now - 1_000L,
                correlationId = token("live-correlation"),
                traceId = token("live-trace"),
                sessionId = token("live-session"),
            )
            assertTrue(store.tryAppend(expired))
            assertTrue(store.tryAppend(live))
            await { store.snapshot().documentCount == 2L }

            assertTrue(store.deleteBefore(now - ConnectionTraceStoragePolicy.RETENTION_MILLIS))
            assertEquals(1L, store.snapshot().documentCount)
            assertTrue(store.query(query(expired, 0L, now)).events.isEmpty())
            assertEquals(listOf(live), store.query(query(live, 0L, now)).events.map { it.event })
        }
    }

    @Test
    fun `document capacity drops diagnostics without blocking or disabling the store`() {
        withStore(maxDocuments = 1L) { store ->
            val first = event(occurredAt = 1_000L)
            val second = event(occurredAt = 1_001L, detail = "event=second")
            assertTrue(store.tryAppend(first))
            await { store.snapshot().documentCount == 1L }
            assertTrue(store.tryAppend(second))
            await { store.snapshot().droppedEvents >= 1L }
            assertEquals(1L, store.snapshot().documentCount)
            assertTrue(store.isAvailable())
        }
    }

    @Test
    fun `queue count and byte budgets reject immediately`() {
        val root = Files.createTempDirectory("teamtalk-connection-trace-queue-").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = ConnectionTraceSearchIndex(root, maxQueuedEvents = 1, maxQueuedBytes = 10_000L)
        try {
            assertTrue(store.start())
            store.appendHookForTest = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(store.tryAppend(event(occurredAt = 1_000L)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(store.tryAppend(event(occurredAt = 1_001L, detail = "event=queued")))
            assertFalse(store.tryAppend(event(occurredAt = 1_002L, detail = "event=rejected")))
            release.countDown()
            await { store.snapshot().documentCount == 2L }
        } finally {
            release.countDown()
            store.close()
            root.deleteRecursively()
        }

        val byteRoot = Files.createTempDirectory("teamtalk-connection-trace-byte-").toFile()
        val byteEntered = CountDownLatch(1)
        val byteRelease = CountDownLatch(1)
        val byteStore = ConnectionTraceSearchIndex(byteRoot, maxQueuedBytes = 700L)
        try {
            assertTrue(byteStore.start())
            byteStore.appendHookForTest = {
                byteEntered.countDown()
                check(byteRelease.await(5, TimeUnit.SECONDS))
            }
            assertTrue(byteStore.tryAppend(event(occurredAt = 2_000L)))
            assertTrue(byteEntered.await(5, TimeUnit.SECONDS))
            assertFalse(byteStore.tryAppend(event(occurredAt = 2_001L)))
        } finally {
            byteRelease.countDown()
            byteStore.close()
            byteRoot.deleteRecursively()
        }
    }

    @Test
    fun `writer failure is isolated and all later appends bypass`() {
        withStore { store ->
            store.appendHookForTest = { throw IOException("synthetic trace disk failure") }
            assertTrue(store.tryAppend(event(occurredAt = 1_000L)))
            await { !store.isAvailable() }
            assertFalse(store.tryAppend(event(occurredAt = 1_001L)))
            assertTrue(store.snapshot().droppedEvents >= 2L)
        }
    }

    private fun withStore(
        maxDocuments: Long = ConnectionTraceSearchIndex.DEFAULT_MAX_DOCUMENTS,
        clock: () -> Long = { 10_000L },
        block: (ConnectionTraceSearchIndex) -> Unit,
    ) {
        val root = Files.createTempDirectory("teamtalk-connection-trace-").toFile()
        val store = ConnectionTraceSearchIndex(root, maxDocuments = maxDocuments, clock = clock)
        try {
            assertTrue(store.start())
            block(store)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    private fun event(
        occurredAt: Long,
        correlationId: String = token("correlation"),
        traceId: String = token("trace"),
        sessionId: String = token("session"),
        connectionGeneration: Long = 1L,
        policyRevision: Long = 1L,
        detail: String? = "event=dispatch",
    ) = ConnectionTraceEventDraft(
        uid = "trace-uid",
        deviceId = "trace-device",
        correlationId = correlationId,
        traceId = traceId,
        sessionId = sessionId,
        connectionGeneration = connectionGeneration,
        policyRevision = policyRevision,
        occurredAt = occurredAt,
        phase = ConnectionTracePhase.RPC,
        outcome = ConnectionTraceOutcome.SUCCEEDED,
        detail = detail,
    )

    private fun query(
        event: ConnectionTraceEventDraft,
        from: Long = event.occurredAt,
        until: Long = event.occurredAt,
    ) = ConnectionTraceQuery(
        uid = event.uid ?: error("query fixture requires uid"),
        deviceId = event.deviceId ?: error("query fixture requires deviceId"),
        correlationId = event.correlationId,
        traceId = event.traceId,
        sessionId = event.sessionId,
        connectionGeneration = event.connectionGeneration,
        policyRevision = event.policyRevision,
        occurredAtFrom = from,
        occurredAtUntil = until,
    )

    private fun token(value: String): String = value.replace("-", "_").padEnd(16, 'x')

    private fun await(assertion: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!assertion()) {
            check(System.nanoTime() < deadline) { "timed out waiting for connection trace store" }
            Thread.sleep(10L)
        }
    }
}
