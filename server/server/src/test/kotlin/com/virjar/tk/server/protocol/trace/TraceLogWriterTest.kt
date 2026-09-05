package com.virjar.tk.server.protocol.trace

import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventDraft
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventSink
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.protocol.connection.traceNotifyDelivery
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceLogWriterTest {
    @Test
    fun `throwable snapshot cannot retain message cause suppressed or business payload graphs`() {
        val failure = PayloadFailure(ByteArray(1_024 * 1_024)).apply {
            initCause(IllegalStateException("cause-token=secret"))
            addSuppressed(IllegalArgumentException("suppressed-body={secret}"))
        }
        val snapshot = TraceThrowableSnapshot.capture(failure)

        assertEquals(PayloadFailure::class.java.name, snapshot.typeName)
        assertTrue(
            snapshot.javaClass.declaredFields.none { field ->
                Throwable::class.java.isAssignableFrom(field.type) ||
                    ByteArray::class.java.isAssignableFrom(field.type)
            },
        )
        val rendered = snapshot.toString()
        assertFalse(rendered.contains("cause-token"))
        assertFalse(rendered.contains("suppressed-body"))
        assertFalse(rendered.contains("secret"))
    }

    @Test
    fun `baseline never records merely because capacity exists and preauth cache is strictly bounded`() {
        val evaluated = AtomicLong(0)
        val runtime = TraceRuntime(threadName = "trace-baseline-test", maxWriters = 2)
        try {
            val recorder = recorder(runtime)
            repeat(Recorder.MAX_CACHE_SIZE + 1) { index ->
                recorder.record(
                    ConnectionTracePhase.CONNECTION,
                    ConnectionTraceOutcome.SUCCEEDED,
                    Supplier { evaluated.incrementAndGet(); "event=frameDecoded type=Frame$index" },
                )
            }

            assertEquals(0, evaluated.get())
            assertEquals(0, runtime.snapshot().activeWriters)
            assertEquals(1, runtime.snapshot().droppedPreAuthOverflow)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNull(recorder.disablePolicy(policyRevision = 0))
            assertNull(recorder.context())
            assertEquals(0, evaluated.get(), "BASELINE must discard, never evaluate, pre-auth work")
            recorder.record(
                ConnectionTracePhase.MESSAGE,
                ConnectionTraceOutcome.STARTED,
                Supplier { evaluated.incrementAndGet(); "event=send type=1" },
            )
            assertEquals(0, evaluated.get(), "authenticated BASELINE must drop instead of buffering")
            assertEquals(0, runtime.snapshot().acceptedEvents)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `effective diagnostic policy is the only admission path and carries exact connection identity`() {
        val events = ConcurrentLinkedQueue<ConnectionTraceEventDraft>()
        val delivered = CountDownLatch(1)
        val clock = AtomicLong(1_000)
        val runtime = TraceRuntime(
            threadName = "trace-policy-test",
            maxWriters = 1,
            eventSink = ConnectionTraceEventSink { event ->
                events += event
                delivered.countDown()
                true
            },
            clock = clock::get,
        )
        try {
            val recorder = recorder(runtime, clock)
            recorder.record(
                ConnectionTracePhase.CONNECTION,
                ConnectionTraceOutcome.SUCCEEDED,
                "event=frameDecoded type=AuthRequestPayload",
            )
            clock.set(1_500)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 7, SESSION_A))
            val update = recorder.applyDiagnosticPolicy("owner", "device", 9, 5_000)
            val context = assertNotNull(update?.context)
            assertEquals(CORRELATION_A, context.correlationId)
            assertEquals(7, context.connectionGeneration)
            assertEquals(SESSION_A, context.sessionId)
            assertEquals(9, context.policyRevision)
            assertEquals(5_000, context.expiresAtEpochMs)
            assertTrue(delivered.await(2, TimeUnit.SECONDS))

            val event = assertNotNull(events.poll())
            assertEquals(ConnectionTracePhase.CONNECTION, event.phase)
            assertEquals(ConnectionTraceOutcome.SUCCEEDED, event.outcome)
            assertEquals(context.traceId, event.traceId)
            assertEquals("event=frameDecoded type=AuthRequestPayload", event.detail)
            assertEquals(1_000, event.occurredAt, "pre-auth occurrence time must survive policy admission")

            val disable = assertNotNull(recorder.disablePolicy(10))
            assertNull(disable.context)
            assertEquals(CORRELATION_A, disable.correlationId)
            assertEquals(7, disable.connectionGeneration)
            assertEquals(0, runtime.snapshot().activeWriters)

            assertNull(recorder.disablePolicy(9), "a stale live policy must not cross the revision fence")
            val reenabled = assertNotNull(
                recorder.applyDiagnosticPolicy("owner", "device", 11, 6_000)?.context,
            )
            assertEquals(context.traceId, reenabled.traceId, "one physical connection keeps one trace id")
            assertEquals(11, reenabled.policyRevision)
            assertEquals(1, runtime.snapshot().activeWriters)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `queued event freezes revision and occurrence time before a policy refresh`() {
        val clock = AtomicLong(1_000)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delivered = ConcurrentLinkedQueue<ConnectionTraceEventDraft>()
        val runtime = TraceRuntime(
            threadName = "trace-context-freeze-test",
            clock = clock::get,
            eventSink = ConnectionTraceEventSink { event ->
                if (event.detail == "event=first") {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                delivered += event
                true
            },
        )
        try {
            val recorder = recorder(runtime, clock)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, 10_000)?.context)
            recorder.record(ConnectionTracePhase.CONNECTION, ConnectionTraceOutcome.STARTED, "event=first")
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

            clock.set(1_200)
            recorder.record(ConnectionTracePhase.RPC, ConnectionTraceOutcome.STARTED, "event=second")
            clock.set(1_300)
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 2, 10_000)?.context)
            clock.set(1_400)
            releaseFirst.countDown()

            assertTrue(eventually { delivered.size == 2 })
            val second = assertNotNull(delivered.firstOrNull { it.detail == "event=second" })
            assertEquals(1, second.policyRevision)
            assertEquals(1_200, second.occurredAt)
        } finally {
            releaseFirst.countDown()
            runtime.close()
        }
    }

    @Test
    fun `release preserves already admitted work but rejects every later supplier`() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delivered = ConcurrentLinkedQueue<String>()
        val runtime = TraceRuntime(
            threadName = "trace-release-order-test",
            eventSink = ConnectionTraceEventSink { event ->
                if (event.detail == "event=first") {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                delivered += checkNotNull(event.detail)
                true
            },
        )
        try {
            val recorder = recorder(runtime)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context)
            recorder.record(ConnectionTracePhase.CONNECTION, ConnectionTraceOutcome.STARTED, "event=first")
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            recorder.record(ConnectionTracePhase.SHUTDOWN, ConnectionTraceOutcome.CLOSED, "event=channelInactive")
            recorder.release()

            val lateEvaluated = AtomicBoolean(false)
            recorder.record(
                ConnectionTracePhase.MESSAGE,
                ConnectionTraceOutcome.STARTED,
                Supplier { lateEvaluated.set(true); "event=send type=1" },
            )
            releaseFirst.countDown()
            assertTrue(eventually { delivered.size == 2 })
            assertTrue(delivered.contains("event=channelInactive"))
            assertFalse(lateEvaluated.get())
            assertEquals(1, runtime.snapshot().droppedAfterRelease)
        } finally {
            releaseFirst.countDown()
            runtime.close()
        }
    }

    @Test
    fun `hard writer cap denial cannot revive on the same policy revision`() {
        val runtime = TraceRuntime(threadName = "trace-cap-test", maxWriters = 1)
        try {
            val first = recorder(runtime)
            val second = recorder(runtime)
            assertTrue(first.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertTrue(second.bindAuthentication(CORRELATION_B, 1, SESSION_B))
            assertNotNull(first.applyDiagnosticPolicy("owner-a", "device-a", 1, Long.MAX_VALUE)?.context)
            assertNull(second.applyDiagnosticPolicy("owner-b", "device-b", 1, Long.MAX_VALUE)?.context)
            assertEquals(1, runtime.snapshot().activeWriters)
            assertEquals(1, runtime.snapshot().deniedWriters)

            first.release()
            assertEquals(0, runtime.snapshot().activeWriters)
            assertNull(
                second.applyDiagnosticPolicy("owner-b", "device-b", 1, Long.MAX_VALUE),
                "a client-visible null revision must remain terminal after capacity becomes free",
            )
            assertNull(second.context())
            assertEquals(0, runtime.snapshot().activeWriters)
            assertEquals(1, runtime.snapshot().deniedWriters, "same revision must not retry writer admission")

            assertNotNull(second.applyDiagnosticPolicy("owner-b", "device-b", 2, Long.MAX_VALUE)?.context)
            assertEquals(1, runtime.snapshot().activeWriters)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `event byte expiry and release budgets drop lazily without evaluating on caller`() {
        val clock = AtomicLong(1_000)
        val delivered = CountDownLatch(1)
        val runtime = TraceRuntime(
            threadName = "trace-budget-test",
            maxEventsPerConnection = 1,
            maxBytesPerConnection = 16_384,
            clock = clock::get,
            eventSink = ConnectionTraceEventSink { delivered.countDown(); true },
        )
        try {
            val recorder = recorder(runtime, clock)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 3, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 4, 2_000)?.context)
            recorder.record(
                ConnectionTracePhase.AUTHENTICATION,
                ConnectionTraceOutcome.SUCCEEDED,
                "event=identityAccepted state=synchronizing",
            )
            assertTrue(delivered.await(2, TimeUnit.SECONDS))

            val eventBudgetSupplier = AtomicBoolean(false)
            recorder.record(
                ConnectionTracePhase.SYNC,
                ConnectionTraceOutcome.STARTED,
                Supplier { eventBudgetSupplier.set(true); "event=batch count=1" },
            )
            assertFalse(eventBudgetSupplier.get())
            assertEquals(1, runtime.snapshot().droppedEventBudget)

            clock.set(2_000)
            val expiredSupplier = AtomicBoolean(false)
            recorder.record(
                ConnectionTracePhase.SYNC,
                ConnectionTraceOutcome.FAILED,
                Supplier { expiredSupplier.set(true); "event=timeout timeoutSeconds=60" },
            )
            assertFalse(expiredSupplier.get())
            assertNull(assertNotNull(recorder.currentPolicyDecision()).context)
            assertEquals(0, runtime.snapshot().activeWriters)

            recorder.release()
            val releasedSupplier = AtomicBoolean(false)
            recorder.record(
                ConnectionTracePhase.SHUTDOWN,
                ConnectionTraceOutcome.CLOSED,
                Supplier { releasedSupplier.set(true); "event=channelInactive" },
            )
            assertFalse(releasedSupplier.get())
            assertTrue(runtime.snapshot().droppedAfterRelease >= 1)
        } finally {
            runtime.close()
        }

        val byteSupplier = AtomicBoolean(false)
        val byteRuntime = TraceRuntime(
            threadName = "trace-byte-budget-test",
            maxBytesPerConnection = 1,
            eventSink = ConnectionTraceEventSink { error("byte-over-budget event must not be delivered") },
        )
        try {
            val recorder = recorder(byteRuntime)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context)
            recorder.record(
                ConnectionTracePhase.RPC,
                ConnectionTraceOutcome.SUCCEEDED,
                Supplier { byteSupplier.set(true); "event=response service=1 method=2 status=0" },
            )
            assertTrue(eventually { byteRuntime.snapshot().droppedByteBudget == 1L })
            assertTrue(byteSupplier.get(), "byte accounting and supplier resolution belong to the trace worker")
            assertEquals(0, byteRuntime.snapshot().deliveredEvents)
        } finally {
            byteRuntime.close()
        }
    }

    @Test
    fun `policy toggle cannot reset the physical connection event budget or trace id`() {
        val delivered = CountDownLatch(1)
        val generatedIds = AtomicLong(0)
        val runtime = TraceRuntime(
            threadName = "trace-policy-toggle-event-budget-test",
            maxEventsPerConnection = 1,
            eventSink = ConnectionTraceEventSink { delivered.countDown(); true },
        )
        try {
            val recorder = Recorder(
                runtime = runtime,
                idFactory = {
                    if (generatedIds.getAndIncrement() == 0L) TRACE_A else TRACE_B
                },
            )
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            val firstContext = assertNotNull(
                recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context,
            )
            recorder.record(ConnectionTracePhase.CONNECTION, ConnectionTraceOutcome.STARTED, "event=first")
            assertTrue(delivered.await(2, TimeUnit.SECONDS))

            assertNotNull(recorder.disablePolicy(2))
            val reenabledContext = assertNotNull(
                recorder.applyDiagnosticPolicy("owner", "device", 3, Long.MAX_VALUE)?.context,
            )
            assertEquals(firstContext.traceId, reenabledContext.traceId)
            assertEquals(1, generatedIds.get(), "one physical connection must allocate exactly one trace id")

            val evaluated = AtomicBoolean(false)
            recorder.record(
                ConnectionTracePhase.CONNECTION,
                ConnectionTraceOutcome.STARTED,
                Supplier { evaluated.set(true); "event=second" },
            )
            assertFalse(evaluated.get(), "event budget rejection must remain caller-side and lazy")
            assertEquals(1, runtime.snapshot().droppedEventBudget)
            assertEquals(1, runtime.snapshot().deliveredEvents)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `policy toggle cannot reset the physical connection byte budget`() {
        val delivered = CountDownLatch(1)
        val runtime = TraceRuntime(
            threadName = "trace-policy-toggle-byte-budget-test",
            maxBytesPerConnection = 256,
            eventSink = ConnectionTraceEventSink { delivered.countDown(); true },
        )
        try {
            val recorder = recorder(runtime)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context)
            recorder.record(ConnectionTracePhase.CONNECTION, ConnectionTraceOutcome.STARTED, "event=first")
            assertTrue(delivered.await(2, TimeUnit.SECONDS), "the first event must fit the byte budget")

            assertNotNull(recorder.disablePolicy(2))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 3, Long.MAX_VALUE)?.context)
            val evaluated = AtomicBoolean(false)
            recorder.record(
                ConnectionTracePhase.CONNECTION,
                ConnectionTraceOutcome.STARTED,
                Supplier { evaluated.set(true); "event=second" },
            )

            assertTrue(eventually { runtime.snapshot().droppedByteBudget == 1L })
            assertTrue(evaluated.get(), "byte accounting remains on the trace worker")
            assertEquals(1, runtime.snapshot().deliveredEvents)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `decode alarm stores only typed mapping and a bounded throwable summary`() {
        val events = ConcurrentLinkedQueue<ConnectionTraceEventDraft>()
        val runtime = TraceRuntime(
            threadName = "trace-redaction-test",
            eventSink = ConnectionTraceEventSink { event -> events += event; true },
        )
        try {
            val recorder = recorder(runtime)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context)
            recorder.record(
                ConnectionTracePhase.CONNECTION,
                ConnectionTraceOutcome.FAILED,
                Supplier { "event=decodeAlarm" },
                IllegalArgumentException("token=secret body={x} /Users/private/file"),
            )
            assertTrue(eventually { events.isNotEmpty() })
            val detail = assertNotNull(events.poll().detail)
            assertTrue(detail.contains("event=decodeAlarm"))
            assertTrue(detail.contains("errorType=java.lang.IllegalArgumentException"))
            assertFalse(detail.contains("secret"))
            assertFalse(detail.contains("body"))
            assertFalse(detail.contains("/Users"))

            recorder.record(
                ConnectionTracePhase.MESSAGE,
                ConnectionTraceOutcome.REJECTED,
                "payload={token=secret}",
            )
            assertTrue(eventually { events.isNotEmpty() })
            assertNull(events.poll().detail)
            assertTrue(runtime.snapshot().droppedUnsafeDetail >= 1)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `notify delivery records only the fixed event phase and primitive type`() {
        val events = ConcurrentLinkedQueue<ConnectionTraceEventDraft>()
        val runtime = TraceRuntime(
            threadName = "trace-notify-delivery-test",
            eventSink = ConnectionTraceEventSink { event -> events += event; true },
        )
        try {
            val recorder = recorder(runtime)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context)

            recorder.traceNotifyDelivery(61)

            assertTrue(eventually { events.isNotEmpty() })
            val event = assertNotNull(events.poll())
            assertEquals(ConnectionTracePhase.EVENT, event.phase)
            assertEquals(ConnectionTraceOutcome.SUCCEEDED, event.outcome)
            assertEquals("event=notify type=61", event.detail)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `sink failure is isolated from IM and increments delivery failure`() {
        val runtime = TraceRuntime(
            threadName = "trace-sink-failure-test",
            eventSink = ConnectionTraceEventSink { false },
        )
        try {
            val recorder = recorder(runtime)
            assertTrue(recorder.bindAuthentication(CORRELATION_A, 1, SESSION_A))
            assertNotNull(recorder.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context)
            recorder.record(
                ConnectionTracePhase.RPC,
                ConnectionTraceOutcome.SUCCEEDED,
                "event=response service=1 method=2 status=0",
            )
            assertTrue(eventually { runtime.snapshot().deliveryFailures == 1L })
            recorder.record(ConnectionTracePhase.RPC, ConnectionTraceOutcome.STARTED, "event=request")
            assertTrue(
                eventually { runtime.snapshot().deliveryFailures == 2L },
                "sink failure must leave subsequent connection events deliverable",
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `reconnect contexts are isolated while generation may reset after app restart`() {
        val runtime = TraceRuntime(threadName = "trace-reconnect-test", maxWriters = 2)
        try {
            val first = recorder(runtime)
            val restarted = recorder(runtime, traceId = TRACE_B)
            assertTrue(first.bindAuthentication(CORRELATION_A, 9, SESSION_A))
            assertFalse(first.bindAuthentication(CORRELATION_B, 10, SESSION_B), "second AUTH is fail-closed")
            assertTrue(restarted.bindAuthentication(CORRELATION_B, 1, SESSION_B))
            val firstContext = assertNotNull(
                first.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context,
            )
            val restartedContext = assertNotNull(
                restarted.applyDiagnosticPolicy("owner", "device", 1, Long.MAX_VALUE)?.context,
            )
            assertEquals(1, restartedContext.connectionGeneration)
            assertNotEquals(firstContext.correlationId, restartedContext.correlationId)
            assertNotEquals(firstContext.traceId, restartedContext.traceId)
            assertNotEquals(firstContext.sessionId, restartedContext.sessionId)
        } finally {
            runtime.close()
        }
    }

    private fun recorder(
        runtime: TraceRuntime,
        clock: AtomicLong? = null,
        traceId: String = TRACE_A,
    ): Recorder = Recorder(
        runtime = runtime,
        clock = clock?.let { it::get } ?: System::currentTimeMillis,
        idFactory = { traceId },
    )

    private fun eventually(timeoutMillis: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private companion object {
        const val CORRELATION_A = "correlation_00000001"
        const val CORRELATION_B = "correlation_00000002"
        const val SESSION_A = "serverSession_000001"
        const val SESSION_B = "serverSession_000002"
        const val TRACE_A = "serverTrace_00000001"
        const val TRACE_B = "serverTrace_00000002"
    }

    private class PayloadFailure(@Suppress("unused") val payload: ByteArray) :
        RuntimeException("token=secret body={secret} /Users/private/file")
}
