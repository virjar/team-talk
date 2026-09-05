package com.virjar.tk.server.infra.search

import com.virjar.tk.server.domain.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientTelemetryConnectionTraceContextTest {
    @Test
    fun `connection context and exact internal record id survive schema restart`() = runTest {
        val root = Files.createTempDirectory("teamtalk-client-telemetry-trace-context-").toFile()
        val context = ConnectionTraceContext(
            correlationId = token("correlation"),
            traceId = token("trace"),
            sessionId = token("session"),
            connectionGeneration = 7L,
            policyRevision = 11L,
        )
        val batch = TelemetryBatchDraft(
            batchId = UUID.randomUUID().toString(),
            payloadSha256 = "a".repeat(64),
            createdAt = 1_000L,
            runtime = runtime(),
            events = listOf(
                TelemetryEventDraft(
                    eventId = UUID.randomUUID().toString(),
                    runId = UUID.randomUUID().toString(),
                    sequence = 1L,
                    occurredAt = 1_000L,
                    category = TelemetryEventKind.ACTION.name,
                    eventName = "trace-context-action",
                    message = "trace context action succeeded",
                    searchText = "trace context action succeeded",
                    connectionTraceContext = context,
                ),
            ),
        )
        var recordId: Long? = null
        val first = ClientTelemetrySearchIndex(root)
        try {
            assertTrue(first.start())
            first.ingest("trace-context-uid", "trace-context-device", batch, 1_000L, 1_024)
            val hit = first.search(
                TelemetrySearchQuery(receivedAtFrom = 1_000L, receivedAtUntil = 1_000L),
                0,
                10,
            ).hits.single().event
            recordId = hit.id
            assertEquals(context, hit.event.connectionTraceContext)
            assertEquals(hit, first.findEventById(hit.id))
        } finally {
            first.close()
        }

        val reopened = ClientTelemetrySearchIndex(root)
        try {
            assertTrue(reopened.start())
            assertEquals(
                context,
                assertNotNull(reopened.findEventById(checkNotNull(recordId))).event.connectionTraceContext,
            )
        } finally {
            reopened.close()
            root.deleteRecursively()
        }
    }

    private fun runtime() = TelemetryRuntimeSnapshot(
        platform = "desktop",
        osName = "macOS",
        osVersion = "15",
        architecture = "arm64",
        deviceModel = "Mac",
        appVersion = "1.0",
        buildNumber = "1",
        gitCommit = "abcdef",
        buildIdentity = "test",
        buildTime = "2026-01-01T00:00:00Z",
        protocolVersion = 1,
        distribution = "test",
    )

    private fun token(value: String) = value.padEnd(16, 'x')
}
