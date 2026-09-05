package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentHierarchyConflictException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentHierarchyTraversalTest {
    @Test
    fun `ancestor query rows resolve from root to direct parent`() {
        val rows = listOf(
            DocumentAncestorQueryRow("parent", "space", "section", 1, false),
            DocumentAncestorQueryRow("section", "space", "root", 2, false),
            DocumentAncestorQueryRow("root", "space", null, 3, false),
        )

        val result = DocumentHierarchyTraversal.resolveAncestorIds("space", "parent", rows)

        assertEquals(listOf("root", "section", "parent"), result)
    }

    @Test
    fun `ancestor query rows reject cross-space cycles incomplete paths and depth overflow`() {
        assertFailsWith<DocumentNotFoundException> {
            DocumentHierarchyTraversal.resolveAncestorIds("space", "missing", emptyList())
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.resolveAncestorIds(
                "space",
                "foreign",
                listOf(DocumentAncestorQueryRow("foreign", "other-space", null, 1, false)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.resolveAncestorIds(
                "space",
                "first",
                listOf(
                    DocumentAncestorQueryRow("first", "space", "second", 1, false),
                    DocumentAncestorQueryRow("second", "space", "first", 2, false),
                    DocumentAncestorQueryRow("first", "space", "second", 3, true),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.resolveAncestorIds(
                "space",
                "first",
                listOf(DocumentAncestorQueryRow("first", "space", "missing", 1, false)),
            )
        }

        val tooDeep = List(Document.MAX_ANCESTOR_DEPTH + 1) { index ->
            DocumentAncestorQueryRow(
                nodeId = "ancestor-$index",
                spaceId = "space",
                parentId = if (index == Document.MAX_ANCESTOR_DEPTH) {
                    null
                } else {
                    "ancestor-${index + 1}"
                },
                depth = index + 1,
                cycle = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.resolveAncestorIds("space", "ancestor-0", tooDeep)
        }
    }

    @Test
    fun `ancestor hierarchy SQL is one bounded active same-space recursive query`() {
        assertEquals(1, Regex("WITH RECURSIVE").findAll(DOCUMENT_ANCESTOR_PATH_SQL).count())
        assertTrue("node.space_id = params.space_id" in DOCUMENT_ANCESTOR_PATH_SQL)
        assertTrue("parent.space_id = params.space_id" in DOCUMENT_ANCESTOR_PATH_SQL)
        assertTrue("node.status = $DOCUMENT_STATUS_ACTIVE" in DOCUMENT_ANCESTOR_PATH_SQL)
        assertTrue("parent.status = $DOCUMENT_STATUS_ACTIVE" in DOCUMENT_ANCESTOR_PATH_SQL)
        assertTrue("walk.depth <= ${Document.MAX_ANCESTOR_DEPTH}" in DOCUMENT_ANCESTOR_PATH_SQL)
        assertTrue("ORDER BY depth ASC" in DOCUMENT_ANCESTOR_PATH_SQL)
        assertTrue("markdown" !in DOCUMENT_ANCESTOR_PATH_SQL)
    }

    @Test
    fun `path spine orders root through target and rejects mismatched paths`() {
        val target = pathNode("target", "parent")
        val parent = pathNode("parent", "root")
        val root = pathNode("root", null)
        val rows = listOf(
            DocumentPathQueryRow(target, 1, false),
            DocumentPathQueryRow(parent, 2, false),
            DocumentPathQueryRow(root, 3, false),
        )

        val spine = DocumentHierarchyTraversal.resolvePathSpine("space", "target", rows)

        assertEquals(listOf("root", "parent", "target"), spine.nodes.map(DocumentNode::nodeId))
        assertFailsWith<DocumentNotFoundException> {
            DocumentHierarchyTraversal.resolvePathSpine("space", "missing", emptyList())
        }
        assertFailsWith<IllegalStateException> {
            DocumentHierarchyTraversal.resolvePathSpine(
                "space",
                "target",
                rows.mapIndexed { index, row ->
                    if (index == 1) row.copy(node = row.node.copy(spaceId = "other")) else row
                },
            )
        }
        assertFailsWith<IllegalStateException> {
            DocumentHierarchyTraversal.resolvePathSpine(
                "space",
                "another-target",
                rows,
            )
        }
        assertFailsWith<IllegalStateException> {
            DocumentHierarchyTraversal.resolvePathSpine(
                "space",
                "target",
                rows.mapIndexed { index, row ->
                    if (index == 1) row.copy(cycle = true) else row
                },
            )
        }
        assertFailsWith<IllegalStateException> {
            DocumentHierarchyTraversal.resolvePathSpine("space", "target", rows.dropLast(1))
        }
    }

    @Test
    fun `path spine SQL returns bounded summaries without markdown or N plus one statements`() {
        assertEquals(1, Regex("WITH RECURSIVE").findAll(DOCUMENT_PATH_SPINE_SQL).count())
        assertTrue("node.space_id = params.space_id" in DOCUMENT_PATH_SPINE_SQL)
        assertTrue("parent.space_id = params.space_id" in DOCUMENT_PATH_SPINE_SQL)
        assertTrue("child.parent_id = walk.node_id" in DOCUMENT_PATH_SPINE_SQL)
        assertTrue("walk.depth <= ${Document.MAX_ANCESTOR_DEPTH}" in DOCUMENT_PATH_SPINE_SQL)
        assertTrue("ORDER BY walk.depth ASC" in DOCUMENT_PATH_SPINE_SQL)
        assertTrue("markdown" !in DOCUMENT_PATH_SPINE_SQL)
    }

    @Test
    fun `move snapshot orders ancestors and accepts an aggregated wide subtree`() {
        val rows = listOf(
            DocumentMoveHierarchyQueryRow(true, "target", "root-parent", 1, false),
            DocumentMoveHierarchyQueryRow(true, "root-parent", null, 2, false),
            DocumentMoveHierarchyQueryRow(false, "moving", null, 1, false, nodeCount = 50_001),
        )

        val snapshot = DocumentHierarchyTraversal.moveSnapshot("moving", "target", rows)

        assertEquals(listOf("root-parent", "target"), snapshot.targetAncestorIds)
        assertEquals(1, snapshot.maxDescendantDepth)
        assertEquals(50_001L, snapshot.subtreeNodeCount)
    }

    @Test
    fun `move snapshot rejects broken ancestor paths depth overflow and cycles`() {
        val subtreeRoot = DocumentMoveHierarchyQueryRow(false, "moving", null, 0, false)
        assertFailsWith<DocumentHierarchyConflictException> {
            DocumentHierarchyTraversal.moveSnapshot("moving", "missing", listOf(subtreeRoot))
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.moveSnapshot(
                "moving",
                "target",
                listOf(DocumentMoveHierarchyQueryRow(true, "target", "missing", 1, false), subtreeRoot),
            )
        }

        val tooDeep = buildList {
            repeat(Document.MAX_ANCESTOR_DEPTH + 1) { index ->
                add(
                    DocumentMoveHierarchyQueryRow(
                        ancestor = true,
                        nodeId = "ancestor-$index",
                        parentId = if (index == Document.MAX_ANCESTOR_DEPTH) {
                            null
                        } else {
                            "ancestor-${index + 1}"
                        },
                        depth = index + 1,
                        cycle = false,
                    ),
                )
            }
            add(subtreeRoot)
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.moveSnapshot("moving", "ancestor-0", tooDeep)
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.moveSnapshot(
                "moving",
                null,
                listOf(
                    DocumentMoveHierarchyQueryRow(false, "moving", null, 1, true, nodeCount = 2),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentHierarchyTraversal.moveSnapshot(
                "moving",
                null,
                listOf(
                    DocumentMoveHierarchyQueryRow(
                        false,
                        "moving",
                        null,
                        Document.MAX_ANCESTOR_DEPTH + 1,
                        false,
                        nodeCount = 130,
                    ),
                ),
            )
        }
    }

    @Test
    fun `move hierarchy SQL combines both recursive branches and aggregates the subtree`() {
        assertEquals(1, Regex("WITH RECURSIVE").findAll(DOCUMENT_MOVE_HIERARCHY_SQL).count())
        assertTrue("ancestor_walk" in DOCUMENT_MOVE_HIERARCHY_SQL)
        assertTrue("subtree_walk" in DOCUMENT_MOVE_HIERARCHY_SQL)
        assertTrue("MAX(subtree.depth)" in DOCUMENT_MOVE_HIERARCHY_SQL)
        assertTrue("BOOL_OR(subtree.cycle)" in DOCUMENT_MOVE_HIERARCHY_SQL)
        assertTrue("COUNT(subtree.node_id)" in DOCUMENT_MOVE_HIERARCHY_SQL)
    }

    private fun pathNode(nodeId: String, parentId: String?) = DocumentNode(
        nodeId = nodeId,
        spaceId = "space",
        parentId = parentId,
        hasChildren = nodeId != "target",
        name = nodeId,
        revision = 1,
        createdBy = "owner",
        createdAt = 1,
        updatedBy = "owner",
        updatedAt = 1,
    )
}
