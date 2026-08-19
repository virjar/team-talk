package com.virjar.tk

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentRevision
import com.virjar.tk.model.DocumentRevisionSummary
import com.virjar.tk.model.DocumentSummary
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentModelTest {
    @Test
    fun `document models round trip`() {
        val document = Document("doc-1", Document.SCOPE_GROUP_CHAT, "chat-1", "设计说明", "# v1\n正文", 2, "u1", 10, "u2", 20)
        assertEquals(document, ProtoCodec.decode(Document, ProtoCodec.encode(document)))

        val summary = DocumentSummary("doc-1", 1, "chat-1", "设计说明", "正文", 2, "u1", 10, "u2", 20)
        assertEquals(summary, ProtoCodec.decode(DocumentSummary, ProtoCodec.encode(summary)))

        val revision = DocumentRevision("doc-1", 1, "设计说明", "# v1", "u1", 10)
        assertEquals(revision, ProtoCodec.decode(DocumentRevision, ProtoCodec.encode(revision)))

        val revisionSummary = DocumentRevisionSummary("doc-1", 1, "设计说明", 4, "u1", 10)
        assertEquals(revisionSummary, ProtoCodec.decode(DocumentRevisionSummary, ProtoCodec.encode(revisionSummary)))
    }
}
