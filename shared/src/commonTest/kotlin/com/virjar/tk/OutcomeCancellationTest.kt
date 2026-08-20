package com.virjar.tk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutcomeCancellationTest {
    @Test
    fun `cancellation remains structured concurrency signal`() = runTest {
        var propagated = false
        try {
            outcome<Unit> { throw CancellationException("owner closed") }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun `programming illegal state is not disguised as network failure`() = runTest {
        val result = outcome<Unit> { throw IllegalStateException("broken invariant") }
        assertIs<Outcome.Failure>(result)
        assertIs<AppError.Unknown>(result.error)
    }
}
