package com.virjar.tk.integration

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.repository.ExposedUserRepository
import com.virjar.tk.infra.db.repository.UserProfileLockObserver
import com.virjar.tk.model.ProfilePatch
import com.virjar.tk.model.ProfilePatchValue
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.RpcStubRegistry
import com.virjar.tk.protocol.rpc.UserRpcImpl
import com.virjar.tk.rpc.gen.UserRpcContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real PostgreSQL gates for User profile facts and USER_UPDATED durable-event atomicity. */
class UserProfileUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `profile fact and durable event roll back together`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-rollback"))
        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                avatar = ProfilePatchValue.Set("https://example.test/before.png"),
                phone = ProfilePatchValue.Set(uniquePhone("13")),
            ),
        )
        val before = ctx.userService.getProfile(uid)
        val eventsBefore = eventCount(uid)

        assertIs<InjectedUserProfileRollbackException>(runCatching {
            userService(failingUnitOfWork()).updateProfile(
                uid,
                ProfilePatch(
                    name = ProfilePatchValue.Set("must roll back"),
                    avatar = ProfilePatchValue.Set(null),
                    phone = ProfilePatchValue.Set(null),
                ),
            )
        }.exceptionOrNull())

        assertEquals(before, ctx.userService.getProfile(uid))
        assertEquals(eventsBefore, eventCount(uid))
    }

    @Test
    fun `explicit null clears nullable fields while absent fields remain unchanged`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-clear"))
        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                name = ProfilePatchValue.Set("kept name"),
                avatar = ProfilePatchValue.Set("https://example.test/avatar.png"),
                sex = ProfilePatchValue.Set(2),
                phone = ProfilePatchValue.Set(uniquePhone("15")),
            ),
        )
        val eventsBefore = eventCount(uid)

        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                avatar = ProfilePatchValue.Set(null),
                phone = ProfilePatchValue.Set(null),
            ),
        )

        val persisted = ctx.userService.getProfile(uid)
        assertEquals("kept name", persisted.name)
        assertEquals(2, persisted.sex)
        assertNull(persisted.avatar)
        assertNull(persisted.phone)
        assertEquals(eventsBefore + 1L, eventCount(uid))
        assertEquals(persisted, latestEvent(uid))
    }

    @Test
    fun `empty and same-value patches do not write or emit USER_UPDATED`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-noop"))
        val before = ctx.userService.getProfile(uid)
        val updatedAtBefore = updatedAt(uid)
        val eventsBefore = eventCount(uid)

        ctx.userService.updateProfile(uid, ProfilePatch())
        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                name = ProfilePatchValue.Set(before.name),
                avatar = ProfilePatchValue.Set(before.avatar),
                sex = ProfilePatchValue.Set(before.sex),
                phone = ProfilePatchValue.Set(before.phone),
            ),
        )

        assertEquals(before, ctx.userService.getProfile(uid))
        assertEquals(updatedAtBefore, updatedAt(uid))
        assertEquals(eventsBefore, eventCount(uid))
    }

    @Test
    fun `phone uniqueness error is safe and rolls back profile fact with its event`() = runTest {
        val phone = uniquePhone("16")
        ctx.userService.register(uniqueUsername("profile-phone-owner"), "pass123", "phone owner", phone)
        val targetUid = ctx.registerUser(uniqueUsername("profile-phone-conflict"))
        val before = ctx.userService.getProfile(targetUid)
        val eventsBefore = eventCount(targetUid)

        val error = assertFailsWith<IllegalArgumentException> {
            ctx.userService.updateProfile(
                targetUid,
                ProfilePatch(
                    name = ProfilePatchValue.Set("must also roll back"),
                    phone = ProfilePatchValue.Set(phone),
                ),
            )
        }

        assertEquals("手机号已被使用", error.message)
        assertFalse(requireNotNull(error.message).contains(phone))
        val publicCauseChain = generateSequence(error as Throwable?) { it.cause }.toList()
        assertTrue(publicCauseChain.all { it is IllegalArgumentException })
        assertTrue(publicCauseChain.all { it.message == "手机号已被使用" && !it.message.orEmpty().contains(phone) })

        val registry = RpcStubRegistry().apply {
            register(UserRpcContract.SERVICE) { session -> UserRpcImpl(session.uid, ctx.userService) }
        }
        val response = RpcDispatcher(registry).dispatch(
            uid = targetUid,
            deviceId = "profile-conflict-device",
            userCredentialEpoch = 1L,
            deviceCredentialEpoch = 1L,
            sessionId = "profile-conflict-session",
            invoke = InvokePayload(
                requestId = 7,
                serviceId = UserRpcContract.SERVICE,
                methodId = UserRpcContract.M_UPDATE_PROFILE,
                payload = ProtoCodec.encode(
                    ProfilePatch(phone = ProfilePatchValue.Set(phone)),
                ),
            ),
        )
        assertEquals(400, response.status)
        val responseMessage = requireNotNull(response.payload).decodeToString()
        assertEquals("手机号已被使用", responseMessage)
        assertFalse(responseMessage.contains(phone))
        assertEquals(before, ctx.userService.getProfile(targetUid))
        assertEquals(eventsBefore, eventCount(targetUid))
    }

    @Test
    fun `second profile update cannot cross User FOR UPDATE while the first holds it`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-concurrent"))
        val phone = uniquePhone("17")
        val eventBaseline = eventCount(uid)
        val firstHasUserLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondReachedLockStatement = CountDownLatch(1)
        val secondHasUserLock = CountDownLatch(1)
        val firstRepository = ExposedUserRepository(
            object : UserProfileLockObserver {
                override fun beforeUserRowLock(uid: String) = Unit

                override fun afterUserRowLock(uid: String) {
                    firstHasUserLock.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "timed out holding first User lock" }
                }
            },
        )
        val secondRepository = ExposedUserRepository(
            object : UserProfileLockObserver {
                override fun beforeUserRowLock(uid: String) {
                    secondReachedLockStatement.countDown()
                }

                override fun afterUserRowLock(uid: String) {
                    secondHasUserLock.countDown()
                }
            },
        )
        val firstService = userService(
            ExposedPgUnitOfWork(onEventsCommitted = {}),
            firstRepository,
        )
        val secondService = userService(
            ExposedPgUnitOfWork(onEventsCommitted = {}),
            secondRepository,
        )

        val firstWrite = async(Dispatchers.IO) {
            firstService.updateProfile(
                uid,
                ProfilePatch(name = ProfilePatchValue.Set("concurrent name")),
            )
        }
        var secondWrite: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { firstHasUserLock.await(5, TimeUnit.SECONDS) })
            secondWrite = async(Dispatchers.IO) {
                secondService.updateProfile(
                    uid,
                    ProfilePatch(phone = ProfilePatchValue.Set(phone)),
                )
            }
            assertTrue(withContext(Dispatchers.IO) {
                secondReachedLockStatement.await(5, TimeUnit.SECONDS)
            })
            assertFalse(
                withContext(Dispatchers.IO) { secondHasUserLock.await(300, TimeUnit.MILLISECONDS) },
                "second update crossed FOR UPDATE before the first transaction released the User row",
            )
            assertFalse(requireNotNull(secondWrite).isCompleted)
        } finally {
            releaseFirst.countDown()
        }
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { listOf(firstWrite, requireNotNull(secondWrite)).awaitAll() }
        }
        assertTrue(secondHasUserLock.await(0, TimeUnit.MILLISECONDS))

        val persisted = ctx.userService.getProfile(uid)
        assertEquals("concurrent name", persisted.name)
        assertEquals(phone, persisted.phone)
        assertEquals(eventBaseline + 2L, eventCount(uid))
        assertEquals(persisted, latestEvent(uid))
    }

    @Test
    fun `profile repository rejects a foreign transaction handle`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-context"))

        assertFailsWith<IllegalStateException> {
            ctx.userRepo.updateProfile(
                object : PgTransactionContext {},
                uid,
                ProfilePatch(name = ProfilePatchValue.Set("forbidden")),
            )
        }
    }

    private fun userService(
        unitOfWork: PgUnitOfWork,
        repository: UserRepository = ctx.userRepo,
    ): UserService = UserService(
        userStore = UserStore(repository),
        unitOfWork = unitOfWork,
    )

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedUserProfileRollback
            }
        },
    )

    private fun eventCount(uid: String): Long = transaction {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.USER_UPDATED.code)
        }.count()
    }

    private fun latestEvent(uid: String): User = transaction {
        val payload = SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.USER_UPDATED.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .single()[SyncEvents.payload]
        ProtoCodec.decode(User, payload)
    }

    private fun updatedAt(uid: String): Long = transaction {
        Users.selectAll().where { Users.uid eq uid }.single()[Users.updatedAt]
    }

    private fun uniquePhone(prefix: String): String =
        prefix + System.nanoTime().toString().takeLast(9).padStart(9, '0')

    private object InjectedUserProfileRollback : InjectedUserProfileRollbackException()
    private open class InjectedUserProfileRollbackException : RuntimeException("injected user profile rollback")
}
