package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.NotifyType
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 使用隔离数据库，因为组织聚合有意只允许一个根节点。 */
class OrganizationManagedChatEventIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `managed chat emits one canonical event per recipient and leader swap refreshes roles`() = runTest {
        val leaderA = ctx.registerUser(uniqueUsername("org-event-leader-a"))
        val leaderB = ctx.registerUser(uniqueUsername("org-event-leader-b"))
        val observer = ctx.registerUser(uniqueUsername("org-event-observer"))
        val joining = ctx.registerUser(uniqueUsername("org-event-joining"))
        val root = ctx.organizationService.createUnit(null, "Event Root", null)
        val unitId = UUID.randomUUID().toString()
        val create = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.createUnit(
                transaction,
                OrganizationUnit(unitId, root.unitId, "事件语义组", leaderUid = leaderA),
                enableGroup = true,
            )
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(create))

        listOf(leaderB, observer).forEach { uid ->
            val assign = ctx.pgUnitOfWork.write {
                ctx.organizationRepo.assignMember(
                    transaction,
                    OrganizationMember(unitId, uid, joinedAt = System.currentTimeMillis()),
                )
            }.projections.single { it.unitId == unitId }
            assertTrue(ctx.organizationProjector.project(assign))
        }

        val stableMembers = listOf(leaderA, leaderB, observer)
        val beforeLeaderSwap = stableMembers.associateWith(::syncEventCount)
        val leaderSwap = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.updateUnit(
                transaction,
                unitId,
                root.unitId,
                "事件语义组",
                leaderB,
                0,
            )
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(leaderSwap))
        stableMembers.forEach { uid ->
            assertEquals(
                listOf(NotifyType.MEMBER_ROLE_CHANGED),
                syncEventTypesAfter(uid, beforeLeaderSwap.getValue(uid)),
                "负责人切换后，每个既有成员只需收到一次角色投影刷新",
            )
        }
        assertEquals(
            mapOf(leaderA to 0, leaderB to 2, observer to 0),
            ctx.chatService.getMembers(unitId).associate { it.uid to it.role },
        )

        val beforeJoin = (stableMembers + joining).associateWith(::syncEventCount)
        val addition = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.assignMember(
                transaction,
                OrganizationMember(unitId, joining, joinedAt = System.currentTimeMillis()),
            )
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(addition))
        assertEquals(
            listOf(NotifyType.CHAT_CREATED),
            syncEventTypesAfter(joining, beforeJoin.getValue(joining)),
        )
        stableMembers.forEach { uid ->
            assertEquals(
                listOf(NotifyType.MEMBER_ADDED),
                syncEventTypesAfter(uid, beforeJoin.getValue(uid)),
            )
        }

        val beforeRemoval = (stableMembers + joining).associateWith(::syncEventCount)
        val removal = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.removeMember(transaction, unitId, joining)
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(removal))
        assertEquals(
            listOf(NotifyType.CHAT_DELETED),
            syncEventTypesAfter(joining, beforeRemoval.getValue(joining)),
        )
        stableMembers.forEach { uid ->
            assertEquals(
                listOf(NotifyType.MEMBER_REMOVED),
                syncEventTypesAfter(uid, beforeRemoval.getValue(uid)),
            )
        }

        val beforeRename = stableMembers.associateWith(::syncEventCount)
        val rename = ctx.pgUnitOfWork.write {
            ctx.organizationRepo.updateUnit(
                transaction,
                unitId,
                root.unitId,
                "事件语义组已重命名",
                leaderB,
                0,
            )
        }.projections.single { it.unitId == unitId }
        assertTrue(ctx.organizationProjector.project(rename))
        stableMembers.forEach { uid ->
            assertEquals(
                listOf(NotifyType.CHAT_UPDATED),
                syncEventTypesAfter(uid, beforeRename.getValue(uid)),
            )
        }
    }

    private fun syncEventCount(uid: String): Int = transaction(ctx.database) {
        SyncEvents.selectAll().where { SyncEvents.uid eq uid }.count().toInt()
    }

    private fun syncEventTypesAfter(uid: String, count: Int): List<NotifyType> =
        transaction(ctx.database) {
            SyncEvents.selectAll().where { SyncEvents.uid eq uid }
                .orderBy(SyncEvents.streamSeq, SortOrder.ASC)
                .drop(count)
                .map { NotifyType.fromCode(it[SyncEvents.eventType]) }
        }
}
