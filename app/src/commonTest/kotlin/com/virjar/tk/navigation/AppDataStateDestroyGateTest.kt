package com.virjar.tk.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataStateDestroyGateTest {
    @Test
    fun `explicit logout and later composition disposal share one destroy admission`() {
        val gate = AppDataStateDestroyGate()

        val first = gate.destroy { emptyList() }
        val second = gate.destroy { error("cleanup must not repeat") }
        val third = gate.destroy { error("cleanup must not repeat") }

        assertTrue(first.completedNow)
        assertFalse(second.completedNow)
        assertFalse(third.completedNow)
    }

    @Test
    fun `terminal cleanup failure is retained without throwing or reopening`() {
        val gate = AppDataStateDestroyGate()
        val failure = IllegalStateException("owner failed")

        val first = gate.destroy { listOf("owner" to failure) }
        val follower = gate.destroy { error("cleanup must not repeat") }

        assertEquals(listOf("owner" to failure), first.failures)
        assertEquals(first.failures, follower.failures)
    }
}
