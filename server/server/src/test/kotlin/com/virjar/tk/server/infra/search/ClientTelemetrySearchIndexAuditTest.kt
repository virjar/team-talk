package com.virjar.tk.server.infra.search

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientTelemetrySearchIndexAuditTest {
    @Test
    fun `retention readiness records success and becomes overdue without another run`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-retention-health-").toFile()
        var now = 1_000L
        val store = ClientTelemetrySearchIndex(root, clock = { now })
        try {
            assertTrue(store.start())
            val initial = store.retentionStatus(now)
            assertTrue(initial.backlog)
            assertFalse(initial.overdue)

            assertTrue(store.deleteBefore(Long.MIN_VALUE))
            val successful = store.retentionStatus(now)
            assertEquals(now, successful.lastSuccessAt)
            assertFalse(successful.backlog)
            assertFalse(successful.overdue)

            now += CLIENT_TELEMETRY_RETENTION_OVERDUE_MILLIS + 1L
            assertTrue(store.retentionStatus(now).overdue)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}
