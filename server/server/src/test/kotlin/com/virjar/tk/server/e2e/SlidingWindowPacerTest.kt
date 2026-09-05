package com.virjar.tk.server.e2e

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SlidingWindowPacerTest {
    @Test
    fun `pacer never admits beyond the rolling source budget`() = runTest {
        var now = 0L
        val waits = mutableListOf<Long>()
        val admitted = mutableListOf<Long>()
        val pacer = SlidingWindowPacer(
            maxAttempts = 2,
            windowNanos = 10L,
            safetyNanos = 1L,
            monotonicNanos = { now },
            waitNanos = { duration ->
                waits += duration
                now += duration
            },
        )

        repeat(5) {
            pacer.awaitPermit()
            admitted += now
        }

        assertEquals(listOf(0L, 0L, 11L, 11L, 22L), admitted)
        assertEquals(listOf(11L, 11L), waits)
    }
}
