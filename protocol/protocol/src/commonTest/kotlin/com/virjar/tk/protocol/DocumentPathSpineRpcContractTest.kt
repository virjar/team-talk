package com.virjar.tk.protocol

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentPathSpineRpcContractTest {
    @Test
    fun `path spine request carries one exact space and target identity`() {
        val spaceId = "00000000-0000-4000-8000-000000000101"
        val nodeId = "00000000-0000-4000-8000-000000000102"

        ProtoCodec.withPayload(DocumentRpcContract.encodeGetNodePathSpine(spaceId, nodeId)) {
            assertEquals(spaceId, readRequiredString(fieldName = "document.getNodePathSpine.spaceId"))
            assertEquals(nodeId, readRequiredString(fieldName = "document.getNodePathSpine.nodeId"))
        }
    }
}
