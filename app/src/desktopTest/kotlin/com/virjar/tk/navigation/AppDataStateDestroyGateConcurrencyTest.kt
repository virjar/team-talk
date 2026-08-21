package com.virjar.tk.navigation

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataStateDestroyGateConcurrencyTest {
    @Test
    fun `concurrent follower joins complete best effort cleanup`() {
        val gate = AppDataStateDestroyGate()
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val followerAttempting = CountDownLatch(1)
        val followerReturned = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())

        val leader = thread(name = "app-data-destroy-leader") {
            gate.destroy {
                calls += "first-owner"
                cleanupEntered.countDown()
                assertTrue(allowCleanup.await(5, TimeUnit.SECONDS))
                calls += "second-owner"
                emptyList()
            }
        }
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))

        val follower = thread(name = "app-data-destroy-follower") {
            followerAttempting.countDown()
            gate.destroy {
                calls += "unexpected-repeat"
                emptyList()
            }
            followerReturned.countDown()
        }

        assertTrue(followerAttempting.await(5, TimeUnit.SECONDS))
        assertFalse(followerReturned.await(150, TimeUnit.MILLISECONDS))
        allowCleanup.countDown()
        leader.join(5_000)
        follower.join(5_000)

        assertFalse(leader.isAlive)
        assertFalse(follower.isAlive)
        assertTrue(followerReturned.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("first-owner", "second-owner"), calls)
    }
}
