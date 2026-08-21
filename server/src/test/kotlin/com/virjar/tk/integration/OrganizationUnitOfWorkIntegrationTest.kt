package com.virjar.tk.integration

import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.chat.ChatAccessDeniedException
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.groupfile.GroupFileService
import com.virjar.tk.domain.organization.OrganizationProjectionHooks
import com.virjar.tk.domain.organization.OrganizationProjectionStage
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.infra.db.AutomationBotGrants
import com.virjar.tk.infra.db.AutomationBots
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.GroupInviteLinks
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.OrganizationState
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.infra.db.repository.OrganizationLockHooks
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.Message
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `organization revisions fence stale positive work and recover full positive and negative projections`() = runTest {
        val leader = ctx.registerUser(uniqueUsername("org-uow-leader"))
        val member = ctx.registerUser(uniqueUsername("org-uow-member"))
        val orphan = ctx.registerUser(uniqueUsername("org-uow-orphan"))
        val lockOrderUser = ctx.registerUser(uniqueUsername("org-uow-lock-order"))

        val roots = listOf("Root A", "Root B").map { name ->
            async(Dispatchers.Default) { runCatching { ctx.organizationService.createUnit(null, name, null) } }
        }.awaitAll()
        assertEquals(1, roots.count { it.isSuccess })
        val root = roots.single { it.isSuccess }.getOrThrow()

        val cycleA = ctx.organizationService.createUnit(root.unitId, "Cycle A", null)
        val cycleB = ctx.organizationService.createUnit(root.unitId, "Cycle B", null)
        val cycleMoves = listOf(
            async(Dispatchers.Default) {
                runCatching { ctx.organizationService.updateUnit(cycleA.unitId, cycleB.unitId, cycleA.name, null, 0) }
            },
            async(Dispatchers.Default) {
                runCatching { ctx.organizationService.updateUnit(cycleB.unitId, cycleA.unitId, cycleB.name, null, 0) }
            },
        ).awaitAll()
        assertEquals(1, cycleMoves.count { it.isSuccess }, "concurrent moves cannot commit a cycle")

        val unitId = UUID.randomUUID().toString()
        val r1 = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.createUnit(
                transaction,
                OrganizationUnit(unitId, root.unitId, "并发研发组", leaderUid = leader),
                enableGroup = true,
            )
        }.projections.single { it.unitId == unitId }
        val r1Paused = CompletableDeferred<Unit>()
        val releaseR1 = CompletableDeferred<Unit>()
        val pausedProjector = ctx.freshOrganizationProjector(
            lifecycleGate = ChatLifecycleGate(),
            hooks = OrganizationProjectionHooks { stage, task ->
                if (stage == OrganizationProjectionStage.BEFORE_APPLY && task == r1) {
                    r1Paused.complete(Unit)
                    releaseR1.await()
                }
            },
        )
        val staleApply = async(Dispatchers.Default) { pausedProjector.project(r1) }
        r1Paused.await()
        val r2 = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.disableGroup(transaction, unitId)
        }.projections.single()
        val negativeCrash = ctx.freshOrganizationProjector(
            lifecycleGate = ChatLifecycleGate(),
            hooks = OrganizationProjectionHooks { stage, task ->
                if (stage == OrganizationProjectionStage.AFTER_APPLY_BEFORE_EVENT_FLUSH && task == r2) {
                    throw InjectedOrganizationProjectionFailure()
                }
            },
        )
        assertFalse(negativeCrash.project(r2))
        assertEquals(1L, ctx.organizationProjectionStore.countPending())
        assertTrue(ctx.freshOrganizationProjector(lifecycleGate = ChatLifecycleGate()).project(r2))
        releaseR1.complete(Unit)
        assertTrue(staleApply.await(), "old work is a successful revision-CAS no-op")
        assertNull(ctx.chatService.getChat(unitId), "negative revision converges even before Chat exists")
        assertEquals(0L, ctx.organizationProjectionStore.countPending())

        val r3 = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.enableGroup(transaction, unitId)
        }.projections.single()
        assertFalse(ctx.organizationRepo.isProjectionReady(unitId))
        assertIs<ChatAccessDeniedException>(
            runCatching { ctx.chatAccess.requireMember(leader, unitId) }.exceptionOrNull(),
        )
        assertEquals("DOWN", ctx.healthChecker.check().components["managed-chat-projection"]?.status)
        assertTrue(ctx.conversationService.listConversations(leader).none { it.chatId == unitId })
        assertFailsWith<IllegalArgumentException> {
            ctx.conversationService.setDraft(leader, unitId, "must remain pending")
        }
        assertTrue(ctx.organizationProjector.project(r3))
        assertTrue(ctx.organizationRepo.isProjectionReady(unitId))
        assertNotNull(ctx.chatService.getChat(unitId))

        // Three-way barrier for the global order. A projector-like transaction owns State and
        // waits for Chat; a Bot-like transaction owns Chat and then needs User. The organization
        // command must reach its State/projection fence without already owning that User, allowing
        // the Bot side to finish and release Chat instead of closing User -> State -> Chat -> User.
        val chatLocked = CompletableDeferred<Unit>()
        val allowBotUserLock = CompletableDeferred<Unit>()
        val botLike = async(Dispatchers.IO) {
            ctx.pgUnitOfWork.write {
                Chats.selectAll().where { Chats.chatId eq unitId }.forUpdate().single()
                chatLocked.complete(Unit)
                allowBotUserLock.await()
                Users.selectAll().where { Users.uid eq lockOrderUser }.forUpdate().single()
            }
        }
        chatLocked.await()
        val stateLocked = CompletableDeferred<Unit>()
        val projectorLike = async(Dispatchers.IO) {
            ctx.pgUnitOfWork.write {
                OrganizationState.selectAll().where { OrganizationState.id eq 1 }.forUpdate().single()
                stateLocked.complete(Unit)
                Chats.selectAll().where { Chats.chatId eq unitId }.forUpdate().single()
            }
        }
        stateLocked.await()

        val organizationAtFence = CountDownLatch(1)
        val orderedRepository = ExposedOrganizationRepository(
            OrganizationLockHooks {
                organizationAtFence.countDown()
            },
        )
        val orderedService = OrganizationService(
            orderedRepository,
            ctx.userStore,
            ctx.pgUnitOfWork,
            ctx.organizationProjector,
        )
        val organizationCommand = async(Dispatchers.IO) {
            orderedService.assignMember(unitId, lockOrderUser, null, primary = false)
        }
        assertTrue(organizationAtFence.await(10, TimeUnit.SECONDS))
        allowBotUserLock.complete(Unit)
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                botLike.await()
                projectorLike.await()
            }
        }
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { organizationCommand.await() }
        }
        ctx.organizationService.removeMember(unitId, lockOrderUser)

        // Corrupt projections model an interrupted older implementation: inactive/missing member
        // plus Conversation and mute must be cleaned and receive privacy tombstones.
        transaction {
            GroupMembers.insert {
                it[GroupMembers.chatId] = unitId
                it[GroupMembers.chatType] = 2
                it[GroupMembers.uid] = orphan
                it[GroupMembers.role] = 0
                it[GroupMembers.status] = 0
                it[GroupMembers.joinedAt] = System.currentTimeMillis()
            }
            Conversations.insert {
                it[Conversations.uid] = orphan
                it[Conversations.chatId] = unitId
                it[Conversations.chatType] = 2
                it[Conversations.lastMsgSeq] = 0L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
            GroupMemberMutes.insert {
                it[GroupMemberMutes.chatId] = unitId
                it[GroupMemberMutes.uid] = orphan
                it[GroupMemberMutes.operatorUid] = leader
                it[GroupMemberMutes.expiresAt] = Long.MAX_VALUE
                it[GroupMemberMutes.createdAt] = System.currentTimeMillis()
            }
        }
        val r4 = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.updateUnit(
                transaction,
                unitId,
                root.unitId,
                "并发研发组",
                leader,
                0,
            )
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(r4))
        transaction {
            assertEquals(0L, Conversations.selectAll().where {
                (Conversations.chatId eq unitId) and (Conversations.uid eq orphan)
            }.count())
            assertEquals(0L, GroupMemberMutes.selectAll().where {
                (GroupMemberMutes.chatId eq unitId) and (GroupMemberMutes.uid eq orphan)
            }.count())
            val tombstones = SyncEvents.selectAll().where { SyncEvents.uid eq orphan }
                .map { it[SyncEvents.eventType] }
            assertTrue(NotifyType.CHAT_DELETED.code in tombstones)
            assertTrue(NotifyType.CONVERSATION_DELETED.code in tombstones)
        }
        val eventCount = transaction { SyncEvents.selectAll().count() }
        assertTrue(ctx.organizationProjector.project(r4))
        assertEquals(eventCount, transaction { SyncEvents.selectAll().count() })

        val r5 = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.assignMember(
                transaction,
                OrganizationMember(unitId, member, joinedAt = System.currentTimeMillis()),
            )
        }.projections.single { it.unitId == unitId }
        assertIs<ChatAccessDeniedException>(
            runCatching { ctx.chatAccess.requireMember(leader, unitId) }.exceptionOrNull(),
        )

        // Pending managed authority is the write-side linearization fence too: a stale in-memory
        // member snapshot must not allocate a sequence, write RocksDB, or mutate the group-file
        // tree after the organization fact revision has committed.
        val maxSeqBeforeDeniedWrite = transaction {
            Chats.selectAll().where { Chats.chatId eq unitId }.single()[Chats.maxSeq]
        }
        val deniedMessage = Message(
            chatId = unitId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = leader,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody("must remain pending"),
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.messageService.sendMessage(leader, deniedMessage)
        }
        assertEquals(maxSeqBeforeDeniedWrite, transaction {
            Chats.selectAll().where { Chats.chatId eq unitId }.single()[Chats.maxSeq]
        })
        assertTrue(ctx.messageStore.getHistory(unitId, 0, 10).isEmpty())

        val now = System.currentTimeMillis()
        val deniedFolder = GroupFileEntry(
            entryId = UUID.randomUUID().toString(),
            chatId = unitId,
            kind = GroupFileEntry.KIND_FOLDER,
            name = "must remain pending",
            createdBy = leader,
            createdAt = now,
            updatedBy = leader,
            updatedAt = now,
        )
        assertFailsWith<IllegalArgumentException> {
            ctx.groupFileRepo.create(deniedFolder, null, GroupFileService.DEFAULT_QUOTA_BYTES)
        }
        assertTrue(ctx.groupFileRepo.list(unitId, null).isEmpty())

        assertTrue(ctx.organizationProjector.project(r5))
        assertEquals("UP", ctx.healthChecker.check().components["managed-chat-projection"]?.status)
        assertTrue(ctx.chatService.getMembers(unitId).any { it.uid == member })

        val bot = ctx.botService.createForGroup(leader, unitId, "受管群机器人").bot
        transaction {
            Chats.update({ Chats.chatId eq unitId }) { it[Chats.maxSeq] = 42L }
            GroupInviteLinks.insert {
                it[GroupInviteLinks.token] = UUID.randomUUID().toString()
                it[GroupInviteLinks.chatId] = unitId
                it[GroupInviteLinks.creatorUid] = leader
                it[GroupInviteLinks.name] = "stale invite"
                it[GroupInviteLinks.createdAt] = System.currentTimeMillis()
            }
        }
        ctx.pgUnitOfWork.write { ctx.organizationRepo.disableGroup(transaction, unitId) }
        val coldDrain = ctx.freshOrganizationProjector(lifecycleGate = ChatLifecycleGate())
            .drainPending(includeDeferred = true, pageSize = 1)
        assertEquals(0L, coldDrain.remaining)
        transaction {
            assertEquals(0L, Chats.selectAll().where {
                (Chats.chatId eq unitId) and (Chats.status eq 1)
            }.count())
            assertEquals(0L, Conversations.selectAll().where { Conversations.chatId eq unitId }.count())
            assertEquals(0L, AutomationBotGrants.selectAll().where {
                AutomationBotGrants.chatId eq unitId
            }.count())
            assertEquals(0L, GroupInviteLinks.selectAll().where { GroupInviteLinks.chatId eq unitId }.count())
            assertEquals(0L, AutomationBots.selectAll().where {
                (AutomationBots.botId eq bot.botId) and (AutomationBots.status eq 1)
            }.count())
        }

        val reenable = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.enableGroup(transaction, unitId)
        }.projections.single()
        assertTrue(ctx.organizationProjector.project(reenable))
        assertEquals(42L, ctx.chatService.getChat(unitId)?.maxSeq)
        assertEquals(setOf(leader, member), ctx.chatService.getMembers(unitId).mapTo(mutableSetOf()) { it.uid })

        val rollbackUnitId = UUID.randomUUID().toString()
        val rollbackTask = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.createUnit(
                transaction,
                OrganizationUnit(rollbackUnitId, root.unitId, "回滚组", leaderUid = leader),
                enableGroup = true,
            )
        }.projections.single { it.unitId == rollbackUnitId }
        val failOnce = AtomicBoolean(true)
        val failingUow = ExposedPgUnitOfWork(
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT && failOnce.getAndSet(false)) {
                    throw InjectedOrganizationProjectionFailure()
                }
            },
        )
        assertFalse(ctx.freshOrganizationProjector(unitOfWork = failingUow).project(rollbackTask))
        assertNull(ctx.chatService.getChat(rollbackUnitId))
        assertNotNull(ctx.organizationProjectionStore.currentFailure())
        assertTrue(ctx.organizationProjector.project(rollbackTask))
        val afterRetry = transaction { SyncEvents.selectAll().count() }
        assertTrue(ctx.organizationProjector.project(rollbackTask))
        assertEquals(afterRetry, transaction { SyncEvents.selectAll().count() })
    }
}

private class InjectedOrganizationProjectionFailure : RuntimeException("injected organization projection rollback")
