package com.virjar.tk.server.protocol.connection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ImAgentAuthStateTest {

    @Test
    fun `invalid sync cursor reset admits zero on the same connection`() {
        val cursor = ImAgentSyncCursor()

        assertTrue(cursor.admit(Long.MAX_VALUE))
        assertFalse(cursor.admit(Long.MAX_VALUE))
        assertFalse(cursor.admit(0L))

        cursor.reset()

        assertEquals(-1L, cursor.current)
        assertTrue(cursor.admit(0L))
        assertFalse(cursor.admit(0L), "a duplicate zero request still violates page acknowledgement")
        assertTrue(cursor.admit(1L))
        assertFailsWith<IllegalArgumentException> { cursor.admit(-1L) }
    }

    @Test
    fun `one connection accepts only its first authentication attempt`() {
        val state = ImAgentAuthState()

        assertEquals(ImAgentAuthAdmission.ACCEPT, state.admitAuthentication())
        assertEquals(ImAgent.State.AUTHENTICATING, state.current)
        assertEquals(
            ImAgentAuthAdmission.REJECT_AND_CLOSE,
            state.admitAuthentication(),
            "认证处理中必须拒绝后续 AUTH 并断开连接",
        )
        assertTrue(state.markSynchronizing())
        assertEquals(ImAgent.State.SYNCHRONIZING, state.current)
        assertTrue(state.markReady())
        assertEquals(ImAgent.State.AUTHENTICATED, state.current)
        assertEquals(
            ImAgentAuthAdmission.REJECT_AND_CLOSE,
            state.admitAuthentication(),
            "认证成功后必须拒绝后续 AUTH 并断开连接",
        )
    }

    @Test
    fun `concurrent authentication attempts have exactly one winner`() {
        val state = ImAgentAuthState()
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val attempts = List(workers) {
                pool.submit<ImAgentAuthAdmission> {
                    ready.countDown()
                    start.await()
                    state.admitAuthentication()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertEquals(
                1,
                attempts.count { it.get(5, TimeUnit.SECONDS) == ImAgentAuthAdmission.ACCEPT },
            )
            assertEquals(ImAgent.State.AUTHENTICATING, state.current)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `checkpoint and replay share one synchronization operation admission`() {
        val admission = ImAgentSyncOperationAdmission()
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val attempts = List(workers) {
                pool.submit<ImAgentSyncOperationAdmission.Lease?> {
                    ready.countDown()
                    start.await()
                    admission.tryAcquire()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val winners = attempts.mapNotNull { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, winners.size)
            assertNull(admission.tryAcquire())
            winners.single().close()
            winners.single().close()
            assertTrue(admission.tryAcquire() != null)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `disconnected authentication cannot be completed by stale worker`() {
        val state = ImAgentAuthState()

        assertEquals(ImAgentAuthAdmission.ACCEPT, state.admitAuthentication())
        assertEquals(ImAgent.State.AUTHENTICATING, state.disconnect())
        assertEquals(ImAgent.State.DISCONNECTED, state.current)
        assertFalse(state.markSynchronizing())
        assertFalse(state.markReady())
    }
}
