package com.virjar.tk.protocol

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupCreationRpcContractTest {
    @Test
    fun `group creation carries stable operation id before canonical payload`() {
        val operationId = "00000000-0000-4000-8000-000000000021"

        ProtoCodec.withPayload(
            ChatRpcContract.encodeCreateGroup(
                operationId,
                "项目协作",
                null,
                listOf("member-b", "member-a"),
            ),
        ) {
            assertEquals(operationId, readRequiredString(fieldName = "chat.createGroup.operationId"))
            assertEquals("项目协作", readRequiredString(fieldName = "chat.createGroup.name"))
            assertNull(readString())
            assertEquals(2, readVarInt())
            assertEquals("member-b", readRequiredString(fieldName = "chat.createGroup.memberUids[]"))
            assertEquals("member-a", readRequiredString(fieldName = "chat.createGroup.memberUids[]"))
            requireExhausted("chat.createGroup request")
        }
    }
}
