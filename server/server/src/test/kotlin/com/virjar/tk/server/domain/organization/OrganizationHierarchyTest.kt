package com.virjar.tk.server.domain.organization

import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrganizationHierarchyTest {
    @Test
    fun `depth sixty-four is accepted and depth sixty-five is rejected`() {
        val accepted = chain(OrganizationCapacityPolicy.MAX_TREE_DEPTH)
        val hierarchy = OrganizationHierarchy.validate(accepted)
        assertEquals(OrganizationCapacityPolicy.MAX_TREE_DEPTH, hierarchy.depth(accepted.last().unitId))
        assertEquals(accepted.size, hierarchy.ancestors(accepted.last().unitId).size)
        assertEquals(accepted.size, hierarchy.descendants(accepted.first().unitId).size)

        val failure = assertFailsWith<IllegalArgumentException> {
            OrganizationHierarchy.validate(chain(OrganizationCapacityPolicy.MAX_TREE_DEPTH + 1))
        }
        assertEquals(OrganizationCapacityPolicy.TREE_DEPTH_REASON, failure.message)
    }

    @Test
    fun `orphan cycle and multiple roots fail closed`() {
        assertFailsWith<IllegalStateException> {
            OrganizationHierarchy.validate(
                listOf(
                    OrganizationHierarchyNode("root", null),
                    OrganizationHierarchyNode("orphan", "missing"),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            OrganizationHierarchy.validate(
                listOf(
                    OrganizationHierarchyNode("root", null),
                    OrganizationHierarchyNode("cycle-a", "cycle-b"),
                    OrganizationHierarchyNode("cycle-b", "cycle-a"),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            OrganizationHierarchy.validate(
                listOf(
                    OrganizationHierarchyNode("root-a", null),
                    OrganizationHierarchyNode("root-b", null),
                ),
            )
        }
    }

    @Test
    fun `ten-thousand-node wide tree uses an explicit bounded stack`() {
        val nodes = buildList {
            add(OrganizationHierarchyNode("root", null))
            repeat(OrganizationCapacityPolicy.MAX_ACTIVE_UNITS - 1) { index ->
                add(OrganizationHierarchyNode("child-$index", "root"))
            }
        }
        val descendants = OrganizationHierarchy.validate(nodes).descendants("root")
        assertEquals(OrganizationCapacityPolicy.MAX_ACTIVE_UNITS, descendants.size)
        assertTrue("root" in descendants)
    }

    private fun chain(size: Int): List<OrganizationHierarchyNode> = List(size) { index ->
        OrganizationHierarchyNode(
            unitId = "unit-$index",
            parentId = if (index == 0) null else "unit-${index - 1}",
        )
    }
}
