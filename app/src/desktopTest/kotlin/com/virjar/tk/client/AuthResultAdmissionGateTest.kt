package com.virjar.tk.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthResultAdmissionGateTest {
    @Test
    fun `replacement drains blocked A commit clears it then admits B`() {
        val gate = AuthResultAdmissionGate(initiallyActive = true)
        val holder = AuthCredentialSnapshotHolder(11L, null)
        val session = UserSession()
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val aFinished = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)

        val aThread = thread(name = "auth-a") {
            try {
                gate.use {
                    session.onAuthSuccess("uid-a", "a", "A", "refresh-a", "access-a") {
                        aEntered.countDown()
                        check(releaseA.await(5, TimeUnit.SECONDS))
                        holder.publish(StoredLogin("uid-a", "refresh-a", 11L))
                    }
                }
            } finally {
                aFinished.countDown()
            }
        }
        assertTrue(aEntered.await(5, TimeUnit.SECONDS))

        val replacementThread = thread(name = "auth-replacement") {
            gate.replaceAttempt {
                holder.clear()
                session.onAuthFailed(null)
            }
            replacementFinished.countDown()
        }
        assertFalse(
            replacementFinished.await(100, TimeUnit.MILLISECONDS),
            "replacement crossed a still-admitted A durable commit",
        )

        releaseA.countDown()
        assertTrue(aFinished.await(5, TimeUnit.SECONDS))
        assertTrue(replacementFinished.await(5, TimeUnit.SECONDS))
        aThread.join(5_000)
        replacementThread.join(5_000)

        assertEquals(null, holder.snapshot())
        assertEquals("", session.uid)
        gate.use {
            session.onAuthSuccess("uid-b", "b", "B", "refresh-b", "access-b") {
                holder.publish(StoredLogin("uid-b", "refresh-b", 11L))
            }
        }
        assertEquals("uid-b", session.uid)
        assertEquals("refresh-b", holder.snapshot()?.refreshToken)
    }
}
