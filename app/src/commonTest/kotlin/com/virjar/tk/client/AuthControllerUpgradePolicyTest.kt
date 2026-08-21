package com.virjar.tk.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthControllerUpgradePolicyTest {
    @Test
    fun `force upgrade requires typed protocol failure`() {
        assertTrue(
            requiresForcedProtocolUpgrade(
                AuthenticationFailure(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, "upgrade"),
            ),
        )
        assertFalse(
            requiresForcedProtocolUpgrade(
                AuthenticationFailure(AuthenticationFailureKind.REJECTED, "network-like error"),
            ),
        )
        assertFalse(requiresForcedProtocolUpgrade(null))
    }

    @Test
    fun `auto login uses a renewable sync no-progress window instead of identity timeout`() {
        assertEquals(12_000L, autoLoginTimeoutMillis(ConnectionState.DISCONNECTED))
        assertEquals(12_000L, autoLoginTimeoutMillis(ConnectionState.CONNECTING))
        assertEquals(12_000L, autoLoginTimeoutMillis(ConnectionState.CONNECTED))
        assertEquals(35_000L, autoLoginTimeoutMillis(ConnectionState.SYNCHRONIZING))
        assertNull(autoLoginTimeoutMillis(ConnectionState.AUTHENTICATED))
        assertNull(autoLoginTimeoutMillis(ConnectionState.AUTH_FAILED))
    }

    @Test
    fun `already cancelled controller scope still runs retirement close fallback exactly once`() = runBlocking {
        val owner = Job().apply { cancel() }
        val cancelledScope = CoroutineScope(coroutineContext + owner)
        var closeFallbacks = 0

        val retirement = cancelledScope.launchRetirementWithFallback(
            fallback = { closeFallbacks += 1 },
        ) {
            // The hard assertion is the completion fallback; this body may or may not be entered
            // by a coroutine implementation whose parent was already cancelled.
        }
        retirement.join()

        assertEquals(1, closeFallbacks)
        assertTrue(retirement.isCompleted)
    }

    @Test
    fun `credential holder only accepts its fixed owner generation and publishes in order`() {
        val first = StoredLogin("uid", "refresh-1", 7L, "a".repeat(64))
        val holder = AuthCredentialSnapshotHolder(7L, first)

        val second = StoredLogin("uid", "refresh-2", 7L, "a".repeat(64))
        holder.publish(second)
        assertEquals(second, holder.snapshot())
        assertFailsWith<IllegalArgumentException> {
            holder.publish(StoredLogin("uid", "stale-owner", 8L, "a".repeat(64)))
        }
        assertEquals(second, holder.clear())
        assertNull(holder.snapshot())
    }

    @Test
    fun `retired auth result gate rejects persistence until explicit replacement`() {
        val gate = AuthResultAdmissionGate(initiallyActive = true)
        var commits = 0

        gate.retire()
        assertFailsWith<IllegalStateException> { gate.use { commits += 1 } }
        gate.replaceAttempt { assertEquals(0, commits) }
        gate.use { commits += 1 }

        assertEquals(1, commits)
    }

    @Test
    fun `terminal auth failure disconnects even before a session exists or retirement fails`() {
        var retirementCalls = 0
        var disconnectCalls = 0

        assertFailsWith<AuthenticationRetirementFailure> {
            retireAuthFailureAndDisconnect(
                endSession = {
                    retirementCalls += 1
                    throw AuthenticationRetirementFailure()
                },
                disconnectTransport = { disconnectCalls += 1 },
            )
        }

        assertEquals(1, retirementCalls)
        assertEquals(1, disconnectCalls)
    }

    private class AuthenticationRetirementFailure : RuntimeException()
}
