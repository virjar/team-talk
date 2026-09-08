package com.virjar.tk.shared.client

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionPendingMirrorWakeConcurrencyTest {
    @Test
    fun `concurrent commits authentication and timers retain every latest wake generation`() = runBlocking {
        val wake = SessionPendingMirrorWake()
        val executor = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(5)
        val rounds = 2_048
        val updatesPerRound = 16
        val publishers = listOf<(Long) -> Unit>(
            { wake.pendingCommitted() },
            { wake.authenticated() },
            wake::retryDue,
            wake::reliableCommandExpiryDue,
        )
        val tasks = publishers.map { publish ->
            executor.submit {
                repeat(rounds) { round ->
                    barrier.await(10L, TimeUnit.SECONDS)
                    repeat(updatesPerRound) { index ->
                        publish((round * updatesPerRound + index + 1).toLong())
                    }
                    barrier.await(10L, TimeUnit.SECONDS)
                }
            }
        }
        try {
            repeat(rounds) { round ->
                barrier.await(10L, TimeUnit.SECONDS)
                barrier.await(10L, TimeUnit.SECONDS)
                val expected = ((round + 1) * updatesPerRound).toLong()
                val snapshot = withTimeout(10_000L) { wake.await() }
                assertEquals(
                    SessionPendingMirrorWake.Snapshot(expected, expected, expected, expected),
                    snapshot,
                    "after all publishers complete, conflation must retain the latest authoritative state",
                )
            }
            tasks.forEach { it.get(10L, TimeUnit.SECONDS) }
        } finally {
            wake.close()
            tasks.forEach { it.cancel(true) }
            executor.shutdownNow()
            executor.awaitTermination(10L, TimeUnit.SECONDS)
        }
    }
}
