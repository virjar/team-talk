package com.virjar.tk.server.infra.health

import com.virjar.tk.server.domain.message.MessageProjectionFailure
import com.virjar.tk.server.domain.telemetry.TelemetryRetentionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProjectionHealthRedactionTest {
    @Test
    fun `disposable client telemetry does not control messaging readiness`() {
        assertEquals(
            "UP",
            readinessStatus(
                mapOf(
                    "postgres" to ComponentHealth("UP"),
                    "client-telemetry" to ComponentHealth("DOWN"),
                ),
            ),
        )
        assertEquals(
            "DOWN",
            readinessStatus(
                mapOf(
                    "postgres" to ComponentHealth("DOWN"),
                    "client-telemetry" to ComponentHealth("UP"),
                ),
            ),
        )
    }

    @Test
    fun `message projection health never exposes retained operation or failure detail`() {
        val component = messageProjectionHealth(
            MessageProjectionFailure(
                projectionKey = "message/v1/internal-chat/7",
                detail = "jdbc://internal-host/private-path?password=redacted",
            ),
        )

        assertEquals("DOWN", component.status)
        assertEquals("Message projection recovery is pending", component.detail)
        assertFalse(component.detail.orEmpty().contains("internal-chat"))
        assertFalse(component.detail.orEmpty().contains("password"))
    }

    @Test
    fun `managed projection health exposes only a fixed lifecycle phase`() {
        val failed = managedChatProjectionHealth(pending = 12L, failed = true)
        val retrying = managedChatProjectionHealth(pending = 12L, failed = false)

        assertEquals(ComponentHealth("DOWN", "Managed-chat projection recovery has failed"), failed)
        assertEquals(ComponentHealth("DOWN", "Managed-chat projection recovery is pending"), retrying)
        assertEquals(ComponentHealth("UP"), managedChatProjectionHealth(pending = 0L, failed = true))
    }

    @Test
    fun `client telemetry health exposes only fixed retention readiness facts`() {
        val current = TelemetryRetentionStatus(lastSuccessAt = 123L, backlog = false, overdue = false)
        assertEquals(
            ComponentHealth("UP", lastSuccessAt = 123L, backlog = false, overdue = false),
            clientTelemetryHealth(available = true, retention = current),
        )
        val unavailable = clientTelemetryHealth(available = false)
        assertEquals(
            ComponentHealth(
                "DOWN",
                "Client telemetry event store is unavailable",
                backlog = true,
                overdue = true,
            ),
            unavailable,
        )
        assertFalse(unavailable.detail.orEmpty().contains("/"))
        assertFalse(unavailable.detail.orEmpty().contains("exception", ignoreCase = true))

        val overdue = clientTelemetryHealth(
            available = true,
            retention = TelemetryRetentionStatus(lastSuccessAt = 123L, backlog = true, overdue = true),
        )
        assertEquals("DOWN", overdue.status)
        assertEquals("Client telemetry retention is overdue", overdue.detail)
    }
}
