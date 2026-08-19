package com.virjar.tk.navigation.feature

import com.virjar.tk.model.DocumentNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentWorkspaceStateTest {

    @Test
    fun `new navigation invalidates every older target`() {
        val navigation = DocumentNavigationGeneration()

        val first = navigation.next()
        val second = navigation.next()

        assertFalse(navigation.isCurrent(first))
        assertTrue(navigation.isCurrent(second))
    }

    @Test
    fun `closing active tab picks latest replacement only from same space`() {
        val remaining = listOf(
            tab("other-old", "space-b"),
            tab("same-old", "space-a"),
            tab("other-new", "space-b"),
            tab("same-new", "space-a"),
        )

        assertEquals("same-new", replacementDocumentTab(remaining, "space-a")?.tabId)
        assertNull(replacementDocumentTab(remaining, "space-missing"))
    }

    @Test
    fun `folder target path is rebuilt from root to selected folder`() {
        val root = folder("root", parentId = null)
        val chapter = folder("chapter", parentId = root.nodeId)
        val section = folder("section", parentId = chapter.nodeId)
        val tree = mapOf(
            null to listOf(root),
            root.nodeId to listOf(chapter),
            chapter.nodeId to listOf(section),
        )

        assertEquals(listOf("root", "chapter", "section"), folderAncestorIds("section", tree))
        assertEquals(emptyList(), folderAncestorIds(null, tree))
        assertEquals(emptyList(), folderAncestorIds("unknown", tree))
    }

    private fun tab(id: String, spaceId: String) = DocumentTabState(
        tabId = id,
        documentId = id,
        spaceId = spaceId,
        parentId = null,
        ancestorIds = emptyList(),
        savedTitle = id,
        savedMarkdown = "",
        draftTitle = id,
        draftMarkdown = "",
        revision = 1,
    )

    private fun folder(id: String, parentId: String?) = DocumentNode(
        nodeId = id,
        spaceId = "space-a",
        parentId = parentId,
        nodeType = DocumentNode.TYPE_FOLDER,
        name = id,
        revision = 1,
        createdBy = "u1",
        createdAt = 1,
        updatedBy = "u1",
        updatedAt = 1,
    )
}
