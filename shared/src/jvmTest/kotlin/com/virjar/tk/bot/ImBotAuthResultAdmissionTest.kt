package com.virjar.tk.bot

import com.virjar.tk.client.UserSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImBotAuthResultAdmissionTest {
    @Test
    fun `shutdown admission drains blocked durable hook and rejects every later result`() {
        val admission = ImBotAuthResultAdmission()
        val shutdownLifecycle = ImBotShutdownLifecycle(admission)
        val userSession = UserSession()
        val durableHookEntered = CountDownLatch(1)
        val releaseDurableHook = CountDownLatch(1)
        val authFinished = CountDownLatch(1)
        val shutdownAttempted = CountDownLatch(2)
        val shutdownReturned = CountDownLatch(2)
        val durableWrites = AtomicInteger(0)
        val cleanupCalls = AtomicInteger(0)

        val authThread = thread(name = "imbot-auth-result") {
            try {
                admission.use {
                    admitImBotAuthentication(
                        userSession = userSession,
                        uid = "uid-a",
                        username = "bot-a",
                        displayName = "Bot A",
                        refreshToken = "refresh-a",
                        accessToken = "access-a",
                        onRefreshCredentials = { _, _, _ ->
                            durableHookEntered.countDown()
                            check(releaseDurableHook.await(5, TimeUnit.SECONDS))
                            durableWrites.incrementAndGet()
                        },
                    )
                }
            } finally {
                authFinished.countDown()
            }
        }
        assertTrue(durableHookEntered.await(5, TimeUnit.SECONDS))

        val firstShutdown = thread(name = "imbot-shutdown-a") {
            shutdownAttempted.countDown()
            shutdownLifecycle.shutdown("cleanup" to { cleanupCalls.incrementAndGet() })
            shutdownReturned.countDown()
        }
        val secondShutdown = thread(name = "imbot-shutdown-b") {
            shutdownAttempted.countDown()
            shutdownLifecycle.shutdown("cleanup" to { cleanupCalls.incrementAndGet() })
            shutdownReturned.countDown()
        }
        assertTrue(shutdownAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(
            shutdownReturned.await(100, TimeUnit.MILLISECONDS),
            "shutdown returned while the durable credential hook was still admitted",
        )

        releaseDurableHook.countDown()
        assertTrue(authFinished.await(5, TimeUnit.SECONDS))
        assertTrue(shutdownReturned.await(5, TimeUnit.SECONDS))
        authThread.join(5_000)
        firstShutdown.join(5_000)
        secondShutdown.join(5_000)

        assertEquals(1, cleanupCalls.get())
        assertEquals(1, durableWrites.get())
        assertEquals("uid-a", userSession.uid)
        assertFailsWith<IllegalStateException> {
            admission.use {
                durableWrites.incrementAndGet()
                userSession.onAuthSuccess("uid-b", "bot-b", "Bot B", "refresh-b", "access-b")
            }
        }
        assertFalse(
            admission.runIfActive {
                durableWrites.incrementAndGet()
                userSession.onAuthFailed("late failure")
            },
        )
        assertEquals(1, durableWrites.get())
        assertEquals("uid-a", userSession.uid)
    }

    @Test
    fun `shutdown first rejects auth result before durable or live identity publication`() {
        val admission = ImBotAuthResultAdmission()
        val shutdownLifecycle = ImBotShutdownLifecycle(admission)
        val userSession = UserSession()
        var durableWrites = 0

        shutdownLifecycle.shutdown()
        assertFailsWith<IllegalStateException> {
            admission.use {
                admitImBotAuthentication(
                    userSession = userSession,
                    uid = "uid-late",
                    username = "late",
                    displayName = "Late",
                    refreshToken = "refresh-late",
                    accessToken = "access-late",
                    onRefreshCredentials = { _, _, _ -> durableWrites += 1 },
                )
            }
        }

        assertEquals(0, durableWrites)
        assertEquals("", userSession.uid)
        assertEquals(null, userSession.refreshToken)
        assertEquals(null, userSession.accessToken)
    }

    @Test
    fun `reentrant shutdown completes cleanup and every later caller observes the same hard failure`() {
        val admission = ImBotAuthResultAdmission()
        val shutdownLifecycle = ImBotShutdownLifecycle(admission)
        var cleanupCalls = 0

        val firstFailure = assertFailsWith<IllegalStateException> {
            admission.use {
                shutdownLifecycle.shutdown("cleanup" to { cleanupCalls += 1 })
            }
        }
        val laterFailure = assertFailsWith<IllegalStateException> {
            shutdownLifecycle.shutdown("cleanup" to { cleanupCalls += 1 })
        }

        assertSame(firstFailure, laterFailure)
        assertEquals(1, cleanupCalls)
    }
}
