package com.virjar.tk.server.domain.user

import com.virjar.tk.server.domain.auth.PasswordHasher
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UserServiceAuthenticationPolicyTest {
    @Test
    fun `missing and wrong password each consume one equivalent verification`() = runTest {
        val repository = FakeUserRepository().apply {
            internals["known-user"] = humanInternal(passwordHash = "stored-human-hash")
        }
        val hasher = RecordingPasswordHasher(verificationResult = false)
        val service = service(repository, hasher)

        val missing = assertFailsWith<IllegalArgumentException> {
            service.authenticateForCredentialIssue("missing-user", "valid-password")
        }
        val wrong = assertFailsWith<IllegalArgumentException> {
            service.authenticateForCredentialIssue("known-user", "valid-password")
        }

        assertEquals(missing.message, wrong.message)
        assertEquals(listOf(null, "stored-human-hash"), hasher.verificationTargets)
    }

    @Test
    fun `banned and service identities never expose stored verifier but still consume dummy work`() = runTest {
        val repository = FakeUserRepository().apply {
            internals["banned-user"] = humanInternal(status = 2, passwordHash = "banned-hash")
            internals["service-user"] = humanInternal(role = UserRole.BOT, passwordHash = "service-marker")
        }
        val hasher = RecordingPasswordHasher(verificationResult = true)
        val service = service(repository, hasher)

        val banned = assertFailsWith<IllegalArgumentException> {
            service.authenticateForCredentialIssue("banned-user", "valid-password")
        }
        val serviceAccount = assertFailsWith<IllegalArgumentException> {
            service.authenticateForCredentialIssue("service-user", "valid-password")
        }

        assertTrue(requireNotNull(banned.message).contains("封禁"))
        assertTrue(requireNotNull(serviceAccount.message).contains("服务账户"))
        assertEquals(listOf<String?>(null, null), hasher.verificationTargets)
    }

    @Test
    fun `password verification cancellation preserves exact object`() = runTest {
        val cancellation = CancellationException("authentication owner stopped")
        val repository = FakeUserRepository().apply {
            internals["known-user"] = humanInternal()
        }
        val hasher = RecordingPasswordHasher(
            verificationFailure = cancellation,
        )

        val observed = try {
            service(repository, hasher).authenticateForCredentialIssue("known-user", "valid-password")
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, observed)
    }

    @Test
    fun `service account provisioning does no password work inside aggregate transaction`() {
        val repository = FakeUserRepository()
        val hasher = RecordingPasswordHasher()
        val service = service(repository, hasher)

        val created = service.createServiceAccount(TestTransaction, "Notification Bot")

        assertEquals(UserRole.BOT, created.role)
        assertTrue(created.username.startsWith("bot-"))
        assertEquals(0, hasher.hashInputs.size)
        assertEquals(0, hasher.verificationTargets.size)
        assertEquals(1, repository.serviceAccountCreates)
    }

    @Test
    fun `public directory search normalizes adapter order`() {
        val repository = FakeUserRepository().apply {
            publicDirectoryResults = listOf(
                User(uid = "uid-b", username = "bravo", name = "Same"),
                User(uid = "uid-c", username = "alpha", name = "Zulu"),
                User(uid = "uid-a", username = "alpha", name = "Same"),
            )
        }

        val results = service(repository, RecordingPasswordHasher()).search("alpha")

        assertEquals(listOf("uid-a", "uid-b", "uid-c"), results.map(User::uid))
    }

    @Test
    fun `public directory search fails closed on hidden adapter identities`() {
        val repository = FakeUserRepository()
        val service = service(repository, RecordingPasswordHasher())
        listOf(
            User(uid = "bot", username = "search-bot", name = "Search Bot", role = UserRole.BOT),
            User(uid = "disabled", username = "search-disabled", name = "Disabled", status = 2),
        ).forEach { hiddenIdentity ->
            repository.publicDirectoryResults = listOf(hiddenIdentity)

            assertFailsWith<IllegalStateException> { service.search("search") }
        }
    }

    private fun service(
        repository: FakeUserRepository,
        hasher: PasswordHasher,
    ) = UserService(
        users = repository,
        unitOfWork = UnusedUnitOfWork,
        passwordHasher = hasher,
        profileAudience = UserProfileAudience { _, _ -> emptySet() },
        attachmentCatalog = UnusedAttachmentCatalog,
        attachmentLifecycle = AttachmentLifecycleGate(),
        profileChanges = UserProfileChangePublisher { _, _ -> },
    )

    private object UnusedAttachmentCatalog : AttachmentCatalog {
        override fun getAttachment(path: String) = null
        override fun getOwnerUid(path: String) = null
    }

    private class RecordingPasswordHasher(
        private val verificationResult: Boolean = true,
        private val verificationFailure: CancellationException? = null,
    ) : PasswordHasher {
        val hashInputs = mutableListOf<String>()
        val verificationTargets = mutableListOf<String?>()

        override suspend fun hash(rawPassword: String): String {
            hashInputs += rawPassword
            return "derived-hash"
        }

        override suspend fun verify(rawPassword: String, encodedHash: String?): Boolean {
            verificationTargets += encodedHash
            verificationFailure?.let { throw it }
            return verificationResult
        }
    }

    private class FakeUserRepository : UserRepository {
        val internals = mutableMapOf<String, UserInternal>()
        var publicDirectoryResults = emptyList<User>()
        var identityPreReads = 0
        var serviceAccountCreates = 0

        override fun findByUid(uid: String): User? {
            identityPreReads++
            return internals.values.firstOrNull { it.user.uid == uid }?.user
        }

        override suspend fun findInternalByUsername(username: String): UserInternal? = internals[username]

        override suspend fun findInternalByUid(uid: String): UserInternal? =
            internals.values.firstOrNull { it.user.uid == uid }

        override fun findByUsername(username: String): User? {
            identityPreReads++
            return internals[username]?.user
        }

        override fun findByPhone(phone: String): User? {
            identityPreReads++
            return internals.values.firstOrNull { it.user.phone == phone }?.user
        }

        override fun registerHuman(
            transaction: PgWriteTransactionContext,
            command: HumanRegistrationCommand,
        ): User = error("not used")

        override fun createServiceAccount(
            transaction: PgWriteTransactionContext,
            uid: String,
            username: String,
            name: String,
            role: Int,
        ): User {
            assertSame(TestTransaction, transaction)
            serviceAccountCreates++
            return User(uid = uid, username = username, name = name, role = role)
        }

        override fun updateProfile(
            transaction: PgWriteTransactionContext,
            uid: String,
            patch: ProfilePatch,
        ): UserProfileMutation = error("not used")

        override fun searchPublicDirectory(keyword: String, limit: Int): List<User> = publicDirectoryResults
    }

    private object TestTransaction : PgWriteTransactionContext

    private object UnusedUnitOfWork : PgUnitOfWork {
        override suspend fun <T> read(block: PgReadScope.() -> T): T = error("not used")
        override suspend fun <T> write(block: PgWriteScope.() -> T): T = error("not used")
    }

    private companion object {
        fun humanInternal(
            status: Int = 1,
            role: Int = UserRole.HUMAN,
            passwordHash: String = "stored-human-hash",
        ) = UserInternal(
            user = User(
                uid = "uid-$role-$status",
                username = "user-$role-$status",
                name = "User",
                role = role,
                status = status,
            ),
            passwordHash = passwordHash,
            credentialEpoch = 1L,
        )
    }
}
