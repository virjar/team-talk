package com.virjar.tk.server.protocol

import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventSink
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.protocol.executor.IOExecutor
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.server.protocol.trace.TraceRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame

class TcpServerLifecycleTest {
    @Test
    fun `stop fails closed when either connection admission lease is leaked`() {
        val connections = TcpConnectionAdmission(capacity = 1)
        val unauthenticated = UnauthenticatedConnectionAdmission(capacity = 1)
        val totalLease = connections.tryAcquire()!!
        val unauthenticatedLease = unauthenticated.tryAcquire()!!
        val server = TcpServer(
            configuration = TcpServerConfiguration.plaintext(),
            connections = connections,
            unauthenticatedConnections = unauthenticated,
        )

        try {
            val first = assertFailsWith<IllegalStateException> { server.stop() }
            assertTrue(first.message.orEmpty().contains("1 total and 1 unauthenticated"))
            assertSame(first, assertFailsWith<IllegalStateException> { server.stop() })
        } finally {
            unauthenticatedLease.close()
            totalLease.close()
        }
    }

    @Test
    fun `repeated stop replays the first terminal failure`() {
        val sinkStarted = CountDownLatch(1)
        val releaseSink = AtomicBoolean(false)
        val sinkExited = CountDownLatch(1)
        val traceRuntime = TraceRuntime(
            threadName = "tcp-stop-failure-trace",
            maxWriters = 1,
            queueCapacity = 1,
            workerJoinTimeoutMillis = 20,
            eventSink = ConnectionTraceEventSink {
                sinkStarted.countDown()
                try {
                    while (!releaseSink.get()) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            // 模拟一个忽略中断的外部日志 sink。
                        }
                    }
                } finally {
                    sinkExited.countDown()
                }
                true
            },
        )
        val writer = traceRuntime.acquireWriter(
            uid = "owner",
            deviceId = "device",
            context = ConnectionTraceContext(
                correlationId = "correlation_00000001",
                traceId = "serverTrace_00000001",
                sessionId = "serverSession_000001",
                connectionGeneration = 1,
                policyRevision = 1,
                expiresAtEpochMs = Long.MAX_VALUE,
            ),
            connectionBudget = traceRuntime.createConnectionBudget(),
        )
        checkNotNull(writer).write(
            ConnectionTracePhase.CONNECTION,
            ConnectionTraceOutcome.STARTED,
            Supplier { "event=frameDecoded type=Blocked" },
        )
        assertTrue(sinkStarted.await(1, TimeUnit.SECONDS))

        val server = TcpServer(
            configuration = TcpServerConfiguration.plaintext(),
            ioExecutor = IOExecutor(workerCount = 1, queueCapacity = 1),
            traceRuntime = traceRuntime,
        )
        try {
            val firstFailure = assertFailsWith<IllegalStateException> { server.stop() }
            val repeatedFailure = assertFailsWith<IllegalStateException> { server.stop() }
            assertSame(firstFailure, repeatedFailure)
        } finally {
            releaseSink.set(true)
            assertTrue(sinkExited.await(1, TimeUnit.SECONDS))
        }
    }
}
