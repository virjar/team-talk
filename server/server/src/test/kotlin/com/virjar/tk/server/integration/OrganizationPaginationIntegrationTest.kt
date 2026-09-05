package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.OrganizationUnits
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.OrganizationMemberPage
import com.virjar.tk.protocol.model.OrganizationMemberPageRequest
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.OrganizationUnitPage
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import com.virjar.tk.protocol.model.UserRole
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrganizationPaginationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `unit and member keysets are bounded revision fenced and scope bound`() = runTest {
        val root = ctx.organizationService.createUnit(null, "分页公司", null)
        val childIds = List(270) { index -> "page-unit-${index.toString().padStart(3, '0')}" }
        transaction(ctx.database) {
            val now = System.currentTimeMillis()
            OrganizationUnits.batchInsert(childIds, shouldReturnGeneratedValues = false) { unitId ->
                this[OrganizationUnits.unitId] = unitId
                this[OrganizationUnits.parentId] = root.unitId
                this[OrganizationUnits.name] = "分页部门 $unitId"
                this[OrganizationUnits.leaderUid] = null
                this[OrganizationUnits.sortOrder] = 0
                this[OrganizationUnits.groupChatId] = null
                this[OrganizationUnits.status] = OrganizationUnit.STATUS_ACTIVE
                this[OrganizationUnits.createdAt] = now
                this[OrganizationUnits.updatedAt] = now
            }
        }

        val first = ctx.organizationService.listUnitPage(OrganizationUnitPageRequest())
        assertEquals(OrganizationUnitPage.MAX_PAGE_SIZE, first.items.size)
        val firstCursor = assertNotNull(first.nextCursor)
        val second = ctx.organizationService.listUnitPage(OrganizationUnitPageRequest(firstCursor))
        assertFalse(second.snapshotChanged)
        assertEquals(first.revision, second.revision)
        assertEquals(271, (first.items + second.items).map { it.unitId }.distinct().size)
        assertEquals(null, second.nextCursor)

        ctx.organizationService.createUnit(root.unitId, "revision advance", null)
        val changed = ctx.organizationService.listUnitPage(OrganizationUnitPageRequest(firstCursor))
        assertTrue(changed.snapshotChanged)
        assertTrue(changed.items.isEmpty())
        assertEquals(null, changed.nextCursor)
        assertTrue(changed.revision > first.revision)
        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.listUnitPage(OrganizationUnitPageRequest("AA"))
        }

        val memberUids = List(OrganizationMemberPage.MAX_PAGE_SIZE + 1) { index ->
            "page-member-${index.toString().padStart(3, '0')}"
        }
        transaction(ctx.database) {
            val now = System.currentTimeMillis()
            Users.batchInsert(memberUids, shouldReturnGeneratedValues = false) { uid ->
                this[Users.uid] = uid
                this[Users.username] = "user-$uid"
                this[Users.name] = "User $uid"
                this[Users.passwordHash] = "organization-page-fixture"
                this[Users.role] = UserRole.HUMAN
                this[Users.status] = 1
                this[Users.createdAt] = now
                this[Users.updatedAt] = now
            }
            OrganizationMemberships.batchInsert(memberUids, shouldReturnGeneratedValues = false) { uid ->
                this[OrganizationMemberships.unitId] = root.unitId
                this[OrganizationMemberships.uid] = uid
                this[OrganizationMemberships.title] = null
                this[OrganizationMemberships.primary] = false
                this[OrganizationMemberships.joinedAt] = now
                this[OrganizationMemberships.updatedAt] = now
            }
            val state = OrganizationState.selectAll().where { OrganizationState.id eq 1 }.single()
            OrganizationState.update({ OrganizationState.id eq 1 }) {
                it[OrganizationState.revision] = state[OrganizationState.revision] + 1
                it[OrganizationState.updatedAt] = now
            }
        }

        val memberFirst = ctx.organizationService.listMemberPage(
            OrganizationMemberPageRequest(root.unitId, recursive = false),
        )
        assertEquals(OrganizationMemberPage.MAX_PAGE_SIZE, memberFirst.items.size)
        val memberCursor = assertNotNull(memberFirst.nextCursor)
        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.listMemberPage(
                OrganizationMemberPageRequest(childIds.first(), recursive = false, cursor = memberCursor),
            )
        }
        val memberSecond = ctx.organizationService.listMemberPage(
            OrganizationMemberPageRequest(root.unitId, recursive = false, cursor = memberCursor),
        )
        assertEquals(1, memberSecond.items.size)
        assertEquals(memberUids.toSet(), (memberFirst.items + memberSecond.items).map { it.uid }.toSet())

        val recursiveFirst = ctx.organizationService.listMemberPage(
            OrganizationMemberPageRequest(root.unitId, recursive = true),
        )
        val recursiveSecond = ctx.organizationService.listMemberPage(
            OrganizationMemberPageRequest(
                root.unitId,
                recursive = true,
                cursor = assertNotNull(recursiveFirst.nextCursor),
            ),
        )
        assertEquals(memberUids.toSet(), (recursiveFirst.items + recursiveSecond.items).map { it.uid }.toSet())
    }
}
