package com.virjar.tk.server.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerResourceOwnerTest {
    @Test
    fun `resources close once in reverse acquisition order`() {
        val closed = mutableListOf<String>()
        val owner = ServerResourceOwner { _, error -> throw error }
        owner.own("database") { closed += "database" }
        owner.own("registry") { closed += "registry" }
        owner.own("tcp") { closed += "tcp" }

        owner.close()
        owner.close()

        assertEquals(listOf("tcp", "registry", "database"), closed)
    }

    @Test
    fun `one close failure does not strand older resources`() {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val owner = ServerResourceOwner { name, _ -> failures += name }
        owner.own("database") { closed += "database" }
        owner.own("broken") {
            closed += "broken"
            error("close failed")
        }
        owner.own("tcp") { closed += "tcp" }

        val failure = assertFailsWith<ServerResourceCloseException> { owner.close() }

        assertEquals(listOf("tcp", "broken", "database"), closed)
        assertEquals(listOf("broken"), failures)
        assertEquals(listOf("broken"), failure.failures.map(ServerResourceCloseFailure::resourceName))
    }

    @Test
    fun `all ordinary close failures are reported after the complete reverse drain`() {
        val closed = mutableListOf<String>()
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("database") {
            closed += "database"
            throw IllegalStateException("database close")
        }
        owner.own("registry") {
            closed += "registry"
            throw IllegalArgumentException("registry close")
        }

        val failure = assertFailsWith<ServerResourceCloseException> { owner.close() }

        assertEquals(listOf("registry", "database"), closed)
        assertEquals(listOf("registry", "database"), failure.failures.map { it.resourceName })
        assertEquals(1, failure.suppressed.size)
    }

    @Test
    fun `diagnostic callback rethrowing the same error cannot interrupt cleanup`() {
        val closed = mutableListOf<String>()
        val owner = ServerResourceOwner { _, error -> throw error }
        owner.own("database") { closed += "database" }
        owner.own("broken") {
            closed += "broken"
            error("close failed")
        }
        owner.own("tcp") { closed += "tcp" }

        assertFailsWith<ServerResourceCloseException> { owner.close() }
        assertEquals(listOf("tcp", "broken", "database"), closed)
    }

    @Test
    fun `fatal close failure propagates only after remaining resources are released`() {
        val closed = mutableListOf<String>()
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("database") { closed += "database" }
        owner.own("native") {
            closed += "native"
            throw AssertionError("fatal native close")
        }
        owner.own("tcp") { closed += "tcp" }

        assertFailsWith<AssertionError> { owner.close() }
        assertEquals(listOf("tcp", "native", "database"), closed)
    }

    @Test
    fun `fatal diagnostic replaces ordinary close failure only after complete drain`() {
        val ordinary = IllegalStateException("ordinary close")
        val fatal = AssertionError("diagnostic defect")
        val closed = mutableListOf<String>()
        val owner = ServerResourceOwner { name, error ->
            assertEquals("registry", name)
            assertSame(ordinary, error)
            throw fatal
        }
        owner.own("database") { closed += "database" }
        owner.own("registry") {
            closed += "registry"
            throw ordinary
        }
        owner.own("tcp") { closed += "tcp" }

        val observed = assertFailsWith<AssertionError> { owner.close() }

        assertSame(fatal, observed)
        assertEquals(listOf("tcp", "registry", "database"), closed)
        assertTrue(observed.suppressed.any { it === ordinary })
        assertSame(observed, assertFailsWith<AssertionError> { owner.close() })
    }

    @Test
    fun `fatal close remains primary when diagnostic throws ordinary exception`() {
        val fatal = AssertionError("native close defect")
        val diagnostic = IllegalStateException("logger unavailable")
        val owner = ServerResourceOwner { _, error ->
            assertSame(fatal, error)
            throw diagnostic
        }
        owner.own("native") { throw fatal }

        val observed = assertFailsWith<AssertionError> { owner.close() }

        assertSame(fatal, observed)
        assertTrue(observed.suppressed.any { it === diagnostic })
        assertSame(observed, assertFailsWith<AssertionError> { owner.close() })
    }

    @Test
    fun `concurrent close follower waits for successful owner drain`() {
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val followerDone = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val firstFailure = AtomicReference<Throwable?>(null)
        val followerFailure = AtomicReference<Throwable?>(null)
        val owner = ServerResourceOwner { _, error -> throw error }
        owner.own("blocking") {
            closeCount.incrementAndGet()
            enteredClose.countDown()
            check(releaseClose.await(5, TimeUnit.SECONDS)) { "test did not release close" }
        }

        val first = thread(isDaemon = true, name = "server-resource-owner-first-close") {
            firstFailure.set(runCatching { owner.close() }.exceptionOrNull())
            firstDone.countDown()
        }
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS))
        val follower = thread(isDaemon = true, name = "server-resource-owner-follow-close") {
            followerFailure.set(runCatching { owner.close() }.exceptionOrNull())
            followerDone.countDown()
        }

        try {
            assertTrue(awaitWaiting(follower), "concurrent close must wait for the elected owner")
            assertFalse(followerDone.await(0, TimeUnit.MILLISECONDS))
        } finally {
            releaseClose.countDown()
            assertTrue(firstDone.await(5, TimeUnit.SECONDS))
            assertTrue(followerDone.await(5, TimeUnit.SECONDS))
            first.join(5_000)
            follower.join(5_000)
        }

        assertFalse(first.isAlive)
        assertFalse(follower.isAlive)
        assertNull(firstFailure.get())
        assertNull(followerFailure.get())
        assertEquals(1, closeCount.get())
        owner.close()
    }

    @Test
    fun `concurrent and repeated close replay the same terminal failure object`() {
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val followerDone = CountDownLatch(1)
        val closeFailure = IllegalStateException("close failed")
        val firstFailure = AtomicReference<Throwable?>(null)
        val followerFailure = AtomicReference<Throwable?>(null)
        val owner = ServerResourceOwner { _, _ -> }
        owner.own("blocking") {
            enteredClose.countDown()
            check(releaseClose.await(5, TimeUnit.SECONDS)) { "test did not release close" }
            throw closeFailure
        }

        val first = thread(isDaemon = true, name = "server-resource-owner-failing-close") {
            firstFailure.set(runCatching { owner.close() }.exceptionOrNull())
            firstDone.countDown()
        }
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS))
        val follower = thread(isDaemon = true, name = "server-resource-owner-failing-follower") {
            followerFailure.set(runCatching { owner.close() }.exceptionOrNull())
            followerDone.countDown()
        }

        try {
            assertTrue(awaitWaiting(follower), "failed close follower must wait for terminal publication")
            assertFalse(followerDone.await(0, TimeUnit.MILLISECONDS))
        } finally {
            releaseClose.countDown()
            assertTrue(firstDone.await(5, TimeUnit.SECONDS))
            assertTrue(followerDone.await(5, TimeUnit.SECONDS))
            first.join(5_000)
            follower.join(5_000)
        }

        val terminal = assertIs<ServerResourceCloseException>(firstFailure.get())
        assertSame(terminal, followerFailure.get())
        assertSame(terminal, assertFailsWith<ServerResourceCloseException> { owner.close() })
        assertSame(closeFailure, terminal.failures.single().error)
    }

    @Test
    fun `late ownership closes immediately and preserves fatal diagnostic`() {
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val ownerDone = CountDownLatch(1)
        val lateClosed = AtomicInteger()
        val ordinary = IllegalStateException("late close failed")
        val fatal = AssertionError("late close diagnostic defect")
        val ownerFailure = AtomicReference<Throwable?>(null)
        val owner = ServerResourceOwner { name, error ->
            if (name == "late") {
                assertSame(ordinary, error)
                throw fatal
            }
        }
        owner.own("blocking") {
            enteredClose.countDown()
            check(releaseClose.await(5, TimeUnit.SECONDS)) { "test did not release close" }
        }
        val closer = thread(isDaemon = true, name = "server-resource-owner-late-own") {
            ownerFailure.set(runCatching { owner.close() }.exceptionOrNull())
            ownerDone.countDown()
        }
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS))

        try {
            var successfulLateClosed = false
            assertFailsWith<IllegalStateException> {
                owner.own("late-success") { successfulLateClosed = true }
            }
            assertTrue(successfulLateClosed)

            val observed = assertFailsWith<AssertionError> {
                owner.own("late") {
                    lateClosed.incrementAndGet()
                    throw ordinary
                }
            }
            assertSame(fatal, observed)
            assertEquals(1, lateClosed.get())
            assertTrue(observed.suppressed.any { it === ordinary })
            assertTrue(observed.suppressed.any { it is IllegalStateException && it !== ordinary })
        } finally {
            releaseClose.countDown()
            assertTrue(ownerDone.await(5, TimeUnit.SECONDS))
            closer.join(5_000)
        }

        assertNull(ownerFailure.get())
        owner.close()
    }

    @Test
    fun `sync dispatcher is closed before the client registry it delivers through`() {
        val closed = mutableListOf<String>()
        var dispatcherClosed = false
        val owner = ServerResourceOwner { _, error -> throw error }
        // 应用按依赖顺序获取这些资源；ServerResourceOwner 按相反顺序关闭。
        owner.own("client registry") {
            assertTrue(dispatcherClosed, "dispatcher must not retain work after registry shutdown")
            closed += "client registry"
        }
        owner.own("sync event dispatcher") {
            dispatcherClosed = true
            closed += "sync event dispatcher"
        }
        owner.own("tcp server") { closed += "tcp server" }

        owner.close()

        assertEquals(listOf("tcp server", "sync event dispatcher", "client registry"), closed)
    }

    @Test
    fun `closed owner rejects newly acquired resources`() {
        var lateClosed = false
        val owner = ServerResourceOwner { _, error -> throw error }
        owner.close()

        assertFailsWith<IllegalStateException> {
            owner.own("late") { lateClosed = true }
        }
        assertTrue(lateClosed)
    }

    private fun awaitWaiting(target: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && target.isAlive) {
            if (target.state == Thread.State.WAITING) return true
            Thread.yield()
        }
        return target.state == Thread.State.WAITING
    }
}
