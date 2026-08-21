package com.virjar.tk.integration

import com.virjar.tk.application.bot.ChatServiceBotMembership
import com.virjar.tk.application.bot.MessageServiceBotSender
import com.virjar.tk.application.bot.UserServiceBotAccounts
import com.virjar.tk.domain.bot.AutomationBot
import com.virjar.tk.domain.bot.BotGroupMembership
import com.virjar.tk.domain.bot.BotMessageSender
import com.virjar.tk.domain.bot.BotRepository
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.chat.LockedChat
import com.virjar.tk.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.AutomationBotGrants
import com.virjar.tk.infra.db.AutomationBots
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.repository.ExposedBotRepository
import com.virjar.tk.model.UserRole
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real PostgreSQL coverage for the bot identity/grant/member/Conversation/event aggregate. */
class BotUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `group bot event failure rolls back identity bot grant membership and conversation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-uow-owner"))
        val group = ctx.chatService.createGroup("Bot rollback", null, owner, emptyList())
        val countsBefore = aggregateCounts()
        val ownerEventBefore = latestEventSeq(owner)
        val failing = freshBotService(failingUnitOfWork())

        assertIs<InjectedBotRollbackException>(
            runCatching {
                failing.createForGroup(owner, group.chatId, "Rollback bot")
            }.exceptionOrNull(),
        )

        assertEquals(countsBefore, aggregateCounts())
        assertEquals(ownerEventBefore, latestEventSeq(owner))
        assertTrue(ctx.chatService.getMembers(group.chatId).none { member ->
            ctx.userRepo.findByUid(member.uid)?.role == UserRole.BOT
        })
    }

    @Test
    fun `grant removal event failure rolls back grant membership and conversation`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-revoke-owner"))
        val observer = ctx.registerUser(uniqueUsername("bot-revoke-observer"))
        val group = ctx.chatService.createGroup("Bot revoke rollback", null, owner, listOf(observer))
        val normal = freshBotService(ctx.pgUnitOfWork)
        val created = normal.create("Rollback system bot")
        normal.grant(created.bot.botId, group.chatId)
        val baselines = listOf(owner, observer, created.bot.userUid).associateWith(::latestEventSeq)
        val failing = freshBotService(failingUnitOfWork())

        assertIs<InjectedBotRollbackException>(
            runCatching { failing.revokeGrant(created.bot.botId, group.chatId) }.exceptionOrNull(),
        )

        assertTrue(hasGrant(created.bot.botId, group.chatId))
        assertTrue(isActiveMember(group.chatId, created.bot.userUid))
        assertTrue(hasConversation(group.chatId, created.bot.userUid))
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }
    }

    @Test
    fun `dissolve event failure rolls back chat bots grants memberships and conversations`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-dissolve-uow-owner"))
        val observer = ctx.registerUser(uniqueUsername("bot-dissolve-uow-observer"))
        val group = ctx.chatService.createGroup("Bot dissolve rollback", null, owner, listOf(observer))
        val owned = ctx.botService.createForGroup(owner, group.chatId, "Owned rollback bot")
        val bot = ctx.botService.list().single { it.botId == owned.bot.botId }
        val baselines = listOf(owner, observer, bot.userUid).associateWith(::latestEventSeq)
        val failingChatService = ctx.freshChatService(failingUnitOfWork())

        assertIs<InjectedBotRollbackException>(
            runCatching { failingChatService.dissolveGroup(owner, group.chatId) }.exceptionOrNull(),
        )

        assertNotNull(ctx.chatService.getChat(group.chatId))
        assertEquals(AutomationBot.STATUS_ACTIVE, ctx.botService.list().single { it.botId == bot.botId }.status)
        assertTrue(hasGrant(bot.botId, group.chatId))
        assertTrue(isActiveMember(group.chatId, bot.userUid))
        assertTrue(hasConversation(group.chatId, bot.userUid))
        baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }
    }

    @Test
    fun `startup recovery removes disabled and no-grant membership conversation orphans`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-recovery-owner"))
        val group = ctx.chatService.createGroup("Bot recovery", null, owner, emptyList())
        val service = freshBotService(ctx.pgUnitOfWork)
        val disabled = service.create("Disabled orphan")
        service.grant(disabled.bot.botId, group.chatId)
        val managedCredentials = service.createForGroup(owner, group.chatId, "No grant orphan")
        val managed = service.list().single { it.botId == managedCredentials.bot.botId }

        transaction {
            AutomationBots.update({ AutomationBots.botId eq disabled.bot.botId }) {
                it[status] = AutomationBot.STATUS_DISABLED
            }
            AutomationBotGrants.deleteWhere {
                (AutomationBotGrants.botId eq managed.botId) and
                    (AutomationBotGrants.chatId eq group.chatId)
            }
        }

        assertTrue(service.recoverGrantMemberships().isEmpty())

        listOf(disabled.bot, managed).forEach { bot ->
            assertEquals(AutomationBot.STATUS_DISABLED, service.list().single { it.botId == bot.botId }.status)
            assertFalse(hasGrant(bot.botId, group.chatId))
            assertFalse(isActiveMember(group.chatId, bot.userUid))
            assertFalse(hasConversation(group.chatId, bot.userUid))
        }
    }

    @Test
    fun `startup recovery tolerates missing chats and removes dangling grant and managed projections`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-dangling-owner"))
        val group = ctx.chatService.createGroup("Bot dangling recovery", null, owner, emptyList())
        val service = freshBotService(ctx.pgUnitOfWork)
        val danglingGrant = service.create("Dangling grant bot")
        val managedCredentials = service.createForGroup(owner, group.chatId, "Dangling managed bot")
        val managed = service.list().single { it.botId == managedCredentials.bot.botId }
        val missingGrantChatId = "missing-grant-${uniqueUsername("chat").takeLast(18)}"
        val missingManagedChatId = "missing-managed-${uniqueUsername("chat").takeLast(16)}"

        transaction {
            AutomationBotGrants.insert {
                it[AutomationBotGrants.botId] = danglingGrant.bot.botId
                it[AutomationBotGrants.chatId] = missingGrantChatId
                it[AutomationBotGrants.createdAt] = System.currentTimeMillis()
            }
            AutomationBots.update({ AutomationBots.botId eq managed.botId }) {
                it[AutomationBots.managedChatId] = missingManagedChatId
            }
        }

        val delegate = ChatServiceBotMembership(ctx.chatService)
        var missingSnapshotObserved = false
        val checkingMembership = object : BotGroupMembership by delegate {
            override fun cleanupServiceMemberProjection(
                transaction: PgTransactionContext,
                chatId: String,
                uid: String,
                lockedChat: LockedChat?,
            ): ServiceMemberProjectionCleanup? {
                if (chatId == missingGrantChatId || chatId == missingManagedChatId) {
                    assertNull(lockedChat, "missing Chat must remain a nullable pre-Bot lock snapshot")
                    missingSnapshotObserved = true
                }
                return delegate.cleanupServiceMemberProjection(transaction, chatId, uid, lockedChat)
            }
        }
        val recoveryService = freshBotService(
            unitOfWork = ctx.pgUnitOfWork,
            groupMembership = checkingMembership,
        )

        assertTrue(recoveryService.recoverGrantMemberships().isEmpty())
        assertTrue(missingSnapshotObserved)

        assertEquals(
            AutomationBot.STATUS_ACTIVE,
            service.list().single { it.botId == danglingGrant.bot.botId }.status,
        )
        assertFalse(hasGrant(danglingGrant.bot.botId, missingGrantChatId))
        assertEquals(
            AutomationBot.STATUS_DISABLED,
            service.list().single { it.botId == managed.botId }.status,
        )
        assertFalse(hasGrant(managed.botId, group.chatId))
        assertFalse(isActiveMember(group.chatId, managed.userUid))
        assertFalse(hasConversation(group.chatId, managed.userUid))
    }

    @Test
    fun `chat deactivation and startup recovery fail closed for a bot row that references a human identity`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-human-owner"))
        val group = ctx.chatService.createGroup("Bot human identity", null, owner, emptyList())
        val service = freshBotService(ctx.pgUnitOfWork)
        val created = service.create("Corrupt identity bot")
        service.grant(created.bot.botId, group.chatId)
        val baselines = listOf(owner, created.bot.userUid).associateWith(::latestEventSeq)

        transaction {
            Users.update({ Users.uid eq created.bot.userUid }) { it[role] = UserRole.HUMAN }
        }
        try {
            assertIs<IllegalStateException>(
                runCatching { ctx.chatService.dissolveGroup(owner, group.chatId) }.exceptionOrNull(),
            )
            assertTrue(isActiveChat(group.chatId))
            assertTrue(created.bot.botId in service.recoverGrantMemberships())
            assertEquals(
                AutomationBot.STATUS_ACTIVE,
                service.list().single { it.botId == created.bot.botId }.status,
            )
            assertTrue(hasGrant(created.bot.botId, group.chatId))
            assertTrue(isActiveMember(group.chatId, created.bot.userUid))
            assertTrue(hasConversation(group.chatId, created.bot.userUid))
            baselines.forEach { (uid, seq) -> assertEquals(seq, latestEventSeq(uid)) }
        } finally {
            transaction {
                Users.update({ Users.uid eq created.bot.userUid }) { it[role] = UserRole.BOT }
            }
        }
    }

    @Test
    fun `startup recovery cleans conversation and mute projections and emits only actual tombstones`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-projection-owner"))
        val group = ctx.chatService.createGroup("Bot projection recovery", null, owner, emptyList())
        val service = freshBotService(ctx.pgUnitOfWork)
        val inactiveMember = service.create("Inactive projection bot")
        val missingMember = service.create("Missing projection bot")
        val missingConversation = service.create("Missing conversation bot")
        listOf(inactiveMember, missingMember, missingConversation).forEach { created ->
            service.grant(created.bot.botId, group.chatId)
        }

        transaction {
            listOf(inactiveMember, missingMember, missingConversation).forEach { created ->
                AutomationBots.update({ AutomationBots.botId eq created.bot.botId }) {
                    it[AutomationBots.status] = AutomationBot.STATUS_DISABLED
                }
                AutomationBotGrants.deleteWhere {
                    (AutomationBotGrants.botId eq created.bot.botId) and
                        (AutomationBotGrants.chatId eq group.chatId)
                }
            }
            GroupMembers.update({
                (GroupMembers.chatId eq group.chatId) and
                    (GroupMembers.uid eq inactiveMember.bot.userUid)
            }) { it[status] = 0 }
            GroupMembers.deleteWhere {
                (GroupMembers.chatId eq group.chatId) and
                    (GroupMembers.uid eq missingMember.bot.userUid)
            }
            Conversations.deleteWhere {
                (Conversations.chatId eq group.chatId) and
                    (Conversations.uid eq missingConversation.bot.userUid)
            }
            listOf(inactiveMember, missingMember).forEach { created ->
                GroupMemberMutes.insert {
                    it[GroupMemberMutes.chatId] = group.chatId
                    it[GroupMemberMutes.uid] = created.bot.userUid
                    it[GroupMemberMutes.operatorUid] = owner
                    it[GroupMemberMutes.expiresAt] = Long.MAX_VALUE
                    it[GroupMemberMutes.createdAt] = System.currentTimeMillis()
                }
            }
        }
        val baselines = listOf(
            owner,
            inactiveMember.bot.userUid,
            missingMember.bot.userUid,
            missingConversation.bot.userUid,
        ).associateWith(::latestEventSeq)

        assertTrue(service.recoverGrantMemberships().isEmpty())

        listOf(inactiveMember, missingMember).forEach { created ->
            assertFalse(hasConversation(group.chatId, created.bot.userUid))
            assertFalse(hasMute(group.chatId, created.bot.userUid))
            assertEquals(
                listOf(NotifyType.CONVERSATION_DELETED),
                eventTypesAfter(created.bot.userUid, baselines.getValue(created.bot.userUid)),
            )
        }
        assertFalse(isActiveMember(group.chatId, missingConversation.bot.userUid))
        assertEquals(
            listOf(NotifyType.CHAT_DELETED),
            eventTypesAfter(
                missingConversation.bot.userUid,
                baselines.getValue(missingConversation.bot.userUid),
            ),
        )
        assertEquals(
            listOf(NotifyType.MEMBER_REMOVED),
            eventTypesAfter(owner, baselines.getValue(owner)),
        )
    }

    @Test
    fun `startup recovery rethrows cancellation and fatal errors`() = runTest {
        val service = freshBotService(ctx.pgUnitOfWork)
        service.create("Recovery throwable bot")
        val cancellation = CancellationException("cancel recovery")
        val cancellationRepository = object : BotRepository by ExposedBotRepository() {
            override fun find(botId: String): AutomationBot? = throw cancellation
        }
        val cancellationService = freshBotService(
            unitOfWork = ctx.pgUnitOfWork,
            repository = cancellationRepository,
        )

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> { cancellationService.recoverGrantMemberships() },
        )

        val fatal = AssertionError("fatal recovery")
        val fatalRepository = object : BotRepository by ExposedBotRepository() {
            override fun find(botId: String): AutomationBot? = throw fatal
        }
        val fatalService = freshBotService(
            unitOfWork = ctx.pgUnitOfWork,
            repository = fatalRepository,
        )

        assertSame(fatal, assertFailsWith<AssertionError> { fatalService.recoverGrantMemberships() })
    }

    @Test
    fun `cross-process group quota lock admits at most the configured creator quota`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-quota-race-owner"))
        val group = ctx.chatService.createGroup("Bot quota race", null, owner, emptyList())
        val services = List(BotService.MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP + 1) {
            freshBotService(ctx.pgUnitOfWork)
        }

        val outcomes = services.mapIndexed { index, service ->
            async(Dispatchers.Default) {
                runCatching { service.createForGroup(owner, group.chatId, "Concurrent bot $index") }
            }
        }.awaitAll()

        assertEquals(BotService.MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP, outcomes.count { it.isSuccess })
        assertEquals(
            BotService.MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP,
            serviceBotsForCreator(owner, group.chatId),
        )
    }

    @Test
    fun `cross-process grant racing dissolve cannot resurrect authorization projections`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-dissolve-race-owner"))
        val group = ctx.chatService.createGroup("Bot dissolve race", null, owner, emptyList())
        val service = freshBotService(ctx.pgUnitOfWork)
        val created = service.create("Dissolve race bot")
        val start = CompletableDeferred<Unit>()

        val grant = async(Dispatchers.Default) {
            start.await()
            runCatching { service.grant(created.bot.botId, group.chatId) }
        }
        val dissolve = async(Dispatchers.Default) {
            start.await()
            ctx.chatService.dissolveGroup(owner, group.chatId)
        }
        start.complete(Unit)
        grant.await()
        dissolve.await()

        assertNull(ctx.chatService.getChat(group.chatId))
        assertFalse(hasGrant(created.bot.botId, group.chatId))
        assertFalse(isActiveMember(group.chatId, created.bot.userUid))
        assertFalse(hasConversation(group.chatId, created.bot.userUid))
    }

    @Test
    fun `bot command gate prevents disable from overtaking admitted delivery`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-gate-owner"))
        val group = ctx.chatService.createGroup("Bot gate", null, owner, emptyList())
        val enteredDelivery = CompletableDeferred<Unit>()
        val releaseDelivery = CompletableDeferred<Unit>()
        val service = freshBotService(
            unitOfWork = ctx.pgUnitOfWork,
            sender = BotMessageSender { _, _ ->
                enteredDelivery.complete(Unit)
                releaseDelivery.await()
                7L
            },
        )
        val created = service.create("Gate bot")
        service.grant(created.bot.botId, group.chatId)

        val delivery = async {
            service.deliver(created.bot.botId, created.webhookToken, group.chatId, "ok", "gate-1")
        }
        enteredDelivery.await()
        val disable = async { service.disable(created.bot.botId) }
        yield()
        assertFalse(disable.isCompleted)

        releaseDelivery.complete(Unit)
        assertEquals(7L, delivery.await().serverSeq)
        disable.await()
        assertEquals(AutomationBot.STATUS_DISABLED, service.list().single { it.botId == created.bot.botId }.status)
        assertFalse(hasGrant(created.bot.botId, group.chatId))
    }

    private fun freshBotService(
        unitOfWork: com.virjar.tk.domain.transaction.PgUnitOfWork,
        sender: BotMessageSender = MessageServiceBotSender(ctx.messageService),
        repository: BotRepository = ExposedBotRepository(),
        groupMembership: BotGroupMembership = ChatServiceBotMembership(ctx.chatService),
    ): BotService = BotService(
        repository = repository,
        accounts = UserServiceBotAccounts(ctx.userService),
        access = ctx.chatAccess,
        groupMembership = groupMembership,
        messageSender = sender,
        lifecycleGate = ChatLifecycleGate(),
        unitOfWork = unitOfWork,
    )

    private fun failingUnitOfWork() = ExposedPgUnitOfWork(
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedBotRollbackException()
            }
        },
    )

    private fun aggregateCounts(): AggregateCounts = transaction {
        AggregateCounts(
            botUsers = Users.selectAll().where { Users.role eq UserRole.BOT }.count(),
            bots = AutomationBots.selectAll().count(),
            grants = AutomationBotGrants.selectAll().count(),
            members = GroupMembers.selectAll().count(),
            conversations = Conversations.selectAll().count(),
            events = SyncEvents.selectAll().count(),
        )
    }

    private fun latestEventSeq(uid: String): Long = transaction {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }
            .maxOfOrNull { it[SyncEvents.streamSeq] } ?: 0L
    }

    private fun eventTypesAfter(uid: String, afterSeq: Long): List<NotifyType> = transaction {
        SyncEvents.selectAll().where {
            (SyncEvents.uid eq uid) and (SyncEvents.streamSeq greater afterSeq)
        }.orderBy(SyncEvents.streamSeq, SortOrder.ASC)
            .map { row -> NotifyType.fromCode(row[SyncEvents.eventType]) }
    }

    private fun hasGrant(botId: String, chatId: String): Boolean = transaction {
        AutomationBotGrants.selectAll().where {
            (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
        }.any()
    }

    private fun isActiveMember(chatId: String, uid: String): Boolean = transaction {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.any()
    }

    private fun isActiveChat(chatId: String): Boolean = transaction {
        Chats.selectAll().where {
            (Chats.chatId eq chatId) and (Chats.status eq 1)
        }.any()
    }

    private fun hasConversation(chatId: String, uid: String): Boolean = transaction {
        Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        }.any()
    }

    private fun hasMute(chatId: String, uid: String): Boolean = transaction {
        GroupMemberMutes.selectAll().where {
            (GroupMemberMutes.chatId eq chatId) and (GroupMemberMutes.uid eq uid)
        }.any()
    }

    private fun serviceBotsForCreator(uid: String, chatId: String): Int = transaction {
        AutomationBots.selectAll().where {
            (AutomationBots.createdByUid eq uid) and
                (AutomationBots.managedChatId eq chatId) and
                (AutomationBots.status eq AutomationBot.STATUS_ACTIVE)
        }.count().toInt()
    }
}

private data class AggregateCounts(
    val botUsers: Long,
    val bots: Long,
    val grants: Long,
    val members: Long,
    val conversations: Long,
    val events: Long,
)

private class InjectedBotRollbackException : RuntimeException()
