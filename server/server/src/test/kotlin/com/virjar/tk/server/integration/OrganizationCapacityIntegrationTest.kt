package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.server.infra.db.repository.OrganizationCapacityLimits
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizationCapacityIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `serialized organization writers reject every explicit capacity before mutation`() = runTest {
        val user1 = ctx.registerUser(uniqueUsername("org-cap-u1"))
        val user2 = ctx.registerUser(uniqueUsername("org-cap-u2"))
        val user3 = ctx.registerUser(uniqueUsername("org-cap-u3"))
        val user4 = ctx.registerUser(uniqueUsername("org-cap-u4"))
        val repository = ExposedOrganizationRepository(
            database = ctx.database,
            capacityLimits = OrganizationCapacityLimits(
                activeUnits = 20,
                membershipRelations = 3,
                membersPerUnit = 2,
                membershipsPerUser = 1,
            ),
        )
        val root = OrganizationUnit("capacity-root", null, "Capacity root")
        val child = OrganizationUnit("capacity-child", root.unitId, "Capacity child")
        ctx.pgUnitOfWork.write { repository.createUnit(transaction, root, enableGroup = false) }
        ctx.pgUnitOfWork.write { repository.createUnit(transaction, child, enableGroup = false) }

        suspend fun assign(unitId: String, uid: String) {
            ctx.pgUnitOfWork.write {
                repository.assignMember(
                    transaction,
                    OrganizationMember(unitId, uid, joinedAt = System.currentTimeMillis()),
                )
            }
        }

        assign(root.unitId, user1)
        assertCapacity(OrganizationCapacityPolicy.USER_MEMBERSHIP_CAPACITY_REASON) {
            assign(child.unitId, user1)
        }
        assign(root.unitId, user2)
        assertCapacity(OrganizationCapacityPolicy.UNIT_MEMBER_CAPACITY_REASON) {
            assign(root.unitId, user3)
        }
        assign(child.unitId, user3)
        assertCapacity(OrganizationCapacityPolicy.MEMBERSHIP_CAPACITY_REASON) {
            assign(child.unitId, user4)
        }

        val unitLimited = ExposedOrganizationRepository(
            database = ctx.database,
            capacityLimits = OrganizationCapacityLimits(activeUnits = 2),
        )
        assertCapacity(OrganizationCapacityPolicy.UNIT_CAPACITY_REASON) {
            ctx.pgUnitOfWork.write {
                unitLimited.createUnit(
                    transaction,
                    OrganizationUnit("capacity-overflow", root.unitId, "Overflow"),
                    enableGroup = false,
                )
            }
        }

        ctx.pgUnitOfWork.write { repository.removeMember(transaction, child.unitId, user3) }
        ctx.pgUnitOfWork.write { repository.archiveUnit(transaction, child.unitId) }
        val historyLimited = ExposedOrganizationRepository(
            database = ctx.database,
            capacityLimits = OrganizationCapacityLimits(activeUnits = 2, unitRecords = 2),
        )
        assertCapacity(OrganizationCapacityPolicy.UNIT_RECORD_CAPACITY_REASON) {
            ctx.pgUnitOfWork.write {
                historyLimited.createUnit(
                    transaction,
                    OrganizationUnit("capacity-history-overflow", root.unitId, "History overflow"),
                    enableGroup = false,
                )
            }
        }
        assertEquals(null, historyLimited.findUnit("capacity-history-overflow"))

        val projectionLimited = ExposedOrganizationRepository(
            database = ctx.database,
            capacityLimits = OrganizationCapacityLimits(managedChatProjections = 2),
        )
        ctx.pgUnitOfWork.write {
            projectionLimited.updateUnit(
                transaction,
                root.unitId,
                parentId = null,
                name = root.name,
                leaderUid = user1,
                sortOrder = 0,
            )
        }
        ctx.pgUnitOfWork.write { projectionLimited.enableGroup(transaction, root.unitId) }
        assertCapacity(OrganizationCapacityPolicy.MANAGED_CHAT_PROJECTION_CAPACITY_REASON) {
            ctx.pgUnitOfWork.write {
                projectionLimited.createUnit(
                    transaction,
                    OrganizationUnit(
                        unitId = "capacity-projection-overflow",
                        parentId = root.unitId,
                        name = "Projection overflow",
                        leaderUid = user2,
                    ),
                    enableGroup = true,
                )
            }
        }
        assertEquals(null, projectionLimited.findUnit("capacity-projection-overflow"))
        assertEquals(
            2L,
            transaction(ctx.database) { OrganizationManagedChatProjections.selectAll().count() },
        )
    }

    private suspend fun assertCapacity(reason: String, block: suspend () -> Unit) {
        val failure = assertFailsWith<IllegalArgumentException> { block() }
        assertEquals(reason, failure.message)
    }
}
