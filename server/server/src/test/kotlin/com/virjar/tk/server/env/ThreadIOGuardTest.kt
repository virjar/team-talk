package com.virjar.tk.server.env

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ThreadIOGuardTest {
    @Test
    fun `nested owners keep protection until the final matching release`() {
        ThreadIOGuard.protectCurrentThread()
        try {
            assertFailsWith<BlockingIoOnProtectedThreadException> {
                ThreadIOGuard.check("first protected operation")
            }

            ThreadIOGuard.protectCurrentThread()
            try {
                assertFailsWith<BlockingIoOnProtectedThreadException> {
                    ThreadIOGuard.check("nested protected operation")
                }
            } finally {
                ThreadIOGuard.unprotectCurrentThread()
            }

            assertFailsWith<BlockingIoOnProtectedThreadException> {
                ThreadIOGuard.check("outer owner still active")
            }
        } finally {
            ThreadIOGuard.unprotectCurrentThread()
        }

        ThreadIOGuard.check("ordinary worker operation")
        assertFailsWith<IllegalStateException> {
            ThreadIOGuard.unprotectCurrentThread()
        }
    }

    @Test
    fun `protection is confined to its owning thread`() {
        val otherThreadFailure = AtomicReference<Throwable?>(null)
        ThreadIOGuard.protectCurrentThread()
        try {
            assertIs<BlockingIoOnProtectedThreadException>(
                runCatching { ThreadIOGuard.check("owner operation") }.exceptionOrNull(),
            )

            val ordinaryWorker = Thread(
                { otherThreadFailure.set(runCatching { ThreadIOGuard.check("worker operation") }.exceptionOrNull()) },
                "thread-io-guard-ordinary-worker-test",
            )
            ordinaryWorker.start()
            ordinaryWorker.join(5_000)

            assertFalse(ordinaryWorker.isAlive)
            assertNull(otherThreadFailure.get())
        } finally {
            ThreadIOGuard.unprotectCurrentThread()
        }
    }
}
