package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.infra.db.DocumentNodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DocumentNodeProjectionTest {
    @Test
    fun `tree summary projection never loads markdown`() {
        assertEquals(
            listOf(
                DocumentNodes.nodeId,
                DocumentNodes.spaceId,
                DocumentNodes.parentId,
                DocumentNodes.name,
                DocumentNodes.excerpt,
                DocumentNodes.revision,
                DocumentNodes.createdBy,
                DocumentNodes.createdAt,
                DocumentNodes.updatedBy,
                DocumentNodes.updatedAt,
            ),
            DOCUMENT_NODE_SUMMARY_PROJECTION,
        )
        assertFalse(DocumentNodes.markdown in DOCUMENT_NODE_SUMMARY_PROJECTION)
    }

    @Test
    fun `active identity projection excludes content and path fields`() {
        assertEquals(
            listOf(DocumentNodes.nodeId, DocumentNodes.spaceId),
            DOCUMENT_NODE_IDENTITY_PROJECTION,
        )
        assertFalse(DocumentNodes.markdown in DOCUMENT_NODE_IDENTITY_PROJECTION)
        assertFalse(DocumentNodes.parentId in DOCUMENT_NODE_IDENTITY_PROJECTION)
    }

    @Test
    fun `only single-target content projection includes markdown`() {
        assertEquals(
            listOf(
                DocumentNodes.nodeId,
                DocumentNodes.spaceId,
                DocumentNodes.parentId,
                DocumentNodes.name,
                DocumentNodes.markdown,
                DocumentNodes.revision,
                DocumentNodes.createdBy,
                DocumentNodes.createdAt,
                DocumentNodes.updatedBy,
                DocumentNodes.updatedAt,
            ),
            DOCUMENT_NODE_CONTENT_PROJECTION,
        )
        assertEquals(
            listOf(DocumentNodes.nodeId, DocumentNodes.spaceId, DocumentNodes.revision),
            DOCUMENT_NODE_DELETE_PROJECTION,
        )
        assertFalse(DocumentNodes.markdown in DOCUMENT_NODE_DELETE_PROJECTION)
    }
}
