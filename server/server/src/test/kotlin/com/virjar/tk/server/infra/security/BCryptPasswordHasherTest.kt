package com.virjar.tk.server.infra.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BCryptPasswordHasherTest {
    @Test
    fun `hash and verify stay behind owned adapter`() = runBlocking {
        BCryptPasswordHasher(workerCount = 1, queueCapacity = 2, cost = 4).use { hasher ->
            val encoded = hasher.hash("correct horse battery staple")

            assertTrue(hasher.verify("correct horse battery staple", encoded))
            assertFalse(hasher.verify("wrong password", encoded))
            assertFalse(hasher.verify("password", null), "dummy work can never authenticate an identity")
            assertFalse(hasher.verify("anything", "!service-account:v1:invalid"))
        }
    }

    @Test
    fun `persisted verifier above owned cost budget is replaced by dummy work`() = runBlocking {
        val engine = RecordingEngine()
        BCryptPasswordHasher(workerCount = 1, queueCapacity = 1, cost = 4, engine = engine).use { hasher ->
            val attackerControlledCost = "\$2a\$31\$" + "A".repeat(53)

            assertFalse(hasher.verify("candidate", attackerControlledCost))
            assertTrue(engine.verified.single().startsWith("\$2a\$10\$"))
        }
    }

    @Test
    fun `cancelled permit wait keeps exact cancellation object and owner closes after drain`() = runBlocking {
        val cancellation = CancellationException("caller retired")
        val engine = BlockingEngine()
        val hasher = BCryptPasswordHasher(
            workerCount = 1,
            queueCapacity = 1,
            cost = 4,
            engine = engine,
        )
        try {
            val running = async { hasher.hash("first password") }
            assertTrue(withContext(Dispatchers.IO) { engine.entered.await(5, TimeUnit.SECONDS) })
            val queued = async(start = CoroutineStart.UNDISPATCHED) { hasher.hash("second password") }
            val waitingForPermit = async(start = CoroutineStart.UNDISPATCHED) {
                hasher.hash("third password")
            }

            waitingForPermit.cancel(cancellation)
            val observed = try {
                waitingForPermit.await()
                null
            } catch (error: CancellationException) {
                error
            }

            assertSame(cancellation, observed)
            engine.release.countDown()
            running.await()
            queued.await()
        } finally {
            engine.release.countDown()
            hasher.close()
            hasher.close()
        }
    }

    private class BlockingEngine : BCryptEngine {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun hash(rawPassword: String, cost: Int): String {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release BCrypt worker" }
            return "encoded:$rawPassword"
        }

        override fun verify(rawPassword: String, encodedHash: String): Boolean = false
    }

    private class RecordingEngine : BCryptEngine {
        val verified = mutableListOf<String>()

        override fun hash(rawPassword: String, cost: Int): String = error("not used")

        override fun verify(rawPassword: String, encodedHash: String): Boolean {
            verified += encodedHash
            return true
        }
    }
}
