package com.virjar.tk

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAuthenticatedUiRetirementTest {
    @Test
    fun `logout retires view models and platform resources before authenticated session`() {
        val calls = mutableListOf<String>()
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = { calls += "navigation" },
            closePlatformResources = { calls += "platform" },
        )

        retirement.beforeSessionRetirement()
        calls += "user-session"
        retirement.afterSessionRetirement()

        assertEquals(listOf("navigation", "platform", "user-session"), calls)
    }

    @Test
    fun `authentication expiry uses the same owner order`() {
        val calls = mutableListOf<String>()
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = { calls += "navigation" },
            closePlatformResources = { calls += "platform" },
        )

        retirement.beforeSessionRetirement()
        calls += "expired-session"
        retirement.afterSessionRetirement()

        assertEquals(listOf("navigation", "platform", "expired-session"), calls)
    }

    @Test
    fun `tray quit retires the session before exiting`() {
        val calls = mutableListOf<String>()
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = { calls += "navigation" },
            closePlatformResources = { calls += "platform" },
        )

        retirement.beforeSessionRetirement()
        calls += "user-session"
        retirement.afterSessionRetirement()
        calls += "exit"

        assertEquals(listOf("navigation", "platform", "user-session", "exit"), calls)
    }

    @Test
    fun `duplicate boundary and later disposal cannot repeat platform retirement`() {
        val calls = mutableListOf<String>()
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = { calls += "navigation" },
            closePlatformResources = { calls += "platform" },
        )

        retirement.beforeSessionRetirement()
        calls += "session"
        retirement.afterSessionRetirement()
        retirement.beforeSessionRetirement()
        retirement.afterSessionRetirement()

        assertEquals(listOf("navigation", "platform", "session"), calls)
    }

    @Test
    fun `cleanup failure does not skip later owners`() {
        val calls = mutableListOf<String>()
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = {
                calls += "navigation"
                error("navigation cleanup failed")
            },
            closePlatformResources = { calls += "platform" },
        )

        retirement.beforeSessionRetirement()
        calls += "session"
        retirement.afterSessionRetirement()

        assertEquals(listOf("navigation", "platform", "session"), calls)
    }

    @Test
    fun `retirement first terminal close failure cannot escape later composition disposal`() {
        val terminalFailure = IllegalStateException("terminal platform close failure")
        val reportedFailures = mutableListOf<Throwable>()
        var closeCalls = 0
        val closeResources = {
            closeCalls += 1
            throw terminalFailure
        }
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = {},
            closePlatformResources = closeResources,
        )

        retirement.beforeSessionRetirement()
        retirement.afterSessionRetirement()

        disposeDesktopAuthenticatedResources(
            closeResources = closeResources,
            recordFailure = { failure -> reportedFailures += failure },
        )

        assertEquals(2, closeCalls)
        assertEquals(1, reportedFailures.size)
        assertTrue(reportedFailures.single() === terminalFailure)
    }

    @Test
    fun `concurrent auth expiry and tray quit join through session completion`() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val navigationEntered = CountDownLatch(1)
        val allowNavigation = CountDownLatch(1)
        val sessionEntered = CountDownLatch(1)
        val allowSessionCompletion = CountDownLatch(1)
        val followerAttempting = CountDownLatch(1)
        val followerReturned = CountDownLatch(1)
        val retirement = DesktopAuthenticatedUiRetirement(
            destroyNavigation = {
                calls += "navigation"
                navigationEntered.countDown()
                assertTrue(allowNavigation.await(5, TimeUnit.SECONDS))
            },
            closePlatformResources = { calls += "platform" },
        )

        val authExpiry = thread(name = "desktop-auth-expiry") {
            retirement.beforeSessionRetirement()
            calls += "session-start"
            sessionEntered.countDown()
            assertTrue(allowSessionCompletion.await(5, TimeUnit.SECONDS))
            calls += "session-complete"
            retirement.afterSessionRetirement()
        }
        assertTrue(navigationEntered.await(5, TimeUnit.SECONDS))

        val trayQuit = thread(name = "desktop-tray-quit") {
            followerAttempting.countDown()
            retirement.beforeSessionRetirement()
            calls += "follower-session"
            retirement.afterSessionRetirement()
            calls += "exit"
            followerReturned.countDown()
        }
        assertTrue(followerAttempting.await(5, TimeUnit.SECONDS))
        assertFalse(followerReturned.await(150, TimeUnit.MILLISECONDS))

        allowNavigation.countDown()
        assertTrue(sessionEntered.await(5, TimeUnit.SECONDS))
        assertFalse(followerReturned.await(150, TimeUnit.MILLISECONDS))

        allowSessionCompletion.countDown()
        authExpiry.join(5_000)
        trayQuit.join(5_000)

        assertFalse(authExpiry.isAlive)
        assertFalse(trayQuit.isAlive)
        assertTrue(followerReturned.await(1, TimeUnit.SECONDS))
        assertTrue(calls.indexOf("platform") < calls.indexOf("session-start"))
        assertTrue(calls.indexOf("session-complete") < calls.indexOf("follower-session"))
        assertTrue(calls.indexOf("session-complete") < calls.indexOf("exit"))
    }
}
