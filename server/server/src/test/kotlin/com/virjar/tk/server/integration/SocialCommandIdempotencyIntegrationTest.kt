package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandExpiredException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.contact.ContactDecisionType
import com.virjar.tk.server.domain.contact.ContactPolicy
import com.virjar.tk.server.domain.contact.ContactService
import com.virjar.tk.server.domain.chat.InviteLinkPolicy
import com.virjar.tk.server.domain.chat.InviteLinkCreationCommand
import com.virjar.tk.server.infra.db.ContactDecisionReceipts
import com.virjar.tk.server.infra.db.GroupInviteLinks
import com.virjar.tk.server.infra.db.InviteLinkCreationReceipts
import com.virjar.tk.server.infra.db.ReliableCommandReceiptMaintenance
import com.virjar.tk.server.infra.db.repository.ContactRepositoryHooks
import com.virjar.tk.server.infra.db.repository.ContactRepositoryStage
import com.virjar.tk.server.infra.db.repository.ExposedContactRepository
import com.virjar.tk.server.infra.db.repository.ExposedInviteLinkRepository
import com.virjar.tk.server.infra.db.repository.InviteLinkRepositoryHooks
import com.virjar.tk.server.infra.db.repository.InviteLinkRepositoryStage
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocialCommandIdempotencyIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private val ZERO_FINGERPRINT = "0".repeat(64)
    }

    private val ctx get() = ext.env

    @Test
    fun `concurrent exact contact decision returns one result and one durable receipt`() = runBlocking {
        val sender = ctx.registerUser(uniqueUsername("decision-sender"))
        val receiver = ctx.registerUser(uniqueUsername("decision-receiver"))
        ctx.contactService(sender).apply(receiver, "hello")
        val token = ctx.contactService(receiver).listPendingApplies().single().token!!
        val operationId = UUID.randomUUID().toString()
        val issuedAt = System.currentTimeMillis()

        val firstReserved = CountDownLatch(1)
        val bothAttempted = CountDownLatch(2)
        val releaseFirst = CountDownLatch(1)
        val repository = ExposedContactRepository(
            database = ctx.database,
            userRepo = ctx.userRepo,
            hooks = ContactRepositoryHooks { stage, seenOperation ->
                if (seenOperation != operationId) return@ContactRepositoryHooks
                when (stage) {
                    ContactRepositoryStage.BEFORE_DECISION_RESERVATION -> bothAttempted.countDown()
                    ContactRepositoryStage.AFTER_DECISION_RESERVATION -> {
                        firstReserved.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS)) { "test did not release first command" }
                    }
                }
            },
        )
        val service = ContactService(repository, ctx.pgUnitOfWork, ctx.userRepo)

        val (first, second) = coroutineScope {
            val firstAttempt = async(Dispatchers.IO) {
                service.accept(receiver, operationId, issuedAt, token)
            }
            withContext(Dispatchers.IO) {
                assertTrue(firstReserved.await(5, TimeUnit.SECONDS))
            }
            val secondAttempt = async(Dispatchers.IO) {
                service.accept(receiver, operationId, issuedAt, token)
            }
            withContext(Dispatchers.IO) {
                assertTrue(bothAttempted.await(5, TimeUnit.SECONDS))
            }
            releaseFirst.countDown()
            firstAttempt.await() to secondAttempt.await()
        }

        assertEquals(first, second)
        assertTrue(ctx.contactRepo.isFriend(sender, receiver))
        assertEquals(1L, transaction(ctx.database) {
            ContactDecisionReceipts.selectAll().where {
                (ContactDecisionReceipts.actorUid eq receiver) and
                    (ContactDecisionReceipts.operationId eq operationId)
            }.count()
        })
        assertFailsWith<ReliableCommandConflictException> {
            service.reject(receiver, operationId, issuedAt, token)
        }
        assertFailsWith<ReliableCommandConflictException> {
            service.accept(receiver, operationId, issuedAt + 1L, token)
        }

        val otherSender = ctx.registerUser(uniqueUsername("decision-other"))
        ctx.contactService(otherSender).apply(receiver, "different payload")
        val otherToken = ctx.contactService(receiver).listPendingApplies().single().token!!
        assertFailsWith<ReliableCommandConflictException> {
            service.accept(receiver, operationId, issuedAt, otherToken)
        }
    }

    @Test
    fun `reject replay and invite creation replay return their original terminal result`() = runBlocking {
        val sender = ctx.registerUser(uniqueUsername("reject-sender"))
        val receiver = ctx.registerUser(uniqueUsername("reject-receiver"))
        ctx.contactService(sender).apply(receiver, null)
        val token = ctx.contactService(receiver).listPendingApplies().single().token!!
        val decisionOperation = UUID.randomUUID().toString()
        val decisionIssuedAt = System.currentTimeMillis()
        val decisionEventsBefore = contactApplyEventCount(sender) + contactApplyEventCount(receiver)

        val rejected = ctx.contactService(receiver).reject(decisionOperation, decisionIssuedAt, token)
        val decisionEventsAfterCommit = contactApplyEventCount(sender) + contactApplyEventCount(receiver)
        assertEquals(decisionEventsBefore + 2, decisionEventsAfterCommit)
        assertEquals(
            rejected,
            ctx.contactService(receiver).reject(decisionOperation, decisionIssuedAt, token),
        )
        assertEquals(
            decisionEventsAfterCommit,
            contactApplyEventCount(sender) + contactApplyEventCount(receiver),
            "exact replay must not duplicate cross-device projection hints",
        )

        val owner = ctx.registerUser(uniqueUsername("invite-owner"))
        val group = ctx.chatService.createGroup("Idempotent invite", null, owner, emptyList())
        val inviteOperation = UUID.randomUUID().toString()
        val inviteIssuedAt = System.currentTimeMillis()
        val invite = ctx.chatService.createInviteLink(
            inviteOperation,
            inviteIssuedAt,
            owner,
            group.chatId,
            "项目邀请",
            3,
            0,
        )
        // 链接生命周期不再持有命令重放：即使已撤销的结果也会被精确返回。
        ctx.chatService.revokeInviteLink(owner, invite)
        assertEquals(
            invite,
            ctx.chatService.createInviteLink(
                inviteOperation,
                inviteIssuedAt,
                owner,
                group.chatId,
                "项目邀请",
                3,
                0,
            ),
        )
        assertFailsWith<ReliableCommandConflictException> {
            ctx.chatService.createInviteLink(
                inviteOperation,
                inviteIssuedAt,
                owner,
                group.chatId,
                "另一请求",
                3,
                0,
            )
        }
        assertFailsWith<ReliableCommandConflictException> {
            ctx.chatService.createInviteLink(
                inviteOperation,
                inviteIssuedAt + 1L,
                owner,
                group.chatId,
                "项目邀请",
                3,
                0,
            )
        }
        assertEquals(1L, transaction(ctx.database) {
            InviteLinkCreationReceipts.selectAll().where {
                (InviteLinkCreationReceipts.actorUid eq owner) and
                    (InviteLinkCreationReceipts.operationId eq inviteOperation)
            }.count()
        })
    }

    @Test
    fun `invite replay rechecks current admin authority before returning its secret`() = runBlocking {
        val owner = ctx.registerUser(uniqueUsername("invite-auth-owner"))
        val actor = ctx.registerUser(uniqueUsername("invite-auth-actor"))
        val group = ctx.chatService.createGroup("Replay authorization", null, owner, listOf(actor))
        ctx.chatService.setRole(owner, group.chatId, actor, 1)
        val operationId = UUID.randomUUID().toString()
        val issuedAt = System.currentTimeMillis()

        ctx.chatService.createInviteLink(
            operationId,
            issuedAt,
            actor,
            group.chatId,
            "撤权前创建",
            0,
            0,
        )
        ctx.chatService.setRole(owner, group.chatId, actor, 0)

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createInviteLink(
                operationId,
                issuedAt,
                actor,
                group.chatId,
                "撤权前创建",
                0,
                0,
            )
        }
    }

    @Test
    fun `expiry sweeper race cannot resurrect an invite operation`() = runBlocking {
        val owner = ctx.registerUser(uniqueUsername("invite-expiry-race-owner"))
        val group = ctx.chatService.createGroup("Expiry race", null, owner, emptyList())
        val operationId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val boundaryNow = 1_800_000_000_000L
        val issuedAt = boundaryNow - ReliableCommandPolicy.RETRY_HORIZON_MILLIS
        val fingerprint = reliableCommandFingerprint(
            "invite-link-create-v1",
            owner,
            issuedAt.toString(),
            group.chatId,
            "边界链接",
            "0",
            "0",
        )
        transaction(ctx.database) {
            GroupInviteLinks.insert {
                it[GroupInviteLinks.token] = token
                it[GroupInviteLinks.chatId] = group.chatId
                it[GroupInviteLinks.creatorUid] = owner
                it[GroupInviteLinks.name] = "边界链接"
                it[GroupInviteLinks.createdAt] = issuedAt
            }
            InviteLinkCreationReceipts.insert {
                it[InviteLinkCreationReceipts.actorUid] = owner
                it[InviteLinkCreationReceipts.operationId] = operationId
                it[InviteLinkCreationReceipts.requestFingerprint] = fingerprint
                it[InviteLinkCreationReceipts.chatId] = group.chatId
                it[InviteLinkCreationReceipts.token] = token
                it[InviteLinkCreationReceipts.issuedAt] = issuedAt
                it[InviteLinkCreationReceipts.expiresAt] = boundaryNow
                it[InviteLinkCreationReceipts.createdAt] = issuedAt
            }
        }

        val clock = AtomicLong(boundaryNow)
        val reservationReleased = CountDownLatch(1)
        val initialValidationFinished = CountDownLatch(1)
        val repository = ExposedInviteLinkRepository(
            database = ctx.database,
            wallClockMillis = clock::get,
            hooks = InviteLinkRepositoryHooks { stage, seenOperation ->
                if (
                    stage == InviteLinkRepositoryStage.BEFORE_CREATION_RESERVATION &&
                    seenOperation == operationId
                ) {
                    initialValidationFinished.countDown()
                    check(reservationReleased.await(5, TimeUnit.SECONDS)) {
                        "test did not release invite reservation"
                    }
                }
            },
        )
        val command = InviteLinkCreationCommand(
            operationId = operationId,
            issuedAt = issuedAt,
            creatorUid = owner,
            chatId = group.chatId,
            name = "边界链接",
            maxUses = 0,
            expiresAt = 0L,
            requestFingerprint = fingerprint,
        )
        val attempt = async(Dispatchers.IO) {
            runCatching {
                ctx.pgUnitOfWork.write {
                    repository.createInviteLink(transaction, command) { }
                }
            }.exceptionOrNull()
        }
        withContext(Dispatchers.IO) {
            assertTrue(initialValidationFinished.await(5, TimeUnit.SECONDS))
        }
        clock.incrementAndGet()
        val swept = try {
            ReliableCommandReceiptMaintenance(
                database = ctx.database,
                wallClockMillis = clock::get,
            ).cleanupExpiredReceipts()
        } finally {
            reservationReleased.countDown()
        }
        assertTrue(
            swept.inviteReceiptsDeleted >= 1,
            "global maintenance may also collect expired fixtures left by another test order",
        )

        assertIs<ReliableCommandExpiredException>(attempt.await())
        assertEquals(listOf(token), ctx.chatService.listInviteLinks(owner, group.chatId).map { it.token })
        assertEquals(0L, transaction(ctx.database) {
            InviteLinkCreationReceipts.selectAll().where {
                (InviteLinkCreationReceipts.actorUid eq owner) and
                    (InviteLinkCreationReceipts.operationId eq operationId)
            }.count()
        })
    }

    @Test
    fun `exact invite replay crossing its horizon cannot return the secret`() = runBlocking {
        val owner = ctx.registerUser(uniqueUsername("invite-replay-expiry-owner"))
        val group = ctx.chatService.createGroup("Replay expiry", null, owner, emptyList())
        val operationId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val boundaryNow = 1_800_000_000_000L
        val issuedAt = boundaryNow - ReliableCommandPolicy.RETRY_HORIZON_MILLIS
        val fingerprint = reliableCommandFingerprint(
            "invite-link-create-v1",
            owner,
            issuedAt.toString(),
            group.chatId,
            "边界重放",
            "0",
            "0",
        )
        transaction(ctx.database) {
            GroupInviteLinks.insert {
                it[GroupInviteLinks.token] = token
                it[GroupInviteLinks.chatId] = group.chatId
                it[GroupInviteLinks.creatorUid] = owner
                it[GroupInviteLinks.name] = "边界重放"
                it[GroupInviteLinks.createdAt] = issuedAt
            }
            InviteLinkCreationReceipts.insert {
                it[InviteLinkCreationReceipts.actorUid] = owner
                it[InviteLinkCreationReceipts.operationId] = operationId
                it[InviteLinkCreationReceipts.requestFingerprint] = fingerprint
                it[InviteLinkCreationReceipts.chatId] = group.chatId
                it[InviteLinkCreationReceipts.token] = token
                it[InviteLinkCreationReceipts.issuedAt] = issuedAt
                it[InviteLinkCreationReceipts.expiresAt] = boundaryNow
                it[InviteLinkCreationReceipts.createdAt] = issuedAt
            }
        }

        // createInviteLink 在入口处、构造失败的 INSERT 时、重放查找之前以及
        // 返回之前立即各读一次时钟。只有最后一次读取跨越了 horizon，
        // 证明秘密返回守卫独立于准入校验。
        val clockReads = AtomicInteger()
        val repository = ExposedInviteLinkRepository(
            database = ctx.database,
            wallClockMillis = {
                if (clockReads.incrementAndGet() >= 4) boundaryNow + 1L else boundaryNow
            },
        )
        val command = InviteLinkCreationCommand(
            operationId = operationId,
            issuedAt = issuedAt,
            creatorUid = owner,
            chatId = group.chatId,
            name = "边界重放",
            maxUses = 0,
            expiresAt = 0L,
            requestFingerprint = fingerprint,
        )

        assertFailsWith<ReliableCommandExpiredException> {
            ctx.pgUnitOfWork.write {
                repository.createInviteLink(transaction, command) { }
            }
        }
        assertEquals(1L, transaction(ctx.database) {
            InviteLinkCreationReceipts.selectAll().where {
                (InviteLinkCreationReceipts.actorUid eq owner) and
                    (InviteLinkCreationReceipts.operationId eq operationId)
            }.count()
        })
    }

    @Test
    fun `collected expired invite identity can never execute as a new mutation`() = runBlocking {
        val owner = ctx.registerUser(uniqueUsername("expired-invite-owner"))
        val group = ctx.chatService.createGroup("Expired identity", null, owner, emptyList())
        val operationId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val issuedAt = System.currentTimeMillis() - ReliableCommandPolicy.RETRY_HORIZON_MILLIS - 1L
        val expiresAt = ReliableCommandPolicy.expiresAt(issuedAt)

        // 这是旧的成功命令回执变得可回收并被收集后的持久状态。
        // 活动链接副作用有意保持独立。
        transaction(ctx.database) {
            GroupInviteLinks.insert {
                it[GroupInviteLinks.token] = token
                it[GroupInviteLinks.chatId] = group.chatId
                it[GroupInviteLinks.creatorUid] = owner
                it[GroupInviteLinks.name] = "历史链接"
                it[GroupInviteLinks.maxUses] = 0
                it[GroupInviteLinks.createdAt] = issuedAt
            }
            InviteLinkCreationReceipts.insert {
                it[InviteLinkCreationReceipts.actorUid] = owner
                it[InviteLinkCreationReceipts.operationId] = operationId
                it[InviteLinkCreationReceipts.requestFingerprint] = ZERO_FINGERPRINT
                it[InviteLinkCreationReceipts.chatId] = group.chatId
                it[InviteLinkCreationReceipts.token] = token
                it[InviteLinkCreationReceipts.issuedAt] = issuedAt
                it[InviteLinkCreationReceipts.expiresAt] = expiresAt
                it[InviteLinkCreationReceipts.createdAt] = issuedAt
            }
            InviteLinkCreationReceipts.deleteWhere {
                (InviteLinkCreationReceipts.actorUid eq owner) and
                    (InviteLinkCreationReceipts.operationId eq operationId)
            }
        }

        assertFailsWith<ReliableCommandExpiredException> {
            ctx.chatService.createInviteLink(
                operationId,
                issuedAt,
                owner,
                group.chatId,
                "历史链接",
                0,
                0,
            )
        }
        assertEquals(listOf(token), ctx.chatService.listInviteLinks(owner, group.chatId).map { it.token })
    }

    @Test
    fun `unexpired receipt capacity rejects fresh commands without breaking exact replay`() = runBlocking {
        val issuedAt = System.currentTimeMillis()
        val receiptExpiresAt = ReliableCommandPolicy.expiresAt(issuedAt)

        val sender = ctx.registerUser(uniqueUsername("receipt-cap-sender"))
        val receiver = ctx.registerUser(uniqueUsername("receipt-cap-receiver"))
        ctx.contactService(sender).apply(receiver, null)
        val firstContactToken = ctx.contactService(receiver).listPendingApplies().single().token!!
        val firstContactOperation = UUID.randomUUID().toString()
        val accepted = ctx.contactService(receiver).accept(
            firstContactOperation,
            issuedAt,
            firstContactToken,
        )
        val historicalContactResult = ProtoCodec.encode(accepted)
        transaction(ctx.database) {
            ContactDecisionReceipts.batchInsert(
                (1 until ContactPolicy.MAX_DECISION_RECEIPTS_PER_ACTOR).map { index ->
                    deterministicUuid("contact-cap-receipt-$index")
                },
                shouldReturnGeneratedValues = false,
            ) { operationId ->
                this[ContactDecisionReceipts.actorUid] = receiver
                this[ContactDecisionReceipts.operationId] = operationId
                this[ContactDecisionReceipts.requestFingerprint] = ZERO_FINGERPRINT
                this[ContactDecisionReceipts.decision] = ContactDecisionType.ACCEPT
                this[ContactDecisionReceipts.resultPayload] = historicalContactResult
                this[ContactDecisionReceipts.issuedAt] = issuedAt
                this[ContactDecisionReceipts.expiresAt] = receiptExpiresAt
                this[ContactDecisionReceipts.createdAt] = issuedAt
            }
        }
        val secondSender = ctx.registerUser(uniqueUsername("receipt-cap-second-sender"))
        ctx.contactService(secondSender).apply(receiver, null)
        val secondToken = ctx.contactService(receiver).listPendingApplies().single().token!!
        assertFailsWith<ReliableCommandCapacityException> {
            ctx.contactService(receiver).accept(
                UUID.randomUUID().toString(),
                issuedAt,
                secondToken,
            )
        }
        assertEquals(
            accepted,
            ctx.contactService(receiver).accept(firstContactOperation, issuedAt, firstContactToken),
        )
        assertEquals(ContactPolicy.MAX_DECISION_RECEIPTS_PER_ACTOR.toLong(), transaction(ctx.database) {
            ContactDecisionReceipts.selectAll().where {
                ContactDecisionReceipts.actorUid eq receiver
            }.count()
        })

        val owner = ctx.registerUser(uniqueUsername("invite-cap-owner"))
        val group = ctx.chatService.createGroup("Invite receipt capacity", null, owner, emptyList())
        val firstInviteOperation = UUID.randomUUID().toString()
        val firstInvite = ctx.chatService.createInviteLink(
            firstInviteOperation,
            issuedAt,
            owner,
            group.chatId,
            "first",
            0,
            0,
        )
        transaction(ctx.database) {
            InviteLinkCreationReceipts.batchInsert(
                (1 until InviteLinkPolicy.MAX_CREATION_RECEIPTS_PER_ACTOR).map { index ->
                    deterministicUuid("invite-cap-receipt-$index")
                },
                shouldReturnGeneratedValues = false,
            ) { operationId ->
                this[InviteLinkCreationReceipts.actorUid] = owner
                this[InviteLinkCreationReceipts.operationId] = operationId
                this[InviteLinkCreationReceipts.requestFingerprint] = ZERO_FINGERPRINT
                this[InviteLinkCreationReceipts.chatId] = group.chatId
                this[InviteLinkCreationReceipts.token] = deterministicUuid("invite-cap-token-$operationId")
                this[InviteLinkCreationReceipts.issuedAt] = issuedAt
                this[InviteLinkCreationReceipts.expiresAt] = receiptExpiresAt
                this[InviteLinkCreationReceipts.createdAt] = issuedAt
            }
        }
        assertFailsWith<ReliableCommandCapacityException> {
            ctx.chatService.createInviteLink(
                UUID.randomUUID().toString(),
                issuedAt,
                owner,
                group.chatId,
                "must not execute",
                0,
                0,
            )
        }
        assertEquals(
            firstInvite,
            ctx.chatService.createInviteLink(
                firstInviteOperation,
                issuedAt,
                owner,
                group.chatId,
                "first",
                0,
                0,
            ),
        )
        assertEquals(1, ctx.chatService.listInviteLinks(owner, group.chatId).size)
        assertEquals(InviteLinkPolicy.MAX_CREATION_RECEIPTS_PER_ACTOR.toLong(), transaction(ctx.database) {
            InviteLinkCreationReceipts.selectAll().where {
                InviteLinkCreationReceipts.actorUid eq owner
            }.count()
        })
    }

    private fun deterministicUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.encodeToByteArray()).toString()

    private fun contactApplyEventCount(uid: String): Int =
        ctx.syncEventReader.getEventsAfter(uid, afterEventId = 0L, limit = 10_000)
            .count { it.notifyType == NotifyType.CONTACT_APPLY.code }
}
