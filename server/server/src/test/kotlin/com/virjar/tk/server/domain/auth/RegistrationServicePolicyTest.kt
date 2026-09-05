package com.virjar.tk.server.domain.auth

import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.user.HumanRegistrationCommand
import com.virjar.tk.server.domain.user.UserInternal
import com.virjar.tk.server.domain.user.UserProfileMutation
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegistrationServicePolicyTest {
    @Test
    fun `password hashes before one complete registration unit of work without identity pre reads`() = runTest {
        val order = mutableListOf<String>()
        val repository = RecordingUserRepository { order += "user" }
        val hasher = RecordingPasswordHasher { order += "hash" }
        val issuer = InitialCredentialIssuer { transaction, user, _ ->
            assertSame(TestTransaction, transaction)
            assertEquals(repository.created, user)
            order += "credentials"
            issuedCredentials(user.uid)
        }
        var generated = 0
        val service = RegistrationService(
            users = repository,
            unitOfWork = RecordingUnitOfWork,
            passwordHasher = hasher,
            initialCredentials = issuer,
            uidGenerator = { "uid-${++generated}" },
        )

        val result = service.register(
            username = "new-user",
            password = "valid-password",
            name = "New User",
            phone = "13800000000",
            device = validDevice(),
        )

        assertEquals(listOf("hash", "user", "credentials"), order)
        assertEquals(0, repository.identityPreReads)
        assertEquals(RegistrationService.MAX_UID_ATTEMPTS, generated)
        assertEquals("uid-1", result.user.uid)
        assertEquals("derived-hash", repository.lastCommand?.passwordHash)
    }

    @Test
    fun `cancellation after non cooperative hash prevents registration transaction`() = runTest {
        val cancellation = CancellationException("connection closed while hashing")
        val repository = RecordingUserRepository()
        val hasher = NonCooperativePasswordHasher()
        val registration = async {
            RegistrationService(
                users = repository,
                unitOfWork = RecordingUnitOfWork,
                passwordHasher = hasher,
                initialCredentials = InitialCredentialIssuer { _, _, _ -> error("must not issue") },
            ).register(
                username = "new-user",
                password = "valid-password",
                name = "New User",
                device = validDevice(),
            )
        }

        hasher.started.await()
        registration.cancel(cancellation)
        hasher.release.complete(Unit)
        val observed = try {
            registration.await()
            null
        } catch (error: CancellationException) {
            error
        }

        registration.join()
        assertTrue(
            generateSequence<Throwable>(observed) { it.cause }.any { it === cancellation },
            "await must preserve the original cancellation in its recovered cause chain",
        )
        assertEquals(null, repository.lastCommand)
    }

    private class RecordingPasswordHasher(private val onHash: () -> Unit) : PasswordHasher {
        override suspend fun hash(rawPassword: String): String {
            onHash()
            return "derived-hash"
        }

        override suspend fun verify(rawPassword: String, encodedHash: String?): Boolean = error("not used")
    }

    private class NonCooperativePasswordHasher : PasswordHasher {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun hash(rawPassword: String): String = withContext(NonCancellable) {
            started.complete(Unit)
            release.await()
            "derived-hash"
        }

        override suspend fun verify(rawPassword: String, encodedHash: String?): Boolean = error("not used")
    }

    private class RecordingUserRepository(
        private val onRegister: () -> Unit = {},
    ) : UserRepository {
        var identityPreReads = 0
        var lastCommand: HumanRegistrationCommand? = null
        var created: User? = null

        override fun findByUid(uid: String): User? {
            identityPreReads++
            return null
        }
        override suspend fun findInternalByUsername(username: String): UserInternal? = null
        override suspend fun findInternalByUid(uid: String): UserInternal? = null
        override fun findByUsername(username: String): User? {
            identityPreReads++
            return null
        }

        override fun findByPhone(phone: String): User? {
            identityPreReads++
            return null
        }

        override fun registerHuman(
            transaction: PgWriteTransactionContext,
            command: HumanRegistrationCommand,
        ): User {
            assertSame(TestTransaction, transaction)
            onRegister()
            lastCommand = command
            return User(
                uid = command.uid,
                username = command.username,
                name = command.name,
                phone = command.phone,
            ).also { created = it }
        }

        override fun createServiceAccount(
            transaction: PgWriteTransactionContext,
            uid: String,
            username: String,
            name: String,
            role: Int,
        ): User = error("not used")

        override fun updateProfile(
            transaction: PgWriteTransactionContext,
            uid: String,
            patch: ProfilePatch,
        ): UserProfileMutation = error("not used")

        override fun searchPublicDirectory(keyword: String, limit: Int): List<User> = error("not used")
    }

    private object TestTransaction : PgWriteTransactionContext

    private object RecordingUnitOfWork : PgUnitOfWork {
        override suspend fun <T> read(block: PgReadScope.() -> T): T = error("not used")

        override suspend fun <T> write(block: PgWriteScope.() -> T): T = TestWriteScope.block()
    }

    private object TestWriteScope : PgWriteScope {
        override val transaction: PgWriteTransactionContext = TestTransaction

        override fun appendEvent(uid: String, notifyType: NotifyType, payload: IProto) =
            error("not used")

        override fun afterCommit(action: () -> Unit) = error("not used")
    }

    private fun validDevice() = CredentialDevice(
        deviceId = "registration-device",
        deviceName = "Test device",
        deviceModel = null,
        deviceFlag = 0,
    )

    private fun issuedCredentials(uid: String) = IssuedCredentials(
        accessToken = "access-secret",
        refreshToken = "refresh-secret",
        principal = TokenInfo(
            uid = uid,
            deviceId = "registration-device",
            deviceFlag = 0,
            createdAt = 1L,
            expiresAt = 2L,
            userCredentialEpoch = 1L,
            deviceCredentialEpoch = 1L,
        ),
        subject = CredentialSubject(username = "new-user", name = "New User"),
    )
}
