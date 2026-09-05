package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.DocumentMoveCommandResult
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentMoveCommandContractTest {
    @Test
    fun `content update wire cannot rename a document`() {
        val spaceId = "00000000-0000-4000-8000-000000000101"
        val nodeId = "00000000-0000-4000-8000-000000000102"

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeUpdateDocument(spaceId, nodeId, DocumentContent("# 正文"), 6L),
        ) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.updateDocument.spaceId"))
            assertEquals(nodeId, readRequiredString(fieldName = "document.updateDocument.documentId"))
            assertEquals("# 正文", readRequiredString(fieldName = "document.updateDocument.content.markdown"))
            assertEquals(0, readVarInt())
            assertEquals(6L, readVarLong())
        }
    }

    @Test
    fun `move command freezes revision operation id and issued time`() {
        val spaceId = "00000000-0000-4000-8000-000000000101"
        val nodeId = "00000000-0000-4000-8000-000000000102"
        val parentId = "00000000-0000-4000-8000-000000000103"
        val operationId = "00000000-0000-4000-8000-000000000104"

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeMoveNode(
                spaceId,
                nodeId,
                parentId,
                "新位置",
                7L,
                operationId,
                1_800_000_000_000L,
            ),
        ) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.moveNode.spaceId"))
            assertEquals(nodeId, readRequiredString(fieldName = "document.moveNode.nodeId"))
            assertEquals(parentId, readRequiredString(fieldName = "document.moveNode.parentId"))
            assertEquals("新位置", readRequiredString(fieldName = "document.moveNode.name"))
            assertEquals(7L, readVarLong())
            assertEquals(operationId, readRequiredString(fieldName = "document.moveNode.operationId"))
            assertEquals(1_800_000_000_000L, readVarLong())
        }
    }

    @Test
    fun `exact replay acknowledgement omits stale move projection`() {
        val result = DocumentMoveCommandResult(
            operationId = "00000000-0000-4000-8000-000000000104",
            result = null,
        )

        val decoded = ProtoCodec.decode(DocumentMoveCommandResult, ProtoCodec.encode(result))

        assertEquals(result.operationId, decoded.operationId)
        assertNull(decoded.result)
    }
}
