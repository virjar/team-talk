package com.virjar.tk.server.protocol.executor

import com.virjar.tk.server.env.BlockingIoOnProtectedThreadException
import com.virjar.tk.server.env.ThreadIOGuard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlockingIoGuardThreadFactoryTest {
    @Test
    fun `delegate command is protected and exceptional exit always releases thread state`() {
        val commandGuardFailure = AtomicReference<Throwable?>(null)
        val commandFailure = AtomicReference<Throwable?>(null)
        val postCommandGuardFailure = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val marker = DeliberateCommandFailure()
        val observingDelegate = ThreadFactory { guardedCommand ->
            Thread(
                {
                    commandFailure.set(runCatching { guardedCommand.run() }.exceptionOrNull())
                    postCommandGuardFailure.set(
                        runCatching { ThreadIOGuard.check("delegate cleanup probe") }.exceptionOrNull(),
                    )
                    finished.countDown()
                },
                "blocking-io-guard-thread-factory-test",
            )
        }
        val factory = BlockingIoGuardThreadFactory(observingDelegate)

        val thread = factory.newThread {
            commandGuardFailure.set(
                runCatching { ThreadIOGuard.check("guarded command probe") }.exceptionOrNull(),
            )
            throw marker
        }
        thread.start()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertIs<BlockingIoOnProtectedThreadException>(commandGuardFailure.get())
        assertSame(marker, commandFailure.get())
        assertNull(postCommandGuardFailure.get())
    }

    private class DeliberateCommandFailure : RuntimeException()
}
