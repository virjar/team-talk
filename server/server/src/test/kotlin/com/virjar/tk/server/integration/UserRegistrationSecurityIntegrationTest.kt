package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.auth.CredentialDevice
import com.virjar.tk.server.domain.auth.InitialCredentialIssuer
import com.virjar.tk.server.domain.auth.RegistrationService
import com.virjar.tk.server.domain.user.PhoneAlreadyRegisteredException
import com.virjar.tk.server.domain.user.UserIdentityAllocationException
import com.virjar.tk.server.domain.user.UsernameAlreadyRegisteredException
import com.virjar.tk.server.infra.db.Credentials
import com.virjar.tk.server.infra.db.Devices
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 协程栈恢复可能把异常包装成同类型副本；任何基础设施原因都不得逃逸。 */
private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

class UserRegistrationSecurityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `concurrent same username maps one exact business conflict`() = runTest {
        val username = uniqueUsername("registration-race-username")

        val outcomes = concurrentRegistrations(
            { ctx.registerHuman(username, "password-123", "First") },
            { ctx.registerHuman(username, "password-456", "Second") },
        )

        assertEquals(1, outcomes.count { it is RegistrationOutcome.Success })
        val failure = outcomes.filterIsInstance<RegistrationOutcome.Failure>().single().error
        assertIs<UsernameAlreadyRegisteredException>(failure)
        assertEquals("用户名已存在", failure.message)
        assertTrue(failure.causeChain().all { it is UsernameAlreadyRegisteredException })
        assertEquals(1L, transaction(ctx.database) {
            Users.selectAll().where { Users.username eq username }.count()
        })
        val committedUid = outcomes.filterIsInstance<RegistrationOutcome.Success>().single().user.uid
        assertCompleteCredentialAggregate(committedUid)
    }

    @Test
    fun `concurrent same phone maps one exact business conflict`() = runTest {
        val phone = "13${System.nanoTime().toString().takeLast(9).padStart(9, '0')}"

        val outcomes = concurrentRegistrations(
            { ctx.registerHuman(uniqueUsername("phone-race-a"), "password-123", "First", phone) },
            { ctx.registerHuman(uniqueUsername("phone-race-b"), "password-456", "Second", phone) },
        )

        assertEquals(1, outcomes.count { it is RegistrationOutcome.Success })
        val failure = outcomes.filterIsInstance<RegistrationOutcome.Failure>().single().error
        assertIs<PhoneAlreadyRegisteredException>(failure)
        assertEquals("手机号已注册", failure.message)
        assertTrue(failure.causeChain().all { it is PhoneAlreadyRegisteredException })
        assertEquals(1L, transaction(ctx.database) {
            Users.selectAll().where { Users.phone eq phone }.count()
        })
        val committedUid = outcomes.filterIsInstance<RegistrationOutcome.Success>().single().user.uid
        assertCompleteCredentialAggregate(committedUid)
    }

    @Test
    fun `successful registration commits one user one device and one credential pair`() = runTest {
        val username = uniqueUsername("complete-registration")
        val device = registrationDevice("complete")
        val result = ctx.registrationService.register(
            username = username,
            password = "password-123",
            name = "Complete Registration",
            device = device,
        )

        val persisted = transaction(ctx.database) {
            val userCount = Users.selectAll().where { Users.uid eq result.user.uid }.count()
            val deviceRows = Devices.selectAll().where { Devices.uid eq result.user.uid }.toList()
            val credentialRows = Credentials.selectAll().where { Credentials.uid eq result.user.uid }.toList()
            Triple(userCount, deviceRows, credentialRows)
        }
        assertEquals(1L, persisted.first)
        assertEquals(1, persisted.second.size)
        assertEquals(device.deviceId, persisted.second.single()[Devices.deviceId])
        assertEquals(setOf(1, 2), persisted.third.map { it[Credentials.tokenType] }.toSet())
        assertNotNull(ctx.accessTokenValidator.validateAccessToken(result.credentials.accessToken))
    }

    @Test
    fun `failure after first credentials are written rolls back the complete registration aggregate`() = runTest {
        val username = uniqueUsername("registration-rollback")
        val device = registrationDevice("rollback")
        val issuer = InitialCredentialIssuer { transaction, user, credentialDevice ->
            ctx.initialCredentialIssuer.issueInitialCredentials(transaction, user, credentialDevice)
            throw InjectedRegistrationFailure
        }
        val service = RegistrationService(
            users = ctx.userRepo,
            unitOfWork = ctx.pgUnitOfWork,
            passwordHasher = ctx.passwordHasher,
            initialCredentials = issuer,
        )

        val observed = try {
            service.register(
                username = username,
                password = "password-123",
                name = "Rollback Registration",
                device = device,
            )
            null
        } catch (failure: InjectedRegistrationFailureException) {
            failure
        }

        assertEquals(InjectedRegistrationFailure, observed)
        transaction(ctx.database) {
            assertEquals(0L, Users.selectAll().where { Users.username eq username }.count())
            assertEquals(0L, Devices.selectAll().where { Devices.deviceId eq device.deviceId }.count())
            assertEquals(0L, Credentials.selectAll().where { Credentials.deviceId eq device.deviceId }.count())
        }
    }

    @Test
    fun `uid unique collision retries only the finite repository candidate list`() = runTest {
        val occupied = ctx.registerHuman(
            uniqueUsername("occupied-uid"),
            "password-123",
            "Occupied",
        )
        val sequence = AtomicInteger(0)
        val service = RegistrationService(
            users = ctx.userRepo,
            unitOfWork = ctx.pgUnitOfWork,
            passwordHasher = ctx.passwordHasher,
            initialCredentials = ctx.initialCredentialIssuer,
            uidGenerator = {
                if (sequence.getAndIncrement() == 0) occupied.uid else "retry-${System.nanoTime()}-${sequence.get()}"
            },
        )

        val created = service.registerHuman(
            uniqueUsername("uid-retry"),
            "password-456",
            "Retried",
        )

        assertNotEquals(occupied.uid, created.uid)
        assertTrue(created.uid.startsWith("retry-"))
    }

    @Test
    fun `uid allocation exhausts finite candidates without unsafe fallback`() = runTest {
        val occupied = ctx.registerHuman(
            uniqueUsername("occupied-all-uids"),
            "password-123",
            "Occupied",
        )
        val username = uniqueUsername("uid-exhausted")
        val service = RegistrationService(
            users = ctx.userRepo,
            unitOfWork = ctx.pgUnitOfWork,
            passwordHasher = ctx.passwordHasher,
            initialCredentials = ctx.initialCredentialIssuer,
            uidGenerator = { occupied.uid },
        )

        val failure = try {
            service.registerHuman(username, "password-456", "Exhausted")
            null
        } catch (error: UserIdentityAllocationException) {
            error
        }

        assertEquals("无法分配用户身份，请重试", requireNotNull(failure).message)
        assertEquals(null, failure.cause)
        assertEquals(0L, transaction(ctx.database) {
            Users.selectAll().where { Users.username eq username }.count()
        })
    }

    @Test
    fun `service account persists random non bcrypt marker and password login stays disabled`() = runTest {
        val created = ctx.botService.create("Security Bot")
        val another = ctx.botService.create("Another Security Bot")
        val rows = transaction(ctx.database) {
            Users.selectAll().where {
                (Users.uid eq created.bot.userUid) or (Users.uid eq another.bot.userUid)
            }.associateBy { it[Users.uid] }
        }
        val row = rows.getValue(created.bot.userUid)
        val marker = row[Users.passwordHash]
        val username = row[Users.username]

        assertTrue(marker.startsWith("!service-account:v1:"))
        assertTrue(!marker.startsWith("\$2"))
        assertNotEquals(marker, rows.getValue(another.bot.userUid)[Users.passwordHash])
        val failure = try {
            ctx.userService.login(username, "arbitrary-password")
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertTrue(requireNotNull(failure).message.orEmpty().contains("服务账户"))

        val resetFailure = try {
            ctx.adminService.resetPassword(created.bot.userUid, "replacement-password")
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        assertTrue(requireNotNull(resetFailure).message.orEmpty().contains("服务账户"))
        assertEquals(marker, transaction(ctx.database) {
            Users.selectAll().where { Users.uid eq created.bot.userUid }.single()[Users.passwordHash]
        })
    }

    private suspend fun concurrentRegistrations(
        first: suspend () -> User,
        second: suspend () -> User,
    ): List<RegistrationOutcome> = supervisorScope {
        listOf(first, second).map { registration ->
            async {
                try {
                    RegistrationOutcome.Success(registration())
                } catch (error: IllegalArgumentException) {
                    RegistrationOutcome.Failure(error)
                }
            }
        }.awaitAll()
    }

    private sealed interface RegistrationOutcome {
        data class Success(val user: User) : RegistrationOutcome
        data class Failure(val error: IllegalArgumentException) : RegistrationOutcome
    }

    private suspend fun RegistrationService.registerHuman(
        username: String,
        password: String,
        name: String,
        phone: String? = null,
    ): User = register(
        username = username,
        password = password,
        name = name,
        phone = phone,
        device = registrationDevice("custom"),
    ).user

    private fun registrationDevice(label: String): CredentialDevice = CredentialDevice(
        deviceId = "$label-${java.util.UUID.randomUUID()}",
        deviceName = "Registration integration",
        deviceModel = null,
        deviceFlag = 0,
    )

    private fun assertCompleteCredentialAggregate(uid: String) {
        val counts = transaction(ctx.database) {
            Devices.selectAll().where { Devices.uid eq uid }.count() to
                Credentials.selectAll().where { Credentials.uid eq uid }.count()
        }
        assertEquals(1L, counts.first)
        assertEquals(2L, counts.second)
    }

    private object InjectedRegistrationFailure : InjectedRegistrationFailureException()
    private open class InjectedRegistrationFailureException : RuntimeException("injected registration failure")
}
