package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganizationHierarchySqlTest {
    @Test
    fun `recursive member walk stores constant-size depth state and gates relation rows`() {
        val sql = ORGANIZATION_MEMBER_RECURSIVE_BRANCH + ORGANIZATION_MEMBER_PAGE_SQL
        assertFalse(sql.contains("array_append", ignoreCase = true))
        assertFalse(sql.contains("varchar[]", ignoreCase = true))
        assertFalse(Regex("\\bpath\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql))
        assertTrue(
            ORGANIZATION_MEMBER_RECURSIVE_BRANCH.contains(
                "walk.depth < ${OrganizationCapacityPolicy.MAX_TREE_DEPTH}",
            ),
        )
        assertTrue(ORGANIZATION_MEMBER_PAGE_SQL.contains("COUNT(DISTINCT unit_id)"))
        assertTrue(ORGANIZATION_MEMBER_PAGE_SQL.contains("NOT status.duplicate_detected"))
        assertTrue(ORGANIZATION_MEMBER_PAGE_SQL.contains("NOT status.depth_exceeded"))
    }

    @Test
    fun `snapshot validator reads max plus one and observes one overflow level`() {
        assertTrue(
            ORGANIZATION_HIERARCHY_VALIDATION_SQL.contains(
                "LIMIT ${OrganizationCapacityPolicy.MAX_ACTIVE_UNITS + 1}",
            ),
        )
        assertTrue(
            ORGANIZATION_HIERARCHY_VALIDATION_SQL.contains(
                "tree.depth <= ${OrganizationCapacityPolicy.MAX_TREE_DEPTH}",
            ),
        )
        assertTrue(ORGANIZATION_HIERARCHY_VALIDATION_SQL.contains("COUNT(DISTINCT unit_id)"))
    }
}
