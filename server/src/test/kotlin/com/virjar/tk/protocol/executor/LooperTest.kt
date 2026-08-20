package com.virjar.tk.protocol.executor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LooperTest {
    @Test
    fun `stopped looper rejects new synchronous and asynchronous work`() = runBlocking {
        val looper = Looper("looper-stop-test").apply { start() }
        assertEquals(42, withTimeout(1_000) { looper.suspendAwait { 42 } })

        looper.stop()

        assertFalse(looper.post { error("stopped looper must not run posted work") })
        assertFailsWith<IllegalStateException> {
            withTimeout(1_000) { looper.suspendAwait { error("must not run") } }
        }
    }
}
