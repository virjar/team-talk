package com.virjar.tk.shared.bot

import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.TEST_SYNC_DATASET_ID
import com.virjar.tk.shared.client.UserSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImBotAuthResultAdmissionTest {
    @Test
    fun `final bearer CAS ignores a 401 after reconnect installed a successor`() {
        val admission = ImBotAuthResultAdmission()
        val userSession = authenticatedSession("access-old")
        val lifecycle = ImBotAuthenticationLifecycle(userSession, admission)
        var cleanupCalls = 0
        lifecycle.bindTerminalHandler { cleanupCalls += 1 }

        admission.use {
            userSession.onAuthSuccess(
                "uid-a", "bot-a", "Bot A", "refresh-a", "access-new", TEST_SYNC_DATASET_ID,
            )
        }
        lifecycle.reportHttpUnauthorized("access-old")

        assertEquals("access-new", userSession.accessToken)
        assertEquals("uid-a", userSession.uid)
        assertEquals(0, cleanupCalls)
        assertNull(lifecycle.terminal.value)
        admission.use { userSession.onAuthAttemptFailed("retryable") }
    }

    @Test
    fun `blocked rotation wins over delayed 401 at the shared auth admission`() {
        val admission = ImBotAuthResultAdmission()
        val userSession = authenticatedSession("access-old")
        val lifecycle = ImBotAuthenticationLifecycle(userSession, admission)
        val durableHookEntered = CountDownLatch(1)
        val releaseDurableHook = CountDownLatch(1)
        val rotationFinished = CountDownLatch(1)
        val unauthorizedFinished = CountDownLatch(1)
        var cleanupCalls = 0
        lifecycle.bindTerminalHandler { cleanupCalls += 1 }

        val rotation = thread(name = "imbot-access-rotation") {
            try {
                admission.use {
                    userSession.onAuthSuccess(
                        "uid-a", "bot-a", "Bot A", "refresh-a", "access-new", TEST_SYNC_DATASET_ID,
                    ) {
                        durableHookEntered.countDown()
                        check(releaseDurableHook.await(5, TimeUnit.SECONDS))
                    }
                }
            } finally {
                rotationFinished.countDown()
            }
        }
        assertTrue(durableHookEntered.await(5, TimeUnit.SECONDS))
        val unauthorized = thread(name = "imbot-delayed-401") {
            try {
                lifecycle.reportHttpUnauthorized("access-old")
            } finally {
                unauthorizedFinished.countDown()
            }
        }
        assertFalse(
            unauthorizedFinished.await(100, TimeUnit.MILLISECONDS),
            "401 bypassed the admitted durable credential rotation",
        )

        releaseDurableHook.countDown()
        assertTrue(rotationFinished.await(5, TimeUnit.SECONDS))
        assertTrue(unauthorizedFinished.await(5, TimeUnit.SECONDS))
        rotation.join(5_000)
        unauthorized.join(5_000)

        assertEquals("access-new", userSession.accessToken)
        assertEquals(0, cleanupCalls)
        assertNull(lifecycle.terminal.value)
    }

    @Test
    fun `current bearer 401 retires identity and publishes one terminal after cleanup`() {
        val admission = ImBotAuthResultAdmission()
        val userSession = authenticatedSession("access-current")
        val lifecycle = ImBotAuthenticationLifecycle(userSession, admission)
        val shutdown = ImBotShutdownLifecycle(admission)
        var cleanupCalls = 0
        lifecycle.bindTerminalHandler {
            shutdown.shutdown("cleanup" to { cleanupCalls += 1 })
            lifecycle.publishClaimedTerminalAfterCleanup()
        }

        lifecycle.reportHttpUnauthorized("access-current")
        lifecycle.reportHttpUnauthorized("access-current")

        assertEquals(1, cleanupCalls)
        assertEquals("", userSession.uid)
        assertNull(userSession.accessToken)
        assertEquals(
            ImBotAuthenticationTerminal.HttpUnauthorized,
            lifecycle.terminal.value,
        )
        assertEquals(
            SessionEndReason.AUTH_REVOKED,
            lifecycle.effectiveEndReason(SessionEndReason.SHUTDOWN),
        )
        assertFailsWith<IllegalStateException> {
            admission.use { userSession.onAuthAttemptFailed("late auth") }
        }
    }

    @Test
    fun `established retryable auth failure preserves graph while terminal rejection closes it once`() {
        val admission = ImBotAuthResultAdmission()
        val userSession = authenticatedSession("access-a")
        val lifecycle = ImBotAuthenticationLifecycle(userSession, admission)
        var cleanupCalls = 0
        lifecycle.bindTerminalHandler {
            cleanupCalls += 1
            lifecycle.publishClaimedTerminalAfterCleanup()
        }

        lifecycle.reportAuthenticationFailure(
            AuthenticationFailureKind.SERVER_MAINTENANCE,
            "maintenance",
        )
        assertEquals("uid-a", userSession.uid)
        assertNull(userSession.accessToken)
        assertNull(lifecycle.terminal.value)

        admission.use {
            userSession.onAuthSuccess(
                "uid-a", "bot-a", "Bot A", "refresh-a", "access-b", TEST_SYNC_DATASET_ID,
            )
        }
        lifecycle.reportAuthenticationFailure(AuthenticationFailureKind.DEVICE_BANNED, "banned")
        lifecycle.reportAuthenticationFailure(AuthenticationFailureKind.REJECTED, "late")

        assertEquals(1, cleanupCalls)
        assertEquals("", userSession.uid)
        assertEquals(
            ImBotAuthenticationTerminal.AuthResponseRejected(AuthenticationFailureKind.DEVICE_BANNED),
            lifecycle.terminal.value,
        )
    }

    @Test
    fun `terminal claimed during construction is delivered once when bot owner binds`() {
        val admission = ImBotAuthResultAdmission()
        val userSession = authenticatedSession("access-a")
        val lifecycle = ImBotAuthenticationLifecycle(userSession, admission)
        var cleanupCalls = 0

        lifecycle.reportAuthenticationFailure(AuthenticationFailureKind.REJECTED, "rejected")
        assertNull(lifecycle.terminal.value, "terminal is not public before its resource owner drains")

        lifecycle.bindTerminalHandler {
            cleanupCalls += 1
            lifecycle.publishClaimedTerminalAfterCleanup()
        }

        assertEquals(1, cleanupCalls)
        assertEquals(
            ImBotAuthenticationTerminal.AuthResponseRejected(AuthenticationFailureKind.REJECTED),
            lifecycle.terminal.value,
        )
    }

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
                        datasetId = TEST_SYNC_DATASET_ID,
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
                userSession.onAuthSuccess(
                    "uid-b", "bot-b", "Bot B", "refresh-b", "access-b", TEST_SYNC_DATASET_ID,
                )
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
                    datasetId = TEST_SYNC_DATASET_ID,
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

    @Test
    fun `fatal cleanup still closes lifecycle exactly once and replays the same object`() {
        val lifecycle = ImBotShutdownLifecycle(ImBotAuthResultAdmission())
        val fatal = AssertionError("fatal cleanup")
        var cleanupCalls = 0

        val first = assertFailsWith<AssertionError> {
            lifecycle.shutdown("fatal" to {
                cleanupCalls += 1
                throw fatal
            })
        }
        val replay = assertFailsWith<AssertionError> {
            lifecycle.shutdown("fatal" to { cleanupCalls += 1 })
        }

        assertSame(fatal, first)
        assertSame(fatal, replay)
        assertEquals(1, cleanupCalls)
    }

    private fun authenticatedSession(accessToken: String) = UserSession().apply {
        onAuthSuccess(
            "uid-a", "bot-a", "Bot A", "refresh-a", accessToken, TEST_SYNC_DATASET_ID,
        )
    }
}
