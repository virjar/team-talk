package com.virjar.tk.server.runtime

import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientTelemetryRetentionStepTest {
    @Test
    fun `PostgreSQL policy failure cannot block local event expiry`() = runTest {
        val now = TelemetryStoragePolicy.RETENTION_MILLIS + 123L
        var deletedBefore: Long? = null
        val warnings = mutableListOf<String>()

        val needsCatchUp = runClientTelemetryRetentionStep(
            now = now,
            expirePolicies = { _, _ -> error("PostgreSQL unavailable") },
            ensureEventStoreStarted = { true },
            deleteEventsBefore = { cutoff ->
                deletedBefore = cutoff
                true
            },
            warn = { operation, _ -> warnings += operation },
        )

        assertTrue(needsCatchUp)
        assertEquals(123L, deletedBefore)
        assertEquals(listOf("policy expiry"), warnings)
    }

    @Test
    fun `unavailable event store enters catch-up without issuing deletion`() = runTest {
        var deletionCalled = false

        val needsCatchUp = runClientTelemetryRetentionStep(
            now = TelemetryStoragePolicy.RETENTION_MILLIS,
            expirePolicies = { _, _ -> 0 },
            ensureEventStoreStarted = { false },
            deleteEventsBefore = {
                deletionCalled = true
                true
            },
            warn = { _, _ -> },
        )

        assertTrue(needsCatchUp)
        assertFalse(deletionCalled)
    }

    @Test
    fun `maintenance cancellation remains terminal`() = runTest {
        var eventStartCalled = false

        assertFailsWith<CancellationException> {
            runClientTelemetryRetentionStep(
                now = TelemetryStoragePolicy.RETENTION_MILLIS,
                expirePolicies = { _, _ -> throw CancellationException("shutdown") },
                ensureEventStoreStarted = {
                    eventStartCalled = true
                    true
                },
                deleteEventsBefore = { true },
                warn = { _, _ -> },
            )
        }
        assertFalse(eventStartCalled)
    }
}
