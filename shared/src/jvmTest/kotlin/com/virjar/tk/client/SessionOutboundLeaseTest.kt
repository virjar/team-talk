package com.virjar.tk.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionOutboundLeaseTest {
    @Test
    fun `retire drains admitted actual write and rejects every later write`() {
        val lease = SessionOutboundLease()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val retirementReturned = CountDownLatch(1)
        val writes = mutableListOf<String>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.submit {
                lease.use {
                    writeEntered.countDown()
                    releaseWrite.await(5, TimeUnit.SECONDS)
                    synchronized(writes) { writes += "account-a" }
                    true
                }
            }
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))

            executor.submit {
                lease.retire()
                retirementReturned.countDown()
            }
            assertFalse(
                retirementReturned.await(100, TimeUnit.MILLISECONDS),
                "retirement returned while the EventLoop write still owned admission",
            )

            releaseWrite.countDown()
            assertTrue(retirementReturned.await(5, TimeUnit.SECONDS))
            assertFalse(lease.use { synchronized(writes) { writes += "late" }; true })
            assertEquals(listOf("account-a"), synchronized(writes) { writes.toList() })
        } finally {
            releaseWrite.countDown()
            executor.shutdownNow()
        }
    }
}
