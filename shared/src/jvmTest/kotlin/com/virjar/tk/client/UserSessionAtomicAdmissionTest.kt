package com.virjar.tk.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserSessionAtomicAdmissionTest {
    @Test
    fun `durable failure leaves the complete prior identity unchanged`() {
        val session = UserSession().apply {
            onAuthSuccess("uid-a", "a", "Account A", "refresh-a", "access-a")
        }

        assertFailsWith<CredentialWriteFailure> {
            session.onAuthSuccess("uid-a", "a2", "Account A2", "refresh-a2", "access-a2") {
                throw CredentialWriteFailure()
            }
        }

        assertEquals("uid-a", session.uid)
        assertEquals("a", session.username)
        assertEquals("Account A", session.name)
        assertEquals("refresh-a", session.refreshToken)
        assertEquals("access-a", session.accessToken)
    }

    @Test
    fun `logout waits for admitted durable commit then clears the published identity`() {
        val session = UserSession()
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val authFinished = CountDownLatch(1)
        val logoutFinished = CountDownLatch(1)
        val logoutReturnedEarly = AtomicBoolean(false)

        val authThread = thread(name = "auth-commit") {
            try {
                session.onAuthSuccess("uid-a", "a", "Account A", "refresh-a", "access-a") {
                    commitEntered.countDown()
                    check(releaseCommit.await(5, TimeUnit.SECONDS))
                }
            } finally {
                authFinished.countDown()
            }
        }
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS))

        val logoutThread = thread(name = "auth-logout") {
            session.onAuthFailed(null)
            logoutFinished.countDown()
        }
        logoutReturnedEarly.set(logoutFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(logoutReturnedEarly.get(), "logout returned inside an admitted credential commit")

        releaseCommit.countDown()
        assertTrue(authFinished.await(5, TimeUnit.SECONDS))
        assertTrue(logoutFinished.await(5, TimeUnit.SECONDS))
        authThread.join(5_000)
        logoutThread.join(5_000)

        assertEquals("", session.uid)
        assertEquals(null, session.refreshToken)
        assertEquals(null, session.accessToken)
    }

    private class CredentialWriteFailure : RuntimeException()
}
