package com.virjar.tk.protocol

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals

class SocialCommandRpcContractTest {
    @Test
    fun `contact decisions carry operation identity before their token`() {
        assertDecisionPayload(ContactRpcContract.encodeAccept(OPERATION_ID, ISSUED_AT, CONTACT_TOKEN))
        assertDecisionPayload(ContactRpcContract.encodeReject(OPERATION_ID, ISSUED_AT, CONTACT_TOKEN))
    }

    @Test
    fun `invite creation carries operation identity and the complete immutable payload`() {
        ProtoCodec.withPayload(
            ChatRpcContract.encodeCreateInviteLink(
                OPERATION_ID,
                ISSUED_AT,
                CHAT_ID,
                "项目邀请",
                3,
                17L,
            ),
        ) {
            assertEquals(OPERATION_ID, readRequiredString(fieldName = "chat.createInviteLink.operationId"))
            assertEquals(ISSUED_AT, readVarLong())
            assertEquals(CHAT_ID, readRequiredString(fieldName = "chat.createInviteLink.chatId"))
            assertEquals("项目邀请", readRequiredString(fieldName = "chat.createInviteLink.name"))
            assertEquals(3, readVarInt())
            assertEquals(17L, readVarLong())
            requireExhausted("chat.createInviteLink request")
        }
    }

    private fun assertDecisionPayload(payload: ByteArray) {
        ProtoCodec.withPayload(payload) {
            assertEquals(OPERATION_ID, readRequiredString(fieldName = "contact.decision.operationId"))
            assertEquals(ISSUED_AT, readVarLong())
            assertEquals(CONTACT_TOKEN, readRequiredString(fieldName = "contact.decision.token"))
            requireExhausted("contact decision request")
        }
    }

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000061"
        const val ISSUED_AT = 1_700_000_000_000L
        const val CONTACT_TOKEN = "00000000-0000-4000-8000-000000000062"
        const val CHAT_ID = "00000000-0000-4000-8000-000000000063"
    }
}
