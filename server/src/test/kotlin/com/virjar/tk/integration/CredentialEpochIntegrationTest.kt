package com.virjar.tk.integration

import com.virjar.tk.domain.auth.CredentialDevice
import com.virjar.tk.domain.auth.CredentialIssueRequest
import com.virjar.tk.infra.db.Credentials
import com.virjar.tk.infra.db.Devices
import com.virjar.tk.infra.db.repository.CredentialMutation
import com.virjar.tk.infra.db.repository.CredentialRepositoryHooks
import com.virjar.tk.infra.db.repository.ExposedCredentialRepository
import com.virjar.tk.infra.sync.credentialEpochsDoNotRegress
import com.virjar.tk.protocol.payload.AuthRequestPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialEpochIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `credentials persist only hashes and diagnostic strings redact secrets`() = runTest {
        val username = uniqueUsername("credential-hash")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val proof = ctx.userService.authenticateForCredentialIssue(username, password)
        val request = CredentialIssueRequest(
            uid = uid,
            expectedUserCredentialEpoch = proof.userCredentialEpoch,
            expectedPasswordHash = proof.passwordHashSnapshot,
            device = device("hash-device"),
        )

        val issued = assertNotNull(ctx.tokenRepository.issueCredentials(request))
        val persistedHashes = transaction {
            Credentials.selectAll()
                .where { Credentials.uid eq uid }
                .map { it[Credentials.tokenHash] }
                .toSet()
        }

        assertEquals(setOf(sha256(issued.accessToken), sha256(issued.refreshToken)), persistedHashes)
        assertEquals(username, issued.subject.username)
        assertEquals(username, issued.subject.name)
        assertFalse(persistedHashes.contains(issued.accessToken))
        assertFalse(persistedHashes.contains(issued.refreshToken))
        assertEquals(uid, ctx.accessTokenValidator.validateAccessToken(issued.accessToken)?.uid)
        assertFalse(request.toString().contains(proof.passwordHashSnapshot))
        assertFalse(proof.toString().contains(proof.passwordHashSnapshot))
        assertFalse(
            requireNotNull(ctx.userStore.findInternalByUid(uid)).toString().contains(proof.passwordHashSnapshot),
        )
        assertFalse(issued.toString().contains(issued.accessToken))
        assertFalse(issued.toString().contains(issued.refreshToken))
        assertFalse(issued.toString().contains(username))
    }

    @Test
    fun `same device password login keeps only latest credential pair`() = runTest {
        val username = uniqueUsername("credential-latest")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)

        val first = login(username, password, "same-device")
        val firstDeviceEpoch = transaction {
            Devices.selectAll().where {
                (Devices.uid eq uid) and (Devices.deviceId eq "same-device")
            }.single()[Devices.credentialEpoch]
        }
        val second = login(username, password, "same-device")

        assertNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(first.accessToken)))
        val secondPrincipal = assertNotNull(
            ctx.accessTokenValidator.validateAccessToken(requireNotNull(second.accessToken)),
        )
        assertTrue(secondPrincipal.deviceCredentialEpoch > firstDeviceEpoch)
        assertFalse(
            credentialEpochsDoNotRegress(
                candidateUserEpoch = secondPrincipal.userCredentialEpoch,
                candidateDeviceEpoch = firstDeviceEpoch,
                existingUserEpoch = secondPrincipal.userCredentialEpoch,
                existingDeviceEpoch = secondPrincipal.deviceCredentialEpoch,
            ),
            "a delayed older admission must not supersede the latest credential pair",
        )
        assertEquals(1, refresh(first.refreshToken, "same-device").code)
        assertEquals(uid, second.uid)
        assertEquals(2L, transaction {
            Credentials.selectAll().where { Credentials.uid eq uid }.count()
        })
    }

    @Test
    fun `delayed logout from an older session cannot revoke a newer device pair`() = runTest {
        val username = uniqueUsername("credential-stale-logout")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val first = login(username, password, "stale-logout-device")
        val firstPrincipal = assertNotNull(
            ctx.accessTokenValidator.validateAccessToken(requireNotNull(first.accessToken)),
        )
        val second = login(username, password, "stale-logout-device")
        val secondAccess = requireNotNull(second.accessToken)
        val secondPrincipal = assertNotNull(ctx.accessTokenValidator.validateAccessToken(secondAccess))
        assertTrue(secondPrincipal.deviceCredentialEpoch > firstPrincipal.deviceCredentialEpoch)

        val fencedEpoch = assertNotNull(
            ctx.tokenRepository.revokeDeviceIfCurrent(
                uid = uid,
                deviceId = "stale-logout-device",
                expectedDeviceCredentialEpoch = firstPrincipal.deviceCredentialEpoch,
            ),
        )

        assertEquals(secondPrincipal.deviceCredentialEpoch, fencedEpoch)
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(secondAccess))
        assertEquals(2L, transaction {
            Credentials.selectAll().where {
                (Credentials.uid eq uid) and (Credentials.deviceId eq "stale-logout-device")
            }.count()
        })
    }

    @Test
    fun `refresh rotates the complete pair and remains bounded`() = runTest {
        val username = uniqueUsername("credential-refresh")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val initial = login(username, password, "refresh-device")
        val oldAccess = requireNotNull(initial.accessToken)
        val oldRefresh = requireNotNull(initial.refreshToken)
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(oldAccess))

        val rotated = refresh(oldRefresh, "refresh-device")

        assertEquals(0, rotated.code)
        assertNull(ctx.accessTokenValidator.validateAccessToken(oldAccess))
        assertEquals(1, refresh(oldRefresh, "refresh-device").code)
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(rotated.accessToken)))
        assertEquals(2L, transaction {
            Credentials.selectAll().where { Credentials.uid eq uid }.count()
        })
    }

    @Test
    fun `bearer validation runs on its injected database dispatcher`() = runTest {
        val username = uniqueUsername("credential-dispatcher")
        val password = "pass123"
        ctx.registerUser(username, password)
        val login = login(username, password, "dispatcher-device")
        var validationThread: String? = null
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "credential-db-test").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val validator = ExposedCredentialRepository(
                dbDispatcher = dispatcher,
                clock = {
                    validationThread = Thread.currentThread().name
                    System.currentTimeMillis()
                },
            )
            assertNotNull(validator.validateAccessToken(requireNotNull(login.accessToken)))
            assertTrue(validationThread?.startsWith("credential-db-test") == true)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `ban and unban never resurrect old credentials`() = runTest {
        val username = uniqueUsername("credential-ban")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val beforeBan = login(username, password, "ban-device")
        val oldAccess = requireNotNull(beforeBan.accessToken)

        ctx.adminService.banUser(uid)
        assertNull(ctx.accessTokenValidator.validateAccessToken(oldAccess))
        assertEquals(1, refresh(beforeBan.refreshToken, "ban-device").code)
        assertEquals(1, login(username, password, "blocked-device").code)

        ctx.adminService.unbanUser(uid)
        assertNull(ctx.accessTokenValidator.validateAccessToken(oldAccess))
        assertEquals(1, refresh(beforeBan.refreshToken, "ban-device").code)
        val afterUnban = login(username, password, "ban-device")
        assertEquals(0, afterUnban.code)
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(afterUnban.accessToken)))
    }

    @Test
    fun `device revoke is isolated and relogin preserves advanced epoch`() = runTest {
        val username = uniqueUsername("credential-device")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val deviceA = login(username, password, "device-a")
        val deviceB = login(username, password, "device-b")

        val committedEpoch = assertNotNull(ctx.authService.revokeDevice(uid, "device-a"))
        assertTrue(committedEpoch > 1L)
        assertNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(deviceA.accessToken)))
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(deviceB.accessToken)))
        assertEquals(1, refresh(deviceA.refreshToken, "device-a").code)

        val relogin = login(username, password, "device-a")
        val principal = assertNotNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(relogin.accessToken)))
        assertTrue(principal.deviceCredentialEpoch > committedEpoch)
    }

    @Test
    fun `admin password reset revokes old epoch and requires new password`() = runTest {
        val username = uniqueUsername("credential-reset")
        val oldPassword = "pass123"
        val newPassword = "pass456"
        val uid = ctx.registerUser(username, oldPassword)
        val beforeReset = login(username, oldPassword, "reset-device")

        ctx.adminService.resetPassword(uid, newPassword)

        assertNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(beforeReset.accessToken)))
        assertEquals(1, refresh(beforeReset.refreshToken, "reset-device").code)
        assertEquals(1, login(username, oldPassword, "reset-device").code)
        val afterReset = login(username, newPassword, "reset-device")
        assertEquals(0, afterReset.code)
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(requireNotNull(afterReset.accessToken)))
    }

    @Test
    fun `credential issue committed before concurrent ban is still invalidated`() = runBlocking {
        val username = uniqueUsername("credential-race")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val proof = ctx.userService.authenticateForCredentialIssue(username, password)
        val issueLocked = CompletableDeferred<Unit>()
        val releaseIssue = CompletableDeferred<Unit>()
        val banBeforeLock = CompletableDeferred<Unit>()
        val banLocked = CompletableDeferred<Unit>()
        val repository = ExposedCredentialRepository(
            hooks = object : CredentialRepositoryHooks {
                override suspend fun beforeUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.BAN_USER) banBeforeLock.complete(Unit)
                }

                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    when (operation) {
                        CredentialMutation.ISSUE -> {
                            issueLocked.complete(Unit)
                            releaseIssue.await()
                        }
                        CredentialMutation.BAN_USER -> banLocked.complete(Unit)
                        else -> Unit
                    }
                }
            },
        )

        coroutineScope {
            val issue = async(Dispatchers.IO) {
                repository.issueCredentials(
                    CredentialIssueRequest(
                        uid = uid,
                        expectedUserCredentialEpoch = proof.userCredentialEpoch,
                        expectedPasswordHash = proof.passwordHashSnapshot,
                        device = device("race-device"),
                    ),
                )
            }
            withTimeout(5_000) { issueLocked.await() }
            val ban = async(Dispatchers.IO) { repository.banUser(uid) }
            withTimeout(5_000) { banBeforeLock.await() }
            assertFalse(banLocked.isCompleted, "ban must wait for the user row held by issue")

            releaseIssue.complete(Unit)
            val issued = assertNotNull(withTimeout(5_000) { issue.await() })
            withTimeout(5_000) { ban.await() }
            assertNull(repository.validateAccessToken(issued.accessToken))
        }
    }

    @Test
    fun `ban committed before stale login proof prevents credential issue`() = runBlocking {
        val username = uniqueUsername("credential-ban-first")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val proof = ctx.userService.authenticateForCredentialIssue(username, password)
        val banLocked = CompletableDeferred<Unit>()
        val releaseBan = CompletableDeferred<Unit>()
        val issueBeforeLock = CompletableDeferred<Unit>()
        val issueLocked = CompletableDeferred<Unit>()
        val repository = ExposedCredentialRepository(
            hooks = object : CredentialRepositoryHooks {
                override suspend fun beforeUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.ISSUE) issueBeforeLock.complete(Unit)
                }

                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    when (operation) {
                        CredentialMutation.BAN_USER -> {
                            banLocked.complete(Unit)
                            releaseBan.await()
                        }
                        CredentialMutation.ISSUE -> issueLocked.complete(Unit)
                        else -> Unit
                    }
                }
            },
        )

        coroutineScope {
            val ban = async(Dispatchers.IO) { repository.banUser(uid) }
            withTimeout(5_000) { banLocked.await() }
            val issue = async(Dispatchers.IO) {
                repository.issueCredentials(
                    CredentialIssueRequest(
                        uid = uid,
                        expectedUserCredentialEpoch = proof.userCredentialEpoch,
                        expectedPasswordHash = proof.passwordHashSnapshot,
                        device = device("ban-first-device"),
                    ),
                )
            }
            withTimeout(5_000) { issueBeforeLock.await() }
            assertFalse(issueLocked.isCompleted, "issue must wait for the user row held by ban")

            releaseBan.complete(Unit)
            withTimeout(5_000) { ban.await() }
            assertNull(withTimeout(5_000) { issue.await() })
        }
    }

    @Test
    fun `refresh committed before concurrent ban cannot leave a usable rotated token`() = runBlocking {
        val username = uniqueUsername("credential-refresh-race")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val initial = login(username, password, "refresh-race-device")
        val refreshLocked = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val banBeforeLock = CompletableDeferred<Unit>()
        val banLocked = CompletableDeferred<Unit>()
        val repository = ExposedCredentialRepository(
            hooks = object : CredentialRepositoryHooks {
                override suspend fun beforeUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.BAN_USER) banBeforeLock.complete(Unit)
                }

                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    when (operation) {
                        CredentialMutation.REFRESH -> {
                            refreshLocked.complete(Unit)
                            releaseRefresh.await()
                        }
                        CredentialMutation.BAN_USER -> banLocked.complete(Unit)
                        else -> Unit
                    }
                }
            },
        )

        coroutineScope {
            val refresh = async(Dispatchers.IO) {
                repository.refreshCredentials(
                    requireNotNull(initial.refreshToken),
                    device("refresh-race-device"),
                )
            }
            withTimeout(5_000) { refreshLocked.await() }
            val ban = async(Dispatchers.IO) { repository.banUser(uid) }
            withTimeout(5_000) { banBeforeLock.await() }
            assertFalse(banLocked.isCompleted)

            releaseRefresh.complete(Unit)
            val rotated = assertNotNull(withTimeout(5_000) { refresh.await() })
            withTimeout(5_000) { ban.await() }
            assertNull(repository.validateAccessToken(rotated.accessToken))
        }
    }

    @Test
    fun `device revoke ordered after issue invalidates only that device`() = runBlocking {
        val username = uniqueUsername("credential-revoke-race")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val otherDevice = login(username, password, "revoke-race-other")
        val proof = ctx.userService.authenticateForCredentialIssue(username, password)
        val issueLocked = CompletableDeferred<Unit>()
        val releaseIssue = CompletableDeferred<Unit>()
        val revokeBeforeLock = CompletableDeferred<Unit>()
        val revokeLocked = CompletableDeferred<Unit>()
        val repository = ExposedCredentialRepository(
            hooks = object : CredentialRepositoryHooks {
                override suspend fun beforeUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.REVOKE_DEVICE) revokeBeforeLock.complete(Unit)
                }

                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    when (operation) {
                        CredentialMutation.ISSUE -> {
                            issueLocked.complete(Unit)
                            releaseIssue.await()
                        }
                        CredentialMutation.REVOKE_DEVICE -> revokeLocked.complete(Unit)
                        else -> Unit
                    }
                }
            },
        )

        coroutineScope {
            val issue = async(Dispatchers.IO) {
                repository.issueCredentials(
                    CredentialIssueRequest(
                        uid = uid,
                        expectedUserCredentialEpoch = proof.userCredentialEpoch,
                        expectedPasswordHash = proof.passwordHashSnapshot,
                        device = device("revoke-race-target"),
                    ),
                )
            }
            withTimeout(5_000) { issueLocked.await() }
            val revoke = async(Dispatchers.IO) { repository.revokeDevice(uid, "revoke-race-target") }
            withTimeout(5_000) { revokeBeforeLock.await() }
            assertFalse(revokeLocked.isCompleted)

            releaseIssue.complete(Unit)
            val issued = assertNotNull(withTimeout(5_000) { issue.await() })
            val deviceEpoch = assertNotNull(withTimeout(5_000) { revoke.await() })
            assertNull(repository.validateAccessToken(issued.accessToken))
            assertNotNull(repository.validateAccessToken(requireNotNull(otherDevice.accessToken)))

            val relogin = login(username, password, "revoke-race-target")
            val principal = assertNotNull(repository.validateAccessToken(requireNotNull(relogin.accessToken)))
            assertTrue(principal.deviceCredentialEpoch > deviceEpoch)
        }
    }

    @Test
    fun `password reset committed first rejects a stale password proof`() = runBlocking {
        val username = uniqueUsername("credential-reset-first")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val proof = ctx.userService.authenticateForCredentialIssue(username, password)
        val resetLocked = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        val issueBeforeLock = CompletableDeferred<Unit>()
        val issueLocked = CompletableDeferred<Unit>()
        val repository = ExposedCredentialRepository(
            hooks = object : CredentialRepositoryHooks {
                override suspend fun beforeUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.ISSUE) issueBeforeLock.complete(Unit)
                }

                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    when (operation) {
                        CredentialMutation.RESET_PASSWORD -> {
                            resetLocked.complete(Unit)
                            releaseReset.await()
                        }
                        CredentialMutation.ISSUE -> issueLocked.complete(Unit)
                        else -> Unit
                    }
                }
            },
        )

        coroutineScope {
            val reset = async(Dispatchers.IO) {
                repository.resetPasswordAndRevoke(uid, "replacement-password-hash")
            }
            withTimeout(5_000) { resetLocked.await() }
            val issue = async(Dispatchers.IO) {
                repository.issueCredentials(
                    CredentialIssueRequest(
                        uid = uid,
                        expectedUserCredentialEpoch = proof.userCredentialEpoch,
                        expectedPasswordHash = proof.passwordHashSnapshot,
                        device = device("reset-first-device"),
                    ),
                )
            }
            withTimeout(5_000) { issueBeforeLock.await() }
            assertFalse(issueLocked.isCompleted, "stale issue must wait behind the password reset")

            releaseReset.complete(Unit)
            withTimeout(5_000) { reset.await() }
            assertNull(withTimeout(5_000) { issue.await() })
        }
    }

    @Test
    fun `password reset ordered after refresh invalidates the rotated pair`() = runBlocking {
        val username = uniqueUsername("credential-refresh-reset")
        val password = "pass123"
        val uid = ctx.registerUser(username, password)
        val initial = login(username, password, "refresh-reset-device")
        val refreshLocked = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val resetBeforeLock = CompletableDeferred<Unit>()
        val resetLocked = CompletableDeferred<Unit>()
        val repository = ExposedCredentialRepository(
            hooks = object : CredentialRepositoryHooks {
                override suspend fun beforeUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.RESET_PASSWORD) resetBeforeLock.complete(Unit)
                }

                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    when (operation) {
                        CredentialMutation.REFRESH -> {
                            refreshLocked.complete(Unit)
                            releaseRefresh.await()
                        }
                        CredentialMutation.RESET_PASSWORD -> resetLocked.complete(Unit)
                        else -> Unit
                    }
                }
            },
        )

        coroutineScope {
            val refresh = async(Dispatchers.IO) {
                repository.refreshCredentials(
                    requireNotNull(initial.refreshToken),
                    device("refresh-reset-device"),
                )
            }
            withTimeout(5_000) { refreshLocked.await() }
            val reset = async(Dispatchers.IO) {
                repository.resetPasswordAndRevoke(uid, "replacement-password-hash")
            }
            withTimeout(5_000) { resetBeforeLock.await() }
            assertFalse(resetLocked.isCompleted, "password reset must wait behind refresh")

            releaseRefresh.complete(Unit)
            val rotated = assertNotNull(withTimeout(5_000) { refresh.await() })
            withTimeout(5_000) { reset.await() }
            assertNull(repository.validateAccessToken(rotated.accessToken))
            assertNull(repository.refreshCredentials(rotated.refreshToken, device("refresh-reset-device")))
        }
    }

    private suspend fun login(username: String, password: String, deviceId: String) =
        ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 0,
                username = username,
                password = password,
                deviceId = deviceId,
                deviceName = deviceId,
                deviceModel = "test-model",
                deviceFlag = 1,
            ),
        )

    private suspend fun refresh(refreshToken: String?, deviceId: String) =
        ctx.authService.handleAuth(
            AuthRequestPayload(
                authType = 2,
                refreshToken = refreshToken,
                deviceId = deviceId,
                deviceName = deviceId,
                deviceModel = "test-model",
                deviceFlag = 1,
            ),
        )

    private fun device(deviceId: String) = CredentialDevice(
        deviceId = deviceId,
        deviceName = deviceId,
        deviceModel = "test-model",
        deviceFlag = 1,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
