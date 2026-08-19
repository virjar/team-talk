package com.virjar.tk.protocol.codec

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImAgentAuthStateTest {

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
        assertTrue(state.markAuthenticated())
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
    fun `disconnected authentication cannot be completed by stale worker`() {
        val state = ImAgentAuthState()

        assertEquals(ImAgentAuthAdmission.ACCEPT, state.admitAuthentication())
        assertEquals(ImAgent.State.AUTHENTICATING, state.disconnect())
        assertEquals(ImAgent.State.DISCONNECTED, state.current)
        assertFalse(state.markAuthenticated())
    }
}
