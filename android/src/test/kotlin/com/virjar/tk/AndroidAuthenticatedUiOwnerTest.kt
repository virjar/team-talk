package com.virjar.tk

import com.virjar.tk.client.SessionEndReason
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidAuthenticatedUiOwnerTest {
    @Test
    fun `retirement reasons preserve only the intended draft scope`() {
        assertEquals(
            AndroidUiRetirementPolicy.DISCARD_DRAFTS,
            SessionEndReason.USER_LOGOUT.androidUiRetirementPolicy(),
        )
        assertEquals(
            AndroidUiRetirementPolicy.PRESERVE_DURABLE_DRAFTS,
            SessionEndReason.AUTH_REVOKED.androidUiRetirementPolicy(),
        )
        assertEquals(
            AndroidUiRetirementPolicy.PRESERVE_DURABLE_DRAFTS,
            SessionEndReason.PROTOCOL_UPGRADE.androidUiRetirementPolicy(),
        )
        assertEquals(
            AndroidUiRetirementPolicy.PRESERVE_SAME_USER_CONTINUATION,
            SessionEndReason.PROCESS_REPLACED.androidUiRetirementPolicy(),
        )
        assertEquals(
            AndroidUiRetirementPolicy.PRESERVE_SAME_USER_CONTINUATION,
            SessionEndReason.SHUTDOWN.androidUiRetirementPolicy(),
        )
    }

    @Test
    fun `session gate retires only the exact owner once`() {
        data class EqualOwner(val id: Int)

        val gate = AndroidSessionOwnerGate<EqualOwner>()
        val exactOwner = EqualOwner(7)
        val equalButDifferentOwner = EqualOwner(7)
        var transitionOwner: EqualOwner? = null
        var retireCalls = 0

        assertEquals("ready", gate.replaceOwner(exactOwner) { previous ->
            transitionOwner = previous
            "ready"
        })
        assertNull(transitionOwner)
        assertFalse(gate.retireIfOwner(equalButDifferentOwner) { retireCalls += 1 })
        assertTrue(gate.retireIfOwner(exactOwner) { retireCalls += 1 })
        assertFalse(gate.retireIfOwner(exactOwner) { retireCalls += 1 })
        assertEquals(1, retireCalls)
    }

    @Test
    fun `session replacement publishes the new identity after transition`() {
        val gate = AndroidSessionOwnerGate<Any>()
        val first = Any()
        val second = Any()
        gate.replaceOwner(first) {}

        var observed: Any? = null
        gate.replaceOwner(second) { previous -> observed = previous }

        assertSame(first, observed)
        assertFalse(gate.retireIfOwner(first) {})
        assertTrue(gate.retireIfOwner(second) {})
    }

    @Test
    fun `sealed owner never invokes a late acquisition factory`() {
        val owner = AndroidAuthenticatedResourceOwner()
        val createCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        assertTrue(owner.closeAll().isEmpty())

        val lease = owner.acquire {
            createCalls.incrementAndGet()
            RecordingCloseable(closeCalls = closeCalls)
        }

        assertEquals(0, createCalls.get())
        assertEquals(0, closeCalls.get())
        assertNull(lease.resourceOrNull())
        lease.close()
        assertEquals(0, closeCalls.get())
    }

    @Test
    fun `owner close is best effort and lease disposal stays idempotent`() {
        val closeOrder = mutableListOf<String>()
        val owner = AndroidAuthenticatedResourceOwner()
        val first = owner.acquire {
            RecordingCloseable("first", closeOrder, failure = IllegalStateException("first failed"))
        }
        val second = owner.acquire {
            RecordingCloseable("second", closeOrder, failure = IllegalArgumentException("second failed"))
        }

        val failures = owner.closeAll()

        assertEquals(listOf("first", "second"), closeOrder)
        assertEquals(2, failures.size)
        assertNull(first.resourceOrNull())
        assertNull(second.resourceOrNull())
        first.close()
        second.close()
        assertTrue(owner.closeAll().isEmpty())
        assertEquals(listOf("first", "second"), closeOrder)
    }

    @Test
    fun `owner sealing joins an acquisition factory that already holds admission`() {
        val owner = AndroidAuthenticatedResourceOwner()
        val createEntered = CountDownLatch(1)
        val allowCreateToFinish = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val lease = AtomicReference<AndroidAuthenticatedResourceLease<RecordingCloseable>>()
        val failure = AtomicReference<Throwable?>()
        val acquireThread = thread(name = "android-resource-acquire") {
            runCatching {
                owner.acquire {
                    createEntered.countDown()
                    allowCreateToFinish.await()
                    RecordingCloseable(closeCalls = closeCalls)
                }
            }.onSuccess(lease::set).onFailure(failure::set)
        }
        assertTrue(createEntered.await(5, TimeUnit.SECONDS))
        val closeThread = thread(name = "android-resource-owner-close") {
            closeAttempted.countDown()
            runCatching(owner::closeAll).onFailure(failure::set)
            closeFinished.countDown()
        }
        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))

        allowCreateToFinish.countDown()
        acquireThread.join(5_000)
        closeThread.join(5_000)

        assertFalse(acquireThread.isAlive)
        assertFalse(closeThread.isAlive)
        failure.get()?.let { throw AssertionError("concurrent owner operation failed", it) }
        assertTrue(closeFinished.await(0, TimeUnit.MILLISECONDS))
        assertEquals(1, closeCalls.get())
        val acquiredLease = requireNotNull(lease.get())
        assertNull(acquiredLease.resourceOrNull())
        acquiredLease.close()
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun `concurrent lease disposal and owner sealing close once`() {
        repeat(64) {
            val owner = AndroidAuthenticatedResourceOwner()
            val closeCalls = AtomicInteger()
            val lease = owner.acquire { RecordingCloseable(closeCalls = closeCalls) }
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val disposeThread = thread(name = "android-resource-dispose") {
                ready.countDown()
                start.await()
                runCatching(lease::close).exceptionOrNull()?.let(failures::add)
            }
            val closeThread = thread(name = "android-resource-owner-close") {
                ready.countDown()
                start.await()
                runCatching(owner::closeAll).exceptionOrNull()?.let(failures::add)
            }
            ready.await()
            start.countDown()
            disposeThread.join()
            closeThread.join()

            assertTrue(failures.isEmpty())
            assertEquals(1, closeCalls.get())
            assertNull(lease.resourceOrNull())
        }
    }

    @Test
    fun `media resources close controllers then voice then media and keep going after failures`() {
        val order = mutableListOf<String>()
        val controllerFailure = IllegalStateException("controller failed")
        val voiceFailure = IllegalArgumentException("voice failed")
        val mediaFailure = UnsupportedOperationException("media failed")

        val failure = assertFailsWith<AndroidAuthenticatedResourceCloseException> {
            closeAndroidAuthenticatedMediaResources(
                closeControllers = {
                    order += "controllers"
                    throw controllerFailure
                },
                stopVoice = {
                    order += "voice"
                    throw voiceFailure
                },
                closeMedia = {
                    order += "media"
                    throw mediaFailure
                },
            )
        }

        assertEquals(listOf("controllers", "voice", "media"), order)
        assertEquals(3, failure.failures.size)
        assertSame(controllerFailure, failure.failures[0])
        assertSame(voiceFailure, failure.failures[1])
        assertSame(mediaFailure, failure.failures[2])
    }

    private class RecordingCloseable(
        private val label: String? = null,
        private val closeOrder: MutableList<String>? = null,
        private val closeCalls: AtomicInteger = AtomicInteger(),
        private val failure: Throwable? = null,
    ) : AutoCloseable {
        override fun close() {
            closeCalls.incrementAndGet()
            label?.let { closeOrder?.add(it) }
            failure?.let { throw it }
        }
    }
}
