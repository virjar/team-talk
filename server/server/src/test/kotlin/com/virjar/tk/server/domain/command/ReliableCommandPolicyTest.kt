package com.virjar.tk.server.domain.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReliableCommandPolicyTest {
    @Test
    fun `retry horizon and future clock allowance have exact deterministic boundaries`() {
        val now = 1_800_000_000_000L
        val oldestAccepted = now - ReliableCommandPolicy.RETRY_HORIZON_MILLIS
        val newestAccepted = now + ReliableCommandPolicy.MAX_FUTURE_CLOCK_SKEW_MILLIS

        assertEquals(
            oldestAccepted,
            ReliableCommandPolicy.requireActiveIssuedAt(oldestAccepted, now, "test"),
        )
        assertEquals(
            newestAccepted,
            ReliableCommandPolicy.requireActiveIssuedAt(newestAccepted, now, "test"),
        )
        assertEquals(now, ReliableCommandPolicy.expiresAt(oldestAccepted))

        assertFailsWith<ReliableCommandExpiredException> {
            ReliableCommandPolicy.requireActiveIssuedAt(oldestAccepted - 1L, now, "test")
        }
        assertFailsWith<IllegalArgumentException> {
            ReliableCommandPolicy.requireActiveIssuedAt(newestAccepted + 1L, now, "test")
        }
    }
}
