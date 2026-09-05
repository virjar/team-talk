package com.virjar.tk.protocol

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import com.virjar.tk.protocol.model.DocumentContent
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentDestructiveRpcContractTest {
    @Test
    fun `create command carries its stable document identity before content`() {
        val documentId = "00000000-0000-4000-8000-000000000031"
        val spaceId = "00000000-0000-4000-8000-000000000032"
        val parentId = "00000000-0000-4000-8000-000000000033"

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeCreateDocument(
                documentId,
                spaceId,
                parentId,
                "可靠创建",
                DocumentContent("# 初稿"),
            ),
        ) {
            assertEquals(documentId, readRequiredString(fieldName = "document.createDocument.documentId"))
            assertEquals(spaceId, readRequiredString(fieldName = "document.createDocument.spaceId"))
            assertEquals(parentId, readRequiredString(fieldName = "document.createDocument.parentId"))
            assertEquals("可靠创建", readRequiredString(fieldName = "document.createDocument.title"))
            assertEquals("# 初稿", readRequiredString(fieldName = "document.createDocument.content.markdown"))
            assertEquals(0, readVarInt())
        }
    }

    @Test
    fun `archive command carries its stable operation id`() {
        val spaceId = "00000000-0000-4000-8000-000000000001"
        val operationId = "00000000-0000-4000-8000-000000000002"

        ProtoCodec.withPayload(DocumentRpcContract.encodeArchiveSpace(spaceId, operationId)) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.archiveSpace.spaceId"))
            assertEquals(operationId, readRequiredString(fieldName = "document.archiveSpace.operationId"))
        }
    }

    @Test
    fun `delete command preserves revision before its stable operation id`() {
        val spaceId = "00000000-0000-4000-8000-000000000011"
        val nodeId = "00000000-0000-4000-8000-000000000012"
        val operationId = "00000000-0000-4000-8000-000000000013"

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeDeleteNode(spaceId, nodeId, 17L, operationId),
        ) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.deleteNode.spaceId"))
            assertEquals(nodeId, readRequiredString(fieldName = "document.deleteNode.nodeId"))
            assertEquals(17L, readVarLong())
            assertEquals(operationId, readRequiredString(fieldName = "document.deleteNode.operationId"))
        }
    }

    @Test
    fun `custody transfer freezes target revision and operation identity`() {
        val spaceId = "00000000-0000-4000-8000-000000000021"
        val unitId = "00000000-0000-4000-8000-000000000022"
        val stewardUid = "00000000-0000-4000-8000-000000000023"
        val operationId = "00000000-0000-4000-8000-000000000024"

        ProtoCodec.withPayload(
            DocumentRpcContract.encodeTransferSpaceCustody(
                spaceId,
                2,
                unitId,
                stewardUid,
                7L,
                operationId,
            ),
        ) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.transferSpaceCustody.spaceId"))
            assertEquals(2, readVarInt())
            assertEquals(unitId, readRequiredString(fieldName = "document.transferSpaceCustody.ownerPrincipalId"))
            assertEquals(stewardUid, readRequiredString(fieldName = "document.transferSpaceCustody.stewardUid"))
            assertEquals(7L, readVarLong())
            assertEquals(operationId, readRequiredString(fieldName = "document.transferSpaceCustody.operationId"))
        }
    }
}
