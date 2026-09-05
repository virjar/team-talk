package com.virjar.tk.server.application.admin

import com.virjar.tk.server.domain.auth.CredentialAdministration
import com.virjar.tk.server.domain.auth.PasswordHasher
import com.virjar.tk.server.domain.session.OnlineSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AdminCredentialCommandsTest {
    @Test
    fun `reset hashes before commit and publishes committed fence`() = runTest {
        val order = mutableListOf<String>()
        val credentials = RecordingCredentials(order, committedEpoch = 9L)
        val sessions = RecordingSessions(order)
        val hasher = RecordingHasher(order)

        AdminCredentialCommands(credentials, sessions, hasher)
            .resetPassword("user-1", "replacement-password")

        assertEquals(listOf("hash", "commit", "fence:9"), order)
        assertEquals("derived-password-hash", credentials.receivedHash)
    }

    @Test
    fun `reset reuses shared password rules before starting cpu work`() = runTest {
        val order = mutableListOf<String>()
        val commands = AdminCredentialCommands(
            RecordingCredentials(order, 2L),
            RecordingSessions(order),
            RecordingHasher(order),
        )

        assertFailsWith<IllegalArgumentException> {
            commands.resetPassword("user-1", "short")
        }

        assertEquals(emptyList(), order)
    }

    @Test
    fun `reset hash cancellation preserves exact owner signal and skips mutation`() = runTest {
        val cancellation = CancellationException("admin request retired")
        val order = mutableListOf<String>()
        val commands = AdminCredentialCommands(
            RecordingCredentials(order, 2L),
            RecordingSessions(order),
            RecordingHasher(order, cancellation),
        )

        val observed = try {
            commands.resetPassword("user-1", "replacement-password")
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, observed)
        assertEquals(listOf("hash"), order)
    }

    private class RecordingHasher(
        private val order: MutableList<String>,
        private val failure: CancellationException? = null,
    ) : PasswordHasher {
        override suspend fun hash(rawPassword: String): String {
            order += "hash"
            failure?.let { throw it }
            return "derived-password-hash"
        }

        override suspend fun verify(rawPassword: String, encodedHash: String?): Boolean = error("not used")
    }

    private class RecordingCredentials(
        private val order: MutableList<String>,
        private val committedEpoch: Long,
    ) : CredentialAdministration {
        var receivedHash: String? = null

        override suspend fun banUser(uid: String): Long = error("not used")
        override suspend fun unbanUser(uid: String): Unit = error("not used")

        override suspend fun resetPasswordAndRevoke(uid: String, passwordHash: String): Long {
            order += "commit"
            receivedHash = passwordHash
            return committedEpoch
        }
    }

    private class RecordingSessions(
        private val order: MutableList<String>,
    ) : OnlineSessions {
        override suspend fun onlineCount(): Int = error("not used")
        override suspend fun isOnline(uid: String): Boolean = error("not used")
        override suspend fun kickUser(uid: String): Unit = error("not used")

        override suspend fun invalidateUserCredentials(uid: String, minimumEpoch: Long) {
            order += "fence:$minimumEpoch"
        }

        override suspend fun invalidateUserCredentialsExceptSession(
            uid: String,
            minimumEpoch: Long,
            sessionId: String,
        ): Unit = error("not used")

        override suspend fun invalidateDeviceCredentials(
            uid: String,
            deviceId: String,
            minimumEpoch: Long,
        ): Unit = error("not used")

        override suspend fun invalidateDeviceCredentialsExceptSession(
            uid: String,
            deviceId: String,
            minimumEpoch: Long,
            sessionId: String,
        ): Unit = error("not used")
    }
}
