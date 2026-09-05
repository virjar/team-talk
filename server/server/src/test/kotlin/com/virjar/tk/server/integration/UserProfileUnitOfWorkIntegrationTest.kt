package com.virjar.tk.server.integration

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.server.domain.user.UserProfileChangePublisher
import com.virjar.tk.server.domain.user.UserService
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.repository.ExposedUserRepository
import com.virjar.tk.server.infra.db.repository.ExposedUserAvatarReferences
import com.virjar.tk.server.infra.db.repository.UserProfileLockObserver
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.ProfilePatchValue
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.server.protocol.rpc.RpcStubRegistry
import com.virjar.tk.server.protocol.rpc.UserRpcImpl
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
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
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 针对 User 资料事实与 USER_UPDATED 持久事件原子性的真实 PostgreSQL 门禁测试。 */
class UserProfileUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `avatar binding retry replacement and clear preserve exact current reference ACL`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("profile-avatar-owner"))
        val reader = ctx.registerUser(uniqueUsername("profile-avatar-reader"))
        val first = stagingAvatar(owner, "first")
        val eventsBefore = eventCount(owner)
        val revisionBefore = ctx.userService.getProfile(owner).revision

        assertTrue(ctx.fileStore.isStaging(first.path))
        ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(first)))

        val references = ExposedUserAvatarReferences(ctx.database)
        assertFalse(ctx.fileStore.isStaging(first.path))
        assertEquals(first, ctx.userService.getProfile(owner).avatar)
        assertEquals(revisionBefore + 1L, ctx.userService.getProfile(owner).revision)
        assertEquals(setOf(first.path), references.getReferencedPaths(setOf(first.path)))
        assertTrue(ctx.attachmentAccess.canRead(reader, first.path))
        assertEquals(eventsBefore + 1L, eventCount(owner))

        // 响应丢失后的精确重试是 no-op，即使 FileStore 已不再处于暂存状态。
        ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(first)))
        assertEquals(eventsBefore + 1L, eventCount(owner))
        assertEquals(revisionBefore + 1L, ctx.userService.getProfile(owner).revision)

        val second = stagingAvatar(owner, "second")
        ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(second)))
        assertEquals(second, ctx.userService.getProfile(owner).avatar)
        assertEquals(revisionBefore + 2L, ctx.userService.getProfile(owner).revision)
        assertEquals(setOf(second.path), references.getReferencedPaths(setOf(first.path, second.path)))
        assertFalse(ctx.attachmentAccess.canRead(reader, first.path))
        assertTrue(ctx.attachmentAccess.canRead(reader, second.path))

        ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(null)))
        assertNull(ctx.userService.getProfile(owner).avatar)
        assertEquals(revisionBefore + 3L, ctx.userService.getProfile(owner).revision)
        assertTrue(references.getReferencedPaths(setOf(first.path, second.path)).isEmpty())
        assertFalse(ctx.attachmentAccess.canRead(reader, second.path))
    }

    @Test
    fun `avatar update rejects foreign non-image drifted and previously bound assets`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("profile-avatar-policy-owner"))
        val other = ctx.registerUser(uniqueUsername("profile-avatar-policy-other"))

        val foreign = stagingAvatar(owner, "foreign")
        assertFailsWith<IllegalArgumentException> {
            ctx.userService.updateProfile(other, ProfilePatch(avatar = ProfilePatchValue.Set(foreign)))
        }
        assertTrue(ctx.fileStore.isStaging(foreign.path))

        val nonImage = stagingAvatar(owner, "not-image", contentType = "application/octet-stream")
        assertFailsWith<IllegalArgumentException> {
            ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(nonImage)))
        }
        assertTrue(ctx.fileStore.isStaging(nonImage.path))

        val drifted = stagingAvatar(owner, "drifted")
        assertFailsWith<IllegalArgumentException> {
            ctx.userService.updateProfile(
                owner,
                ProfilePatch(avatar = ProfilePatchValue.Set(drifted.copy(size = drifted.size + 1))),
            )
        }
        assertTrue(ctx.fileStore.isStaging(drifted.path))

        val alreadyBound = stagingAvatar(owner, "already-bound")
        ctx.fileStore.markBusinessBound(listOf(alreadyBound.path))
        assertFailsWith<IllegalArgumentException> {
            ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(alreadyBound)))
        }
        assertNull(ctx.userService.getProfile(owner).avatar)
    }

    @Test
    fun `failed profile commit leaves a new avatar staging and retryable`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-avatar-pg-failure"))
        val avatar = stagingAvatar(uid, "pg-failure")
        val eventsBefore = eventCount(uid)

        assertIs<InjectedUserProfileRollbackException>(runCatching {
            userService(failingUnitOfWork()).updateProfile(
                uid,
                ProfilePatch(avatar = ProfilePatchValue.Set(avatar)),
            )
        }.exceptionOrNull())

        assertNull(ctx.userService.getProfile(uid).avatar)
        assertTrue(ctx.fileStore.isStaging(avatar.path))
        assertEquals(eventsBefore, eventCount(uid))

        ctx.userService.updateProfile(uid, ProfilePatch(avatar = ProfilePatchValue.Set(avatar)))
        assertEquals(avatar, ctx.userService.getProfile(uid).avatar)
        assertFalse(ctx.fileStore.isStaging(avatar.path))
    }

    @Test
    fun `publication failure is repaired without duplicate event and stale retry cannot replace newer avatar`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-avatar-publication-failure"))
        val catalog = ControllablePublicationCatalog(ctx.fileStore)
        val transientPublications = mutableListOf<User>()
        val service = userService(
            unitOfWork = ctx.pgUnitOfWork,
            attachmentCatalog = catalog,
            profileChanges = UserProfileChangePublisher { user, _ ->
                transientPublications += user
            },
        )
        val first = stagingAvatar(uid, "publication-first")
        val eventsBefore = eventCount(uid)

        catalog.failNextPublication()
        assertIs<InjectedAvatarPublicationException>(runCatching {
            service.updateProfile(uid, ProfilePatch(avatar = ProfilePatchValue.Set(first)))
        }.exceptionOrNull())
        assertEquals(first, ctx.userService.getProfile(uid).avatar)
        assertTrue(ctx.fileStore.isStaging(first.path))
        assertEquals(eventsBefore + 1L, eventCount(uid))
        assertEquals(listOf(ctx.userService.getProfile(uid)), transientPublications)

        // 精确重试发布已提交的描述符，但从 PG patch 中剥离 avatar，
        // 因此不会发出重复事件。
        service.updateProfile(uid, ProfilePatch(avatar = ProfilePatchValue.Set(first)))
        assertFalse(ctx.fileStore.isStaging(first.path))
        assertEquals(eventsBefore + 1L, eventCount(uid))
        assertEquals(1, transientPublications.size)

        val second = stagingAvatar(uid, "publication-second")
        catalog.failNextPublication()
        assertIs<InjectedAvatarPublicationException>(runCatching {
            service.updateProfile(uid, ProfilePatch(avatar = ProfilePatchValue.Set(second)))
        }.exceptionOrNull())
        assertEquals(second, ctx.userService.getProfile(uid).avatar)
        assertTrue(ctx.fileStore.isStaging(second.path))

        // 之后的替换必须先发布已提交但仍暂存的当前头像。旧的未知结果重试
        // 是已绑定、非当前的描述符，不能覆盖它。
        val third = stagingAvatar(uid, "publication-third")
        service.updateProfile(uid, ProfilePatch(avatar = ProfilePatchValue.Set(third)))
        assertFalse(ctx.fileStore.isStaging(second.path))
        assertFalse(ctx.fileStore.isStaging(third.path))
        assertFailsWith<IllegalArgumentException> {
            service.updateProfile(uid, ProfilePatch(avatar = ProfilePatchValue.Set(second)))
        }
        assertEquals(third, ctx.userService.getProfile(uid).avatar)
    }

    @Test
    fun `USER_UPDATED durable audience is self and friends while transient publisher receives their exclusion set`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("profile-fanout-owner"))
        val friend = ctx.registerUser(uniqueUsername("profile-fanout-friend"))
        val outsider = ctx.registerUser(uniqueUsername("profile-fanout-outsider"))
        ctx.pgUnitOfWork.write {
            ctx.contactRepo.addFriend(transaction, owner, friend)
        }
        val ownerBefore = eventCount(owner)
        val friendBefore = eventCount(friend)
        val outsiderBefore = eventCount(outsider)
        val transientPublications = mutableListOf<Pair<User, Set<String>>>()
        val service = userService(
            unitOfWork = ctx.pgUnitOfWork,
            profileChanges = UserProfileChangePublisher { user, excluded ->
                transientPublications += user to excluded
            },
        )

        service.updateProfile(
            owner,
            ProfilePatch(name = ProfilePatchValue.Set("好友可见资料")),
        )

        val updated = ctx.userService.getProfile(owner)
        assertEquals(ownerBefore + 1L, eventCount(owner))
        assertEquals(friendBefore + 1L, eventCount(friend))
        assertEquals(outsiderBefore, eventCount(outsider))
        assertEquals(updated, latestEvent(friend))
        assertEquals(listOf(updated to setOf(owner, friend)), transientPublications)
    }

    @Test
    fun `revision orders a newer transient profile after an older durable friend event`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("profile-mixed-owner"))
        val observer = ctx.registerUser(uniqueUsername("profile-mixed-observer"))
        ctx.pgUnitOfWork.write {
            ctx.contactRepo.addFriend(transaction, owner, observer)
        }
        val transientPublications = mutableListOf<Pair<User, Set<String>>>()
        val service = userService(
            unitOfWork = ctx.pgUnitOfWork,
            profileChanges = UserProfileChangePublisher { user, excluded ->
                transientPublications += user to excluded
            },
        )

        service.updateProfile(owner, ProfilePatch(name = ProfilePatchValue.Set("durable first")))
        val delayedDurable = latestEvent(observer)
        ctx.pgUnitOfWork.write {
            ctx.contactRepo.removeFriend(transaction, owner, observer)
        }
        service.updateProfile(owner, ProfilePatch(name = ProfilePatchValue.Set("transient second")))

        val newerTransient = transientPublications.last().first
        assertEquals("durable first", delayedDurable.name)
        assertEquals("transient second", newerTransient.name)
        assertEquals(delayedDurable.revision + 1L, newerTransient.revision)
        assertEquals(setOf(owner), transientPublications.last().second)
        assertEquals(delayedDurable, latestEvent(observer), "former friend receives no newer durable USER_UPDATED")
    }

    @Test
    fun `transient profile publication failure cannot turn a committed update into RPC failure`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-transient-failure"))
        var attempts = 0
        val service = userService(
            unitOfWork = ctx.pgUnitOfWork,
            profileChanges = UserProfileChangePublisher { _, _ ->
                attempts += 1
                throw InjectedTransientProfilePublicationException()
            },
        )

        service.updateProfile(uid, ProfilePatch(name = ProfilePatchValue.Set("committed name")))

        assertEquals(1, attempts)
        assertEquals("committed name", ctx.userService.getProfile(uid).name)
        assertEquals("committed name", latestEvent(uid).name)
    }

    @Test
    fun `personal conversation snapshots carry exact peer identity and avatar descriptor`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("profile-conversation-owner"))
        val peer = ctx.registerUser(uniqueUsername("profile-conversation-peer"))
        val avatar = stagingAvatar(owner, "conversation")
        ctx.userService.updateProfile(owner, ProfilePatch(avatar = ProfilePatchValue.Set(avatar)))
        val chat = ctx.chatService.createPersonalChat(owner, peer)

        val coldSnapshot = requireNotNull(ctx.conversationRepo.getConversation(peer, chat.chatId))
        assertEquals(owner, coldSnapshot.peerUid)
        assertEquals(ctx.userService.getProfile(owner).revision, coldSnapshot.peerRevision)
        assertEquals(avatar, coldSnapshot.chatAvatar)

        ctx.messageService.sendMessage(
            owner,
            Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = owner,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = 1,
                body = buildRichTextBody("refresh conversation"),
            ),
        )
        val projected = latestConversationEvent(peer)
        assertEquals(owner, projected.peerUid)
        assertEquals(ctx.userService.getProfile(owner).revision, projected.peerRevision)
        assertEquals(avatar, projected.chatAvatar)
    }

    @Test
    fun `profile fact and durable event roll back together`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-rollback"))
        val avatar = stagingAvatar(uid, "rollback")
        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                avatar = ProfilePatchValue.Set(avatar),
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
        val avatar = stagingAvatar(uid, "clear")
        ctx.userService.updateProfile(
            uid,
            ProfilePatch(
                name = ProfilePatchValue.Set("kept name"),
                avatar = ProfilePatchValue.Set(avatar),
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
        var transientPublications = 0
        val service = userService(
            unitOfWork = ctx.pgUnitOfWork,
            profileChanges = UserProfileChangePublisher { _, _ -> transientPublications += 1 },
        )

        service.updateProfile(uid, ProfilePatch())
        service.updateProfile(
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
        assertEquals(0, transientPublications)
    }

    @Test
    fun `phone uniqueness error is safe and rolls back profile fact with its event`() = runTest {
        val phone = uniquePhone("16")
        ctx.registerHuman(uniqueUsername("profile-phone-owner"), "pass123", "phone owner", phone)
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
        val revisionBaseline = ctx.userService.getProfile(uid).revision
        val firstHasUserLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondReachedLockStatement = CountDownLatch(1)
        val secondHasUserLock = CountDownLatch(1)
        val firstRepository = ExposedUserRepository(
            database = ctx.database,
            profileLockObserver = object : UserProfileLockObserver {
                override fun beforeUserRowLock(uid: String) = Unit

                override fun afterUserRowLock(uid: String) {
                    firstHasUserLock.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "timed out holding first User lock" }
                }
            },
        )
        val secondRepository = ExposedUserRepository(
            database = ctx.database,
            profileLockObserver = object : UserProfileLockObserver {
                override fun beforeUserRowLock(uid: String) {
                    secondReachedLockStatement.countDown()
                }

                override fun afterUserRowLock(uid: String) {
                    secondHasUserLock.countDown()
                }
            },
        )
        val firstService = userService(
            ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
            firstRepository,
        )
        val secondService = userService(
            ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
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
        assertEquals(revisionBaseline + 2L, persisted.revision)
        assertEquals(eventBaseline + 2L, eventCount(uid))
        assertEquals(persisted, latestEvent(uid))
    }

    @Test
    fun `profile repository rejects a foreign transaction handle`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("profile-uow-context"))

        assertFailsWith<IllegalStateException> {
            ctx.userRepo.updateProfile(
                object : PgWriteTransactionContext {},
                uid,
                ProfilePatch(name = ProfilePatchValue.Set("forbidden")),
            )
        }
    }

    private fun userService(
        unitOfWork: PgUnitOfWork,
        repository: UserRepository = ctx.userRepo,
        attachmentCatalog: AttachmentCatalog = ctx.fileStore,
        profileChanges: UserProfileChangePublisher = UserProfileChangePublisher { _, _ -> },
    ): UserService = UserService(
        users = repository,
        unitOfWork = unitOfWork,
        passwordHasher = ctx.passwordHasher,
        profileAudience = com.virjar.tk.server.domain.user.UserProfileAudience { transaction, uid ->
            ctx.contactRepo.listFriendUids(transaction, uid)
        },
        attachmentCatalog = attachmentCatalog,
        attachmentLifecycle = AttachmentLifecycleGate(),
        profileChanges = profileChanges,
    )

    private fun stagingAvatar(
        uid: String,
        label: String,
        contentType: String = "image/png",
    ): Attachment {
        val path = ctx.fileStore.store(
            uid = uid,
            fileName = "$label.png",
            contentType = contentType,
            inputStream = ByteArrayInputStream("avatar-$label".encodeToByteArray()),
        )
        return requireNotNull(ctx.fileStore.getAttachment(path))
    }

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
        database = ctx.database,
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedUserProfileRollback
            }
        },
    )

    private fun eventCount(uid: String): Long = transaction(ctx.database) {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.USER_UPDATED.code)
        }.count()
    }

    private fun latestEvent(uid: String): User = transaction(ctx.database) {
        val payload = SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.USER_UPDATED.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .single()[SyncEvents.payload]
        ProtoCodec.decode(User, payload)
    }

    private fun latestConversationEvent(uid: String): Conversation = transaction(ctx.database) {
        val payload = SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.eventType eq NotifyType.CONVERSATION_UPDATED.code)
        }.orderBy(SyncEvents.streamSeq, SortOrder.DESC)
            .limit(1)
            .single()[SyncEvents.payload]
        ProtoCodec.decode(Conversation, payload)
    }

    private fun updatedAt(uid: String): Long = transaction(ctx.database) {
        Users.selectAll().where { Users.uid eq uid }.single()[Users.updatedAt]
    }

    private fun uniquePhone(prefix: String): String =
        prefix + System.nanoTime().toString().takeLast(9).padStart(9, '0')

    private object InjectedUserProfileRollback : InjectedUserProfileRollbackException()
    private open class InjectedUserProfileRollbackException : RuntimeException("injected user profile rollback")

    private class ControllablePublicationCatalog(
        private val delegate: AttachmentCatalog,
    ) : AttachmentCatalog {
        private var failuresRemaining = 0

        fun failNextPublication() {
            failuresRemaining += 1
        }

        override fun getAttachment(path: String): Attachment? = delegate.getAttachment(path)

        override fun getOwnerUid(path: String): String? = delegate.getOwnerUid(path)

        override fun isStaging(path: String): Boolean = delegate.isStaging(path)

        override fun markBusinessBound(paths: Collection<String>) {
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw InjectedAvatarPublicationException()
            }
            delegate.markBusinessBound(paths)
        }
    }

    private class InjectedAvatarPublicationException : RuntimeException("injected avatar publication failure")
    private class InjectedTransientProfilePublicationException : RuntimeException("injected transient failure")
}
