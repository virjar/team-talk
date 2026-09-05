package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.repository.ExposedOrganizationRepository
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizationTreeDepthIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `create move and paged reads reject trees deeper than sixty-four`() = runTest {
        val repository = ExposedOrganizationRepository(ctx.database)
        var parentId: String? = null
        repeat(OrganizationCapacityPolicy.MAX_TREE_DEPTH) { index ->
            val unitId = "depth-$index"
            ctx.pgUnitOfWork.write {
                repository.createUnit(
                    transaction,
                    OrganizationUnit(unitId, parentId, "Depth $index"),
                    enableGroup = false,
                )
            }
            parentId = unitId
        }
        val rootId = "depth-0"
        val leafId = requireNotNull(parentId)

        val createFailure = assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                repository.createUnit(
                    transaction,
                    OrganizationUnit("depth-overflow", leafId, "Overflow"),
                    enableGroup = false,
                )
            }
        }
        assertEquals(OrganizationCapacityPolicy.TREE_DEPTH_REASON, createFailure.message)

        val movable = OrganizationUnit("depth-movable", rootId, "Movable")
        ctx.pgUnitOfWork.write { repository.createUnit(transaction, movable, enableGroup = false) }
        val moveFailure = assertFailsWith<IllegalArgumentException> {
            ctx.pgUnitOfWork.write {
                repository.updateUnit(
                    transaction,
                    movable.unitId,
                    leafId,
                    movable.name,
                    leaderUid = null,
                    sortOrder = 0,
                )
            }
        }
        assertEquals(OrganizationCapacityPolicy.TREE_DEPTH_REASON, moveFailure.message)
        assertEquals(rootId, repository.findUnit(movable.unitId)?.parentId)

        transaction(ctx.database) {
            OrganizationUnits.update({ OrganizationUnits.unitId eq movable.unitId }) {
                it[OrganizationUnits.parentId] = leafId
            }
        }
        val unitReadFailure = assertFailsWith<IllegalArgumentException> {
            repository.listUnitPage(expectedRevision = null, after = null, pageSize = 16)
        }
        assertEquals(OrganizationCapacityPolicy.TREE_DEPTH_REASON, unitReadFailure.message)
        val memberReadFailure = assertFailsWith<IllegalArgumentException> {
            repository.listMemberPage(
                rootUnitId = rootId,
                recursive = true,
                expectedRevision = null,
                after = null,
                pageSize = 16,
            )
        }
        assertEquals(OrganizationCapacityPolicy.TREE_DEPTH_REASON, memberReadFailure.message)

        transaction(ctx.database) {
            OrganizationUnits.update({ OrganizationUnits.unitId eq movable.unitId }) {
                it[OrganizationUnits.parentId] = rootId
            }
            OrganizationUnits.update({ OrganizationUnits.unitId eq rootId }) {
                it[OrganizationUnits.parentId] = leafId
            }
        }
        assertFailsWith<IllegalStateException> {
            repository.listUnitPage(expectedRevision = null, after = null, pageSize = 16)
        }
    }
}
