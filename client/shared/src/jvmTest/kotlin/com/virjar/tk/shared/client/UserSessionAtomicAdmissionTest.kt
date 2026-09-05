package com.virjar.tk.shared.client

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
    fun `persisted login opens an offline identity without granting HTTP access`() {
        val session = UserSession().apply {
            restorePersistedLogin("uid-a", "refresh-a", TEST_SYNC_DATASET_ID)
        }

        assertEquals("uid-a", session.uid)
        assertEquals("refresh-a", session.refreshToken)
        assertEquals(null, session.username)
        assertEquals(null, session.name)
        assertEquals(null, session.accessToken)

        session.onAuthSuccess(
            "uid-a", "alice", "Account A", "refresh-b", "access-b", TEST_SYNC_DATASET_ID,
        )
        assertEquals("refresh-b", session.refreshToken)
        assertEquals("access-b", session.accessToken)
    }

    @Test
    fun `restored offline identity rejects a different authenticated uid`() {
        val session = UserSession().apply {
            restorePersistedLogin("uid-a", "refresh-a", TEST_SYNC_DATASET_ID)
        }

        assertFailsWith<IllegalStateException> {
            session.onAuthSuccess(
                "uid-b", "bob", "Account B", "refresh-b", "access-b", TEST_SYNC_DATASET_ID,
            )
        }
        assertEquals("uid-a", session.uid)
        assertEquals(null, session.accessToken)
    }

    @Test
    fun `durable failure preserves offline graph and later auth rotates its bearer`() {
        val session = UserSession().apply {
            onAuthSuccess(
                "uid-a", "a", "Account A", "refresh-a", "access-a", TEST_SYNC_DATASET_ID,
            )
        }
        val before = session.httpCredentialsSnapshot()

        assertFailsWith<CredentialWriteFailure> {
            session.onAuthSuccess(
                "uid-a", "a2", "Account A2", "refresh-a2", "access-a2", TEST_SYNC_DATASET_ID,
            ) {
                throw CredentialWriteFailure()
            }
        }

        assertEquals("uid-a", session.uid)
        assertEquals("a", session.username)
        assertEquals("Account A", session.name)
        assertEquals("refresh-a", session.refreshToken)
        assertEquals(null, session.accessToken)
        assertEquals(LOCAL_CREDENTIAL_COMMIT_FAILURE_REASON, session.authFailureReason)
        assertEquals(before.identityEpoch, session.httpCredentialsSnapshot().identityEpoch)

        session.onAuthSuccess(
            "uid-a", "a2", "Account A2", "refresh-a2", "access-a2", TEST_SYNC_DATASET_ID,
        )
        assertEquals(before.identityEpoch, session.httpCredentialsSnapshot().identityEpoch)
        assertEquals("access-a2", session.accessToken)
    }

    @Test
    fun `dataset replacement atomically retires old HTTP resource epoch`() {
        val session = UserSession().apply {
            restorePersistedLogin("uid-a", "refresh-a", TEST_SYNC_DATASET_ID)
        }
        val offlineOwner = session.httpCredentialsSnapshot()

        session.onAuthSuccess(
            "uid-a",
            "alice",
            "Account A",
            "refresh-b",
            "access-b",
            OTHER_TEST_SYNC_DATASET_ID,
        )

        val rebuiltOwner = session.httpCredentialsSnapshot()
        assertEquals(offlineOwner.identityEpoch + 1L, rebuiltOwner.identityEpoch)
        assertEquals("uid-a", rebuiltOwner.uid)
        assertEquals("access-b", rebuiltOwner.accessToken)
        assertEquals(OTHER_TEST_SYNC_DATASET_ID, session.datasetId)
    }

    @Test
    fun `same dataset bearer rotation keeps existing HTTP resource epoch`() {
        val session = UserSession().apply {
            restorePersistedLogin("uid-a", "refresh-a", TEST_SYNC_DATASET_ID)
        }
        val offlineOwner = session.httpCredentialsSnapshot()

        session.onAuthSuccess(
            "uid-a",
            "alice",
            "Account A",
            "refresh-b",
            "access-b",
            TEST_SYNC_DATASET_ID,
        )

        assertEquals(offlineOwner.identityEpoch, session.httpCredentialsSnapshot().identityEpoch)
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
                session.onAuthSuccess(
                    "uid-a", "a", "Account A", "refresh-a", "access-a", TEST_SYNC_DATASET_ID,
                ) {
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
