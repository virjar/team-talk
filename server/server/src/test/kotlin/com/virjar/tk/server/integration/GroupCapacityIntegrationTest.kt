package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.ChatLifecycleGate
import com.virjar.tk.server.infra.db.AutomationBotGrants
import com.virjar.tk.server.infra.db.AutomationBots
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.ConversationUsages
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupInviteLinks
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.repository.ChatMemberRepositoryHooks
import com.virjar.tk.server.infra.db.repository.ChatMemberRepositoryStage
import com.virjar.tk.server.infra.db.repository.ChatRepositoryHooks
import com.virjar.tk.server.infra.db.repository.ChatRepositoryStage
import com.virjar.tk.server.infra.db.repository.ExposedChatMemberRepository
import com.virjar.tk.server.infra.db.repository.ExposedChatRepository
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 针对规范群成员容量包络的真实 PostgreSQL 覆盖测试。 */
class GroupCapacityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `chat row serializes capacity and every user and bot entry rolls back at the limit`() = runTest {
        val fixture = seedGroup(activeMemberCount = GroupPolicy.MAX_MEMBERS - 1)
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondReachedDatabaseFence = CountDownLatch(1)
        val token = ctx.chatService.createInviteLink(
            operatorUid = fixture.ownerUid,
            chatId = fixture.chatId,
            name = "capacity",
            maxUses = 1,
            expiresAt = 0,
        )
        val firstRepository = ExposedChatMemberRepository(
            database = ctx.database,
            hooks = ChatMemberRepositoryHooks { stage, chatId ->
                if (stage == ChatMemberRepositoryStage.AFTER_CHAT_LOCK && chatId == fixture.chatId) {
                    firstLocked.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) {
                        "test did not release the first capacity writer"
                    }
                }
            },
        )
        val secondRepository = ExposedChatRepository(
            database = ctx.database,
            hooks = ChatRepositoryHooks { stage, chatId ->
                if (stage == ChatRepositoryStage.BEFORE_CHAT_LOCK && chatId == fixture.chatId) {
                    secondReachedDatabaseFence.countDown()
                }
            },
        )

        supervisorScope {
            // 独立的生命周期门禁模拟两个服务器进程。因此正确性来自
            // 权威的 PostgreSQL Chat 行，而不是进程本地互斥量。
            val first = async(Dispatchers.IO) {
                ChatLifecycleGate().withChat(fixture.chatId) {
                    ctx.pgUnitOfWork.write {
                        firstRepository.addMembers(
                            transaction = transaction,
                            chatId = fixture.chatId,
                            operatorUid = fixture.ownerUid,
                            uids = listOf(fixture.firstCandidateUid, fixture.firstCandidateUid),
                            requiredHumanUids = setOf(fixture.ownerUid, fixture.firstCandidateUid),
                        ) { facts ->
                            require(facts.operator?.role == OWNER_ROLE) { "owner snapshot missing" }
                        }
                    }
                }
            }
            try {
                assertTrue(await(firstLocked), "first writer did not acquire the Chat row")
                val second = async(Dispatchers.IO) {
                    ChatLifecycleGate().withChat(fixture.chatId) {
                        ctx.pgUnitOfWork.write {
                            secondRepository.joinByInvite(
                                transaction = transaction,
                                uid = fixture.secondCandidateUid,
                                token = token,
                                nowMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                }
                assertTrue(await(secondReachedDatabaseFence), "second writer did not reach the Chat row fence")
                assertFalse(second.isCompleted, "second writer bypassed the locked Chat aggregate")
                releaseFirst.countDown()

                val committed = first.await()
                assertEquals(listOf(fixture.firstCandidateUid), committed.addedUids)
                assertCapacityFailure { second.await() }
            } finally {
                releaseFirst.countDown()
            }
        }

        assertEquals(GroupPolicy.MAX_MEMBERS.toLong(), activeMemberCount(fixture.chatId))
        assertTrue(hasActiveMembership(fixture.chatId, fixture.firstCandidateUid))
        assertTrue(hasConversation(fixture.chatId, fixture.firstCandidateUid))
        assertFalse(hasActiveMembership(fixture.chatId, fixture.secondCandidateUid))
        assertEquals(INACTIVE, membershipStatus(fixture.chatId, fixture.secondCandidateUid))
        assertFalse(hasConversation(fixture.chatId, fixture.secondCandidateUid))
        assertEquals(0, inviteUseCount(token), "the losing invite must not consume quota")

        val fullBaseline = aggregateCounts()
        assertCapacityFailure {
            ctx.chatService.addMembers(
                fixture.ownerUid,
                fixture.chatId,
                listOf(fixture.thirdCandidateUid, fixture.thirdCandidateUid),
            )
        }
        assertEquals(fullBaseline, aggregateCounts())
        assertFalse(hasActiveMembership(fixture.chatId, fixture.thirdCandidateUid))
        assertEquals(INACTIVE, membershipStatus(fixture.chatId, fixture.thirdCandidateUid))
        assertFalse(hasConversation(fixture.chatId, fixture.thirdCandidateUid))

        val inviteBaseline = aggregateCounts()
        assertEquals(fixture.chatId, ctx.chatService.joinByInvite(fixture.ownerUid, token).chatId)
        assertEquals(0, inviteUseCount(token), "an active-member retry must not consume invite quota")
        assertEquals(inviteBaseline, aggregateCounts())

        assertCapacityFailure { ctx.chatService.joinByInvite(fixture.fourthCandidateUid, token) }
        assertEquals(0, inviteUseCount(token), "a rejected join must not consume invite quota")
        assertEquals(inviteBaseline, aggregateCounts())
        assertFalse(hasActiveMembership(fixture.chatId, fixture.fourthCandidateUid))
        assertFalse(hasConversation(fixture.chatId, fixture.fourthCandidateUid))

        val createBaseline = aggregateCounts()
        val initialTargets = List(GroupPolicy.MAX_MEMBERS) { index ->
            "new-${fixture.suffix}-${index.toString().padStart(4, '0')}"
        }
        assertCapacityFailure {
            ctx.chatService.createGroup("Too large", null, fixture.ownerUid, initialTargets)
        }
        assertEquals(createBaseline, aggregateCounts())

        val managedBotBaseline = aggregateCounts()
        assertCapacityFailure {
            ctx.botService.createGroupBotForTest(fixture.ownerUid, fixture.chatId, "Capacity managed bot")
        }
        assertEquals(
            managedBotBaseline,
            aggregateCounts(),
            "service identity, Bot, grant, membership, Conversation and events must share rollback",
        )

        val standalone = ctx.botService.create("Capacity standalone bot")
        val grantBaseline = aggregateCounts()
        assertCapacityFailure { ctx.botService.grant(standalone.bot.botId, fixture.chatId) }
        assertEquals(grantBaseline, aggregateCounts())
        assertFalse(hasGrant(standalone.bot.botId, fixture.chatId))
        assertFalse(hasActiveMembership(fixture.chatId, standalone.bot.userUid))
        assertFalse(hasConversation(fixture.chatId, standalone.bot.userUid))

        transaction(ctx.database) {
            AutomationBotGrants.insert {
                it[AutomationBotGrants.botId] = standalone.bot.botId
                it[AutomationBotGrants.chatId] = fixture.chatId
                it[AutomationBotGrants.createdAt] = System.currentTimeMillis()
            }
        }
        val recoveryBaseline = aggregateCounts()
        assertTrue(standalone.bot.botId in ctx.botService.recoverGrantMemberships())
        assertEquals(recoveryBaseline, aggregateCounts())
        assertTrue(hasGrant(standalone.bot.botId, fixture.chatId))
        assertFalse(hasActiveMembership(fixture.chatId, standalone.bot.userUid))
        assertFalse(hasConversation(fixture.chatId, standalone.bot.userUid))
    }

    @Test
    fun `1000 humans plus active bot stays pending without side effects then recovers`() = runTest(timeout = 3.minutes) {
        val suffix = UUID.randomUUID().toString().take(8)
        val leaderUid = "org-$suffix-leader"
        val memberUids = List(GroupPolicy.MAX_MEMBERS - 1) { index ->
            "org-$suffix-${index.toString().padStart(4, '0')}"
        }
        insertHumanUsers(listOf(leaderUid) + memberUids)
        val unitId = "unit-$suffix"
        ctx.pgUnitOfWork.write {
            ctx.organizationRepo.createUnit(
                transaction,
                OrganizationUnit(
                    unitId = unitId,
                    name = "Oversized managed group",
                    leaderUid = leaderUid,
                ),
                enableGroup = true,
            )
        }
        transaction(ctx.database) {
            val now = System.currentTimeMillis()
            OrganizationMemberships.batchInsert(memberUids, shouldReturnGeneratedValues = false) { uid ->
                this[OrganizationMemberships.unitId] = unitId
                this[OrganizationMemberships.uid] = uid
                this[OrganizationMemberships.title] = null
                this[OrganizationMemberships.primary] = false
                this[OrganizationMemberships.joinedAt] = now
                this[OrganizationMemberships.updatedAt] = now
            }
        }
        val bot = ctx.botService.create("Managed capacity bot")
        transaction(ctx.database) {
            AutomationBotGrants.insert {
                it[AutomationBotGrants.botId] = bot.bot.botId
                it[AutomationBotGrants.chatId] = unitId
                it[AutomationBotGrants.createdAt] = System.currentTimeMillis()
            }
        }
        val task = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.updateUnit(
                transaction,
                unitId = unitId,
                parentId = null,
                name = "Oversized managed group",
                leaderUid = leaderUid,
                sortOrder = 0,
            )
        }.projections.single { it.unitId == unitId }
        val baseline = aggregateCounts()
        assertEquals(GroupPolicy.MAX_MEMBERS.toLong(), transaction(ctx.database) {
            OrganizationMemberships.selectAll().where {
                OrganizationMemberships.unitId eq unitId
            }.count()
        })

        assertFalse(ctx.organizationProjector.project(task))

        assertEquals(baseline, aggregateCounts())
        transaction(ctx.database) {
            assertEquals(0L, Chats.selectAll().where { Chats.chatId eq unitId }.count())
            assertEquals(0L, GroupMembers.selectAll().where { GroupMembers.chatId eq unitId }.count())
            assertEquals(0L, Conversations.selectAll().where { Conversations.chatId eq unitId }.count())
            val projection = OrganizationManagedChatProjections.selectAll().where {
                OrganizationManagedChatProjections.unitId eq unitId
            }.single()
            assertEquals(task.revision, projection[OrganizationManagedChatProjections.desiredRevision])
            assertEquals(0L, projection[OrganizationManagedChatProjections.appliedRevision])
            assertEquals(GroupPolicy.CAPACITY_LIMIT_REASON, projection[OrganizationManagedChatProjections.lastFailure])
            assertTrue(projection[OrganizationManagedChatProjections.attemptCount] > 0)
        }
        val failure = assertNotNull(ctx.organizationProjectionStore.currentFailure())
        assertEquals(unitId, failure.unitId)
        assertEquals(task.revision, failure.revision)
        assertEquals(GroupPolicy.CAPACITY_LIMIT_REASON, failure.detail)

        val removedUid = memberUids.last()
        transaction(ctx.database) {
            OrganizationMemberships.deleteWhere {
                (OrganizationMemberships.unitId eq unitId) and
                    (OrganizationMemberships.uid eq removedUid)
            }
        }
        val recoveryTask = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.updateUnit(
                transaction,
                unitId = unitId,
                parentId = null,
                name = "Managed group at capacity",
                leaderUid = leaderUid,
                sortOrder = 0,
            )
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(recoveryTask))
        assertEquals(GroupPolicy.MAX_MEMBERS.toLong(), activeMemberCount(unitId))
        assertTrue(hasActiveMembership(unitId, bot.bot.userUid))
        assertNull(ctx.organizationProjectionStore.currentFailure())
    }

    private fun seedGroup(activeMemberCount: Int): GroupFixture {
        require(activeMemberCount in 1..GroupPolicy.MAX_MEMBERS)
        val suffix = UUID.randomUUID().toString().take(8)
        val memberUids = List(activeMemberCount) { index ->
            "cap-$suffix-${index.toString().padStart(4, '0')}"
        }
        val fixture = GroupFixture(
            suffix = suffix,
            // 邀请链接命令有意拒绝非规范资源标识符。保持这个手工播种的
            // 聚合与生产创建的会话具有相同的代表性。
            chatId = UUID.randomUUID().toString(),
            ownerUid = memberUids.first(),
            firstCandidateUid = "cap-$suffix-a",
            secondCandidateUid = "cap-$suffix-b",
            thirdCandidateUid = "cap-$suffix-c",
            fourthCandidateUid = "cap-$suffix-d",
        )
        transaction(ctx.database) {
            val now = System.currentTimeMillis()
            insertHumanUsersInCurrentTransaction(memberUids + fixture.candidateUids, now)
            Chats.insert {
                it[Chats.chatId] = fixture.chatId
                it[Chats.chatType] = GROUP_CHAT_TYPE
                it[Chats.maxSeq] = 0L
                it[Chats.status] = ACTIVE
                it[Chats.createdAt] = now
                it[Chats.updatedAt] = now
            }
            GroupChats.insert {
                it[GroupChats.chatId] = fixture.chatId
                it[GroupChats.name] = "Capacity fixture"
                it[GroupChats.creator] = fixture.ownerUid
                it[GroupChats.mutedAll] = false
                it[GroupChats.updatedAt] = now
            }
            GroupMembers.batchInsert(memberUids, shouldReturnGeneratedValues = false) { uid ->
                this[GroupMembers.chatId] = fixture.chatId
                this[GroupMembers.chatType] = GROUP_CHAT_TYPE
                this[GroupMembers.uid] = uid
                this[GroupMembers.role] = if (uid == fixture.ownerUid) OWNER_ROLE else MEMBER_ROLE
                this[GroupMembers.status] = ACTIVE
                this[GroupMembers.joinedAt] = now
            }
            GroupMembers.batchInsert(
                listOf(fixture.secondCandidateUid, fixture.thirdCandidateUid),
                shouldReturnGeneratedValues = false,
            ) { uid ->
                this[GroupMembers.chatId] = fixture.chatId
                this[GroupMembers.chatType] = GROUP_CHAT_TYPE
                this[GroupMembers.uid] = uid
                this[GroupMembers.role] = MEMBER_ROLE
                this[GroupMembers.status] = INACTIVE
                this[GroupMembers.joinedAt] = now
            }
            Conversations.batchInsert(memberUids, shouldReturnGeneratedValues = false) { uid ->
                this[Conversations.uid] = uid
                this[Conversations.chatId] = fixture.chatId
                this[Conversations.chatType] = GROUP_CHAT_TYPE
                this[Conversations.lastMsgSeq] = 0L
                this[Conversations.updatedAt] = now
            }
            ConversationUsages.batchInsert(memberUids, shouldReturnGeneratedValues = false) { uid ->
                this[ConversationUsages.uid] = uid
                this[ConversationUsages.conversationCount] = 1
                this[ConversationUsages.draftCharacters] = 0L
                this[ConversationUsages.updatedAt] = now
            }
        }
        return fixture
    }

    private fun insertHumanUsers(uids: List<String>) {
        transaction(ctx.database) {
            insertHumanUsersInCurrentTransaction(uids, System.currentTimeMillis())
        }
    }

    private fun insertHumanUsersInCurrentTransaction(uids: List<String>, now: Long) {
        Users.batchInsert(uids, shouldReturnGeneratedValues = false) { uid ->
            this[Users.uid] = uid
            this[Users.username] = "user-$uid"
            this[Users.name] = "User $uid"
            this[Users.passwordHash] = "integration-fixture"
            this[Users.role] = UserRole.HUMAN
            this[Users.status] = ACTIVE
            this[Users.createdAt] = now
            this[Users.updatedAt] = now
        }
    }

    private suspend fun assertCapacityFailure(block: suspend () -> Unit) {
        val failure = assertIs<IllegalArgumentException>(runCatching { block() }.exceptionOrNull())
        assertEquals(GroupPolicy.CAPACITY_LIMIT_REASON, failure.message)
    }

    private suspend fun await(latch: CountDownLatch): Boolean = withContext(Dispatchers.IO) {
        latch.await(10, TimeUnit.SECONDS)
    }

    private fun activeMemberCount(chatId: String): Long = transaction(ctx.database) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq ACTIVE)
        }.count()
    }

    private fun hasActiveMembership(chatId: String, uid: String): Boolean = transaction(ctx.database) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq ACTIVE)
        }.any()
    }

    private fun membershipStatus(chatId: String, uid: String): Int? = transaction(ctx.database) {
        GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.singleOrNull()?.get(GroupMembers.status)
    }

    private fun hasConversation(chatId: String, uid: String): Boolean = transaction(ctx.database) {
        Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid eq uid)
        }.any()
    }

    private fun hasGrant(botId: String, chatId: String): Boolean = transaction(ctx.database) {
        AutomationBotGrants.selectAll().where {
            (AutomationBotGrants.botId eq botId) and (AutomationBotGrants.chatId eq chatId)
        }.any()
    }

    private fun inviteUseCount(token: String): Int = transaction(ctx.database) {
        GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
            .single()[GroupInviteLinks.useCount]
    }

    private fun aggregateCounts(): GroupCapacityAggregateCounts = transaction(ctx.database) {
        GroupCapacityAggregateCounts(
            users = Users.selectAll().count(),
            botUsers = Users.selectAll().where { Users.role eq UserRole.BOT }.count(),
            chats = Chats.selectAll().count(),
            groupChats = GroupChats.selectAll().count(),
            bots = AutomationBots.selectAll().count(),
            grants = AutomationBotGrants.selectAll().count(),
            inviteLinks = GroupInviteLinks.selectAll().count(),
            members = GroupMembers.selectAll().count(),
            conversations = Conversations.selectAll().count(),
            streams = SyncStreams.selectAll().count(),
            events = SyncEvents.selectAll().count(),
        )
    }
}

private data class GroupFixture(
    val suffix: String,
    val chatId: String,
    val ownerUid: String,
    val firstCandidateUid: String,
    val secondCandidateUid: String,
    val thirdCandidateUid: String,
    val fourthCandidateUid: String,
) {
    val candidateUids = listOf(
        firstCandidateUid,
        secondCandidateUid,
        thirdCandidateUid,
        fourthCandidateUid,
    )
}

private data class GroupCapacityAggregateCounts(
    val users: Long,
    val botUsers: Long,
    val chats: Long,
    val groupChats: Long,
    val bots: Long,
    val grants: Long,
    val inviteLinks: Long,
    val members: Long,
    val conversations: Long,
    val streams: Long,
    val events: Long,
)

private const val MEMBER_ROLE = 0
private const val OWNER_ROLE = 2
private const val ACTIVE = 1
private const val INACTIVE = 0
private const val GROUP_CHAT_TYPE = 2
