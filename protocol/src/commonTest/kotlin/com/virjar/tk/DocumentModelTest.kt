package com.virjar.tk

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentModelTest {
    @Test
    fun `document models round trip`() {
        val space = DocumentSpace("space-1", "产品空间", "跨部门产品资料", DocumentSpace.ROLE_EDITOR, "u1", 1, 2)
        assertEquals(space, ProtoCodec.decode(DocumentSpace, ProtoCodec.encode(space)))

        val grant = DocumentSpaceGrant("space-1", DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT, "unit-1", DocumentSpace.ROLE_EDITOR, true, "产品部")
        assertEquals(grant, ProtoCodec.decode(DocumentSpaceGrant, ProtoCodec.encode(grant)))

        val node = DocumentNode("folder-1", "space-1", null, DocumentNode.TYPE_FOLDER, "需求", "", 1, "u1", 1, "u1", 1)
        assertEquals(node, ProtoCodec.decode(DocumentNode, ProtoCodec.encode(node)))

        val document = Document("doc-1", "space-1", "folder-1", "设计说明", "# v1\n正文", 2, "u1", 10, "u2", 20)
        assertEquals(document, ProtoCodec.decode(Document, ProtoCodec.encode(document)))

        val revision = DocumentRevision("doc-1", 1, "设计说明", "# v1", "u1", 10)
        assertEquals(revision, ProtoCodec.decode(DocumentRevision, ProtoCodec.encode(revision)))

        val revisionSummary = DocumentRevisionSummary("doc-1", 1, "设计说明", 4, "u1", 10)
        assertEquals(revisionSummary, ProtoCodec.decode(DocumentRevisionSummary, ProtoCodec.encode(revisionSummary)))
    }
}
